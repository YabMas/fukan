(ns fukan.cozo.law
  "The law engine on Cozo — compile a defstructure law's datalog (offenders + where, plus the
   rules it reads at) → CozoScript via the general query compiler (`fukan.cozo.query`), run it, and
   return offenders. THIS is `check`: it evaluates the laws the kernel DEFINES (`structure/laws-of`).
   Evaluation lives here (the engine) rather than as a hollow shell in the kernel — the kernel owns
   definition, the engine owns evaluation, and the dependency runs one way (engine → kernel), so
   there is no cycle and no registry to break one.

   HYBRID: the auto-generated scalar TYPE-CHECK laws don't compile — they validate a leaf value
   through the malli dialect (`typing/value-valid?`), which has no CozoScript form. So
   `check-structural` runs them split: Cozo finds each instance's leaf value (in its typed bucket),
   Clojure runs the malli check. Everything else compiles to pure CozoScript.

   `check` is the fail-closed violation view; `check-structural` is the full per-law roll-call
   (including coverage/`:unsupported`). `violations-of`/`violation-names` are the worklist readers
   over `check`."
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

(defn ^{:malli/schema [:=> [:cat :any :any :any] :string]}
  compile-law
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
        [rule-lines body] (query/compile-body where* rules index offenders)]
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
        validate-value (fn [v]
                         ;; The mirror stores keyword scalar values in t_str without the
                         ;; leading colon, matching query literal compilation. Rehydrate for
                         ;; the hybrid type-law check so :keyword slots validate the authored
                         ;; value rather than the storage encoding.
                         (if (= :keyword target) (keyword v) v))
        rows   (mapcat (fn [bucket]
                         (db/q cdb (str "?[x, v] := *" bucket "[x, '" attr-s "', v], "
                                        "*t_str[x, 'structure/of', '" tag-s "']")))
                       ["t_int" "t_str" "t_bool"])]
    (->> rows
         (filter (fn [[_ v]] (false? (typing/value-valid? target (validate-value v)))))
         (mapv (fn [[x _]] [(str x)])))))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  check-structural
  "Run every law over the Cozo db `cdb`, returning `[{:structure :law :offenders}]` (offenders
   = matched eid-string tuples) for laws that fire, and `{:structure :law :unsupported true}`
   for laws whose form (or a vocab rule they read) isn't compiled yet. A type-check law runs
   the hybrid (`value-offenders`); everything else compiles to CozoScript and runs."
  [cdb]
  (let [index       (query/vocab-index)
        direct-tags (structure/direct-scope-tags (structure/all-structures))]
    (vec (for [[tag law] (all-laws)]
           (cond
             (value-check-law law)
             (let [offs (value-offenders cdb (value-check-law law))]
               (cond-> {:structure tag :law (:desc law)}
                 (:key law) (assoc :key (:key law))
                 (seq offs) (assoc :offenders offs)))

             :else
             (let [program (try (compile-law law direct-tags index)
                                (catch clojure.lang.ExceptionInfo _ ::unsupported))]
               (if (= program ::unsupported)
                 {:structure tag :law (:desc law) :unsupported true}
                 (try
                   (let [rows (db/q cdb (str query/preamble "\n" program))]
                     (cond-> {:structure tag :law (:desc law)}
                       (:key law) (assoc :key (:key law))
                       (seq rows) (assoc :offenders (vec rows))))
                   (catch clojure.lang.ExceptionInfo _
                     {:structure tag :law (:desc law) :unsupported true})))))))))

(defn ^{:malli/schema [:=> [:cat :CozoDb] :any]}
  check
  "Run every law over the Cozo db `cdb` and return its VIOLATIONS — `[{:structure :law
   :offenders}]` (offenders are eid-string tuples). THE check: it runs the same laws the kernel
   DEFINES (`structure/laws-of`/`all-structures`), which is why check lives here in the engine and
   not as a hollow shell in the kernel — evaluation is the engine's job, the kernel's is definition.
   Satisfaction is FAIL-CLOSED: if any law cannot be compiled or evaluated, throws with every
   unsupported law in ex-data. An unevaluated sentence is neither satisfied nor refuted, so returning
   a green violation list would be a false claim."
  [cdb]
  (let [results     (check-structural cdb)
        unsupported (vec (filter :unsupported results))]
    (when (seq unsupported)
      (throw (ex-info (str "cannot decide model satisfaction: " (count unsupported)
                           " law(s) could not be evaluated")
                      {:unsupported unsupported})))
    (vec (for [r results :when (:offenders r)]
           (select-keys r [:structure :law :key :offenders])))))

(defn- known-law-keys
  "Every `:key` across the laws `check` runs — the addressable worklist surface, from the
   same registry source (`all-laws`) check evaluates."
  []
  (into #{} (keep (comp :key second)) (all-laws)))

(defn ^{:malli/schema [:=> [:cat :CozoDb :keyword] :any]}
  violations-of
  "The offender eids of the law keyed `k` — the generic reader behind every law-specific worklist
   fn (filter `check` by the law's stable `:key`, first offender var). Returns a set of eid strings;
   callers name them through `violation-names`.

   An UNKNOWN key throws (naming the known keys): a reader addressing a retired or misspelled
   law must fail the moment it runs, not report an empty worklist forever — the reader-side
   symmetric of the duplicate-law-key registration guard."
  [cdb k]
  (let [known (known-law-keys)]
    (when-not (contains? known k)
      (throw (ex-info (str "no law keyed " k " — known keys: " (str/join ", " (sort known)))
                      {:key k :known known})))
    (->> (check cdb) (filter #(= k (:key %))) (mapcat :offenders) (map first) set)))

(defn ^{:malli/schema [:=> [:cat :CozoDb :keyword] [:set :string]]}
  violation-names
  "The `:entity/name` of every offender of the law keyed `k` — `violations-of` (offender eids)
   resolved through `query/entity`. The one home for the recurring worklist-reader shape."
  [cdb k]
  (set (map #(:entity/name (query/entity cdb %)) (violations-of cdb k))))
