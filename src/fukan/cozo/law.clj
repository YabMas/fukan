(ns fukan.cozo.law
  "The general law engine on Cozo — compile a defstructure law's datalog (offenders +
   where) → CozoScript over the unified `triple` view, run it, and return offenders:
   the Cozo analog of `structure/check`, the keystone that replaces it at cut-over.
   Built alongside datascript so the dual-engine oracle holds until it does.

   INCREMENTAL: `compile-clause` handles the forms fukan's laws use, growing one
   family at a time (datom patterns, `not`, `not-join`, the `not=` predicate so far —
   the slot-cardinality laws). A law using a not-yet-supported form is reported as
   `:unsupported` by `check-structural`, never silently skipped, so coverage gaps stay
   explicit."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as structure]
            [fukan.cozo.db :as db]
            [fukan.cozo.rules :as rules]))

(defn- dvar? [t] (and (symbol? t) (str/starts-with? (name t) "?")))
(defn- cvar [t] (subs (name t) 1))                          ; ?e → e
(defn- clit [v] (str \' (if (keyword? v) (subs (str v) 1) v) \'))  ; :calls → 'calls'
(defn- cterm [t] (if (dvar? t) (cvar t) (clit t)))

(declare compile-clause compile-clauses)

(defn- compile-clause
  "One datalog clause → `[cozo-fragment extra-rules]`. `counter` (an atom) names
   not-join helper rules uniquely. Throws ex-info on an unsupported form."
  [c counter]
  (cond
    ;; [?e :attr ?v] — a datom pattern
    (and (vector? c) (= 3 (count c)) (keyword? (nth c 1)))
    [(str "triple[" (cterm (nth c 0)) ", '" (subs (str (nth c 1)) 1) "', " (cterm (nth c 2)) "]") nil]
    ;; [(not= ?a ?b)] — the distinctness predicate
    (and (vector? c) (= 1 (count c)) (seq? (first c)) (= 'not= (ffirst c)))
    (let [[_ a b] (first c)] [(str (cterm a) " != " (cterm b)) nil])
    ;; (not <datom>) — negation of a single pattern
    (and (seq? c) (= 'not (first c)))
    (let [[frag _] (compile-clause (second c) counter)] [(str "not " frag) nil])
    ;; (not-join [vars] clauses…) — projected negation via a helper rule
    (and (seq? c) (= 'not-join (first c)))
    (let [[_ vars & clauses] c
          hn (str "nj_" (swap! counter inc))
          vs (str/join ", " (map cvar vars))
          [body extra] (compile-clauses clauses counter)]
      [(str "not " hn "[" vs "]") (cons (str hn "[" vs "] := " body) extra)])
    :else
    (throw (ex-info (str "unsupported law clause: " (pr-str c)) {:clause c}))))

(defn- compile-clauses [clauses counter]
  (let [rs (mapv #(compile-clause % counter) clauses)]
    [(str/join ", " (map first rs)) (mapcat second rs)]))

(defn- scope-tag
  "The structure tag a free law's first offender var is scoped to (nil for :global
   and for self-scoped slot-derived laws), mirroring `check`."
  [{:keys [scope owner]}]
  (case scope :global nil, nil owner, scope))

(defn compile-law
  "Compile a law's offender query → a CozoScript program (any not-join helper rules,
   then the `?` entry). A non-global law's first offender var is bound by a prepended
   scope clause `[?o :structure/of tag]` for a DIRECT tag (`direct-tags`); a
   facet/realized scope rides a short-name rule and is not yet supported. Slot-derived
   laws self-scope via their own `structof`."
  [{:keys [offenders where] :as law} direct-tags]
  (let [st           (scope-tag law)
        scope-clause (when st
                       (if (contains? direct-tags st)
                         [(first offenders) :structure/of st]
                         (throw (ex-info "facet/realized scope not yet supported" {:scope st}))))
        where        (cond->> where scope-clause (cons scope-clause))
        counter      (atom 0)
        [body extra] (compile-clauses where counter)]
    (str/join "\n" (concat extra
                           [(str "?[" (str/join ", " (map cvar offenders)) "] := " body)]))))

(defn- all-laws
  "`[tag law]` for every law `check` would run — the same set, from the live registry."
  []
  (for [sdef (structure/all-structures), law (structure/laws-of sdef)]
    [(:tag sdef) law]))

(defn check-structural
  "Run every law the compiler currently supports over the Cozo db `cdb`, returning
   `[{:structure :law :offenders}]` (offenders = matched eid-string tuples) for laws
   that fire, and `{:structure :law :unsupported true}` for laws whose form isn't
   compiled yet. The Cozo analog of `structure/check`, incrementally."
  [cdb]
  (let [preamble    (str rules/eav rules/triple)
        direct-tags (structure/direct-scope-tags (structure/all-structures))]
    (vec (for [[tag law] (all-laws)
               :let [program (try (compile-law law direct-tags) (catch clojure.lang.ExceptionInfo _ ::unsupported))]]
           (if (= program ::unsupported)
             {:structure tag :law (:desc law) :unsupported true}
             (let [rows (db/q cdb (str preamble "\n" program))]
               (cond-> {:structure tag :law (:desc law)}
                 (seq rows) (assoc :offenders (vec rows)))))))))
