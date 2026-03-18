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

(defn- type-expr->label
  "Convert a TypeExpr to a short display label."
  [type-expr]
  (when (map? type-expr)
    (case (:tag type-expr)
      :ref       (name (:name type-expr))
      :primitive (:name type-expr)
      :vector    (str "[" (type-expr->label (:element type-expr)) "]")
      :set       (str "#{" (type-expr->label (:element type-expr)) "}")
      :map-of    (str "{" (type-expr->label (:key-type type-expr))
                      " \u2192 " (type-expr->label (:value-type type-expr)) "}")
      :maybe     (str (type-expr->label (:inner type-expr)) "?")
      :or        (str/join " | " (map type-expr->label (:variants type-expr)))
      :map       "map"
      :unknown   (or (:original type-expr) "?")
      "?")))

(defn- schema-ref-with-doc
  "Build a schema reference map, including node ID and description from the schema node."
  [model k]
  (let [sid (proj.schema/find-schema-node-id model k)
        doc (when sid (get-in model [:nodes sid :data :doc]))]
    (cond-> {:label (name k) :key k}
      sid (assoc :id sid)
      doc (assoc :doc doc))))

(defn- type-expr->io-item
  "Convert a TypeExpr to a dataflow item.
   Schema refs include :key and :id for click navigation."
  [model type-expr]
  (let [base {:label (or (type-expr->label type-expr) "?")}]
    (if (and (map? type-expr) (= :ref (:tag type-expr)))
      (let [k (:name type-expr)
            sid (proj.schema/find-schema-node-id model k)
            doc (when sid (get-in model [:nodes sid :data :doc]))]
        (cond-> (assoc base :key k)
          sid (assoc :id sid)
          doc (assoc :doc doc)))
      base)))

(defn- extract-description
  "Extract description text from a node.
   Tries top-level :description, :data :doc, :data :boundary :description."
  [node]
  (or (:description node)
      (get-in node [:data :doc])
      (get-in node [:data :boundary :description])))

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
      :module
      (when-let [fns (seq (get-in data [:boundary :functions]))]
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
  "Extract input and output schema references from a structured function signature.
   Expects {:inputs [type-exprs...] :output type-expr}.
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}} or nil."
  [model fn-sig]
  (when-let [{:keys [inputs output]} fn-sig]
    {:inputs (into #{} (mapcat #(proj.schema/extract-schema-refs model %) inputs))
     :outputs (proj.schema/extract-schema-refs model output)}))

(defn- extract-dataflow
  "Extract dataflow (input/output schema types) for an entity.
   For :module — aggregates across contract function schemas.
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
      :module
      (when-let [fns (seq (get-in data [:boundary :functions]))]
        (aggregate (keep #(extract-fn-io model (:schema %)) fns)))

      :function
      (when-let [{:keys [inputs output]} (:signature data)]
        {:inputs (mapv #(type-expr->io-item model %) inputs)
         :outputs [(type-expr->io-item model output)]})

      nil)))

(defn- all-descendant-public-schemas
  "Get all public schema keywords defined anywhere within a module's subtree.
   Traverses children recursively, collecting schema keys at any depth.
   Private schemas are excluded — they are internal implementation details."
  [model node-id]
  (let [children (get-in model [:nodes node-id :children] #{})]
    (->> children
         (mapcat (fn [cid]
                   (let [child (get-in model [:nodes cid])]
                     (if (= :schema (:kind child))
                       (when-not (get-in child [:data :private?])
                         [(get-in child [:data :schema-key])])
                       (all-descendant-public-schemas model cid)))))
         set)))

(defn- extract-schemas
  "Extract defined types for modules: public schemas owned by the module.
   Private schemas are internal implementation details and excluded.
   Returns a vector of {:key schema-keyword :doc str?} or nil."
  [model node]
  (let [data (:data node)
        ref-fn (partial schema-ref-with-doc model)]
    (case (:kind data)
      :module
      (let [public-schemas (all-descendant-public-schemas model (:id node))]
        (when (seq public-schemas)
          (->> public-schemas sort (mapv ref-fn))))

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
  (cond-> {:label        (:label node)
           :kind         (:kind node)
           :parent       (extract-parent model node)
           :description  (extract-description node)
           :interface    (extract-interface model node)
           :schemas      (extract-schemas model node)
           :dataflow     (extract-dataflow model node)
           :schema-ids   (proj.schema/schema-key->node-id model)
           :deps         deps
           :dependents   dependents}
    (seq (get-in node [:data :boundary :guarantees]))
    (assoc :guarantees (get-in node [:data :boundary :guarantees]))))

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
   Returns a vector of {:from-fn {:id :label :signature} :to-fn {...}}
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
                  {:from-fn {:id from
                             :label (:label from-node)
                             :signature (get-in from-node [:data :signature])}
                   :to-fn {:id to
                           :label (:label to-node)
                           :signature (get-in to-node [:data :signature])}})))
         (sort-by (fn [e] [(get-in e [:from-fn :label]) (get-in e [:to-fn :label])]))
         vec)))

(defn- compute-underlying-schema-refs
  "Find all schema-level edges that aggregate to this visible schema-reference edge.
   Returns a vector of {:from-schema {:id :label :schema-key} :to-schema {...}}"
  [model from-id to-id]
  (let [from-subtree (subtree model from-id)
        to-subtree (subtree model to-id)
        raw-edges (:edges model)
        matching-edges (->> raw-edges
                            (filter (fn [{:keys [from to kind]}]
                                      (and (= :schema-reference kind)
                                           (contains? from-subtree from)
                                           (contains? to-subtree to)))))]
    (->> matching-edges
         (map (fn [{:keys [from to]}]
                (let [from-node (get-in model [:nodes from])
                      to-node (get-in model [:nodes to])]
                  {:from-schema {:id from
                                 :label (:label from-node)
                                 :schema-key (get-in from-node [:data :schema-key])}
                   :to-schema {:id to
                               :label (:label to-node)
                               :schema-key (get-in to-node [:data :schema-key])}})))
         (sort-by (fn [e] [(get-in e [:from-schema :label]) (get-in e [:to-schema :label])]))
         vec)))

(defn- matching-raw-edges
  "Find all raw model edges between subtrees of from-id and to-id."
  [model from-id to-id]
  (let [from-subtree (subtree model from-id)
        to-subtree (subtree model to-id)]
    (->> (:edges model)
         (filter (fn [{:keys [from to]}]
                   (and (contains? from-subtree from)
                        (contains? to-subtree to)))))))

(defn- compute-edge-details
  "Compute normalized details for an edge entity.
   Dispatches by edge-type:
   - :code-flow → {:label :kind :edge-type :called-fns [...]}
   - :schema-reference → {:label :kind :edge-type :schema-refs [...]}"
  [model edge-id]
  (when-let [{:keys [from-id to-id edge-type]} (parse-edge-id edge-id)]
    (let [from-node (get-in model [:nodes from-id])
          to-node (get-in model [:nodes to-id])]
      (case edge-type
        :code-flow
        (let [underlying-fwd (compute-underlying-edges model from-id to-id)
              underlying-rev (compute-underlying-edges model to-id from-id)
              ;; Function-call edges: always in from→to direction (unaffected by perspective)
              fn-call-edges (filter #(= :function-call (:kind %)) (matching-raw-edges model from-id to-id))
              ;; Dispatches edges: search both directions since perspective may have flipped them.
              ;; Under dependency-graph, the edge ID encodes flipped endpoints, but raw model
              ;; dispatches edges remain in canonical direction.
              dispatches-fwd (filter #(= :dispatches (:kind %)) (matching-raw-edges model from-id to-id))
              dispatches-rev (filter #(= :dispatches (:kind %)) (matching-raw-edges model to-id from-id))
              dispatches-edges (concat dispatches-fwd dispatches-rev)
              called-fns (when (seq fn-call-edges)
                           (->> underlying-fwd
                                (filter (fn [ue]
                                          (some #(and (= (:from %) (get-in ue [:from-fn :id]))
                                                      (= (:to %) (get-in ue [:to-fn :id])))
                                                fn-call-edges)))
                                (map :to-fn)
                                (distinct)
                                (sort-by :label)
                                (mapv (fn [{:keys [id label signature]}]
                                        {:name label :schema signature :id id}))))
              dispatched-fns (when (seq dispatches-edges)
                               (let [all-underlying (concat underlying-fwd underlying-rev)]
                                 (->> all-underlying
                                      (filter (fn [ue]
                                                (some #(and (= (:from %) (get-in ue [:from-fn :id]))
                                                            (= (:to %) (get-in ue [:to-fn :id])))
                                                      dispatches-edges)))
                                      (map :to-fn)
                                      (distinct)
                                      (sort-by :label)
                                      (mapv (fn [{:keys [id label signature]}]
                                              {:name label :schema signature :id id})))))]
          (cond-> {:label       (str (:label from-node) " → " (:label to-node))
                   :kind        :edge
                   :detail-kind :edge
                   :edge-type   :code-flow
                   :schema-ids  (proj.schema/schema-key->node-id model)}
            (seq called-fns)    (assoc :called-fns called-fns)
            (seq dispatched-fns) (assoc :dispatched-fns dispatched-fns)))

        :schema-reference
        (let [schema-refs (compute-underlying-schema-refs model from-id to-id)]
          {:label       (str (:label from-node) " → " (:label to-node))
           :kind        :edge
           :detail-kind :edge
           :edge-type   :schema-reference
           :schema-refs schema-refs
           :schema-ids  (proj.schema/schema-key->node-id model)})

        ;; Fallback for unknown edge types
        {:label       (str (:label from-node) " → " (:label to-node))
         :kind        :edge
         :detail-kind :edge
         :edge-type   edge-type}))))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:private ^:schema EntityDepInfo
  [:map {:description "Aggregated dependency count and display label for one target."}
   [:count :int]
   [:label :string]])

(def ^:private ^:schema EntityDeps
  [:map-of {:description "Map from entity ID to aggregated dependency info."}
   :string :EntityDepInfo])

(def ^:private ^:schema FnEntry
  [:map {:description "A function in a public API listing: name, optional signature, and optional navigable ID."}
   [:name :string]
   [:schema {:optional true, :description "Structured function signature: inputs and output TypeExprs."}
    :FunctionSignature]
   [:id {:optional true} :string]])

(def ^:private ^:schema InterfaceData
  [:multi {:dispatch :type
           :description "Public interface of an entity, discriminated by display format."}
   [:fn-list [:map
     [:type [:= :fn-list]]
     [:items [:vector :FnEntry]]]]
   [:fn-inline [:map
     [:type [:= :fn-inline]]
     [:items {:description "FunctionSignature values."} [:vector :FunctionSignature]]]]
   [:schema-def [:map
     [:type [:= :schema-def]]
     [:items {:description "TypeExpr values."} [:vector :TypeExpr]]
     [:registry {:optional true
                 :description "One-level resolved schema refs: keyword to TypeExpr and optional doc."}
      [:map-of :keyword [:map
        [:form :TypeExpr]
        [:doc {:optional true} [:maybe :string]]]]]]]
   [:name-list [:map
     [:type [:= :name-list]]
     [:items [:vector :string]]]]])

(def ^:private ^:schema SchemaRef
  [:map {:description "A type in a dataflow section. Schema refs include :key for click navigation."}
   [:label :string]
   [:key {:optional true} :keyword]
   [:doc {:optional true} [:maybe :string]]])

(def ^:private ^:schema DataflowData
  [:map {:description "Schema references flowing in and out of an entity's boundary."}
   [:inputs [:vector :SchemaRef]]
   [:outputs [:vector :SchemaRef]]])

(def ^:private ^:schema SchemaRefEntry
  [:map {:description "A schema reference in a schema-reference edge detail."}
   [:from-schema [:map [:id :string] [:label :string] [:schema-key {:optional true} [:maybe :keyword]]]]
   [:to-schema [:map [:id :string] [:label :string] [:schema-key {:optional true} [:maybe :keyword]]]]])

(def ^:schema EntityDetails
  [:or {:description "Normalized detail structure for any entity (node or edge)."}
   ;; Node entity detail
   [:map {:description "Node entity: full detail with interface, schemas, dataflow, and dependencies."}
    [:label :string]
    [:kind [:enum :module :function :schema]]
    [:parent [:maybe [:map [:id :string] [:label :string]]]]
    [:description [:maybe :string]]
    [:guarantees {:optional true} [:vector :string]]
    [:interface [:maybe :InterfaceData]]
    [:schemas [:maybe [:vector :SchemaRef]]]
    [:dataflow [:maybe :DataflowData]]
    [:deps :EntityDeps]
    [:dependents :EntityDeps]]
   ;; Code-flow edge detail
   [:map {:description "Code-flow edge: function calls crossing this dependency."}
    [:label :string]
    [:kind [:= :edge]]
    [:edge-type [:= :code-flow]]
    [:called-fns [:vector :FnEntry]]]
   ;; Schema-reference edge detail
   [:map {:description "Schema-reference edge: type references crossing this dependency."}
    [:label :string]
    [:kind [:= :edge]]
    [:edge-type [:= :schema-reference]]
    [:schema-refs [:vector :SchemaRefEntry]]]])

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
