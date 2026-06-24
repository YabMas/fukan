(ns fukan.cozo.law
  "The law engine on Cozo — compile a defstructure law's datalog (offenders + where, plus
   the rules it reads at) → CozoScript via the general query compiler (`fukan.cozo.query`),
   run it, and return offenders: the Cozo analog of `structure/check`, the keystone that
   replaces it at cut-over.

   HYBRID: the auto-generated scalar TYPE-CHECK laws don't compile — they validate a leaf
   value through the malli dialect (`typing/value-valid?`), which has no CozoScript form. So
   `check-structural` runs them split: Cozo finds each instance's leaf value (in its typed
   bucket), Clojure runs the malli check. Everything else compiles to pure CozoScript.

   `check` is the violation-only drop-in for `structure/check`; `check-structural` is the
   full per-law roll-call (incl. coverage/`:unsupported`)."
  (:require [clojure.string :as str]
            [fukan.canvas.core.structure :as structure]
            [fukan.canvas.core.typing :as typing]
            [fukan.cozo.db :as db]
            [fukan.cozo.query :as query]))

(defn- scope-tag
  "The structure tag a free law's first offender var is scoped to (nil for :global
   and for self-scoped slot-derived laws), mirroring `check`."
  [{:keys [scope owner]}]
  (case scope :global nil, nil owner, scope))

(defn compile-law
  "Compile a law's offender query → a CozoScript program: the vocab rules in its reference
   closure, its own `:rules`, helper rules, then the `?` entry. A non-global law's first
   offender var is bound by a prepended scope clause: `[?o :structure/of tag]` for a DIRECT
   tag (`direct-tags`), or the short-name rule-call `(Foo ?o)` for a facet/realized concept
   — mirroring `check`. `index` is the `query/vocab-index`."
  [{:keys [offenders where rules] :as law} direct-tags index]
  (let [st           (scope-tag law)
        scope-clause (when st
                       (if (contains? direct-tags st)
                         [(first offenders) :structure/of st]
                         (list (symbol (name st)) (first offenders))))
        where*       (cond->> where scope-clause (cons scope-clause))
        [rule-lines body] (query/compile-body where* rules index)]
    (str/join "\n" (concat rule-lines
                           [(str "?[" (str/join ", " (map query/cvar offenders)) "] := " body)]))))

(defn- all-laws
  "`[tag law]` for every law `check` would run — the same set, from the live registry."
  []
  (for [sdef (structure/all-structures), law (structure/laws-of sdef)]
    [(:tag sdef) law]))

;; ── the type-check hybrid: Cozo finds the leaf, Clojure runs malli ────────────
(defn- value-check-law
  "If `law` is an auto-generated scalar TYPE-CHECK law — its `:where` carries a
   `[(typing/value-valid? <target> ?v) ?ok]` clause (malli, not CozoScript-expressible) —
   return `{:tag :val-attr :target}`; else nil."
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
   carrying a `val-attr` leaf (so the leaf keeps its real type), keep the ones whose value
   fails the malli `target`, and return their eids as offender rows."
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
  "Run every law over the Cozo db `cdb`, returning `[{:structure :law :offenders}]` (offenders
   = matched eid-string tuples) for laws that fire, and `{:structure :law :unsupported true}`
   for laws whose form (or a vocab rule they read) isn't compiled yet. A type-check law runs
   the hybrid (`value-offenders`); everything else compiles to CozoScript and runs."
  [cdb]
  (let [index       (query/vocab-index)
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
                   (let [rows (db/q cdb (str query/preamble "\n" program))]
                     (cond-> {:structure tag :law (:desc law)}
                       (seq rows) (assoc :offenders (vec rows))))
                   (catch clojure.lang.ExceptionInfo _
                     {:structure tag :law (:desc law) :unsupported true})))))))))

(defn check
  "Run every law over the Cozo db `cdb` and return its VIOLATIONS — `[{:structure :law
   :offenders}]`, the same shape as `structure/check` (offenders are eid-string tuples).
   The Cozo replacement for `structure/check`. A law whose form isn't compilable yet
   contributes nothing (it is skipped, not silently miscounted — `check-structural` still
   reports it `:unsupported`); the law-engine tests assert every law of fukan's own
   vocabulary compiles, so on a fukan-only registry this is a complete check."
  [cdb]
  (vec (for [r (check-structural cdb) :when (:offenders r)]
         (select-keys r [:structure :law :offenders]))))
