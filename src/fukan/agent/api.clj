(ns fukan.agent.api
  "The agent's layered Model interface. The ONLY namespace the SCI sandbox
   exposes alongside fukan.agent.system. See AGENTS.md for orientation;
   call (help) for the live catalog."
  (:require [fukan.agent.edb :as edb]
            [fukan.agent.query :as query]
            [fukan.infra.model :as infra-model]))

;; -- Helpers ------------------------------------------------------------------

(defn- ensure-model
  "Return the currently loaded Model, or throw a `:model-not-loaded` ex-info."
  []
  (or (infra-model/get-model)
      (throw (ex-info "no model loaded" {:type :model-not-loaded}))))

;; -- L0 Kernel ----------------------------------------------------------------

(defn ^{:agent/layer :L0
        :agent/doc "Datalog over the loaded Model. Form: [:find … :where …]."
        :agent/example "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])"}
  q
  "Evaluate a Datalog query against the loaded Model.
   Returns a vector of binding maps keyed by :find variables."
  [form]
  (let [m (ensure-model)]
    (query/evaluate (query/parse form) (edb/model->edb m))))

;; -- L1 Probes ----------------------------------------------------------------

(def ^:private default-limit 1000)

(defn- summary [p]
  (select-keys p [:id :kind :label]))

(defn- apply-filters [p filters]
  (reduce-kv
    (fn [keep? k v]
      (and keep?
           (case k
             :kind  (= v (:kind p))
             :label (= v (:label p))
             (throw (ex-info (str "unknown primitive filter: " k)
                             {:type :unknown-filter :filter k})))))
    true filters))

(defn- envelope [rows limit]
  (let [total (count rows)]
    {:rows (vec (take limit rows))
     :truncated? (> total limit)
     :total total}))

(defn ^{:agent/layer :L1
        :agent/doc "List Model primitive summaries. Optional filters: :kind :label.
                    Returns {:rows … :truncated? bool :total N}."
        :agent/example "(primitives :kind :primitive/behaviour)"}
  primitives
  [& {:keys [limit] :or {limit default-limit} :as opts}]
  (let [m       (ensure-model)
        filters (dissoc opts :limit :offset)
        rows    (->> (vals (:primitives m))
                     (filter #(apply-filters % filters))
                     (map summary))]
    (envelope rows limit)))

(defn ^{:agent/layer :L1
        :agent/doc "Return the full primitive map for an id, or nil if absent."
        :agent/example "(get-primitive \"behaviour:hex/core/r-mint\")"}
  get-primitive
  [id]
  (when-let [m (infra-model/get-model)]
    (get-in m [:primitives id])))

(def ^:private known-relation-filters
  #{:kind :from :to :validity :projection-kind})

(defn- relation-matches? [edge {:keys [kind from to validity projection-kind] :as filters}]
  (let [unknown (seq (remove known-relation-filters (keys filters)))]
    (when unknown
      (throw (ex-info (str "unknown relation filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (and (or (nil? kind) (= kind (:kind edge)))
       (or (nil? from) (= from (-> edge :from :endpoint/primitive)))
       (or (nil? to)   (= to   (-> edge :to   :endpoint/primitive)))
       (or (nil? validity) (= validity (:validity edge)))
       (or (nil? projection-kind) (= projection-kind (:projection-kind edge)))))

(defn ^{:agent/layer :L1
        :agent/doc "List Model relations (edges). Filters: :kind :from :to :validity
                    :projection-kind. Returns {:rows … :truncated? bool :total N}.
                    Each row is a full edge map (edges are compact)."
        :agent/example "(relations :kind :projects :validity :absent)"}
  relations
  [& {:keys [limit] :or {limit default-limit} :as opts}]
  (let [m       (ensure-model)
        filters (dissoc opts :limit :offset)
        rows    (->> (:edges m)
                     (filter #(relation-matches? % filters)))]
    (envelope rows limit)))

(defn ^{:agent/layer :L1
        :agent/doc "Surface the primitive-kinds and relation-kinds present in the
                    loaded Model. Optional :altitude filter (post-MVP)."
        :agent/example "(vocabulary)"}
  vocabulary
  [& _opts]
  (let [m  (ensure-model)
        pk (into [] (distinct (map :kind (vals (:primitives m)))))
        rk (into [] (distinct (map :kind (:edges m))))]
    {:primitive-kinds (sort pk)
     :relation-kinds  (sort rk)}))

(defn ^{:agent/layer :L1
        :agent/doc "Surface the attribute keys observed on primitives of a given :kind,
                    plus the relation kinds they participate in. Empirical — read from
                    the loaded Model, not the static schema."
        :agent/example "(schema :kind :primitive/behaviour)"}
  schema
  [& {:keys [kind]}]
  (let [m       (ensure-model)
        matched (filter #(= kind (:kind %)) (vals (:primitives m)))
        attrs   (into #{} (mapcat keys) matched)
        ids     (into #{} (map :id) matched)
        rels    (into #{}
                      (keep (fn [e]
                              (when (or (ids (-> e :from :endpoint/primitive))
                                        (ids (-> e :to :endpoint/primitive)))
                                (:kind e))))
                      (:edges m))]
    {:kind kind
     :attributes (sort attrs)
     :relations  (sort rels)
     :count      (count matched)}))

(defn ^{:agent/layer :L1
        :agent/doc "Project-layer idiom entries. Returns a vector of entry maps."
        :agent/example "(idioms)"}
  idioms
  [& _opts]
  (or (when-let [m (infra-model/get-model)]
        (vec (or (:idioms m) (-> m :project-layer :idioms))))
      []))

(defn ^{:agent/layer :L1
        :agent/doc "Project-layer constraint definitions. Returns a vector."
        :agent/example "(constraints)"}
  constraints
  [& _opts]
  (or (when-let [m (infra-model/get-model)]
        (vec (or (:constraints m) (-> m :project-layer :constraints))))
      []))

(defn ^{:agent/layer :L1
        :agent/doc "Current constraint violations. Filters: :severity."
        :agent/example "(violations :severity :error)"}
  violations
  [& {:keys [severity] :as opts}]
  (let [unknown (seq (remove #{:severity} (keys opts)))]
    (when unknown
      (throw (ex-info (str "unknown violation filter: " (first unknown))
                      {:type :unknown-filter :filter (first unknown)}))))
  (let [m (infra-model/get-model)
        all (vec (or (:violations m) []))]
    (if severity
      (filterv #(= severity (:severity %)) all)
      all)))

;; -- L2 Views -----------------------------------------------------------------

(defn ^{:agent/layer :L2
        :agent/origin :built-in
        :agent/doc "Absent projections, joined with their source primitive.
                    Optional :projection-kind filter. Returns a vector (not a
                    listing envelope) because L2 views are pre-shaped for the
                    question they answer."
        :agent/example "(drift) (drift :projection-kind :clojure)"}
  drift
  [& {:keys [projection-kind]}]
  (let [rows (if projection-kind
               (:rows (relations :kind :projects :validity :absent :projection-kind projection-kind))
               (:rows (relations :kind :projects :validity :absent)))]
    (mapv (fn [e]
            (assoc e :primitive (get-primitive (-> e :from :endpoint/primitive))))
          rows)))
