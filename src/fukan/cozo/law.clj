(ns fukan.cozo.law
  "The general law engine on Cozo — compile a defstructure law's datalog (offenders +
   where, plus the rules it reads at) → CozoScript over the unified `triple` view, run
   it, and return offenders: the Cozo analog of `structure/check`, the keystone that
   replaces it at cut-over. Built alongside datascript so the dual-engine oracle holds
   until it does.

   Two compilers, sharing one clause compiler:
     • `compile-clause` turns one datalog clause into a CozoScript fragment — datom
       patterns, `not`/`not-join`/`or-join`, the `not=` and `module-corresponds?`
       predicates, and RULE CALLS `(rule ?a ?b)` → `r_rule[A, B]`.
     • rules — the always-injected vocab rules (`structure/vocab-rules`: kind / relation /
       inclusion / `op-twin` / `in-module`) and each law's own `:rules` — compile to Cozo
       rules `r_name[…] := body`. A law emits only the vocab rules in its reference
       CLOSURE, so each program carries just what it reads.

   HYBRID: the auto-generated scalar TYPE-CHECK laws don't compile — they validate a leaf
   value through the malli dialect (`typing/value-valid?`), which has no CozoScript form. So
   `check-structural` runs them split: Cozo finds each instance's leaf value (in its typed
   bucket), Clojure runs the malli check. Everything else compiles to pure CozoScript.

   INCREMENTAL: a law using a not-yet-supported form (or referencing an uncompilable
   vocab rule) is reported `:unsupported` by `check-structural`, never silently skipped,
   so coverage gaps stay explicit."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as structure]
            [fukan.canvas.core.typing :as typing]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

(defn- dvar? [t] (and (symbol? t) (str/starts-with? (name t) "?")))
(defn- cvar [t] (subs (name t) 1))                          ; ?e → e
(defn- clit [v] (str \' (if (keyword? v) (subs (str v) 1) v) \'))  ; :calls → 'calls', true → 'true'
(defn- cterm [t] (if (dvar? t) (cvar t) (clit t)))
(defn- attr [kw] (subs (str kw) 1))                         ; :rel/from → rel/from
(defn- rname
  "A datalog rule head/call symbol → a CozoScript rule name: `r_`-prefixed, with every
   non-alphanumeric char (`-`, `?`, …) folded to `_` so it never clashes with `triple`,
   a reserved word, or a built-in (`op-twin` → `r_op_twin`, `Operation` → `r_Operation`)."
  [sym]
  (str "r_" (str/replace (name sym) #"[^A-Za-z0-9]" "_")))

;; ── synthetic rules: cozo ports of Clojure fn-predicates ──────────────────────
;; A `[(pred ?a ?b)]` clause whose `pred` is a Clojure fn (not a datalog rule) can't be
;; a CozoScript filter when one arg is otherwise unbound (a `not-join`/rule head var used
;; only in the predicate) — Cozo requires every helper-rule head var to be range-restricted.
;; So such a predicate is ported as a GENERATING rule (both sides produced, then filtered),
;; merged into the vocab index and pulled by the closure like any other rule.
(def ^:private synthetic-rules
  {;; module-corresponds?: cm (canvas module name) ↔ km (code namespace). Generate the
   ;; authored/extracted module names, then keep pairs where (separators normalized to '.')
   ;; km equals cm or has cm as a '.'-bounded suffix. The cozo port of `module-corresponds?`.
   "r_canvas_module" {:lines ["r_canvas_module[cm] := triple[m, 'structure/of', 'canvas.vocab.code.module/Module'], not triple[m, 'val/extracted', 'true'], triple[m, 'entity/name', cm]"]
                      :refs #{}}
   "r_code_module"   {:lines ["r_code_module[km] := triple[m, 'structure/of', 'canvas.vocab.code.module/Module'], triple[m, 'val/extracted', 'true'], triple[m, 'entity/name', km]"]
                      :refs #{}}
   "r_module_corresponds" {:lines ["r_module_corresponds[cm, km] := r_canvas_module[cm], r_code_module[km], cmn = regex_replace_all(cm, '-', '.'), kmn = regex_replace_all(km, '-', '.'), or(kmn == cmn, ends_with(kmn, concat('.', cmn)))"]
                           :refs #{"r_canvas_module" "r_code_module"}}})

(def ^:private predicate-registry
  "Clojure fn-predicate symbol → a builder `(arg-terms refs) → cozo-fragment` that emits a
   call to its synthetic rule (and records the rule name on `refs` so the closure emits it)."
  {'canvas.vocab.code.module/module-corresponds?
   (fn [[cm km] refs]
     (swap! refs conj "r_module_corresponds")
     (str "r_module_corresponds[" cm ", " km "]"))})

(declare compile-clause compile-clauses)

(defn- compile-predicate
  "A `(pred args…)` predicate (the content of a `[(…)]` clause) → a cozo fragment. `not=`
   is built in; a registered fn-predicate emits a call to its synthetic rule (via `refs`)."
  [[op & args] refs]
  (cond
    (= 'not= op) (str (cterm (first args)) " != " (cterm (second args)))
    (contains? predicate-registry op) ((predicate-registry op) (mapv cterm args) refs)
    :else (throw (ex-info (str "unsupported predicate: " (pr-str (cons op args))) {:pred op}))))

(defn- compile-clause
  "One datalog clause → `[cozo-fragment extra-rules]`. `counter` (an atom) names
   `not-join`/`or-join` helper rules uniquely; `refs` (an atom set) accrues every
   rule name the clause CALLS, so the program can emit just the reachable rules.
   Throws ex-info on an unsupported form."
  [c counter refs]
  (cond
    ;; [?e :attr ?v] — a datom pattern
    (and (vector? c) (= 3 (count c)) (keyword? (nth c 1)))
    [(str "triple[" (cterm (nth c 0)) ", '" (attr (nth c 1)) "', " (cterm (nth c 2)) "]") nil]
    ;; [(pred args…)] — a predicate (not= / a registered fn-predicate)
    (and (vector? c) (= 1 (count c)) (seq? (first c)))
    [(compile-predicate (first c) refs) nil]
    ;; (not <clause>) — negation of a single clause
    (and (seq? c) (= 'not (first c)))
    (let [[frag extra] (compile-clause (second c) counter refs)] [(str "not " frag) extra])
    ;; (not-join [vars] clauses…) — projected negation via a helper rule
    (and (seq? c) (= 'not-join (first c)))
    (let [[_ vars & clauses] c
          hn (str "nj_" (swap! counter inc))
          vs (str/join ", " (map cvar vars))
          [body extra] (compile-clauses clauses counter refs)]
      [(str "not " hn "[" vs "]") (cons (str hn "[" vs "] := " body) extra)])
    ;; (or-join [vars] disjunct…) — disjunction via one helper rule per disjunct
    (and (seq? c) (= 'or-join (first c)))
    (let [[_ vars & disjuncts] c
          hn (str "oj_" (swap! counter inc))
          vs (str/join ", " (map cvar vars))
          parts (map (fn [d]                                  ; each disjunct: (and c…) or a single clause
                       (compile-clauses (if (and (seq? d) (= 'and (first d))) (rest d) [d]) counter refs))
                     disjuncts)]
      [(str hn "[" vs "]")
       (concat (map (fn [[body _]] (str hn "[" vs "] := " body)) parts)
               (mapcat second parts))])
    ;; (rule-name args…) — a rule call
    (and (seq? c) (symbol? (first c)))
    (let [nm (rname (first c))]
      (swap! refs conj nm)
      [(str nm "[" (str/join ", " (map cterm (rest c))) "]") nil])
    :else
    (throw (ex-info (str "unsupported law clause: " (pr-str c)) {:clause c}))))

(defn- compile-clauses [clauses counter refs]
  (let [rs (mapv #(compile-clause % counter refs) clauses)]
    [(str/join ", " (map first rs)) (mapcat second rs)]))

(defn- compile-rule
  "A datalog rule `[(head args…) body…]` → its CozoScript definition line(s): the head
   line plus any `not-join`/`or-join` helpers its body spawned. Shares `counter`/`refs`."
  [[head & body] counter refs]
  (let [[bodystr extra] (compile-clauses body counter refs)]
    (cons (str (rname (first head)) "[" (str/join ", " (map cvar (rest head))) "] := " bodystr)
          extra)))

;; ── the vocab-rule index: every always-injected rule compiled once ────────────
(defn- vocab-index
  "Compile `structure/vocab-rules` (the always-injected vocab rules) once into an index
   `rule-name → {:lines [cozo-defs] :refs #{names it calls}}`, merging a rule's multiple
   definitions (e.g. `in-module`'s three). Uncompilable rules are skipped — a law that
   needs one then surfaces as `:unsupported` rather than crashing the whole check. The
   synthetic rules (fn-predicate ports) are merged in as the seed."
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
  "The set of vocab-rule names reachable from `seeds` through the index's `:refs` —
   so a law program carries only the rules it actually reads (plus their dependencies)."
  [index seeds]
  (loop [seen #{}, todo (vec seeds)]
    (if-let [n (peek todo)]
      (let [todo (pop todo)]
        (if (or (seen n) (not (contains? index n)))
          (recur seen todo)
          (recur (conj seen n) (into todo (:refs (index n))))))
      seen)))

(defn- scope-tag
  "The structure tag a free law's first offender var is scoped to (nil for :global
   and for self-scoped slot-derived laws), mirroring `check`."
  [{:keys [scope owner]}]
  (case scope :global nil, nil owner, scope))

(defn compile-law
  "Compile a law's offender query → a CozoScript program: the vocab rules in its
   reference closure, then its own `:rules`, then any `not-join`/`or-join` helpers, then
   the `?` entry. A non-global law's first offender var is bound by a prepended scope
   clause `[?o :structure/of tag]` for a DIRECT tag (`direct-tags`); a facet/realized
   scope rides a short-name rule and is not yet supported. `index` is the `vocab-index`."
  [{:keys [offenders where rules] :as law} direct-tags index]
  (let [st           (scope-tag law)
        scope-clause (when st
                       (if (contains? direct-tags st)
                         [(first offenders) :structure/of st]
                         (throw (ex-info "facet/realized scope not yet supported" {:scope st}))))
        where        (cond->> where scope-clause (cons scope-clause))
        counter      (atom 0)
        refs         (atom #{})
        rule-lines   (mapcat #(compile-rule % counter refs) rules)
        [body extra] (compile-clauses where counter refs)
        vocab-lines  (mapcat #(:lines (index %)) (closure index @refs))]
    ;; `distinct` collapses a rule the law inlines AND the vocab derives (e.g. in-module) to one copy.
    (str/join "\n" (concat (distinct (concat vocab-lines rule-lines extra))
                           [(str "?[" (str/join ", " (map cvar offenders)) "] := " body)]))))

(defn- all-laws
  "`[tag law]` for every law `check` would run — the same set, from the live registry."
  []
  (for [sdef (structure/all-structures), law (structure/laws-of sdef)]
    [(:tag sdef) law]))

;; ── the type-check hybrid: Cozo finds the leaf, Clojure runs malli ────────────
(defn- value-check-law
  "If `law` is an auto-generated scalar TYPE-CHECK law — its `:where` carries a
   `[(typing/value-valid? <target> ?v) ?ok]` clause (malli, not CozoScript-expressible) —
   return `{:tag :val-attr :target}`; else nil. The structof tag and `:val/<slot>` leaf
   attr are read off the same `:where`."
  [{:keys [where]}]
  (when-let [vv (some #(when (and (vector? %) (= 2 (count %)) (seq? (first %))
                                  (= 'fukan.canvas.core.typing/value-valid? (ffirst %)))
                         (first %))
                      where)]
    (let [vvar (nth vv 2)]
      {:target (second vv)
       :val-attr (some #(when (and (vector? %) (= 3 (count %)) (keyword? (nth % 1))
                                   (= vvar (nth % 2))) (nth % 1))
                       where)
       :tag (some #(when (and (vector? %) (= 3 (count %)) (= :structure/of (nth % 1))) (nth % 2))
                  where)})))

(defn- value-offenders
  "Run a type-check law as the hybrid: query each typed bucket for the instances of `tag`
   that carry a `val-attr` leaf (so the leaf keeps its real type — Int/String/Bool), keep
   the ones whose value fails the malli `target`, and return their eids as offender rows."
  [cdb {:keys [tag val-attr target]}]
  (let [tag-s  (subs (str tag) 1)
        attr-s (subs (str val-attr) 1)
        rows   (mapcat (fn [bucket]
                         (db/q cdb (str "?[x, v] := *" bucket "[x, '" attr-s "', v], "
                                        "*t_str[x, 'structure/of', '" tag-s "']")))
                       ["t_int" "t_str" "t_bool"])]
    (->> rows
         (filter (fn [[_ v]] (false? (typing/value-valid? target v))))
         (mapv (fn [[x _]] [(str x)])))))

(defn check-structural
  "Run every law over the Cozo db `cdb`, returning `[{:structure :law :offenders}]`
   (offenders = matched eid-string tuples) for laws that fire, and `{:structure :law
   :unsupported true}` for laws whose form (or a vocab rule they read) isn't compiled yet.
   A type-check law runs the hybrid (`value-offenders`); everything else compiles to
   CozoScript and runs. The Cozo analog of `structure/check`, incrementally."
  [cdb]
  (let [index       (vocab-index)
        direct-tags (structure/direct-scope-tags (structure/all-structures))]
    (vec (for [[tag law] (all-laws)]
           (if-let [vc (value-check-law law)]
             (let [offs (value-offenders cdb vc)]
               (cond-> {:structure tag :law (:desc law)} (seq offs) (assoc :offenders offs)))
             (let [program (try (compile-law law direct-tags index)
                                (catch clojure.lang.ExceptionInfo _ ::unsupported))]
               (if (= program ::unsupported)
                 {:structure tag :law (:desc law) :unsupported true}
                 (try
                   (let [rows (db/q cdb (str rules/triple "\n" program))]
                     (cond-> {:structure tag :law (:desc law)}
                       (seq rows) (assoc :offenders (vec rows))))
                   (catch clojure.lang.ExceptionInfo _
                     {:structure tag :law (:desc law) :unsupported true})))))))))
