(ns fukan.projection.details
  "Entity details projection functions.
   Computes normalized entity detail structures for rendering."
  (:require [clojure.string :as str]
            [fukan.projection.schema :as proj.schema]))

;; -----------------------------------------------------------------------------
;; Private helpers

(defn- find-ancestor-of-kind
  "Find the first ancestor (or self) of the given kind.
   Returns nil if no ancestor of that kind exists."
  [model node-id target-kind]
  (loop [current node-id]
    (when current
      (let [node (get-in model [:nodes current])]
        (if (= target-kind (:kind node))
          current
          (recur (:parent node)))))))

(defn- subtree
  "Return set of node-id and all its descendants.
   For edge filtering: includes edges from the entity or anything inside it."
  [model node-id]
  (let [node (get-in model [:nodes node-id])]
    (if-let [children (:children node)]
      (into #{node-id} (mapcat #(subtree model %) children))
      #{node-id})))

(defn- compute-deps
  "Compute dependencies for an entity, aggregated to its own kind.
   Returns {target-id -> {:count n :label str}}."
  [model entity-id]
  (let [entity (get-in model [:nodes entity-id])
        kind (:kind entity)
        in-subtree (subtree model entity-id)
        edges (:edges model)
        freqs (->> edges
                   ;; Edges FROM this entity or its descendants
                   (filter #(contains? in-subtree (:from %)))
                   ;; Aggregate target to same kind
                   (keep #(find-ancestor-of-kind model (:to %) kind))
                   ;; Exclude self-references
                   (remove #{entity-id})
                   frequencies)]
    (into {}
          (map (fn [[target-id cnt]]
                 [target-id {:count cnt
                             :label (:label (get-in model [:nodes target-id]))}]))
          freqs)))

(defn- compute-dependents
  "Compute dependents for an entity, aggregated to its own kind.
   Returns {source-id -> {:count n :label str}}."
  [model entity-id]
  (let [entity (get-in model [:nodes entity-id])
        kind (:kind entity)
        in-subtree (subtree model entity-id)
        edges (:edges model)
        freqs (->> edges
                   ;; Edges TO this entity or its descendants
                   (filter #(contains? in-subtree (:to %)))
                   ;; Aggregate source to same kind
                   (keep #(find-ancestor-of-kind model (:from %) kind))
                   ;; Exclude self-references
                   (remove #{entity-id})
                   frequencies)]
    (into {}
          (map (fn [[source-id cnt]]
                 [source-id {:count cnt
                             :label (:label (get-in model [:nodes source-id]))}]))
          freqs)))

;; -----------------------------------------------------------------------------
;; Schema lookup

(defn- get-var-schema
  "Get the malli schema from a var's metadata, if present."
  [ns-sym var-sym]
  (try
    (when-let [v (ns-resolve (find-ns ns-sym) var-sym)]
      (:malli/schema (meta v)))
    (catch Exception _ nil)))

(defn- schema-var?
  "Check if a var has ^:schema metadata (is a schema definition, not a function)."
  [ns-sym var-sym]
  (try
    (when-let [v (ns-resolve (find-ns ns-sym) var-sym)]
      (boolean (:schema (meta v))))
    (catch Exception _ false)))

;; -----------------------------------------------------------------------------
;; Normalization helpers

(defn- extract-description
  "Extract description text from a node.
   Tries :data :doc, :data :contract :description, :data :description."
  [node]
  (or (get-in node [:data :doc])
      (get-in node [:data :contract :description])
      (get-in node [:data :description])))

(defn- extract-interface
  "Extract interface data from a node based on its kind.
   Returns {:type :items} or nil."
  [model node]
  (case (:kind node)
    :folder
    (when-let [fns (seq (get-in node [:data :contract :functions]))]
      {:type :fn-list
       :items (vec (sort-by :name fns))})

    :namespace
    (let [ns-sym (get-in node [:data :ns-sym])
          children-ids (:children node)
          public-fns (->> children-ids
                          (map #(get-in model [:nodes %]))
                          (filter #(= :var (:kind %)))
                          (remove #(get-in % [:data :private?]))
                          (remove #(schema-var? ns-sym (get-in % [:data :var-sym])))
                          (map (fn [child]
                                 (let [var-sym (get-in child [:data :var-sym])]
                                   {:name (str var-sym)
                                    :schema (get-var-schema ns-sym var-sym)
                                    :id (:id child)})))
                          (sort-by :name)
                          vec)]
      (when (seq public-fns)
        {:type :fn-list
         :items public-fns}))

    :var
    (let [ns-sym (get-in node [:data :ns-sym])
          var-sym (get-in node [:data :var-sym])
          schema (get-var-schema ns-sym var-sym)]
      (when schema
        {:type :fn-inline
         :items [schema]}))

    :schema
    (let [schema-key (get-in node [:data :schema-key])
          schema-form (proj.schema/get-schema model schema-key)]
      (when schema-form
        {:type :schema-def
         :items [schema-form]}))

    :interface
    (let [fns (get-in node [:data :functions])]
      (when (seq fns)
        {:type :name-list
         :items (vec fns)}))

    nil))

(defn- extract-fn-io
  "Extract input and output schema references from a function schema.
   Expects schema in form [:=> [:cat input1 input2 ...] output].
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}} or nil."
  [model fn-schema]
  (when (and (vector? fn-schema) (= :=> (first fn-schema)))
    (let [[_ input output] fn-schema
          in-schemas (if (and (vector? input) (= :cat (first input)))
                       (rest input)
                       [input])]
      {:inputs (into #{} (mapcat #(proj.schema/extract-schema-refs model %) in-schemas))
       :outputs (proj.schema/extract-schema-refs model output)})))

(defn- extract-dataflow
  "Extract dataflow (input/output schema types) for an entity.
   For :folder — aggregates across contract function schemas.
   For :namespace — aggregates across public var function schemas.
   For :var — extracts from the single var's function schema.
   Returns {:inputs [{:key k}] :outputs [{:key k}]} or nil."
  [model node]
  (let [aggregate (fn [ios]
                    (let [{:keys [inputs outputs]}
                          (reduce (fn [acc {:keys [inputs outputs]}]
                                    (-> acc
                                        (update :inputs into (or inputs #{}))
                                        (update :outputs into (or outputs #{}))))
                                  {:inputs #{} :outputs #{}}
                                  ios)]
                      (when (or (seq inputs) (seq outputs))
                        {:inputs (->> inputs sort (mapv (fn [k] {:key k})))
                         :outputs (->> outputs sort (mapv (fn [k] {:key k})))})))]
    (case (:kind node)
      :folder
      (when-let [fns (seq (get-in node [:data :contract :functions]))]
        (aggregate (keep #(extract-fn-io model (:schema %)) fns)))

      :namespace
      (let [ns-sym (get-in node [:data :ns-sym])
            children-ids (:children node)
            ios (->> children-ids
                     (map #(get-in model [:nodes %]))
                     (filter #(= :var (:kind %)))
                     (remove #(get-in % [:data :private?]))
                     (remove #(schema-var? ns-sym (get-in % [:data :var-sym])))
                     (keep (fn [child]
                             (let [var-sym (get-in child [:data :var-sym])]
                               (get-var-schema ns-sym var-sym))))
                     (keep #(extract-fn-io model %)))]
        (aggregate ios))

      :var
      (let [ns-sym (get-in node [:data :ns-sym])
            var-sym (get-in node [:data :var-sym])
            schema (get-var-schema ns-sym var-sym)]
        (when schema
          (when-let [{:keys [inputs outputs]} (extract-fn-io model schema)]
            (when (or (seq inputs) (seq outputs))
              {:inputs (->> inputs sort (mapv (fn [k] {:key k})))
               :outputs (->> outputs sort (mapv (fn [k] {:key k})))}))))

      nil)))

(defn- extract-schemas
  "Extract schema references relevant to this entity.
   Returns a vector of {:key schema-keyword} or nil."
  [model node]
  (case (:kind node)
    :namespace
    (let [ns-schemas (proj.schema/schemas-for-ns model (:label node))]
      (when (seq ns-schemas)
        (->> ns-schemas sort (mapv (fn [k] {:key k})))))

    :var
    (let [ns-sym (get-in node [:data :ns-sym])
          var-sym (get-in node [:data :var-sym])
          schema (get-var-schema ns-sym var-sym)]
      (when schema
        (let [refs (proj.schema/extract-schema-refs model schema)]
          (when (seq refs)
            (->> refs sort (mapv (fn [k] {:key k})))))))

    nil))

(defn- normalize-entity
  "Assemble the normalized entity detail map."
  [model node deps dependents]
  {:label       (:label node)
   :kind        (:kind node)
   :description (extract-description node)
   :interface   (extract-interface model node)
   :schemas     (extract-schemas model node)
   :dataflow    (extract-dataflow model node)
   :deps        deps
   :dependents  dependents})

;; -----------------------------------------------------------------------------
;; Edge Details

(defn- parse-edge-id
  "Parse an edge ID into components.
   Edge ID format: edge~{from-id}~{to-id}~{edge-type}
   Uses ~ as delimiter since it's URL-safe and won't appear in UUIDs.
   Returns {:from-id :to-id :edge-type} or nil if invalid."
  [edge-id]
  (when (str/starts-with? edge-id "edge~")
    (let [parts (str/split (subs edge-id 5) #"~")]
      (when (= 3 (count parts))
        {:from-id (first parts)
         :to-id (second parts)
         :edge-type (keyword (nth parts 2))}))))

(defn- compute-underlying-edges
  "Find all var-level edges that aggregate to this visible edge.
   Returns a vector of {:from-var {:id :label :signature} :to-var {...}}
   Only includes edges where both endpoints are vars (excludes require relationships)."
  [model from-id to-id]
  (let [from-subtree (subtree model from-id)
        to-subtree (subtree model to-id)
        raw-edges (:edges model)
        ;; Find all raw edges where source is in from-subtree and target is in to-subtree
        ;; Filter to only var-level edges (both endpoints must be vars)
        matching-edges (->> raw-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? from-subtree from)
                                           (contains? to-subtree to)
                                           (= :var (:kind (get-in model [:nodes from])))
                                           (= :var (:kind (get-in model [:nodes to])))))))]
    (->> matching-edges
         (map (fn [{:keys [from to]}]
                (let [from-node (get-in model [:nodes from])
                      to-node (get-in model [:nodes to])
                      from-ns-sym (get-in from-node [:data :ns-sym])
                      from-var-sym (get-in from-node [:data :var-sym])
                      to-ns-sym (get-in to-node [:data :ns-sym])
                      to-var-sym (get-in to-node [:data :var-sym])]
                  {:from-var {:id from
                              :label (:label from-node)
                              :signature (get-var-schema from-ns-sym from-var-sym)}
                   :to-var {:id to
                            :label (:label to-node)
                            :signature (get-var-schema to-ns-sym to-var-sym)}})))
         (sort-by (fn [e] [(get-in e [:from-var :label]) (get-in e [:to-var :label])]))
         vec)))

(defn- compute-edge-details
  "Compute normalized details for an edge entity.
   Returns {:label :kind :edge :called-fns [...]}"
  [model edge-id]
  (when-let [{:keys [from-id to-id]} (parse-edge-id edge-id)]
    (let [from-node (get-in model [:nodes from-id])
          to-node (get-in model [:nodes to-id])
          underlying-edges (compute-underlying-edges model from-id to-id)
          called-fns (->> underlying-edges
                          (map :to-var)
                          (distinct)
                          (sort-by :label)
                          (mapv (fn [{:keys [id label signature]}]
                                  {:name label :schema signature :id id})))]
      {:label      (str (:label from-node) " → " (:label to-node))
       :kind       :edge
       :called-fns called-fns})))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema EntityDepInfo
  [:map
   [:count :int]
   [:label :string]])

(def ^:schema EntityDeps
  [:map-of :string :EntityDepInfo])

(def ^:schema InterfaceData
  [:map
   [:type [:enum :fn-list :fn-inline :schema-def :name-list]]
   [:items :any]])

(def ^:schema SchemaRef
  [:map [:key :keyword]])

(def ^:schema DataflowData
  [:map
   [:inputs [:vector :SchemaRef]]
   [:outputs [:vector :SchemaRef]]])

(def ^:schema EntityDetails
  [:or
   ;; Node entity detail
   [:map
    [:label :string]
    [:kind [:enum :folder :namespace :var :schema :interface]]
    [:description [:maybe :string]]
    [:interface [:maybe :InterfaceData]]
    [:schemas [:maybe [:vector :SchemaRef]]]
    [:dataflow [:maybe :DataflowData]]
    [:deps :EntityDeps]
    [:dependents :EntityDeps]]
   ;; Edge entity detail
   [:map
    [:label :string]
    [:kind [:= :edge]]
    [:called-fns [:vector :any]]]])

;; -----------------------------------------------------------------------------
;; Public API

(defn entity-details
  "Compute normalized details for an entity (node or edge).
   Edge IDs have format: edge~from-id~to-id~edge-type

   For nodes, returns a normalized map:
     {:label :kind :description :interface :schemas :deps :dependents}

   For edges, returns:
     {:label :kind :edge :called-fns [{:name :schema :id}]}"
  {:malli/schema [:=> [:cat :Model :string] :EntityDetails]}
  [model entity-id]
  (if (str/starts-with? (or entity-id "") "edge~")
    (compute-edge-details model entity-id)
    (let [node (get-in model [:nodes entity-id])]
      (when node
        (let [deps (compute-deps model entity-id)
              dependents (compute-dependents model entity-id)]
          (normalize-entity model node deps dependents))))))
