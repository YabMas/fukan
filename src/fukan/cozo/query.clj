(ns fukan.cozo.query
  "The general datalog → CozoScript query compiler — the kernel query primitive on Cozo,
   the cut-over replacement for datascript's `d/q` + `d/entity`. It owns the clause/rule
   compiler (datom / not / not-join / or-join / predicates / rule-calls + the vocab-rule
   index and reachability closure); `fukan.cozo.law` builds the law engine on top of it.

   `q` compiles the datalog subset fukan uses — relation (`[:find ?a ?b]`) and collection
   (`[:find [?v …]]`) find specs, an `:in` of `$` + optional `%` (rules) + bound scalar
   params, and the full where/rule machinery — and runs it. Results come back over the
   unified all-string `triple` view, so EIDS AND VALUES ARE STRINGS (an opaque eid is a
   string handle; `entity` resolves it to typed attributes from the typed buckets). A
   query that needs a typed leaf value reads it through `entity`, not a find-var.

   `entity` resolves an eid (string) to its attribute map — the `d/entity` replacement —
   with values in their real types (Int/String/Bool from the typed buckets)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [datascript.core :as d]          ; TRANSITION: the d/q + d/entity fallback (removed at cut-over)
            [fukan.canvas.core.structure :as structure]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules])
  (:import [org.cozodb CozoJavaBridge]))

;; ── term + name helpers ───────────────────────────────────────────────────────
(defn- dvar? [t] (and (symbol? t) (str/starts-with? (name t) "?")))
(defn cvar "?e → e" [t] (subs (name t) 1))
(defn- clit "a literal → its quoted all-string form (:calls → 'calls', 42 → '42', true → 'true')"
  [v] (str \' (if (keyword? v) (subs (str v) 1) v) \'))
(defn- cterm "a datalog term → its CozoScript form (var → name, literal → quoted)"
  [t] (if (dvar? t) (cvar t) (clit t)))
(defn- attr "​:rel/from → rel/from" [kw] (subs (str kw) 1))
(defn- rname
  "A datalog rule head/call symbol → a CozoScript rule name: `r_`-prefixed, every
   non-alphanumeric char folded to `_` (op-twin → r_op_twin, Operation → r_Operation)."
  [sym]
  (str "r_" (str/replace (name sym) #"[^A-Za-z0-9]" "_")))

;; ── synthetic rules: cozo ports of Clojure fn-predicates ──────────────────────
;; A `[(pred ?a ?b)]` clause whose `pred` is a Clojure fn (not a datalog rule) can't be a
;; CozoScript filter when one arg is otherwise unbound — Cozo requires every helper-rule
;; head var range-restricted. So such a predicate is ported as a GENERATING rule (both
;; sides produced, then filtered), merged into the vocab index and pulled by the closure.
(def ^:private synthetic-rules
  {"r_canvas_module" {:lines ["r_canvas_module[cm] := triple[m, 'structure/of', 'canvas.vocab.code.module/Module'], not triple[m, 'val/extracted', 'true'], triple[m, 'entity/name', cm]"]
                      :refs #{}}
   "r_code_module"   {:lines ["r_code_module[km] := triple[m, 'structure/of', 'canvas.vocab.code.module/Module'], triple[m, 'val/extracted', 'true'], triple[m, 'entity/name', km]"]
                      :refs #{}}
   "r_module_corresponds" {:lines ["r_module_corresponds[cm, km] := r_canvas_module[cm], r_code_module[km], cmn = regex_replace_all(cm, '-', '.'), kmn = regex_replace_all(km, '-', '.'), or(kmn == cmn, ends_with(kmn, concat('.', cmn)))"]
                           :refs #{"r_canvas_module" "r_code_module"}}})

(def ^:private predicate-registry
  "Clojure fn-predicate symbol → a builder `(arg-terms) → [cozo-fragment refs]`. A builder
   needing a generating rule returns its name in `refs` so the closure emits it; the rest
   return `#{}`. `not=` is handled separately (built-in)."
  {'canvas.vocab.code.module/module-corresponds?
   (fn [[cm km]] [(str "r_module_corresponds[" cm ", " km "]") #{"r_module_corresponds"}])
   'canvas.vocab.fukan/reader-realizes-lens?
   (fn [[rn ln]] [(str rn " = concat('probe-', " ln ")") #{}])
   'clojure.string/starts-with?
   (fn [[s prefix]] [(str "starts_with(" s ", " prefix ")") #{}])})

(declare compile-clause compile-clauses)

(defn- compile-predicate
  "A `(pred args…)` predicate (the content of a `[(…)]` clause) → `[cozo-fragment refs]`. `not=`
   (bare or `clojure.core/`-qualified) is built in; a registered fn-predicate is emitted via its
   builder (which may name a synthetic generating rule in `refs`)."
  [[op & args]]
  (cond
    (#{'not= 'clojure.core/not=} op) [(str (cterm (first args)) " != " (cterm (second args))) #{}]
    (contains? predicate-registry op) ((predicate-registry op) (mapv cterm args))
    :else (throw (ex-info (str "unsupported predicate: " (pr-str (cons op args))) {:pred op}))))

(defn- helper-name
  "A unique-within-program name for a `not-join`/`or-join` helper rule, derived from the clause's
   CONTENT (pure — no counter): distinct clauses get distinct names, identical clauses collapse to
   one helper (correct — the program de-dupes rule lines). `prefix` keeps `nj_`/`oj_` apart."
  [prefix c]
  (str prefix (Long/toString (Math/abs (long (hash c))) 36)))

(defn- compile-clause
  "One datalog clause → `[cozo-fragment extra-rules refs]` (PURE): `extra-rules` are the not-join/
   or-join helper definitions it spawns (uniquely named by content), `refs` the set of rule names it
   CALLS (so the program emits just the reachable rules). Throws on an unsupported form."
  [c]
  (cond
    (and (vector? c) (= 3 (count c)) (keyword? (nth c 1)))
    [(str "triple[" (cterm (nth c 0)) ", '" (attr (nth c 1)) "', " (cterm (nth c 2)) "]") nil #{}]
    (and (vector? c) (= 1 (count c)) (seq? (first c)))
    (let [[frag refs] (compile-predicate (first c))] [frag nil refs])
    (and (seq? c) (= 'not (first c)) (= 2 (count c)))     ; (not <single-clause>)
    (let [[frag extra refs] (compile-clause (second c))] [(str "not " frag) extra refs])
    (and (seq? c) (= 'not (first c)))                     ; (not c1 c2 …) — use (not-join […] …)
    (throw (ex-info (str "multi-clause `not` is unsupported — write it as `not-join`: " (pr-str c)) {:clause c}))
    (and (seq? c) (= 'not-join (first c)))
    (let [[_ vars & clauses] c
          hn (helper-name "nj_" c)
          vs (str/join ", " (map cvar vars))
          [body extra refs] (compile-clauses clauses)]
      [(str "not " hn "[" vs "]") (cons (str hn "[" vs "] := " body) extra) refs])
    (and (seq? c) (= 'or-join (first c)))
    (let [[_ vars & disjuncts] c
          hn (helper-name "oj_" c)
          vs (str/join ", " (map cvar vars))
          parts (map (fn [d]
                       (compile-clauses (if (and (seq? d) (= 'and (first d))) (rest d) [d])))
                     disjuncts)]
      [(str hn "[" vs "]")
       (concat (map (fn [[body _ _]] (str hn "[" vs "] := " body)) parts)
               (mapcat second parts))
       (reduce into #{} (map #(nth % 2) parts))])
    (and (seq? c) (symbol? (first c)))
    (let [nm (rname (first c))]
      [(str nm "[" (str/join ", " (map cterm (rest c))) "]") nil #{nm}])
    :else
    (throw (ex-info (str "unsupported clause: " (pr-str c)) {:clause c}))))

(defn- compile-clauses
  "Compile a seq of where-clauses → `[joined-body extra-rule-lines refs]` (PURE — refs/extras unioned)."
  [clauses]
  (let [rs (mapv compile-clause clauses)]
    [(str/join ", " (map first rs))
     (mapcat second rs)
     (reduce into #{} (map #(nth % 2) rs))]))

(defn- compile-rule
  "A datalog rule `[(head args…) body…]` → `[def-lines refs]`: the head line plus any not-join/
   or-join helpers its body spawned, and the rule names its body calls (PURE)."
  [[head & body]]
  (let [[bodystr extra refs] (compile-clauses body)]
    [(cons (str (rname (first head)) "[" (str/join ", " (map cvar (rest head))) "] := " bodystr) extra)
     refs]))

;; ── the vocab-rule index + reachability closure ───────────────────────────────
(defn vocab-index
  "Compile `structure/vocab-rules` (the always-injected vocab rules) once into an index
   `rule-name → {:lines [cozo-defs] :refs #{names it calls}}`, merging a rule's multiple
   definitions. Uncompilable rules are skipped. The synthetic rules are merged in as seed."
  []
  (reduce (fn [idx rule]
            (try
              (let [[lines refs] (compile-rule rule)
                    nm           (rname (ffirst rule))]
                (-> idx
                    (update-in [nm :lines] (fnil into []) lines)
                    (update-in [nm :refs] (fnil into #{}) refs)))
              (catch clojure.lang.ExceptionInfo _ idx)))
          synthetic-rules (structure/vocab-rules)))

(defn- closure
  "The set of vocab-rule names reachable from `seeds` through the index's `:refs`."
  [index seeds]
  (loop [seen #{}, todo (vec seeds)]
    (if-let [n (peek todo)]
      (let [todo (pop todo)]
        (if (or (seen n) (not (contains? index n)))
          (recur seen todo)
          (recur (conj seen n) (into todo (:refs (index n))))))
      seen)))

(defn- dewild
  "Replace each `_` placeholder in `form` with a UNIQUE fresh `?_wN` variable (PURE — a threaded
   counter). Datalog `_` means 'any value, don't bind', but Cozo has no discard wildcard, and
   reusing ONE var for every `_` would wrongly JOIN those positions. Counter restarts per call, so
   `_`s stay distinct within the form; each compiled scope (the where, each rule) is dewilded
   separately, and Cozo rule vars are rule-local, so no cross-scope name need match."
  [form]
  (letfn [(go [x n]
            (cond
              (= '_ x)    [(symbol (str "?_w" n)) (inc n)]
              (vector? x) (let [[items n'] (reduce (fn [[acc n] e] (let [[e' n'] (go e n)] [(conj acc e') n']))
                                                   [[] n] x)]
                            [items n'])
              (seq? x)    (let [[items n'] (reduce (fn [[acc n] e] (let [[e' n'] (go e n)] [(conj acc e') n']))
                                                   [[] n] x)]
                            [(apply list items) n'])
              :else       [x n]))]
    (first (go form 0))))

(defn compile-body
  "Compile `where` (a seq of clauses) + caller-supplied `extra-rules` (datalog rules) into
   `[rule-lines body-str]`: the vocab rules in the reference closure, then the extra rules,
   then any not-join/or-join helpers (deduped), and the joined where body. Shared by the
   law engine (`compile-law`) and `q`. PURE — `_` wildcards are expanded (per scope) first."
  [where extra-rules index]
  (let [where               (dewild where)
        [rule-lines erefs]  (reduce (fn [[lines refs] r]
                                      (let [[l rf] (compile-rule (dewild r))]
                                        [(into lines l) (into refs rf)]))
                                    [[] #{}] extra-rules)
        [body extra wrefs]  (compile-clauses where)
        vocab-lines         (mapcat #(:lines (index %)) (closure index (into erefs wrefs)))]
    [(distinct (concat vocab-lines rule-lines extra)) body]))

(def preamble "The always-prepended substrate: the unified all-string `triple` view." rules/triple)

;; ── the general query runner ──────────────────────────────────────────────────
(defn- split-query
  "Parse `[:find …find… :in …in… :where …clauses…]` → {:find :in :where}. `:in` defaults
   to `[$]`."
  [query]
  (loop [section nil, acc {:find [] :in '[$] :where []}, [x & more] query]
    (cond
      (nil? x)        acc
      (= :find x)     (recur :find (assoc acc :in '[$]) more)  ; reset default :in until/unless seen
      (= :in x)       (recur :in (assoc acc :in []) more)
      (= :where x)    (recur :where acc more)
      :else           (recur section (update acc section (fnil conj []) x) more))))

(defn- collection-find?
  "True when the find spec is `[[?v ...]]` — a single find element that is a `[?v …]` vector."
  [find]
  (and (= 1 (count find)) (vector? (first find)) (= '... (last (first find)))))

(defn- find-vars [find]
  (if (collection-find? find) [(first (first find))] (vec find)))

(defn- bind-inputs
  "Pair the `:in` spec (after the implicit `$`) with `inputs`: a `%` entry consumes a rules
   vector; a `?name` entry consumes a param value (→ a {?name value} substitution map).
   Returns {:rules :subst}."
  [in inputs]
  (loop [[i & in-more] (remove #{'$} in), [v & v-more] inputs, rules nil, subst {}]
    (cond
      (nil? i) {:rules rules :subst subst}
      (= '% i) (recur in-more v-more v subst)
      :else    (recur in-more v-more rules (assoc subst i v)))))

(defn- q-cozo
  "Compile + run `query` over Cozo db `cdb` with `inputs`. Returns a SET of tuples for a
   relation find, or a distinct vector for a collection find. All cells are STRINGS (the
   `triple` view) — eids are opaque string handles (resolve via `entity`)."
  [cdb query inputs]
  (let [{:keys [find in where]} (split-query query)
        {:keys [rules subst]}   (bind-inputs in inputs)
        ;; scalar params bind only the WHERE-body's vars; a `%` rule's vars are head-scoped
        ;; and never close over query `:in` inputs — substituting a scalar into a rule would
        ;; corrupt its head (e.g. a shared name like `?op`), so the rules are passed verbatim.
        where*  (walk/postwalk-replace subst where)
        [rule-lines body] (compile-body where* (vec rules) (vocab-index))
        head    (str/join ", " (map cvar (find-vars find)))
        program (str preamble "\n" (str/join "\n" rule-lines) "\n?[" head "] := " body)
        rows    (db/q cdb program)]
    (if (collection-find? find)
      (vec (distinct (map first rows)))
      (set rows))))

(defn q
  "Run datalog `query` over `db` like `d/q` (same argument order). POLYMORPHIC during the
   cut-over: a Cozo db is compiled + run (relation/collection finds, `:in` of `$` + optional
   `%` rules + scalar params — cells come back as STRINGS over the `triple` view); a
   datascript db falls through to `d/q` unchanged. The d/q branch is removed once the held
   model is Cozo."
  [query db & inputs]
  (if (instance? CozoJavaBridge db)
    (q-cozo db query inputs)
    (apply d/q query db inputs)))

;; ── entity: eid → attribute map (the d/entity replacement) ─────────────────────
(defn entity
  "Resolve `eid` to its attribute map — the `d/entity` replacement, same argument order.
   POLYMORPHIC: a Cozo db reads the typed buckets (values in their real Int/String/Bool
   types; eid is a string handle), returning `{attr-keyword value}` (nil for an unknown
   eid); a datascript db falls through to `d/entity`."
  [db eid]
  (if (instance? CozoJavaBridge db)
    (let [eid  (str eid)
          rows (mapcat (fn [bucket]
                         (db/q db (str "?[a, v] := *" bucket "[e, a, v], e == " eid)))
                       ["t_int" "t_str" "t_bool"])
          m    (reduce (fn [acc [a v]] (assoc acc (keyword a) v)) {} rows)]
      (when (seq m) (assoc m :db/id eid)))
    (d/entity db eid)))
