(ns fukan.canvas.projection.probes
  "Implemented probes — the LLM-authored leaves whose specs are projected from the model.
   Each is a pure (model-db -> finding) reader. `run-probe` is the registry: each leaf
   self-registers via `defmethod run-probe`, and `run`/`run-all` are the live entry:
   run a probe against the held model.
   probe-patterns implements the Instruction projected from the `patterns` probe;
   probe-integrity realizes the modelled `integrity` probe by composing the kernel's
   `check`."
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as structure]
            [fukan.canvas.projection.finding :as f]
            [fukan.target.correspondence :as corr]))

(defn- probe-patterns
  "Recurring structures (a reading): one observation per structural triplet
   (source-tag, relation-kind, target-tag) borne by more than one reified relation.
   The focus is every matching relation-node plus its endpoints. Scopable to `focus`."
  ([db] (probe-patterns db nil))
  ([db focus]
   (let [in?    (if focus (set focus) (constantly true))
         rows   (d/q '[:find ?r ?f ?ft ?rk ?t ?tt
                       :where [?r :rel/from ?f] [?r :rel/kind ?rk] [?r :rel/to ?t]
                              [?f :structure/of ?ft] [?t :structure/of ?tt]] db)
         groups (->> rows
                     (filter (fn [[_ fr _ _ t _]] (and (in? fr) (in? t))))
                     (group-by (fn [[_ _ ft rk _ tt]] [ft rk tt])))]
     (f/finding "patterns" false
       (->> groups
            (filter (fn [[_ rs]] (> (count rs) 1)))
            (sort-by (comp - count val))
            (mapv (fn [[[ft rk tt] rs]]
                    (f/observation
                      (into #{} (mapcat (fn [[r fr _ _ t _]] [r fr t])) rs)
                      :pattern
                      (str (count rs) "× " ft " -[" rk "]-> " tt)))))))))

(defn- probe-integrity
  "The integrity inspect (a gating reading): the kernel's `check` (laws → violations),
   each violation an observation whose focus is its offender node-set. Empty ⇔ every
   law holds. Global — `focus` accepted for a uniform signature but ignored."
  ([db] (probe-integrity db nil))
  ([db _focus]
   (f/finding "integrity" true
     (mapv (fn [v]
             (f/observation (into #{} (mapcat identity) (:offenders v))
                            :violation (str v)))
           (structure/check db)))))

(defn- probe-survey
  "A structural overview (a reading): one observation per structure kind, its focus the
   nodes of that kind. Scopable to `focus`."
  ([db] (probe-survey db nil))
  ([db focus]
   (let [in? (if focus (set focus) (constantly true))]
     (f/finding "survey" false
       (->> (d/q '[:find ?e ?k :where [?e :structure/of ?k]] db)
            (filter (fn [[e _]] (in? e)))
            (reduce (fn [m [e k]] (update m k (fnil conj #{}) e)) {})
            (sort-by (comp - count val))
            (mapv (fn [[k es]] (f/observation es :count (str (count es) " " (name k))))))))))

(defn- probe-consistency
  "Operation-name ambiguity (a reading): one observation per Operation name borne by more than
   one module; the focus is the ambiguous Operation nodes. Scopable to `focus`."
  ([db] (probe-consistency db nil))
  ([db focus]
   (let [in?     (if focus (set focus) (constantly true))
         rows    (->> (d/q '[:find ?s ?sn ?mn
                             :where [?s :structure/of :lib.code/Operation] [?s :entity/name ?sn]
                                    [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?s]
                                    [?m :entity/name ?mn]] db)
                      (filter (fn [[s _ _]] (in? s))))
         by-name (reduce (fn [acc [s sn mn]]
                           (-> acc (update-in [sn :nodes] (fnil conj #{}) s)
                                   (update-in [sn :mods]  (fnil conj #{}) mn)))
                         {} rows)]
     (f/finding "consistency" false
       (->> by-name
            (filter (fn [[_ {:keys [mods]}]] (> (count mods) 1)))
            (sort-by key)
            (mapv (fn [[sn {:keys [nodes mods]}]]
                    (f/observation nodes :ambiguity
                      (str sn " in " (count mods) " modules: " (str/join ", " (sort mods)))))))))))

(defn- probe-tar-pit
  "Complexity hotspots (a reading): the top-10 nodes by relation degree (in + out),
   each its own single-node focus. Scopable to `focus`."
  ([db] (probe-tar-pit db nil))
  ([db focus]
   (let [in?  (if focus (set focus) (constantly true))
         out  (map second (d/q '[:find ?r ?e :where [?r :rel/from ?e]] db))
         ins  (map second (d/q '[:find ?r ?e :where [?r :rel/to ?e]] db))]
     (f/finding "tar-pit" false
       (->> (frequencies (concat out ins))
            (filter (fn [[e _]] (in? e))) (sort-by val >) (take 10)
            (mapv (fn [[e n]]
                    (let [ent (d/entity db e)]
                      (f/observation #{e} :hotspot
                        (str n " edges: " (or (:entity/name ent) "(value)")
                             " (" (name (:structure/of ent)) ")"))))))))))

(defn- probe-coverage
  "Spec ↔ code coverage (a gating reading): extracted Operations not covered by a
   Operation, each an observation whose focus is the uncovered Operation node(s). Empty ⇔
   every Operation is modelled. Global — `focus` accepted but ignored."
  ([db] (probe-coverage db nil))
  ([db _focus]
   (f/finding "coverage" true
     (->> (sort (corr/uncovered-operations db))
          (mapv (fn [n]
                  (f/observation
                    (->> (d/q '[:find ?o :in $ ?n
                                :where [?o :structure/of :lib.code/Operation] [?o :entity/name ?n]] db n)
                         (map first) set)
                    :gap n)))))))

(defn- probe-drift
  "Spec ↔ code divergence (a gating reading): modelled Operations not realized by an
   Operation, each an observation whose focus is the unrealized Operation node(s). Empty ⇔
   the model is fully realized. Global — `focus` accepted but ignored."
  ([db] (probe-drift db nil))
  ([db _focus]
   (f/finding "drift" true
     (->> (sort (corr/drifted-operations db))
          (mapv (fn [n]
                  (f/observation
                    (->> (d/q '[:find ?s :in $ ?n
                                :where [?s :structure/of :lib.code/Operation] [?s :entity/name ?n]] db n)
                         (map first) set)
                    :gap n)))))))

(defn- probe-type-drift
  "Spec ↔ code TYPE divergence (a gating reading): modelled Operations whose type disagrees
   with the realizing function's declared `:malli/schema`, each an observation whose focus is
   the type-drifted Operation node(s). Only checked where the code carries an annotation. Empty
   ⇔ every annotated function adheres to its model. Global — `focus` accepted but ignored."
  ([db] (probe-type-drift db nil))
  ([db _focus]
   (f/finding "type-drift" true
     (->> (sort (corr/type-drifted-operations db))
          (mapv (fn [n]
                  (f/observation
                    (->> (d/q '[:find ?s :in $ ?n
                                :where [?s :structure/of :lib.code/Operation] [?s :entity/name ?n]] db n)
                         (map first) set)
                    :gap n)))))))

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
(defmethod run-probe "tar-pit"     [db _ focus] (probe-tar-pit db focus))
(defmethod run-probe "integrity"   [db _ focus] (probe-integrity db focus))
(defmethod run-probe "coverage"    [db _ focus] (probe-coverage db focus))
(defmethod run-probe "drift"       [db _ focus] (probe-drift db focus))
(defmethod run-probe "type-drift"  [db _ focus] (probe-type-drift db focus))

(defn run
  "Run probe `probe-name` against `target-db`, optionally scoped to `focus`
   (a node-set). Dispatches through `run-probe`; the :default method throws for an
   unregistered name."
  ([target-db probe-name] (run target-db probe-name nil))
  ([target-db probe-name focus] (run-probe target-db probe-name focus)))

(defn run-all
  "Run every registered probe leaf against `target-db` -> {probe-name finding}."
  [target-db]
  (into (sorted-map)
        (for [pn (remove #{:default} (keys (methods run-probe)))]
          [pn (run-probe target-db pn nil)])))
