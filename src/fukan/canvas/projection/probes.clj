(ns fukan.canvas.projection.probes
  "Implemented probes — the LLM-authored leaves whose specs are projected from the model.
   Each is a pure (model-db -> finding) reader. `run-probe` is the registry: each leaf
   self-registers via `defmethod run-probe`, and `run`/`run-all` are the live entry:
   run a probe against the held model.
   probe-patterns implements the Instruction projected from the `patterns` probe.
   (The correspondence reports — integrity/coverage/drift/type-drift — are NOT probes; they
   are the law/correspondence substrate, surfaced directly by the dev helpers.)"
  (:require [clojure.string :as str]
            [fukan.cozo.query :as cq]
            [fukan.canvas.projection.finding :as f]))

;; the Cozo mirror stringifies keyword attr values (:structure/of, :rel/kind); re-keywordize for display
(defn- kw [x] (some-> x keyword))

(defn- probe-patterns
  "Recurring structures (a reading): one observation per structural triplet
   (source-tag, relation-kind, target-tag) borne by more than one reified relation.
   The focus is every matching relation-node plus its endpoints. Scopable to `focus`."
  ([db] (probe-patterns db nil))
  ([db focus]
   (let [in?    (if focus (set focus) (constantly true))
         rows   (cq/q '[:find ?r ?f ?ft ?rk ?t ?tt
                        :where [?r :rel/from ?f] [?r :rel/kind ?rk] [?r :rel/to ?t]
                               [?f :structure/of ?ft] [?t :structure/of ?tt]] db)
         groups (->> rows
                     (filter (fn [[_ fr _ _ t _]] (and (in? fr) (in? t))))
                     (group-by (fn [[_ _ ft rk _ tt]] [ft rk tt])))]
     (f/finding "patterns"
       (->> groups
            (filter (fn [[_ rs]] (> (count rs) 1)))
            ;; deterministic order: by descending count, then by the (keywordized) triplet key — so
            ;; equal-count groups don't depend on the engine's row order (ds vs cozo)
            (sort-by (fn [[k rs]] [(- (count rs)) (mapv (comp str kw) k)]))
            (mapv (fn [[[ft rk tt] rs]]
                    (f/observation
                      (into #{} (mapcat (fn [[r fr _ _ t _]] [r fr t])) rs)
                      :pattern
                      (str (count rs) "× " (kw ft) " -[" (kw rk) "]-> " (kw tt))))))))))

(defn- probe-survey
  "A structural overview (a reading): one observation per structure kind, its focus the
   nodes of that kind. Scopable to `focus`."
  ([db] (probe-survey db nil))
  ([db focus]
   (let [in? (if focus (set focus) (constantly true))]
     (f/finding "survey"
       (->> (cq/q '[:find ?e ?k :where [?e :structure/of ?k]] db)
            (filter (fn [[e _]] (in? e)))
            (reduce (fn [m [e k]] (update m k (fnil conj #{}) e)) {})
            (sort-by (comp - count val))
            (mapv (fn [[k es]] (f/observation es :count (str (count es) " " (name (kw k)))))))))))

(defn- probe-consistency
  "Operation-name ambiguity (a reading): one observation per Operation name borne by more than
   one module; the focus is the ambiguous Operation nodes. Scopable to `focus`."
  ([db] (probe-consistency db nil))
  ([db focus]
   (let [in?     (if focus (set focus) (constantly true))
         rows    (->> (cq/q '[:find ?s ?sn ?mn
                              :where [?s :structure/of :canvas.vocab.code.operation/Operation] [?s :entity/name ?sn]
                                     [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?s]
                                     [?m :entity/name ?mn]] db)
                      (filter (fn [[s _ _]] (in? s))))
         by-name (reduce (fn [acc [s sn mn]]
                           (-> acc (update-in [sn :nodes] (fnil conj #{}) s)
                                   (update-in [sn :mods]  (fnil conj #{}) mn)))
                         {} rows)]
     (f/finding "consistency"
       (->> by-name
            (filter (fn [[_ {:keys [mods]}]] (> (count mods) 1)))
            (sort-by key)
            (mapv (fn [[sn {:keys [nodes mods]}]]
                    (f/observation nodes :ambiguity
                      (str sn " in " (count mods) " modules: " (str/join ", " (sort mods)))))))))))

(defn- probe-callers
  "Realizes the `callers` lens (the `probe-` naming convention): the top-10 nodes by relation degree
   (in + out), each its own single-node focus — a coupling/hotspot reading. (This degree-ranking is
   richer than, and currently diverges from, the lens's `(calls ?n ?callee)` caller-selection;
   aligning them — or splitting out a real complexity angle — is deferred.) Scopable to `focus`."
  ([db] (probe-callers db nil))
  ([db focus]
   (let [in?  (if focus (set focus) (constantly true))
         out  (map second (cq/q '[:find ?r ?e :where [?r :rel/from ?e]] db))
         ins  (map second (cq/q '[:find ?r ?e :where [?r :rel/to ?e]] db))]
     (f/finding "callers"
       (->> (frequencies (concat out ins))
            (filter (fn [[e _]] (in? e))) (sort-by val >) (take 10)
            (mapv (fn [[e n]]
                    (let [ent (cq/entity db e)]
                      (f/observation #{e} :hotspot
                        (str n " edges: " (or (:entity/name ent) "(value)")
                             " (" (name (kw (:structure/of ent))) ")"))))))))))

(defmulti run-probe
  "The probe surface as a self-registering multimethod: dispatch on probe-name.
   A probe leaf registers by `(defmethod run-probe \"<name>\" [db _ focus] …)`, so
   adding a probe is dropping a method — symmetric with `render-base`. `focus` is a
   node-set the leaf reads through (nil = the whole model)."
  (fn [_db probe-name _focus] probe-name))

(defmethod run-probe :default [_ probe-name _]
  (throw (ex-info (str "no implemented probe " (pr-str probe-name))
                  {:probe probe-name
                   :available (vec (remove #{:default} (keys (methods run-probe))))})))

(defmethod run-probe "survey"      [db _ focus] (probe-survey db focus))
(defmethod run-probe "patterns"    [db _ focus] (probe-patterns db focus))
(defmethod run-probe "consistency" [db _ focus] (probe-consistency db focus))
(defmethod run-probe "callers"     [db _ focus] (probe-callers db focus))

(defn ^{:malli/schema [:=> [:cat :StructureDb :ProbeName] :Finding]}
  run
  "Run probe `probe-name` against `target-db`, optionally scoped to `focus`
   (a node-set). Dispatches through `run-probe`; the :default method throws for an
   unregistered name."
  ([target-db probe-name] (run target-db probe-name nil))
  ([target-db probe-name focus] (run-probe target-db probe-name focus)))

(defn ^{:malli/schema [:=> [:cat :StructureDb] :FindingMap]}
  run-all
  "Run every registered probe leaf against `target-db` -> {probe-name finding}."
  [target-db]
  (into (sorted-map)
        (for [pn (remove #{:default} (keys (methods run-probe)))]
          [pn (run-probe target-db pn nil)])))
