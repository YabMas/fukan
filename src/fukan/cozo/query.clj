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
(defn ^{:malli/schema [:=> [:cat :any] :string]} cvar
  "?e → e — a datalog var → a CozoScript variable name, every non-alphanumeric char folded to
   `_` the way `rname` folds a rule name. A datalog var is an ordinary Clojure symbol and
   `?from-band` is the natural way to write one; without the fold it lowers to the invalid
   identifier `from-band`, and the law it appears in fails closed as UNDECIDABLE. Offender var
   names travel to consumers now (`law/check`'s `:vars`), so they are chosen to READ, and a
   compiler that refused the readable spelling would be choosing the wrong master."
  [t]
  (str/replace (subs (name t) 1) #"[^A-Za-z0-9]" "_"))
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
  "A datalog rule head/call symbol → a CozoScript rule name: `r_`-prefixed over the algebra's
   identifier fold (`structure/rule-name`), which covers the WHOLE symbol — `module-depends` →
   `r_module_depends`, `fukan.common.vocab.code.operation/Operation` →
   `r_fukan_common_vocab_code_operation_Operation`. Cozo accepts a name that long.

   The fold is the algebra's and not this compiler's so that the registry can refuse a sort-name
   collision over the same one; folding only `(name sym)` here is what dropped the namespace and
   made a derived sort's kind rule answer across namespaces."
  [sym]
  (str "r_" (structure/rule-name sym)))


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
  "A `[?e :attr ?v]` clause → `[fragment extra-rules refs call-args]`. Single bucket (the
   overwhelming majority) → direct stored-relation access, which is the whole point. Several → a
   per-attribute union helper, named by attribute so it dedupes across clauses, called with the
   clause's subject and value (`call-args`, as `compile-clause` reports them)."
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
         #{}
         [(nth c 0) (nth c 2)]]))))

;; ── synthetic rules: cozo ports of Clojure fn-predicates ──────────────────────
;; A `[(pred ?a ?b)]` clause whose `pred` is a Clojure fn (not a datalog rule) can't be a
;; CozoScript filter when one arg is otherwise unbound — Cozo requires every helper-rule
;; head var range-restricted. So such a predicate is ported as a GENERATING rule (both
;; sides produced, then filtered), merged into the vocab index and pulled by the closure.
(def ^:private synthetic-rules
  "rule-name → {:lines :refs} CozoScript defs that registered predicate-ports reference (seeded into
   the index). Vocab-contributed via `register-predicate-port!` — the kernel seeds none."
  (atom {}))

(def ^:private port-generation
  "Bumped by every predicate-port registration — the engine's half of the key `vocab-index` caches
   under (the kernel's half is `structure/vocabulary-generation`)."
  (atom 0))

(defn ^{:malli/schema [:=> [:cat] [:vector :int]]}
  compiled-generation
  "What the compiled world is made of: the kernel's vocabulary generation and the engine's port
   generation. Anything derived from the rules — the compiled index here, the law results in
   `cozo/law` — keys its memo on this, so there is ONE answer to `has the vocabulary moved` and
   two caches cannot disagree about it."
  []
  [(structure/vocabulary-generation) @port-generation])

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
  ;; the compiled index is seeded from `synthetic-rules`, so a port arriving after one was built
  ;; must retire it — the engine's own half of the vocabulary generation
  (swap! port-generation inc)
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

(defn- clause-kind
  "What clause `c` IS, as the clause compiler reads it — the ONE classification `compile-clause`
   dispatches on and every other reader of a clause asks, so none of them can read a clause as a
   shape the compiler does not: `:datom` (`[?e :attr ?v]`), `:predicate` (`[(f …)]`, exactly one
   call), `:not`, `:not-join`, `:or-join`, `:measure`, `:is`, `:call` (a rule call), or nil for a
   form the compiler refuses."
  [c]
  (cond
    (vector? c) (cond (and (= 3 (count c)) (keyword? (nth c 1))) :datom
                      (and (= 1 (count c)) (seq? (first c)))     :predicate)
    (and (seq? c) (symbol? (first c)))
    (case (first c)
      not :not, not-join :not-join, or-join :or-join, measure :measure, is :is
      :call)))

(defn- disjunct-clauses
  "The clauses of one `or-join` disjunct: an `(and …)`'s, else the disjunct itself."
  [d]
  (if (and (seq? d) (= 'and (first d))) (rest d) [d]))

(defn- measure-clause? [c] (= :measure (clause-kind c)))

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

;; ── clause shapes ────────────────────────────────────────────────────────────
(defn- predicate-clause?
  "True for a predicate clause — `[(= ?a ?b)]`, `[(starts-with? ?n ?p)]`: a one-element vector
   wrapping a call. Distinguishes it from a datom clause `[?e :attr ?v]` and a rule call
   `(rule ?a ?b)`, neither of which is order-sensitive here."
  [c]
  (= :predicate (clause-kind c)))

(defn- literal-datom?
  "True for a datom clause whose VALUE is a literal — `[?r :rel/kind :calls]` — and for a sort pin
   that lowers to one (`structure/pin-clause` answers by triple for a stored sort, by rule for a
   genus). With the entity free that is a seek on the `(a, v)` index rather than a scan of the
   attribute, which is what `order-expansion` needs to know to open an unanchored body."
  [c]
  (case (clause-kind c)
    :datom (not (dvar? (nth c 2)))
    :is    (if (and (= 3 (count c)) (keyword? (nth c 2)))
             (recur (structure/pin-clause (nth c 2) (nth c 1)))
             false)
    false))

(defn- negation? [c] (contains? #{:not :not-join} (clause-kind c)))

(defn- visible-vars
  "The vars of `c` its SIBLINGS can see. A `not-join` exposes its join vars and nothing else — the
   rest are private to the helper rule it compiles to, and a sibling that happens to reuse one of
   their names shares nothing with it."
  [c]
  (if (= :not-join (clause-kind c))
    (filter dvar? (second c))
    (vars-of c)))

(defn- bound-by
  "The vars `c` leaves BOUND for the clauses after it — none, for a negation."
  [c]
  (if (negation? c) () (vars-of c)))

(defn- plain-rule-call?
  "A positive rule call — the only clause shape inlining touches. `not`/`not-join`/`or-join` are
   left alone: `not (A, B)` is not `not A, B`, and the join helpers already compile to their own
   rules. `is`/`measure` are handled by the clause compiler.

   A call under a negation therefore stays a rule call. That is a statement about SOUNDNESS — the
   expansion is not equivalent — and no longer doubles as the reason a filtered generator survives
   inside a `not-join`: `inline-index` refuses those outright, so the two decisions agree instead
   of one silently standing in for the other."
  [c]
  (= :call (clause-kind c)))

(defn- compile-clause
  "One datalog clause → `[cozo-fragment extra-rules refs call-args]` (PURE): `extra-rules` are the
   not-join/or-join helper definitions it spawns (uniquely named by content), `refs` the set of rule
   names it CALLS (so the program emits just the reachable rules), and `call-args` — present only
   when the fragment IS a rule call, a `not`'s when what it negates is one — the datalog terms that
   call passes, in order. Throws on an unsupported form."
  [c]
  (case (clause-kind c)
    :datom
    (compile-datom c)
    :predicate
    (let [[frag refs] (compile-predicate (first c))] [frag nil refs])
    :not
    (if (= 2 (count c))                                   ; (not <single-clause>)
      (let [[frag extra refs args] (compile-clause (second c))] [(str "not " frag) extra refs args])
      ;; (not c1 c2 …) — use (not-join […] …)
      (throw (ex-info (str "multi-clause `not` is unsupported — write it as `not-join`: " (pr-str c)) {:clause c})))
    :not-join
    (let [[_ vars & clauses] c
          hn (helper-name "nj_" c)
          vs (str/join ", " (map cvar vars))
          [body extra refs] (compile-clauses clauses)]
      [(str "not " hn "[" vs "]") (cons (str hn "[" vs "] := " body) extra) refs vars])
    :or-join
    (let [[_ vars & disjuncts] c
          hn (helper-name "oj_" c)
          vs (str/join ", " (map cvar vars))
          parts (map (comp compile-clauses disjunct-clauses) disjuncts)]
      [(str hn "[" vs "]")
       (concat (map (fn [[body _ _]] (str hn "[" vs "] := " body)) parts)
               (mapcat second parts))
       (reduce into #{} (map #(nth % 2) parts))
       vars])
    :measure
    (throw (ex-info (str "(measure …) is not supported inside not-join/or-join — lift it to the top level: "
                         (pr-str c)) {:clause c}))
    ;; (is ?v <qualified-tag>) — the ns-precise sort pin. Declaration forms resolve the sort
    ;; SYMBOL to its tag at parse (structure/resolve-sorts); here the resolved tag LOWERS
    ;; through the algebra, which owns how a sort is pinned (`structure/pin-clause`). Recursion
    ;; through not/not-join/or-join bodies comes free.
    :is
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
        (compile-clause (structure/pin-clause tag v))))
    :call
    (let [nm (rname (first c))]
      [(str nm "[" (str/join ", " (map cterm (rest c))) "]") nil #{nm} (rest c)])
    (throw (ex-info (str "unsupported clause: " (pr-str c)) {:clause c}))))

(defn- compile-clauses
  "Compile a seq of where-clauses → `[joined-body extra-rule-lines refs]` (PURE — refs/extras unioned)."
  [clauses]
  (let [rs (mapv compile-clause clauses)]
    [(str/join ", " (map first rs))
     (mapcat second rs)
     (reduce into #{} (map #(nth % 2) rs))]))

;; ── rule INLINING: a view is a rule, and a rule has no key ────────────────────
;; The same defect the `triple` view had, one level up. A vocab rule (`contains`, `calls`, a
;; single-bodied defrelation) is materialized by Cozo before the join that reads it, and a
;; materialized relation carries no key — so every hop through one degrades to a scan. Measured
;; on brian (898 ns / 10,673 fns / 20,903 call edges), the same `ns-depends` join:
;;
;;   through the emitted rules ......... 123,633 ms
;;   inlined, expansions UNORDERED ..... 305,740 ms   ← inlining alone makes it WORSE
;;   inlined + expansions ORIENTED ......... 1,007 ms
;;
;; Both halves are required. Cozo executes a body largely in WRITTEN ORDER, so the first datom of
;; an expansion decides the hop: `*t_int[r, 'rel/from', a]` with `a` bound is a constrained probe,
;; the same clause with `a` free is a scan of every `rel/from` datom. `order-expansion` therefore
;; re-orients each expanded body against what the preceding clauses have already bound.

(def ^:private inline-max-depth
  "How deep a rule call expands. Bounds pathological or mutually-recursive single-body rules
   without a cycle analysis — at the cap the call is simply left as a rule call."
  6)

(def ^:private inline-max-clauses
  "Expansion size ceiling. Past this a call stays a rule call rather than flooding the body."
  48)

(defn- subst-form
  "Simultaneous variable substitution through `form` (one walk, so a var never re-substitutes).
   A form's metadata is kept: it is how a refusal knows which written clause an expansion came
   from (`mark`)."
  [form m]
  (cond
    (and (symbol? form) (contains? m form)) (m form)
    (vector? form) (cond-> (mapv #(subst-form % m) form) (meta form) (with-meta (meta form)))
    (seq? form)    (cond-> (apply list (map #(subst-form % m) form)) (meta form) (with-meta (meta form)))
    :else          form))

(defn- filtered-generator?
  "True when `body` FILTERS on a var its head does not expose — a rule that generates a cross
   product and cuts it down. Only top-level predicates count: one inside a `not`/`not-join`
   constrains that negation's own scope, not the rows the rule yields."
  [params body]
  (let [head (set params)]
    (boolean (some #(and (predicate-clause? %) (not-every? head (vars-of %))) body))))

(defn ^:private ^{:malli/schema [:=> [:cat :any] :any]}
  inline-index
  "The vocab rules that are INLINABLE — `rule-name → {:params :body}`. A rule qualifies when it
   has exactly ONE definition (a multi-bodied head is a union or a recursion, which must
   materialize), its head args are distinct plain vars (no aggregate heads), and its body does not
   FILTER on a var the head hides. Purely a datalog-level analysis of the rule forms, so it needs
   no compilation and can be built before the compiled index.

   ⚠ THAT LAST CLAUSE IS THE COMPANION TO THE INVARIANT ABOVE, one level up: that one says how to
   compile an inlined rule, this says when not to inline one at all. A rule filtering only on head
   vars is a genuine VIEW — folding it costs nothing, and the win the invariant measures is the
   whole reason inlining exists. A rule filtering on INTERIOR vars is a generator with a
   selectivity: its cross product is what the filter cuts down, and inlining lifts that product
   inside whatever join the call site sits in, where it is paid once per row instead of once.
   Measured on brian, a band's `in-band` — 121 prefixes × 904 namespaces cut to 1,070 rows —
   inlined twice into a 3,273-edge namespace-dependency join cost 27.1s against 4.1s left as a
   rule; ~85% of the law was the fold. It is untouched by any index, because the rows are computed
   rather than stored."
  [rules]
  (into {} (for [[nm defs] (group-by #(rname (ffirst %)) rules)
                 :when (= 1 (count defs))
                 :let  [[head & body] (first defs)
                        args (vec (rest head))]
                 :when (and (seq args) (every? dvar? args) (apply distinct? args)
                            (not (filtered-generator? args body)))]
             [nm {:params args :body (vec body)}])))

(defn- expand-call
  "One rule call → its substituted body, expanded transitively (depth-capped). Non-param body
   vars are renamed per call site (`?i<n>_<k>`) so two expansions in one body never collide.
   Returns `[clauses n']`, or nil when the call is not inlinable / too deep / too large."
  [c idx depth n]
  (when (and (plain-rule-call? c) (< depth inline-max-depth))
    (when-let [{:keys [params body]} (get idx (rname (first c)))]
      (let [args (vec (rest c))]
        (when (and (= (count args) (count params)) (every? #(or (dvar? %) (not (coll? %))) args))
          (let [inner (remove (set params) (vars-of body))
                fresh (into {} (map-indexed (fn [i v] [v (symbol (str "?i" n "_" i))]) inner))
                sub   (merge fresh (zipmap params args))
                body* (mapv #(subst-form % sub) body)]
            (loop [pending body*, out [], n (inc n)]
              (cond
                (empty? pending)                 [out n]
                (> (count out) inline-max-clauses) [(into out pending) n]
                :else
                (let [[c* & more] pending]
                  (if-let [[cs n*] (expand-call c* idx (inc depth) n)]
                    (recur (vec more) (into out cs) n*)
                    (recur (vec more) (conj out c*) n)))))))))))

(defn- order-expansion
  "Order an expanded body so each clause runs as constrained as it can be: repeatedly take the
   clause sharing the most vars with what is already bound, earliest-first on ties. This is the
   half that matters — see the measurements above.

   Re-ordering is SAFE for a `not` landing ahead of the clauses that bind it: Cozo does its own
   binding analysis over the whole body rather than positionally (measured — `not *t_bool[e,…],
   *t_str[e, 'entity/name', n]` answers exactly as the reverse order does). It is not HARMLESS,
   because this loop is positional even where Cozo is not: a clause taken is a clause whose vars
   count as bound from then on, and a negation binds nothing. Taken early it seeds `bound` with
   vars nobody has produced, and every later clause is scored against them — `[?r :rel/to ?t]`
   chosen as a probe on a `?t` that is still free, which is the scan this function exists to
   avoid. Measured on fukan's own model, the signature-agreement law: 258ms → 744ms. So a negation
   is held back until what it mentions is bound, exactly as a predicate is, and it is judged on
   its `visible-vars` — a `not-join`'s private vars are nobody's business but its helper's.

   It is NOT safe for a PREDICATE. That analysis covers relational atoms; a predicate compiles
   to an expression — a comparison, or a registered predicate port's function call — and a
   function call evaluated before its argument is bound fails outright (`starts_with(n, p)`
   ahead of what binds `p`: \"'starts_with' requires strings or bytes\"). A predicate is
   therefore held back until every var it mentions is bound, falling back to written order when
   no such clause remains (a predicate over vars an OUTER clause binds later can only go last).

   `assumed` names vars to ORDER against without treating them as bound — the head vars of a rule
   whose caller is expected to supply them. They pull the clauses that constrain them forward,
   which is the whole point, but they must not license a predicate: the caller's binding is Cozo's
   business, and a `starts_with` emitted ahead of what this body binds fails outright.

   TIES are broken before written order gets a say, because written order is the author's and the
   author was writing logic, not a plan. Two breaks, in this order, both measured on brian:

   A var this body HAS bound outranks one a caller MAY bind. An assumed var is a bet — it pays
   only when the call site binds it, and a rule read under a `not` is evaluated whole, with
   nothing bound at all. Scoring the two alike let `[(declared-dep ?fr ?tr) (reg-within ?fr ?fa)
   (reg-within ?tr ?ta) (may-depend ?fa ?ta)]` keep its written order: the second `reg-within`
   tied with the `may-depend` hop on one var each, won on position, and the body opened with a
   cross product the hop then cut to four rows. With an 11,044-row `reg-within` that is 122M pairs
   and did not finish in ten minutes; the hop taken first — a chain, no product — is 773ms.

   A LITERAL outranks nothing. A datom with a literal value is an `(a, v)` index seek; the same
   attribute with both ends free is a scan of every datom carrying it. So an expansion that shares
   no var with what precedes it — `(interior ?owner ?interior)` after a join that binds neither —
   must open on its `:rel/kind`, not on the `:rel/from` that happens to be written first: one row
   against every relation in the model, per row of the join it landed in (52.9s → 1.9s).

   Both are TIE-breaks and deliberately no more: the var count still decides first, so an ordering
   the var count already settled is exactly what it was."
  ([clauses bound] (order-expansion clauses bound #{}))
  ([clauses bound assumed]
   (loop [pending (vec clauses), bound bound, out []]
     (if (empty? pending)
       out
       (let [ready? (fn [i] (let [c (pending i)]
                              (or (not (or (predicate-clause? c) (negation? c)))
                                  (every? bound (visible-vars c)))))
             scored (into bound assumed)
             score  (fn [i] (let [c (pending i), vs (visible-vars c)]
                              [(count (filter scored vs))
                               (count (filter bound vs))
                               (if (literal-datom? c) 1 0)]))
             idxs   (range (count pending))
             usable (filterv ready? idxs)
             pool   (if (seq usable) usable (vec idxs))
             best   (reduce (fn [b i] (if (pos? (compare (score i) (score b))) i b))
                            (first pool) (rest pool))
             c      (pending best)]
         (recur (into (subvec pending 0 best) (subvec pending (inc best)))
                (into bound (bound-by c))
                (conj out c)))))))

(defn ^:private ^{:malli/schema [:=> [:cat :any :any] :any]}
  inline-clauses
  "Source-to-source: expand every inlinable rule call in `clauses` into its body, each expansion
   oriented against the vars the preceding clauses have already bound. Clause order as WRITTEN is
   otherwise preserved — only the interior of an expansion is re-ordered, so an author's negation
   and predicate placement still hold."
  [clauses idx]
  (if (empty? idx)
    (vec clauses)
    (loop [pending (vec clauses), bound #{}, n 0, out []]
      (if (empty? pending)
        out
        (let [[c & more] pending]
          (if-let [[cs n*] (expand-call c idx 0 n)]
            (let [cs* (order-expansion cs bound)]
              (recur (vec more) (into bound (mapcat bound-by cs*)) n* (into out cs*)))
            (recur (vec more) (into bound (bound-by c)) n (conj out c))))))))

(defn- rule-as-compiled
  "The body `compile-rule` compiles for `rule` — its measures lifted (`expand-measures`), its rule
   calls inlined by `inl`, the result oriented against the head — as `{:body clauses :aux rules}`,
   `:aux` the lifted measure rules, each compiled as a rule in turn."
  [[head & body] inl]
  (let [head-vars   (filter dvar? (rest head))
        [body* aux] (expand-measures head-vars body)
        body*       (cond-> body* inl (inline-clauses inl))]
    {:body (order-expansion body* #{} (set head-vars)) :aux aux}))

(defn- compile-rule
  "A datalog rule `[(head args…) body…]` → `[def-lines refs]`: the head line, any not-join/
   or-join helpers, and any lifted-measure aux rules its body spawned (compiled recursively —
   nested measures fall out), plus the rule names its body calls (PURE). A head arg may be an
   aggregate application `(agg ?v)` (see `chead`) — the rule is then a MEASURE.

   The body is ORIENTED AGAINST ITS OWN HEAD VARS — the same transform `inline-clauses` applies to
   an expansion, for the same reason and with the other side of the call in mind. A rule that
   survives to be emitted is one the compiler could not fold away, and Cozo evaluates such a call
   under the caller's bindings; so the head args are what a call site can be expected to have
   bound, and a body whose first clause ignores them opens with an unconstrained scan repeated per
   binding. Measured on brian: the `fulfils` derivation, whose satisfier definition is authored
   leading with a `[?r :rel/from _]` that binds neither head var, cost 175.4s as the second
   definition of the namespace-dependency graph and 0.09s with the clause binding `?a` moved
   first — the same 27 rows.

   Orienting is the whole transform; HOISTING the call is not the answer and was measured to be
   the opposite of one. Cozo restricts a rule call by the caller's bindings, so moving a call to
   the front of its body removes the restriction: doing that globally took fukan's own 93 laws
   from 0.87s to 236s."
  ([rule] (compile-rule rule nil))
  ([[head :as rule] inl]
  (let [{body* :body aux :aux} (rule-as-compiled rule inl)
        [bodystr extra refs] (compile-clauses body*)
        [aux-lines aux-refs] (reduce (fn [[ls rs] r]
                                       (let [[l rf] (compile-rule (dewild r) inl)]
                                         [(into ls l) (into rs rf)]))
                                     [[] #{}] aux)]
    [(concat [(str (rname (first head)) "[" (str/join ", " (map chead (rest head))) "] := " bodystr)]
             extra aux-lines)
     (into refs aux-refs)])))

;; ── the vocab-rule index + reachability closure ───────────────────────────────
(defn- compile-vocab-index
  "The compile `vocab-index` caches — `structure/vocab-rules` folded into
   `rule-name → {:lines :refs}`."
  []
  (let [rules (structure/vocab-rules)
        inl   (inline-index rules)]
    (-> (reduce (fn [idx rule]
                  (try
                    (let [[lines refs] (compile-rule rule inl)
                          nm           (rname (ffirst rule))]
                      (-> idx
                          (update-in [nm :lines] (fnil into []) lines)
                          (update-in [nm :refs] (fnil into #{}) refs)
                          (update ::kept conj rule)))
                    (catch clojure.lang.ExceptionInfo _ idx)))
                (assoc @synthetic-rules ::kept []) rules)
        ;; carried on the index so `compile-body` reaches them without a signature change; the keys
        ;; are keywords and rule names are strings, so neither can shadow a rule. `::rules` is the
        ;; raw rule FORMS — a scope with its own `%` rules rebuilds the inline index over the
        ;; MERGED set, which is the only way to tell a caller's redefinition from the same rule
        ;; simply being passed through again. `::kept` is the definitions that COMPILED — the only
        ;; vocabulary bodies a program can hold, which is what a reader of a refusal walks.
        (assoc ::inline inl ::rules rules))))

(def ^:private vocab-index-cache
  "The last compiled index, with what it was compiled FROM: the vocabulary generation, and the
   attr→bucket map in force (compared by `identical?` — `buckets-of` already hands out one map per
   db). Both belong in the key: the rules come from the vocabulary, and the stored relation each
   clause reads comes from the db."
  (atom nil))

(defn ^{:malli/schema [:=> [:cat] :any]}
  vocab-index
  "The compiled index of the always-injected vocab rules — `rule-name → {:lines [cozo-defs]
   :refs #{names it calls}}`, a rule's multiple definitions merged, uncompilable rules skipped,
   the synthetic predicate-port rules seeded in.

   MEMOIZED against the vocabulary generation and the bucket index, because neither moves between
   queries on a held model while this compiles the whole rule set on every call — 86% of a trivial
   query's cost went here. Registering a structure, a correspondence or a predicate port bumps the
   generation, and a rebuilt db hands out a new bucket map, so a stale index cannot be served."
  []
  (let [gen (compiled-generation)
        c   @vocab-index-cache]
    (if (and c (= gen (:gen c)) (identical? *attr-buckets* (:buckets c)))
      (:index c)
      (let [idx (compile-vocab-index)]
        (reset! vocab-index-cache {:gen gen :buckets *attr-buckets* :index idx})
        idx))))

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
                            [(cond-> items (meta x) (with-meta (meta x))) n'])
              (seq? x)    (let [[items n'] (reduce (fn [[acc n] e] (let [[e' n'] (go e n)] [(conj acc e') n']))
                                                   [[] n] x)]
                            [(cond-> (apply list items) (meta x) (with-meta (meta x))) n'])
              :else       [x n]))]
    (first (go form 0))))


(defn- body-as-compiled
  "The where `compile-body` compiles, with the rules it compiles beside it and the inline index it
   compiles them under: `where` with its `_` made variables (`dewild`), its measures lifted to
   rules joining `extra-rules`, and its rule calls inlined — `{:where :rules :inline}`."
  [where extra-rules index outer-vars]
  (let [where               (dewild where)
        [where* aux]        (expand-measures outer-vars where)
        extra-rules         (into (vec extra-rules) aux)
        ;; Inlinability is decided over the rules IN SCOPE — the caller's `%` rules merged with the
        ;; vocab's, deduped by form. A name the caller REDEFINES then has two definitions and stops
        ;; being a view, so the call reaches the caller's rule (a test fixture's `:pair` slot
        ;; shadowing a `%`-supplied `pair` is how this surfaced); a name the caller merely passes
        ;; through again dedups to one and still inlines — which matters, because the readings hand
        ;; `q` the WHOLE vocab rule set as `%`.
        inl                 (if-let [vr (::rules index)]
                              (inline-index (distinct (concat extra-rules vr)))
                              (::inline index))
        where*              (cond-> where* (seq inl) (inline-clauses inl))]
    {:where where* :rules extra-rules :inline inl}))

(defn ^{:malli/schema [:=> [:cat :any :any :any :any] :any]}
  compile-body
  "Compile `where` (a seq of clauses) + caller-supplied `extra-rules` (datalog rules) into
   `[rule-lines body-str]`: the vocab rules in the reference closure, then the extra rules,
   then any not-join/or-join helpers (deduped), and the joined where body. Shared by the
   law engine (`compile-law`) and `q`. PURE — `_` wildcards are expanded (per scope) first,
   then top-level `(measure …)` clauses are lifted to aux rules (`expand-measures`), with
   `outer-vars` (find vars / law offenders) counting toward grouping inference."
  [where extra-rules index outer-vars]
  (let [{where* :where extra-rules :rules inl :inline} (body-as-compiled where extra-rules index outer-vars)
        [rule-lines erefs]  (reduce (fn [[lines refs] r]
                                      (let [[l rf] (compile-rule (dewild r) inl)]
                                        [(into lines l) (into refs rf)]))
                                    [[] #{}] extra-rules)
        [body extra wrefs]  (compile-clauses where*)
        vocab-lines         (mapcat #(:lines (index %)) (closure index (into erefs wrefs)))]
    [(distinct (concat vocab-lines rule-lines extra)) body]))

(def ^:private attr-bucket-cache
  "Caches `attr-buckets` per db handle (compared by `identical?`) AND write generation — the index
   is read once per state of the db, not once per query.

   The generation is not optional. A handle keeps its identity across `mirror/insert-datoms`, and
   an index that predates a write is not merely slow. An attribute it has never seen falls back to
   the three-way union, which is correct; an attribute it files under ONE bucket, then written at
   another type, compiles to a read of the old bucket alone — the new datoms are silently absent
   from every answer."
  (atom nil))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  buckets-of
  "`attr-buckets` for `cdb`, memoized on the db handle and `db/write-generation`."
  [cdb]
  (let [c   @attr-bucket-cache
        gen (db/write-generation)]
    (if (and c (identical? (:db c) cdb) (= gen (:gen c)))
      (:idx c)
      (let [idx (attr-buckets cdb)]
        (reset! attr-bucket-cache {:db cdb :gen gen :idx idx})
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

;; ── an engine refusal, as a sentence ───────────────────────────────────────────
;; Attribution reads the bodies `q` COMPILED — its where after the query's inputs were substituted
;; in, its paths expanded and its rule calls inlined, each rule the program emits — because that is
;; what the engine refused: an input changes which rule a `path` calls and which variables a
;; negation still has, and an inlined rule's negation is judged among its caller's clauses. What is
;; SHOWN is the clause its author wrote. Each clause OCCURRENCE carries that on its metadata
;; (`mark`), put there positionally before any of it is compiled — never looked up by value, so a
;; literal that equals a clause, or two clauses that compile alike, cannot take another's source.

(defn- sub-clauses
  "The clauses written inside clause `c`, as the clause compiler reads them — a `not`'s, a
   `not-join`'s after its join vars, each `or-join` disjunct (an `and`'s clauses), a `measure`'s
   body. Nothing else in a clause is a clause: a triple, a predicate, a rule call's arguments and
   any value an input put there are terms."
  [c]
  (case (clause-kind c)
    :not      (rest c)
    :not-join (drop 2 c)
    :or-join  (mapcat disjunct-clauses (drop 2 c))
    :measure  (drop 3 c)
    nil))

(defn- rebuild
  "`c` with its `sub-clauses` replaced, in order, by `subs` (its metadata kept). An `or-join`'s
   `and` disjunct takes as many as it held."
  [c subs]
  (if (empty? (sub-clauses c))
    c
    (with-meta
      (case (clause-kind c)
        :not      (apply list 'not subs)
        :not-join (apply list (concat (take 2 c) subs))
        :measure  (apply list (concat (take 3 c) subs))
        :or-join  (apply list (concat (take 2 c)
                                     (first (reduce (fn [[out subs] d]
                                                      (if (not= [d] (disjunct-clauses d))
                                                        (let [k (count (disjunct-clauses d))]
                                                          [(conj out (with-meta (apply list 'and (take k subs)) (meta d)))
                                                           (drop k subs)])
                                                        [(conj out (first subs)) (rest subs)]))
                                                    [[] subs] (drop 2 c))))))
      (meta c))))

(defn- mark
  "`c` with every clause occurrence in it — itself and its `sub-clauses` at any depth — carrying
   `::written` and `::source`: the clause at the same position of `w`, as its author wrote it, and
   of `s`, after inputs were substituted. Where `c` is not shaped like them (a `path` expanded into
   other clauses) every occurrence in it carries `w` and `s` themselves."
  [c w s]
  (let [cs (sub-clauses c), ws (sub-clauses w), ss (sub-clauses s)
        subs (if (= (count cs) (count ws) (count ss)) (map mark cs ws ss) (map #(mark % w s) cs))]
    (vary-meta (rebuild c subs) assoc ::written w ::source s)))

(defn- mark-rule
  "A rule whose body clauses each carry themselves as written and as source."
  [[head & body]]
  (into [head] (map #(mark % % %)) body))

(defn- written-of [c] (get (meta c) ::written c))
(defn- source-of [c] (get (meta c) ::source c))

(defn- clause-refs
  "The rule names the engine is handed for the compiled clause `c` — read off the clause compiler
   itself (`compile-clause`'s `refs`). A form the compiler refuses calls nothing."
  [c]
  (try (nth (compile-clauses (dewild [c])) 2)
       (catch clojure.lang.ExceptionInfo _ #{})))

(defn- rule-scopes
  "The bodies compiling `rule` under `inl` hands the engine — its own, as `compile-rule` compiles
   it, and those of the measure rules lifted out of it, compiled as `compile-rule` compiles them."
  [rule inl]
  (let [{:keys [body aux]} (rule-as-compiled rule inl)]
    (cons body (mapcat #(rule-scopes (dewild %) inl) aux))))

(defn- compiled-scopes
  "Every body the program for a query compiles, derived through the compiler's OWN steps, so a name
   it generates — a `path`'s intermediate variable, a `_` made a variable, an inlined rule's
   renamed interior — is the name the engine was handed: the where — `written` as authored,
   `substituted` once its inputs were put in — expanded whole by `structure/expand-clauses` as `q`
   expands it, then made into the body `compile-body` compiles (`body-as-compiled`) with `outer`
   the query's find vars; every rule that compiles beside it — the `%` `rules` and the lifted
   measures — as `compile-rule` compiles it; and every vocabulary rule a call in those reaches, as
   the vocabulary index compiles it — of `kept`, the definitions that index compiled, and no
   other: one it skipped is in no program. A rule only ever inlined is no body the engine sees.

   Each clause is `mark`ed with what was written before any of that runs; a `path` expands into
   clauses that carry the path clause's marks. The expansion of the whole where is split back into
   the clauses it came from by the length each expands to alone — the same clauses, under names
   indexed by position."
  [written substituted rules outer kept]
  (let [vocab    (map mark-rule (structure/vocab-rules))
        index    {::rules vocab ::inline (inline-index vocab)}
        by-name  (group-by #(rname (ffirst %)) (map mark-rule kept))
        calls    (fn [body] (into #{} (mapcat clause-refs) body))
        expanded (structure/expand-clauses (vec substituted))
        where    (loop [[w & ws] written, [s & ss] substituted, out (seq expanded), acc []]
                   (if (nil? s)
                     acc
                     (let [k (count (structure/expand-clauses [s]))]
                       (recur ws ss (drop k out) (into acc (map #(mark % w s)) (take k out))))))
        {where :where rules :rules inl :inline} (body-as-compiled where (map mark-rule rules) index outer)]
    (loop [scopes (into [where] (mapcat #(rule-scopes (dewild %) inl)) rules)
           seen   #{}
           todo   (vec (mapcat calls scopes))]
      (if-let [n (peek todo)]
        (let [todo (pop todo)]
          (if (seen n)
            (recur scopes seen todo)
            (let [more (mapcat #(rule-scopes % (::inline index)) (by-name n))]
              (recur (into scopes more) (conj seen n) (into todo (mapcat calls more))))))
        scopes))))

(defn- clause-terms
  "The terms clause `c` holds where the clause compiler reads a TERM — a triple's positions, a
   predicate's or a rule call's arguments, a join-var list, a measure's out and aggregate vars —
   and, through `sub-clauses`, those of every clause written inside it. A collection in a term
   position is ONE term: the compiler writes it as a quoted value, so nothing inside it is a
   variable or a wildcard."
  [c]
  (case (clause-kind c)
    :datom                 c
    :predicate             (rest (first c))
    :measure               (concat [(second c) (second (nth c 2))] (mapcat clause-terms (sub-clauses c)))
    :not                   (mapcat clause-terms (sub-clauses c))
    (:not-join :or-join)   (concat (second c) (mapcat clause-terms (sub-clauses c)))
    (:is :call)            (rest c)
    ()))

(defn- calling-clauses
  "The innermost clauses of the compiled `body` that call the rule named `missing`: every clause
   at any depth whose compiled refs hold it, less one that does only because a clause inside it
   does."
  [body missing]
  (let [hits (fn hits [c]
               (let [inner (mapcat hits (sub-clauses c))]
                 (cond (seq inner)              inner
                       ((clause-refs c) missing) [c])))]
    (mapcat hits body)))

(defn- called-name
  "The name the author of `call` wrote for the rule the engine knows as `missing`: the clause's
   head when that is the rule, else the relation named inside it (a `path` calls its relations,
   and their closures under a name derived from theirs) — the longest whose compiled name `missing`
   starts with. The compiled name less its prefix when the clause names neither."
  [call missing]
  (let [h (first call)]
    (if (and (symbol? h) (= missing (rname h)))
      (str h)
      (or (->> (tree-seq coll? seq (rest call))
               (filter #(or (keyword? %) (and (symbol? %) (not (dvar? %)) (not= '_ %))))
               (map (comp str symbol))
               (filter #(str/starts-with? missing (rname (symbol %))))
               (sort-by (comp - count))
               first)
          (subs missing 2)))))

(defn- negation-scopes
  "`body` and every helper body the clause compiler spawns inside it — a `not-join`'s clauses, each
   `or-join` disjunct — each its own scope, as the helper rule it compiles to is: what binds a
   variable in one says nothing about another. (A measure is no clause of a compiled body: it was
   lifted into a rule, which `compiled-scopes` gives as a scope of its own.)"
  [body]
  (cons body
        (mapcat (fn [c]
                  (case (clause-kind c)
                    :not-join            (negation-scopes (sub-clauses c))
                    :or-join             (mapcat (comp negation-scopes disjunct-clauses) (drop 2 c))
                    :not                 (rest (negation-scopes (sub-clauses c)))
                    nil))
                body)))

(defn- plain-refusal
  "The engine's refusal `data` as plain text: its code, its message and its help, and nothing of
   the script it reported against. What every refusal says at the least."
  [{:keys [code message help]}]
  (str "The engine refused this query (" code "): " message (when help (str ". " help))))

(defn- refusal
  "The engine's refusal `data` (the ex-data cozo throws: `:code`, `:message`, `:help`) of a query,
   as one plain-text sentence. `scopes` are the bodies the query compiled to (`compiled-scopes`),
   each clause of them carrying the clause its author wrote (`mark`).

   The engine reports against the script this compiler generated, which the author of a clause
   never sees, so the two refusals an author can act on are restated in terms of what they
   wrote: a call to a rule that does not exist names the calling clause, and an unsafe negation
   names the negation nothing binds. Any other refusal carries the engine's own message. No span of
   generated script and no terminal escape survives into it."
  [scopes {:keys [code message] :as data}]
  (let [said    (fn [& parts] (str "The engine refused this query (" code "): " (apply str parts)))
        quoted  #(str/join ", " (distinct (map (comp pr-str written-of) %)))]
    (case code
      "eval::rule_not_found"
      (let [missing (second (re-find #"rule (\S+)" (str message)))
            calls   (mapcat #(calling-clauses % missing) scopes)
            names   (distinct (map #(called-name (source-of %) missing) calls))]
        (if (empty? calls)
          (said message ".")
          (said (quoted calls) " calls a rule named `" (str/join "`, `" names)
                "`, and no rule of that name exists."
                (when (some #{"or"} names)
                  " Alternatives are written `(or-join [?vars] clause clause …)`."))))

      "eval::unsafe_negation"
      ;; The engine says only that some negation is unsafe. Which one is read off the compiled
      ;; bodies, scope by scope — a helper body is judged against its own clauses, as the rule it
      ;; compiles to is — by the rule the engine was measured to apply: a negated RULE CALL is
      ;; refused when none of its arguments is a value and none is a variable a positive clause
      ;; beside it binds. So a negation is a candidate only where it compiles to a rule call, with
      ;; that call's arguments — read off the clause compiler itself (`compile-clause`'s
      ;; `call-args`), never off the clause's kind: a `not-join` or an `or-join` calls its helper, a
      ;; triple calls an attribute's union helper when the attribute spans buckets and otherwise reads
      ;; a stored relation, which is never refused. A value among the arguments makes it safe
      ;; whether an input put it there or its author wrote it, and a `_` is a variable nothing
      ;; binds. Variables are compared as the engine sees them (`cvar`: `?a-b` and `?a_b` are one).
      ;; This names the culprit after a refusal and decides nothing — when it singles none out,
      ;; every candidate is named rather than a wrong one. A variable is reported by the name its
      ;; author wrote: terms are paired by position with the written clause's, since inlining
      ;; renames a rule's interior variables and a `_` reaches the engine as a fresh variable.
      (let [pairs   (fn [n] (let [ct (clause-terms n), wt (clause-terms (written-of n))]
                              (when (= (count ct) (count wt)) (map vector ct wt))))
            args    (fn [n] (try (nth (compile-clause n) 3 nil)
                              (catch clojure.lang.ExceptionInfo _ nil)))
            binds   (fn [c] (cond (negation? c)                        ()
                                  (= :or-join (clause-kind c))          (filter dvar? (second c))
                                  :else                                (filter dvar? (clause-terms c))))
            judged  (for [scope (mapcat negation-scopes scopes)
                          :let [bound (into #{} (map cvar) (mapcat binds scope))]
                          n scope
                          :when (negation? n)
                          :let [terms (args n)]
                          :when (seq terms)
                          :let [vars  (filter #(or (dvar? %) (= '_ %)) terms)
                                wild? (into #{'_} (keep (fn [[c w]] (when (= '_ w) c))) (pairs n))
                                as    (into {} (filter #(every? dvar? %)) (pairs n))]]
                      {:negation n
                       :unbound  (map #(get as % %) (remove #(or (wild? %) (bound (cvar %))) vars))
                       :culprit? (and (= (count vars) (count terms))
                                      (not-any? #(and (not= '_ %) (bound (cvar %))) vars))})
            culprits (filter :culprit? judged)
            named    (if (seq culprits) culprits judged)
            vars     (sort (distinct (mapcat :unbound named)))
            named    (map :negation named)]
        (said "a negation is only safe once a positive clause binds its variables"
              (when (seq named) (str ", and nothing binds those of " (quoted named)))
              ". Write a positive clause that binds "
              (if (seq vars) (str/join ", " vars) "a variable of the negation")
              " before negating."
              (when (empty? vars) " A `_` binds nothing: a negation needs a variable a positive clause binds.")))

      (plain-refusal data))))

(defn- run
  "Run the compiled `program` for the query whose where-clauses are `where` as written and
   `substituted` once its inputs were put in, whose `%` rules are `rules`, whose find vars are
   `outer`, and whose vocabulary index kept the definitions `kept`. An engine refusal is rethrown
   as its `refusal` sentence — attributed against every body the query compiled to, compiled
   against the attribute buckets `q` compiled it against, worded in the clauses as written — with
   the engine's `:code` and `:message` kept as data.

   Naming a clause is best effort and the sentence is not: whatever attribution throws, the
   refusal is still rethrown as plain text carrying the engine's code and its own message."
  [db program where substituted rules outer kept]
  (try (db/q db program)
       (catch clojure.lang.ExceptionInfo e
         (let [data (ex-data e)]
           (if (:code data)
             (throw (ex-info (try (binding [*attr-buckets* (buckets-of db)]
                                    (refusal (compiled-scopes where substituted rules outer kept) data))
                                  (catch Throwable _ (plain-refusal data)))
                             {:code (:code data) :engine-message (:message data)}))
             (throw e))))))

(defn ^{:malli/schema [:=> [:cat :any :CozoDb [:* :any]] :any]}
  q
  "Run datalog `query` over the Cozo db `db`: the compiled datalog subset fukan uses —
   relation/collection finds, `:in` of `$` + optional `%` rules + scalar params incl.
   `[attr val]` lookup-refs. Top-level `(path ?from E ?to)` clauses are expanded
   after scalar substitution, so path endpoints may be query inputs. EIDS come back as opaque
   NATIVE (Int) handles; leaf values in their real Int/String/Bool type. A relation find
   returns a SET of tuples; a collection find a distinct vector.

   A query the engine refuses throws a plain-text sentence naming the engine's error code, and
   the offending clause as it was written where the refusal is about one (`refusal`)."
  [query db & inputs]
  (let [{:keys [find in where]} (split-query query)
        {:keys [rules subst]}   (bind-inputs in inputs)
        subst   (into {} (map (fn [[k v]] [k (resolve-param db v)]) subst))
        ;; scalar params bind only the WHERE-body's vars; a `%` rule's vars are head-scoped
        ;; and never close over query `:in` inputs — substituting a scalar into a rule would
        ;; corrupt its head (e.g. a shared name like `?op`), so the rules are passed verbatim.
        substituted (walk/postwalk-replace subst where)
        where*  (structure/expand-clauses substituted)
        [rule-lines body kept]
        (binding [*attr-buckets* (buckets-of db)]
          (let [index (vocab-index)]
            (conj (compile-body where* (vec rules) index (find-vars find)) (::kept index))))
        head    (str/join ", " (map cvar (find-vars find)))
        program (str (str/join "\n" rule-lines) "\n?[" head "] := " body)
        rows    (run db program where substituted rules (find-vars find) kept)]
    (if (collection-find? find)
      (vec (distinct (map first rows)))
      (set rows))))

;; ── entity: eid → attribute map (the d/entity replacement) ─────────────────────
(defn ^{:malli/schema [:=> [:cat :CozoDb :any] :any]}
  entity
  "Resolve `eid` to its attribute map (the `d/entity` replacement): reads the typed buckets,
   so values come back in their real Int/String/Bool types (eid is a native handle), returning
   `{attr-keyword value}` (nil for an unknown eid). `eid` is a native Int handle OR an
   `[attr val]` lookup-ref (resolved to the matching eid first).

   The eid goes in the atom's KEY position — the same ⚠ INVARIANT the clause compiler observes,
   one level below the rules it was written about. `*t_str[e, a, v], e == <eid>` leaves the key
   free and filters, so it SCANS the bucket; `*t_str[<eid>, a, v]` seeks it. Measured on brian
   (12k structures): 22.7ms per call against 0.075ms — 300x, and this is the read every offender
   render makes four times over.

   A NON-INT eid throws rather than answering. Cozo has no way to tell a bare name in key
   position from a variable, so `*t_int[Clause, a, v]` does not error — it binds `Clause` and
   matches every row, which is silently the whole db instead of one entity. (Interpolating it
   unquoted did error, which is how a dangling type-reference used to abort a whole run.)"
  [db eid]
  (when-let [eid (if (lookup-ref? eid) (resolve-lookup db eid) eid)]
    (when-not (int? eid)
      (throw (ex-info (str "entity needs an Int eid (or a lookup-ref), got " (pr-str eid))
                      {:eid eid})))
    (let [rows (mapcat (fn [bucket]
                         (db/q db (str "?[a, v] := *" bucket "[" eid ", a, v]")))
                       ["t_int" "t_str" "t_bool"])
          m    (reduce (fn [acc [a v]] (assoc acc (keyword a) v)) {} rows)]
      (when (seq m) (assoc m :db/id eid)))))

;; `violation-names` (the worklist reader) lives in `fukan.cozo.law` alongside `check`/`violations-of`
;; — it reads check results, so it belongs with evaluation, not in the compiler.
