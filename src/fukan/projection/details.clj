(ns fukan.projection.details
  "Entity detail projection for the sidebar.
   Computes a normalized detail structure for any entity — node or edge —
   including its description, public interface (contract functions or
   schema form), dataflow (input/output schema references), and
   aggregated dependency counts. The normalized shape lets the sidebar
   render all entity kinds with one generic renderer."
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
                   ;; Exclude self and descendants
                   (remove #(contains? in-subtree %))
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
                   ;; Exclude self and descendants
                   (remove #(contains? in-subtree %))
                   frequencies)]
    (into {}
          (map (fn [[source-id cnt]]
                 [source-id {:count cnt
                             :label (:label (get-in model [:nodes source-id]))}]))
          freqs)))

;; -----------------------------------------------------------------------------
;; Tree helpers

(defn- all-leaf-descendants
  "Get all function-kind descendants of a node.
   Traverses the tree recursively to find leaves at any depth."
  [model node-id]
  (let [children (get-in model [:nodes node-id :children] #{})]
    (->> children
         (mapcat (fn [cid]
                   (let [child (get-in model [:nodes cid])]
                     (if (= :function (:kind child))
                       [cid]
                       (all-leaf-descendants model cid)))))
         vec)))

;; -----------------------------------------------------------------------------
;; Normalization helpers

(defn- schema-ref-with-doc
  "Build a schema reference map, including description from the schema node."
  [model k]
  (let [doc (when-let [sid (proj.schema/find-schema-node-id model k)]
              (get-in model [:nodes sid :data :doc]))]
    (cond-> {:key k} doc (assoc :doc doc))))

(defn- extract-description
  "Extract description text from a node.
   Tries top-level :description, :data :doc, :data :contract :description."
  [node]
  (or (:description node)
      (get-in node [:data :doc])
      (get-in node [:data :contract :description])))

(defn- build-schema-registry
  "Build a registry of resolved schema refs found in a schema form.
   Returns {keyword -> {:form schema-form :doc str?}} for all named refs.
   Only resolves one level (no recursive expansion)."
  [model schema-form]
  (let [refs (proj.schema/extract-schema-refs model schema-form)]
    (into {}
          (map (fn [k]
                 (let [form (proj.schema/get-schema model k)
                       doc (when-let [sid (proj.schema/find-schema-node-id model k)]
                             (get-in model [:nodes sid :data :doc]))]
                   [k (cond-> {:form form}
                        doc (assoc :doc doc))])))
          refs)))

(defn- extract-interface
  "Extract interface data from a node based on its kind.
   Returns {:type :items} or nil."
  [model node]
  (let [data (:data node)]
    (case (:kind data)
      :container
      (when-let [fns (seq (get-in data [:contract :functions]))]
        (let [leaf-nodes (->> (all-leaf-descendants model (:id node))
                              (map #(get-in model [:nodes %])))
              name->id (into {} (map (fn [v] [(:label v) (:id v)])) leaf-nodes)]
          {:type :fn-list
           :items (->> fns
                       (mapv #(assoc % :id (name->id (:name %))))
                       (sort-by :name)
                       vec)}))

      :function
      (when-let [sig (:signature data)]
        {:type :fn-inline
         :items [sig]})

      :schema
      (when-let [s (:schema data)]
        {:type :schema-def
         :items [s]
         :registry (build-schema-registry model s)})

      nil)))

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
   For :container — aggregates across contract function schemas.
   For :function — extracts from the single function's schema.
   Returns {:inputs [{:key k :doc str?}] :outputs [{:key k :doc str?}]} or nil."
  [model node]
  (let [data (:data node)
        ref-fn (partial schema-ref-with-doc model)
        aggregate (fn [ios]
                    (let [{:keys [inputs outputs]}
                          (reduce (fn [acc {:keys [inputs outputs]}]
                                    (-> acc
                                        (update :inputs into (or inputs #{}))
                                        (update :outputs into (or outputs #{}))))
                                  {:inputs #{} :outputs #{}}
                                  ios)]
                      (when (or (seq inputs) (seq outputs))
                        {:inputs (->> inputs sort (mapv ref-fn))
                         :outputs (->> outputs sort (mapv ref-fn))})))]
    (case (:kind data)
      :container
      (when-let [fns (seq (get-in data [:contract :functions]))]
        (aggregate (keep #(extract-fn-io model (:schema %)) fns)))

      :function
      (when-let [sig (:signature data)]
        (when-let [{:keys [inputs outputs]} (extract-fn-io model sig)]
          (when (or (seq inputs) (seq outputs))
            {:inputs (->> inputs sort (mapv ref-fn))
             :outputs (->> outputs sort (mapv ref-fn))})))

      nil)))

(defn- extract-schemas
  "Extract schema references relevant to this entity.
   Returns a vector of {:key schema-keyword :doc str?} or nil."
  [model node]
  (let [data (:data node)
        ref-fn (partial schema-ref-with-doc model)]
    (case (:kind data)
      :container
      (let [ns-schemas (proj.schema/schemas-for-ns model (:id node))]
        (when (seq ns-schemas)
          (->> ns-schemas sort (mapv ref-fn))))

      :function
      (when-let [sig (:signature data)]
        (let [refs (proj.schema/extract-schema-refs model sig)]
          (when (seq refs)
            (->> refs sort (mapv ref-fn)))))

      nil)))

(defn- extract-parent
  "Extract parent reference {:id :label} or nil if at root."
  [model node]
  (when-let [parent-id (:parent node)]
    (when-let [parent (get-in model [:nodes parent-id])]
      {:id parent-id :label (:label parent)})))

(defn- normalize-entity
  "Assemble the normalized entity detail map."
  [model node deps dependents]
  (cond-> {:label       (:label node)
           :kind        (:kind node)
           :parent      (extract-parent model node)
           :description (extract-description node)
           :interface   (extract-interface model node)
           :schemas     (extract-schemas model node)
           :dataflow    (extract-dataflow model node)
           :deps        deps
           :dependents  dependents}
    (seq (get-in node [:data :surface :guarantees]))
    (assoc :guarantees (get-in node [:data :surface :guarantees]))))

;; -----------------------------------------------------------------------------
;; Edge Details

(defn- parse-edge-id
  "Parse an edge ID into components.
   Edge ID format: edge~{from-id}~{to-id}~{edge-type}
   Uses ~ as delimiter since it's URL-safe and won't appear in node IDs.
   Returns {:from-id :to-id :edge-type} or nil if invalid."
  [edge-id]
  (when (str/starts-with? edge-id "edge~")
    (let [parts (str/split (subs edge-id 5) #"~")]
      (when (= 3 (count parts))
        {:from-id (first parts)
         :to-id (second parts)
         :edge-type (keyword (nth parts 2))}))))

(defn- compute-underlying-edges
  "Find all function-level edges that aggregate to this visible edge.
   Returns a vector of {:from-var {:id :label :signature} :to-var {...}}
   Only includes edges where both endpoints are functions (excludes require relationships)."
  [model from-id to-id]
  (let [from-subtree (subtree model from-id)
        to-subtree (subtree model to-id)
        raw-edges (:edges model)
        matching-edges (->> raw-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? from-subtree from)
                                           (contains? to-subtree to)
                                           (= :function (:kind (get-in model [:nodes from])))
                                           (= :function (:kind (get-in model [:nodes to])))))))]
    (->> matching-edges
         (map (fn [{:keys [from to]}]
                (let [from-node (get-in model [:nodes from])
                      to-node (get-in model [:nodes to])]
                  {:from-var {:id from
                              :label (:label from-node)
                              :signature (get-in from-node [:data :signature])}
                   :to-var {:id to
                            :label (:label to-node)
                            :signature (get-in to-node [:data :signature])}})))
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
  [:map {:description "Aggregated dependency count and display label for one target."}
   [:count :int]
   [:label :string]])

(def ^:schema EntityDeps
  [:map-of {:description "Map from entity ID to aggregated dependency info."}
   :string :EntityDepInfo])

(def ^:schema FnEntry
  [:map {:description "A function in a public API listing: name, optional schema, and optional navigable ID."}
   [:name :string]
   [:schema {:optional true, :description "Malli function schema [:=> [:cat inputs...] output]."} :any]
   [:id {:optional true} :string]])

(def ^:schema InterfaceData
  [:multi {:dispatch :type
           :description "Public interface of an entity, discriminated by display format."}
   [:fn-list [:map
     [:type [:= :fn-list]]
     [:items [:vector :FnEntry]]]]
   [:fn-inline [:map
     [:type [:= :fn-inline]]
     [:items {:description "Malli function schema forms — legitimate :any."} [:vector :any]]]]
   [:schema-def [:map
     [:type [:= :schema-def]]
     [:items {:description "Malli schema form — legitimate :any."} [:vector :any]]
     [:registry {:optional true
                 :description "One-level resolved schema refs: keyword to form and optional doc."}
      [:map-of :keyword [:map
        [:form :any]
        [:doc {:optional true} [:maybe :string]]]]]]]
   [:name-list [:map
     [:type [:= :name-list]]
     [:items [:vector :string]]]]])

(def ^:schema SchemaRef
  [:map {:description "A reference to a schema type with optional description."}
   [:key :keyword]
   [:doc {:optional true} [:maybe :string]]])

(def ^:schema DataflowData
  [:map {:description "Schema references flowing in and out of an entity's boundary."}
   [:inputs [:vector :SchemaRef]]
   [:outputs [:vector :SchemaRef]]])

(def ^:schema EntityDetails
  [:or {:description "Normalized detail structure for any entity (node or edge)."}
   ;; Node entity detail
   [:map {:description "Node entity: full detail with interface, schemas, dataflow, and dependencies."}
    [:label :string]
    [:kind [:enum :container :function :schema]]
    [:parent [:maybe [:map [:id :string] [:label :string]]]]
    [:description [:maybe :string]]
    [:guarantees {:optional true} [:vector :string]]
    [:interface [:maybe :InterfaceData]]
    [:schemas [:maybe [:vector :SchemaRef]]]
    [:dataflow [:maybe :DataflowData]]
    [:deps :EntityDeps]
    [:dependents :EntityDeps]]
   ;; Edge entity detail
   [:map {:description "Edge entity: function calls crossing this dependency."}
    [:label :string]
    [:kind [:= :edge]]
    [:called-fns [:vector :FnEntry]]]])

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
