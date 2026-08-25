(ns fukan.cozo.query
  "The general datalog → CozoScript query compiler — the kernel query primitive on Cozo.
   It owns the clause/rule compiler (datom / not / not-join / or-join / predicates / rule-calls
   + the vocab-rule index and reachability closure); `fukan.cozo.law` builds the law engine on
   top of it. `q`/`entity` are the read API the lens engine, readers, and print-duals call.

   `q` compiles the datalog subset fukan uses — relation (`[:find ?a ?b]`) and collection
   (`[:find [?v …]]`) find specs, an `:in` of `$` + optional `%` (rules) + bound scalar
   params, and the full where/rule machinery — and runs it. Every clause compiles to DIRECT
   stored-relation access (never a view — see `compile-datom`, where the reason is measured),
   so EIDS COME BACK NATIVE (an opaque Int handle) and leaf values in their real
   Int/String/Bool type.

   `entity` resolves an eid to its attribute map — the `d/entity` replacement — with values
   in their real types (Int/String/Bool from the typed buckets)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [fukan.canvas.core.structure :as structure]
            [fukan.cozo.db :as db]))

;; ── term + name helpers ───────────────────────────────────────────────────────
(defn- dvar? [t] (and (symbol? t) (str/starts-with? (name t) "?")))
(defn ^{:malli/schema [:=> [:cat :any] :string]} cvar "?e → e" [t] (subs (name t) 1))
(defn- clit
  "A datalog literal → its CozoScript form, MATCHING the typed `triple` view: a keyword
   is the colon-stripped quoted string the mirror stores (:calls → 'calls'), a string is
   quoted ('ref'), and a boolean/integer is the NATIVE cozo literal (true, 42) — never
   quoted — so it equals the native leaf the typed view returns. Other compound values
   the mirror pr-str's, so they compare as quoted strings."
  [v]
  (cond
    (keyword? v) (str \' (subs (str v) 1) \')
    (boolean? v) (str v)
    (integer? v) (str v)
    :else        (str \' v \')))
(defn- cterm "a datalog term → its CozoScript form (var → name, literal → quoted)"
  [t] (if (dvar? t) (cvar t) (clit t)))
(defn- attr "​:rel/from → rel/from" [kw] (subs (str kw) 1))
(defn- rname
  "A datalog rule head/call symbol → a CozoScript rule name: `r_`-prefixed, every
   non-alphanumeric char folded to `_` (module-depends → r_module_depends, Operation → r_Operation)."
  [sym]
  (str "r_" (str/replace (name sym) #"[^A-Za-z0-9]" "_")))


;; ── storage buckets: which typed relation holds an attribute's datoms ─────────
;; Every clause compiles to DIRECT stored-relation access (`*t_str[e, 'attr', v]`), never to a
;; view. This is a PERFORMANCE-CRITICAL invariant, measured, not a preference: a view is a rule,
;; a rule is materialized, and a materialized relation carries NO key — so every join over it
;; degrades to a scan. Against the stored relations Cozo drives from an attribute scan and then
;; uses the `(e, a, v)` PRIMARY KEY for e-bound prefix lookups. On clojure-mcp (72 ns / 659 fns)
;; the same nine-way join measured 80.10s through the old unified `triple` view and 0.59s
;; direct — ~136x. Dropping the view's `to_string` alone recovered only 10x of that; the
;; remaining 12x is the materialization itself, so a "fixed" view is not a fix.
;; EIDS ARE THEREFORE NATIVE Ints (the view used to stringify them, which also made every join
;; key a COMPUTED value — no index could ever apply).

(def ^:dynamic *attr-buckets*
  "attr-string → #{bucket-name} for the db being compiled against, bound by `q`/`check`. Lets a
   var-valued clause pick the ONE bucket its attribute actually occupies. Unbound (nil) is
   correct but slow: the clause falls back to a per-attribute union helper."
  nil)

(defn- bucket-of-literal
  "The typed bucket a LITERAL value lands in — mirroring `fukan.cozo.mirror/classify`, so a clause
   with a literal value needs no index at all: it classifies itself. (A literal whose attribute
   does not occupy that bucket simply matches nothing, which is the right answer.)"
  [v]
  (cond (boolean? v) "t_bool"
        (integer? v) "t_int"
        :else        "t_str"))

(defn- ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  attr-buckets
  "Read `attr → #{bucket}` off `cdb` — which typed relation(s) hold each attribute's datoms.
   Exact and self-maintaining (no static table to rot); in practice one attribute spans buckets
   (the generic scalar leaf, authored at more than one type)."
  [cdb]
  (reduce (fn [m b]
            (reduce (fn [m [a]] (update m a (fnil conj #{}) b)) m
                    (db/q cdb (str "?[a] := *" b "[e, a, v]"))))
          {} ["t_int" "t_str" "t_bool"]))

(def ^:private substrate-buckets
  "The bucket each SUBSTRATE attribute always occupies — fixed by the mirror's own encoding rather
   than by what happens to be stored, so these compile to direct access with no db in hand (and with
   no per-db index lookup). Only `:val/*` leaf scalars vary by authored type."
  {"rel/from"     #{"t_int"}
   "rel/to"       #{"t_int"}
   "rel/order"    #{"t_int"}
   "rel/kind"     #{"t_str"}
   "structure/of" #{"t_str"}
   "entity/name"  #{"t_str"}
   "entity/id"    #{"t_str"}})

(defn- buckets-for
  "The bucket(s) a `[?e :attr v]` clause must read: a literal value classifies itself; a substrate
   attribute is fixed by the mirror's encoding; otherwise consult `*attr-buckets*`, falling back to
   all three (correct, slower) when unbound."
  [attr-kw v-term]
  (let [a (attr attr-kw)]
    (cond
      (not (dvar? v-term))            #{(bucket-of-literal v-term)}
      (substrate-buckets a)           (substrate-buckets a)
      (get *attr-buckets* a)          (get *attr-buckets* a)
      :else                           #{"t_int" "t_str" "t_bool"})))

(defn- compile-datom
  "A `[?e :attr ?v]` clause → `[fragment extra-rules refs]`. Single bucket (the overwhelming
   majority) → direct stored-relation access, which is the whole point. Several → a per-attribute
   union helper, named by attribute so it dedupes across clauses."
  [c]
  (let [a  (attr (nth c 1))
        e  (cterm (nth c 0))
        v  (cterm (nth c 2))
        bs (sort (buckets-for (nth c 1) (nth c 2)))]
    (if (= 1 (count bs))
      [(str "*" (first bs) "[" e ", '" a "', " v "]") nil #{}]
      (let [hn (str "at_" (str/replace a #"[^A-Za-z0-9]" "_"))]
        [(str hn "[" e ", " v "]")
         (map #(str hn "[e, v] := *" % "[e, '" a "', v]") bs)
         #{}]))))

;; ── synthetic rules: cozo ports of Clojure fn-predicates ──────────────────────
;; A `[(pred ?a ?b)]` clause whose `pred` is a Clojure fn (not a datalog rule) can't be a
;; CozoScript filter when one arg is otherwise unbound — Cozo requires every helper-rule
;; head var range-restricted. So such a predicate is ported as a GENERATING rule (both
;; sides produced, then filtered), merged into the vocab index and pulled by the closure.
(def ^:private synthetic-rules
  "rule-name → {:lines :refs} CozoScript defs that registered predicate-ports reference (seeded into
   the index). Vocab-contributed via `register-predicate-port!` — the kernel seeds none."
  (atom {}))

(def ^:private predicate-registry
  "Clojure fn-predicate symbol → a builder `(arg-terms) → [cozo-fragment refs]`. Seeded with GENERIC
   ports only; vocab registers domain predicates via `register-predicate-port!`.
   `not=`/comparisons/`contains?` are handled separately (built-ins)."
  (atom {'clojure.string/starts-with?
         (fn [[s prefix]] [(str "starts_with(" s ", " prefix ")") #{}])}))

(defn ^{:malli/schema [:=> [:cat :symbol :any :map] :nil]}
  register-predicate-port!
  "Register a vocab fn-predicate's CozoScript port: `sym` (the fn symbol used in datalog), `builder`
   ((arg-terms) → [fragment refs]), and `synthetic` ({rule-name → {:lines :refs}}) defining the rules
   `builder` references. Vocab calls this at load (the typing-plug-point pattern); the compiler then
   ports `sym` and emits `synthetic`'s rules on demand. Returns nil."
  [sym builder synthetic]
  (swap! predicate-registry assoc sym builder)
  (swap! synthetic-rules merge synthetic)
  nil)

;; ── name-match: a configured predicate for ordinary Datalog carriers ──────────
;; A vocabulary may use `(name-match :strategy ?design-name ?fact-name)` in a named carrier
;; `defrelation`. The compiler lowers it to an inline CozoScript filter per this closed table.
(def ^:private name-match-strategies
  "Strategy keyword → (design-term fact-term) → CozoScript boolean filter."
  {:exact            (fn [a b] (str a " == " b))
   ;; the design (canvas) name is the fact (code) name, or a separator-delimited SUFFIX of it —
   ;; separator-agnostic: canvas names use '-', code paths '.', so normalize both to '.' first.
   :qualified-suffix (fn [a b]
                       (let [an (str "regex_replace_all(" a ", '-', '.')")
                             bn (str "regex_replace_all(" b ", '-', '.')")]
                         (str "or(" bn " == " an ", ends_with(" bn ", concat('.', " an ")))")))})

(declare compile-clause compile-clauses dewild)

(def ^:private comparison-ops
  "Datalog comparison-predicate symbols → their CozoScript infix operator. Both sides are
   compiled via `cterm`, so a literal becomes its NATIVE typed form (`(> ?max 60000)` →
   `max > 60000`, `(= ?opt true)` → `opt == true`) — comparing against the typed `triple`
   view's native leaves. `not=` and `=` accept the `clojure.core/`-qualified form too."
  {'=  "==" 'clojure.core/=  "=="
   'not= "!=" 'clojure.core/not= "!="
   '<  "<"  'clojure.core/<  "<"
   '>  ">"  'clojure.core/>  ">"
   '<= "<=" 'clojure.core/<= "<="
   '>= ">=" 'clojure.core/>= ">="})

(defn- compile-predicate
  "A `(pred args…)` predicate (the content of a `[(…)]` clause) → `[cozo-fragment refs]`. A
   comparison op (`= < > <= >= not=`, bare or `clojure.core/`-qualified) is a built-in infix
   filter; `(contains? #{…} ?v)` is set membership; a registered fn-predicate is emitted via
   its builder (which may name a synthetic generating rule in `refs`)."
  [[op & args]]
  (cond
    (contains? comparison-ops op)
    [(str (cterm (first args)) " " (comparison-ops op) " " (cterm (second args))) #{}]
    ;; (contains? #{a b …} ?v) — set membership → an or of equalities (the set is a LITERAL, not a term)
    (and (#{'contains? 'clojure.core/contains?} op) (set? (first args)))
    [(str "or(" (str/join ", " (map #(str (cterm (second args)) " == " (clit %)) (first args))) ")") #{}]
    ;; (name-match :strategy ?a ?b) — a declarative correspondence bridge → an inline match filter on
    ;; the two bound names (the strategy keyword is a LITERAL selector, not a term)
    (= 'name-match op)
    (if-let [f (name-match-strategies (first args))]
      [(f (cterm (second args)) (cterm (nth args 2))) #{}]
      (throw (ex-info (str "unknown name-match strategy: " (pr-str (first args))) {:strategy (first args)})))
    (contains? @predicate-registry op) ((@predicate-registry op) (mapv cterm args))
    :else (throw (ex-info (str "unsupported predicate: " (pr-str (cons op args))) {:pred op}))))

(defn- helper-name
  "A unique-within-program name for a `not-join`/`or-join` helper rule, derived from the clause's
   CONTENT (pure — no counter): distinct clauses get distinct names, identical clauses collapse to
   one helper (correct — the program de-dupes rule lines). `prefix` keeps `nj_`/`oj_` apart."
  [prefix c]
  (str prefix (Long/toString (Math/abs (long (hash c))) 36)))

(def ^:private agg-ops
  "Rule-head / inline-measure aggregate symbols → their native CozoScript aggregate.
   Whitelist grows under pressure."
  {'count "count" 'sum "sum" 'min "min" 'max "max" 'mean "mean"})

(defn- chead
  "A rule-HEAD term → CozoScript: a plain var (`?k` → `k`, a group key) or an aggregate
   application (`(count ?v)` → `count(v)`, per `agg-ops`). Cozo aggregates live in rule
   heads only; plain head vars are the group-by keys (Cozo semantics). Aggregate
   stratification (no recursion through an aggregate) is enforced by Cozo itself."
  [t]
  (cond
    (dvar? t) (cvar t)
    (and (seq? t) (= 2 (count t)) (contains? agg-ops (first t)) (dvar? (second t)))
    (str (agg-ops (first t)) "(" (cvar (second t)) ")")
    :else (throw (ex-info (str "unsupported rule-head term: " (pr-str t)) {:term t}))))

;; ── inline measures: (measure ?out (agg ?var) body…) lifted to aux rules ──────
;; Cozo aggregates only in rule heads, so an inline measure is lifted at compile time into a
;; content-addressed auxiliary rule and replaced with a call binding the inferred GROUP vars +
;; the out var — the same synthesis move as the not-join helpers and predicate-ports.

(defn- vars-of
  "Every ?var in `form`, first-appearance order, deduped."
  [form]
  (distinct (filter dvar? (tree-seq coll? seq form))))

(defn- measure-clause? [c] (and (seq? c) (= 'measure (first c))))

(defn- parse-measure
  "Validate `(measure ?out (agg ?v) body…)` → {:out :agg :avar :body}. Throws on malformed."
  [[_ out agg-form & body :as c]]
  (let [[agg avar] (when (seq? agg-form) agg-form)]
    (when-not (and (dvar? out) (seq? agg-form) (= 2 (count agg-form))
                   (contains? agg-ops agg) (dvar? avar) (seq body))
      (throw (ex-info (str "malformed measure clause — want (measure ?out (agg ?var) body…), agg one of "
                           (keys agg-ops) ": " (pr-str c)) {:clause c})))
    (when (some #{out} (vars-of body))
      (throw (ex-info (str "measure out-var " out " must not appear in its body: " (pr-str c)) {:clause c})))
    (when-not (some #{avar} (vars-of body))
      (throw (ex-info (str "measure aggregate var " avar " must appear in its body: " (pr-str c)) {:clause c})))
    {:out out :agg agg :avar avar :body (vec body)}))

(defn- expand-measures
  "Lift each TOP-LEVEL `(measure ?out (agg ?v) body…)` in `clauses` into a synthesized aggregate
   rule, replacing it with a call binding the inferred group vars + `?out`. Group vars = body
   vars ∩ (sibling-clause vars ∪ `outer-vars`) — Soufflé-style inference; `outer-vars` carries
   the enclosing scope (find vars / rule-head vars / law offenders). Returns [clauses' aux-rules];
   an aux body may itself contain measures — they expand when the aux rule is compiled (nesting
   falls out of the recursion). Note the inner-join semantics: an empty group yields NO row (not
   a zero) — callers wanting defaults supply them after the query.
   ⚠ Body-local var names are NOT private across sibling measure clauses: grouping inference
   tree-walks the sibling clauses INCLUDING other measures' bodies, so a var reused as a body
   local in two sibling measures becomes a group key of both — and an aggregate var that also
   appears in a sibling clause or the find spec becomes a group key, degenerating the aggregate
   to per-value counts. Give each measure body fresh local names."
  [outer-vars clauses]
  (reduce (fn [[cs rules] c]
            (if (measure-clause? c)
              (let [{:keys [out agg avar body]} (parse-measure c)
                    shared (set (concat outer-vars (vars-of (remove #{c} clauses))))
                    gvars  (vec (filter shared (vars-of body)))
                    head   (symbol (helper-name "measure_" [gvars c]))]
                [(conj cs (apply list head (conj gvars out)))
                 (conj rules (into [(apply list head (conj gvars (list agg avar)))] body))])
              [(conj cs c) rules]))
          [[] []] clauses))

(defn- compile-clause
  "One datalog clause → `[cozo-fragment extra-rules refs]` (PURE): `extra-rules` are the not-join/
   or-join helper definitions it spawns (uniquely named by content), `refs` the set of rule names it
   CALLS (so the program emits just the reachable rules). Throws on an unsupported form."
  [c]
  (cond
    (and (vector? c) (= 3 (count c)) (keyword? (nth c 1)))
    (compile-datom c)
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
    (and (seq? c) (= 'measure (first c)))
    (throw (ex-info (str "(measure …) is not supported inside not-join/or-join — lift it to the top level: "
                         (pr-str c)) {:clause c}))
    ;; (is ?v <qualified-tag>) — the ns-precise sort pin. Declaration forms resolve the sort
    ;; SYMBOL to its tag at parse (structure/resolve-sorts); here the resolved tag LOWERS: a
    ;; direct tag → the `:structure/of` triple; a realized/facet concept (no direct instances)
    ;; → its kind-rule call. Recursion through not/not-join/or-join bodies comes free.
    (and (seq? c) (= 'is (first c)))
    (let [[_ v tag] c]
      (when-not (and (= 3 (count c)) (keyword? tag))
        (throw (ex-info (str "(is ?var <qualified-tag>) — a bare sort symbol resolves only in a "
                             "declaration form; evaluated contexts pass the tag: " (pr-str c))
                        {:clause c})))
      (let [sdef (structure/structure-by-tag tag)]
        (when-not sdef
          (throw (ex-info (str "(is …): no structure registered for " tag) {:clause c :tag tag})))
        (when (:relation-element sdef)
          (throw (ex-info (str "(is …): " tag " is a relation element, not a sort: " (pr-str c))
                          {:clause c :tag tag})))
        (compile-clause
         (if (contains? (structure/direct-scope-tags [sdef]) tag)
           [v :structure/of tag]
           (list (symbol (name tag)) v)))))
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
  "A datalog rule `[(head args…) body…]` → `[def-lines refs]`: the head line, any not-join/
   or-join helpers, and any lifted-measure aux rules its body spawned (compiled recursively —
   nested measures fall out), plus the rule names its body calls (PURE). A head arg may be an
   aggregate application `(agg ?v)` (see `chead`) — the rule is then a MEASURE."
  [[head & body]]
  (let [[body* aux]          (expand-measures (filter dvar? (rest head)) body)
        [bodystr extra refs] (compile-clauses body*)
        [aux-lines aux-refs] (reduce (fn [[ls rs] r]
                                       (let [[l rf] (compile-rule (dewild r))]
                                         [(into ls l) (into rs rf)]))
                                     [[] #{}] aux)]
    [(concat [(str (rname (first head)) "[" (str/join ", " (map chead (rest head))) "] := " bodystr)]
             extra aux-lines)
     (into refs aux-refs)]))

;; ── the vocab-rule index + reachability closure ───────────────────────────────
(defn ^{:malli/schema [:=> [:cat] :any]}
  vocab-index
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
          @synthetic-rules (structure/vocab-rules)))

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

(defn ^{:malli/schema [:=> [:cat :any :any :any :any] :any]}
  compile-body
  "Compile `where` (a seq of clauses) + caller-supplied `extra-rules` (datalog rules) into
   `[rule-lines body-str]`: the vocab rules in the reference closure, then the extra rules,
   then any not-join/or-join helpers (deduped), and the joined where body. Shared by the
   law engine (`compile-law`) and `q`. PURE — `_` wildcards are expanded (per scope) first,
   then top-level `(measure …)` clauses are lifted to aux rules (`expand-measures`), with
   `outer-vars` (find vars / law offenders) counting toward grouping inference."
  [where extra-rules index outer-vars]
  (let [where               (dewild where)
        [where* aux]        (expand-measures outer-vars where)
        extra-rules         (into (vec extra-rules) aux)
        [rule-lines erefs]  (reduce (fn [[lines refs] r]
                                      (let [[l rf] (compile-rule (dewild r))]
                                        [(into lines l) (into refs rf)]))
                                    [[] #{}] extra-rules)
        [body extra wrefs]  (compile-clauses where*)
        vocab-lines         (mapcat #(:lines (index %)) (closure index (into erefs wrefs)))]
    [(distinct (concat vocab-lines rule-lines extra)) body]))

(def ^:private attr-bucket-cache
  "Caches `attr-buckets` per db handle (compared by `identical?`) — the index is read once per
   build, not once per query."
  (atom nil))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  buckets-of
  "`attr-buckets` for `cdb`, memoized on the db handle."
  [cdb]
  (let [c @attr-bucket-cache]
    (if (and c (identical? (:db c) cdb))
      (:idx c)
      (let [idx (attr-buckets cdb)]
        (reset! attr-bucket-cache {:db cdb :idx idx})
        idx))))

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

(defn- lookup-ref?
  "A lookup-ref `[attr val]` (an attribute keyword + a value)."
  [v] (and (vector? v) (= 2 (count v)) (keyword? (first v))))

(defn- resolve-lookup
  "Resolve a lookup-ref `[attr val]` to its NATIVE (Int) eid by reading the typed bucket `val`'s
   type lands in (the `entity/id` lookups carry string ids → t_str). Returns nil for no match.
   Native, not stringified: a string eid would compile to a QUOTED literal and never match the
   Int subject column."
  [cdb [attr val]]
  (let [a (subs (str attr) 1)
        [rel cv] (cond
                   (boolean? val) ["t_bool" val]
                   (integer? val) ["t_int" val]
                   (keyword? val) ["t_str" (subs (str val) 1)]
                   :else          ["t_str" val])]
    (ffirst (db/q cdb (str "?[e] := *" rel "[e, '" a "', v], v == $v") {:v cv}))))

(defn- resolve-param
  "A query `:in` scalar param → the value substituted into the where body: a lookup-ref is
   resolved to its native eid; any other scalar passes through unchanged."
  [cdb v] (if (lookup-ref? v) (resolve-lookup cdb v) v))

(defn ^{:malli/schema [:=> [:cat :CozoDb :any] :any]}
  q
  "Run datalog `query` over the Cozo db `db`: the compiled datalog subset fukan uses —
   relation/collection finds, `:in` of `$` + optional `%` rules + scalar params incl.
   `[attr val]` lookup-refs. Top-level `(path ?from E ?to)` clauses are expanded
   after scalar substitution, so path endpoints may be query inputs. EIDS come back as opaque
   NATIVE (Int) handles; leaf values in their real Int/String/Bool type. A relation find
   returns a SET of tuples; a collection find a distinct vector."
  [query db & inputs]
  (let [{:keys [find in where]} (split-query query)
        {:keys [rules subst]}   (bind-inputs in inputs)
        subst   (into {} (map (fn [[k v]] [k (resolve-param db v)]) subst))
        ;; scalar params bind only the WHERE-body's vars; a `%` rule's vars are head-scoped
        ;; and never close over query `:in` inputs — substituting a scalar into a rule would
        ;; corrupt its head (e.g. a shared name like `?op`), so the rules are passed verbatim.
        where*  (structure/expand-clauses (walk/postwalk-replace subst where))
        [rule-lines body] (binding [*attr-buckets* (buckets-of db)]
                            (compile-body where* (vec rules) (vocab-index) (find-vars find)))
        head    (str/join ", " (map cvar (find-vars find)))
        program (str (str/join "\n" rule-lines) "\n?[" head "] := " body)
        rows    (db/q db program)]
    (if (collection-find? find)
      (vec (distinct (map first rows)))
      (set rows))))

;; ── entity: eid → attribute map (the d/entity replacement) ─────────────────────
(defn ^{:malli/schema [:=> [:cat :CozoDb :any] :any]}
  entity
  "Resolve `eid` to its attribute map (the `d/entity` replacement): reads the typed buckets,
   so values come back in their real Int/String/Bool types (eid is a native handle), returning
   `{attr-keyword value}` (nil for an unknown eid). `eid` may be an opaque string/number handle
   OR an `[attr val]` lookup-ref (resolved to the matching eid first)."
  [db eid]
  (when-let [eid (if (lookup-ref? eid) (resolve-lookup db eid) (str eid))]
    (let [rows (mapcat (fn [bucket]
                         (db/q db (str "?[a, v] := *" bucket "[e, a, v], e == " eid)))
                       ["t_int" "t_str" "t_bool"])
          m    (reduce (fn [acc [a v]] (assoc acc (keyword a) v)) {} rows)]
      (when (seq m) (assoc m :db/id eid)))))

;; `violation-names` (the worklist reader) lives in `fukan.cozo.law` alongside `check`/`violations-of`
;; — it reads check results, so it belongs with evaluation, not in the compiler.
