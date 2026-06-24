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
            [fukan.canvas.core.structure :as structure]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

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
  "Clojure fn-predicate symbol → a builder `(arg-terms refs) → cozo-fragment`. A builder
   needing a generating rule records the rule name on `refs` so the closure emits it; the
   rest are inline CozoScript expressions. `not=` is handled separately (built-in)."
  {'canvas.vocab.code.module/module-corresponds?
   (fn [[cm km] refs]
     (swap! refs conj "r_module_corresponds")
     (str "r_module_corresponds[" cm ", " km "]"))
   'canvas.vocab.fukan/reader-realizes-lens?
   (fn [[rn ln] _] (str rn " = concat('probe-', " ln ")"))
   'clojure.string/starts-with?
   (fn [[s prefix] _] (str "starts_with(" s ", " prefix ")"))})

(declare compile-clause compile-clauses)

(defn- compile-predicate
  "A `(pred args…)` predicate (the content of a `[(…)]` clause) → a cozo fragment. `not=`
   (bare or `clojure.core/`-qualified) is built in; a registered fn-predicate is emitted
   via its builder (which may record a synthetic-rule ref on `refs`)."
  [[op & args] refs]
  (cond
    (#{'not= 'clojure.core/not=} op) (str (cterm (first args)) " != " (cterm (second args)))
    (contains? predicate-registry op) ((predicate-registry op) (mapv cterm args) refs)
    :else (throw (ex-info (str "unsupported predicate: " (pr-str (cons op args))) {:pred op}))))

(defn- compile-clause
  "One datalog clause → `[cozo-fragment extra-rules]`. `counter` (an atom) names
   not-join/or-join helper rules uniquely; `refs` (an atom set) accrues every rule name
   the clause CALLS, so the program can emit just the reachable rules. Throws on an
   unsupported form."
  [c counter refs]
  (cond
    (and (vector? c) (= 3 (count c)) (keyword? (nth c 1)))
    [(str "triple[" (cterm (nth c 0)) ", '" (attr (nth c 1)) "', " (cterm (nth c 2)) "]") nil]
    (and (vector? c) (= 1 (count c)) (seq? (first c)))
    [(compile-predicate (first c) refs) nil]
    (and (seq? c) (= 'not (first c)))
    (let [[frag extra] (compile-clause (second c) counter refs)] [(str "not " frag) extra])
    (and (seq? c) (= 'not-join (first c)))
    (let [[_ vars & clauses] c
          hn (str "nj_" (swap! counter inc))
          vs (str/join ", " (map cvar vars))
          [body extra] (compile-clauses clauses counter refs)]
      [(str "not " hn "[" vs "]") (cons (str hn "[" vs "] := " body) extra)])
    (and (seq? c) (= 'or-join (first c)))
    (let [[_ vars & disjuncts] c
          hn (str "oj_" (swap! counter inc))
          vs (str/join ", " (map cvar vars))
          parts (map (fn [d]
                       (compile-clauses (if (and (seq? d) (= 'and (first d))) (rest d) [d]) counter refs))
                     disjuncts)]
      [(str hn "[" vs "]")
       (concat (map (fn [[body _]] (str hn "[" vs "] := " body)) parts)
               (mapcat second parts))])
    (and (seq? c) (symbol? (first c)))
    (let [nm (rname (first c))]
      (swap! refs conj nm)
      [(str nm "[" (str/join ", " (map cterm (rest c))) "]") nil])
    :else
    (throw (ex-info (str "unsupported clause: " (pr-str c)) {:clause c}))))

(defn- compile-clauses
  "Compile a seq of where-clauses → `[joined-body extra-rule-lines]`."
  [clauses counter refs]
  (let [rs (mapv #(compile-clause % counter refs) clauses)]
    [(str/join ", " (map first rs)) (mapcat second rs)]))

(defn- compile-rule
  "A datalog rule `[(head args…) body…]` → its CozoScript definition line(s): the head
   line plus any not-join/or-join helpers its body spawned. Shares `counter`/`refs`."
  [[head & body] counter refs]
  (let [[bodystr extra] (compile-clauses body counter refs)]
    (cons (str (rname (first head)) "[" (str/join ", " (map cvar (rest head))) "] := " bodystr)
          extra)))

;; ── the vocab-rule index + reachability closure ───────────────────────────────
(defn vocab-index
  "Compile `structure/vocab-rules` (the always-injected vocab rules) once into an index
   `rule-name → {:lines [cozo-defs] :refs #{names it calls}}`, merging a rule's multiple
   definitions. Uncompilable rules are skipped. The synthetic rules are merged in as seed."
  []
  (reduce (fn [idx rule]
            (try
              (let [counter (atom 0), refs (atom #{})
                    lines   (compile-rule rule counter refs)
                    nm      (rname (ffirst rule))]
                (-> idx
                    (update-in [nm :lines] (fnil into []) lines)
                    (update-in [nm :refs] (fnil into #{}) @refs)))
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

(defn compile-body
  "Compile `where` (a seq of clauses) + caller-supplied `extra-rules` (datalog rules) into
   `[rule-lines body-str]`: the vocab rules in the reference closure, then the extra rules,
   then any not-join/or-join helpers (deduped), and the joined where body. Shared by the
   law engine (`compile-law`) and `q`."
  [where extra-rules index]
  (let [counter      (atom 0)
        refs         (atom #{})
        rule-lines   (mapcat #(compile-rule % counter refs) extra-rules)
        [body extra] (compile-clauses where counter refs)
        vocab-lines  (mapcat #(:lines (index %)) (closure index @refs))]
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

(defn q
  "Run datalog `query` over Cozo db `cdb`, like `d/q` (minus the leading db arg, which is
   `cdb`). Extra `inputs` match the `:in` spec after `$`: a `%` consumes a rules vector, a
   `?name` consumes a scalar param (inlined as a literal). Returns a SET of tuples for a
   relation find, or a distinct vector for a collection find `[:find [?v …]]`. All cells
   are STRINGS (the `triple` view) — eids are opaque string handles (resolve via `entity`)."
  [cdb query & inputs]
  (let [{:keys [find in where]} (split-query query)
        {:keys [rules subst]}   (bind-inputs in inputs)
        where*  (walk/postwalk-replace subst where)
        rules*  (walk/postwalk-replace subst (vec rules))
        [rule-lines body] (compile-body where* rules* (vocab-index))
        head    (str/join ", " (map cvar (find-vars find)))
        program (str preamble "\n" (str/join "\n" rule-lines) "\n?[" head "] := " body)
        rows    (db/q cdb program)]
    (if (collection-find? find)
      (vec (distinct (map first rows)))
      (set rows))))

;; ── entity: eid (string) → typed attribute map (the d/entity replacement) ──────
(defn entity
  "Resolve `eid` (a string handle) to its attribute map `{attr-keyword value}`, values in
   their real types (Int/String/Bool, read from the typed buckets) — the `d/entity`
   replacement. A repeated attribute keeps the LAST value (entity attrs are single-valued
   here). Returns nil for an unknown eid."
  [cdb eid]
  (let [eid  (str eid)
        rows (mapcat (fn [bucket]
                       (db/q cdb (str "?[a, v] := *" bucket "[e, a, v], e == " eid)))
                     ["t_int" "t_str" "t_bool"])
        m    (reduce (fn [acc [a v]] (assoc acc (keyword a) v)) {} rows)]
    (when (seq m) (assoc m :db/id eid))))
