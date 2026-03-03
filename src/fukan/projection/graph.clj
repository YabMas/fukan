(ns fukan.projection.graph
  "Graph projection: Model → visible subgraph for visualization.
   Given a view entity, a set of explicitly expanded modules, and a set
   of show-private modules, computes which nodes and edges are visible,
   aggregates raw edges to the visible level, and generates synthetic
   IO nodes from contract boundaries. Returns pure domain data with no
   UI state."
  (:require [fukan.projection.schema :as schema]
            [fukan.projection.path :as path]
            [clojure.set :as set]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema ProjectionNodeKind
  [:enum {:description "Node kinds in projections, extending model kinds with synthetic IO nodes."}
   :module :function :schema :io-container :io-schema])

(def ^:schema ProjectionNode
  [:map {:description "A node in the projected graph with display properties for visualization."}
   [:id :string]
   [:kind :ProjectionNodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe :string]]
   [:expandable? :boolean]
   [:has-private-children? :boolean]
   [:expanded? :boolean]
   [:showing-private? :boolean]
   [:child-count :int]
   [:private? {:optional true} :boolean]
   [:io-type {:optional true} [:enum :input :output]]
   [:schema-key {:optional true} :keyword]])

(def ^:schema ProjectionEdgeType
  [:enum {:description "Edge semantic types: code-flow (call graphs), data-flow (contract IO)."}
   :code-flow :data-flow])

(def ^:schema ProjectionEdge
  [:map {:description "A directed, typed edge in the projected graph."}
   [:id :string]
   [:from :string]
   [:to :string]
   [:edge-type :ProjectionEdgeType]
   [:schema-key {:optional true} :keyword]])

(def ^:schema ProjectionIO
  [:map {:description "Input and output schema key sets for a module's contract boundary."}
   [:inputs [:set :keyword]]
   [:outputs [:set :keyword]]])

(def ^:schema Projection
  [:map {:description "Complete graph projection result: visible nodes, edges, and IO boundary."}
   [:nodes [:vector :ProjectionNode]]
   [:edges [:vector :ProjectionEdge]]
   [:io [:maybe :ProjectionIO]]])

(def ^:schema ProjectionOpts
  [:map {:description "Options for graph projection: which entity to view, which modules are expanded, and which show private children."}
   [:view-id {:optional true} [:maybe :string]]
   [:expanded {:optional true} [:set :NodeId]]
   [:show-private {:optional true} [:set :NodeId]]])

;; -----------------------------------------------------------------------------
;; Node accessor helpers (access via :data map)

(defn- node-private? [node]
  (get-in node [:data :private?] false))


;; -----------------------------------------------------------------------------
;; Visibility helpers

(defn- get-children
  "Get the children of an entity."
  [model entity-id]
  (get-in model [:nodes entity-id :children] #{}))

(defn- has-private-children?
  "Check if a module has any private children."
  [model entity-id]
  (let [children-ids (get-children model entity-id)]
    (some #(node-private? (get-in model [:nodes %])) children-ids)))

(defn- compute-visible-set
  "Compute the set of visible nodes by recursive descent through expanded modules.
   For each child of entity-id:
   - Skip schema nodes (they appear only as IO schemas)
   - Skip private nodes unless parent is in show-private
   - Include the child
   - If the child is a module AND in effective-expanded: recurse into its children"
  [model entity-id effective-expanded show-private]
  (let [children (get-children model entity-id)]
    (reduce
      (fn [visible child-id]
        (let [child-node (get-in model [:nodes child-id])]
          (cond
            ;; Skip schema nodes
            (= :schema (:kind child-node))
            visible

            ;; Skip private nodes unless parent is in show-private
            (and (node-private? child-node)
                 (not (contains? show-private entity-id)))
            visible

            :else
            (let [visible (conj visible child-id)]
              ;; If this is an expanded module, recurse into its children
              (if (and (= :module (:kind child-node))
                       (contains? effective-expanded child-id))
                (into visible (compute-visible-set model child-id effective-expanded show-private))
                visible)))))
      #{}
      children)))

;; -----------------------------------------------------------------------------
;; Ancestry helpers

(defn- descendant-of?
  "Check if node-id is a descendant of ancestor-id."
  [model node-id ancestor-id]
  (loop [current (:parent (get-in model [:nodes node-id]))]
    (cond
      (nil? current) false
      (= current ancestor-id) true
      :else (recur (:parent (get-in model [:nodes current]))))))

(defn- find-visible-ancestor
  "Walk up parent chain to find first node in visible-set.
   Returns nil if no ancestor is visible."
  [model node-id visible-set]
  (loop [current node-id]
    (cond
      (nil? current) nil
      (contains? visible-set current) current
      :else (recur (:parent (get-in model [:nodes current]))))))

;; -----------------------------------------------------------------------------
;; On-demand edge aggregation

(defn- aggregate-edges
  "Aggregate var-level edges to the visible node level.
   For each raw edge, find which visible nodes it connects.
   Returns deduplicated edges between visible nodes.

   Excludes edges where one endpoint is an ancestor of the other -
   parent-child relationships are implicit in compound node structure."
  [model visible-set]
  (let [raw-edges (:edges model)]
    (->> raw-edges
         (keep (fn [{:keys [from to]}]
                 (let [from-visible (find-visible-ancestor model from visible-set)
                       to-visible (find-visible-ancestor model to visible-set)]
                   (when (and from-visible to-visible
                              (not= from-visible to-visible)
                              (not (descendant-of? model to-visible from-visible))
                              (not (descendant-of? model from-visible to-visible)))
                     {:from from-visible :to to-visible}))))
         distinct
         vec)))

;; -----------------------------------------------------------------------------
;; IO Schema computation

(defn- extract-fn-schema-flow
  "Extract input and output schema references from a structured function signature.
   Expects {:inputs [type-exprs...] :output type-expr}.
   Returns {:inputs #{schema-keys...} :outputs #{schema-keys...}}
   or nil if not a function signature.
   Takes the model to determine which keywords are registered schemas."
  [model fn-sig]
  (when-let [{:keys [inputs output]} fn-sig]
    {:inputs (into #{} (mapcat #(schema/extract-schema-refs model %) inputs))
     :outputs (schema/extract-schema-refs model output)}))

(defn- compute-var-schema-info
  "Get schema info for a function node from its :signature data.
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}} or nil.
   Takes the model to determine which keywords are registered schemas."
  [model node]
  (when (= :function (:kind node))
    (when-let [fn-schema (get-in node [:data :signature])]
      (extract-fn-schema-flow model fn-schema))))

(defn- contract->io
  "Derive input/output schema keys from a contract's function schemas."
  [model contract]
  (reduce (fn [acc {:keys [schema]}]
            (if-let [{:keys [inputs outputs]} (extract-fn-schema-flow model schema)]
              (-> acc
                  (update :inputs into inputs)
                  (update :outputs into outputs))
              acc))
          {:inputs #{} :outputs #{}}
          (:functions contract)))

(defn- compute-io-schemas
  "Compute input/output schemas for a module based on its boundary.
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}} or empty sets
   when no boundary is present."
  [model module-id]
  (if-let [boundary (get-in model [:nodes module-id :data :boundary])]
    (contract->io model boundary)
    {:inputs #{} :outputs #{}}))

;; -----------------------------------------------------------------------------
;; IO Nodes and Data-Flow Edges

(defn- create-io-nodes
  "Create IO container and schema nodes for a module view.
   Returns a vector of nodes: IO container + child schema nodes.
   io-type is :input or :output.
   owned-ns-ids is a set of namespace IDs inside the current module.
   IO containers are orphans - positioned by JS relative to main module."
  [model entity-id io-type schema-keys owned-ns-ids]
  (when (seq schema-keys)
    (let [container-id (str (name io-type) ":" entity-id)
          label (if (= io-type :input) "Inputs" "Outputs")]
      (into [{:id container-id
              :kind :io-container
              :io-type io-type
              :label label
              :parent nil  ;; IO containers are orphans, positioned by JS
              :expandable? false
              :has-private-children? false
              :expanded? false
              :showing-private? false
              :child-count (count schema-keys)
              :private? false}]
            (for [schema-key schema-keys
                  :let [owner-id (schema/schema-owner-id model schema-key)
                        owned? (contains? owned-ns-ids owner-id)]]
              {:id (str (name io-type) "-schema:" (name schema-key))
               :kind :io-schema
               :io-type io-type
               :label (name schema-key)
               :parent container-id
               :schema-key schema-key
               :expandable? false
               :has-private-children? false
               :expanded? false
               :showing-private? false
               :child-count 0
               :private? false
               :owned? owned?})))))

(defn- compute-io-flow-edges
  "Compute data-flow edges between IO schema nodes and visible nodes.
   For inputs: edge from input-schema to each visible node that consumes it.
   For outputs: edge from each visible node that produces it to output-schema.
   Function nodes inside collapsed modules aggregate up to their closest
   visible ancestor, mirroring how code-flow edges are aggregated."
  [model entity-id io-data visible-set]
  (let [inside? (fn [node-id]
                  (or (= node-id entity-id)
                      (descendant-of? model node-id entity-id)))
        var-ids (->> (:nodes model)
                     vals
                     (filter #(and (= :function (:kind %))
                                   (inside? (:id %))))
                     (map :id))
        input-keys (:inputs io-data)
        output-keys (:outputs io-data)
        edge-nodes
        (->> var-ids
             (mapcat (fn [var-id]
                       (let [var-node (get-in model [:nodes var-id])
                             var-schema-info (compute-var-schema-info model var-node)
                             visible-target (find-visible-ancestor model var-id visible-set)]
                         (when (and var-schema-info visible-target)
                           (concat
                            (for [schema-key (:inputs var-schema-info)
                                  :when (contains? input-keys schema-key)]
                              {:id (str "io-e:in:" (name schema-key) ":" visible-target)
                               :from (str "input-schema:" (name schema-key))
                               :to visible-target
                               :edge-type :data-flow
                               :schema-key schema-key})
                            (for [schema-key (:outputs var-schema-info)
                                  :when (contains? output-keys schema-key)]
                              {:id (str "io-e:out:" visible-target ":" (name schema-key))
                               :from visible-target
                               :to (str "output-schema:" (name schema-key))
                               :edge-type :data-flow
                               :schema-key schema-key}))))))
             distinct)]
    (vec edge-nodes)))

;; -----------------------------------------------------------------------------
;; Edge subsumption

(defn- subsume-edges
  "Remove edges subsumed by more specific edges.
   Edge A→T is subsumed if there exists D→T where D is a strict visible
   descendant of A. The more specific edge is the representative."
  [model edges]
  (let [by-target (group-by :to edges)
        subsumed (into #{}
                   (for [[target edges-to-target] by-target
                         :let [sources (set (map :from edges-to-target))]
                         source sources
                         :when (some #(and (not= % source)
                                          (descendant-of? model % source))
                                     sources)]
                     [source target]))]
    (if (empty? subsumed)
      edges
      (filterv #(not (contains? subsumed [(:from %) (:to %)]))
               edges))))

;; -----------------------------------------------------------------------------
;; View node construction

(defn- make-view-node
  "Convert a model node to a projection node with rendering properties.
   Returns pure domain data - NO selected? or highlighted? fields.

   parent-override can be:
   - nil: use the node's actual parent
   - :no-parent: explicitly set parent to nil (for bounding box root)
   - any other value: use that as the parent"
  [model node parent-override expanded show-private]
  (let [id (:id node)
        kind (:kind node)
        parent (cond
                 (= parent-override :no-parent) nil
                 (some? parent-override) parent-override
                 :else (:parent node))]
    {:id id
     :kind kind
     :label (:label node)
     :parent parent
     :expandable? (boolean (seq (:children node)))
     :has-private-children? (has-private-children? model id)
     :expanded? (contains? expanded id)
     :showing-private? (and (contains? show-private id)
                            (contains? expanded id)
                            (boolean (has-private-children? model id)))
     :child-count (count (:children node))
     :private? (node-private? node)}))

;; -----------------------------------------------------------------------------
;; Private inheritance helpers

(defn- all-descendants-of
  "Get all descendant node ids of a given node (not including the node itself)."
  [model node-id]
  (let [children (get-children model node-id)]
    (into children
          (mapcat #(all-descendants-of model %) children))))

(defn- find-public-callers
  "Given a private function within a module, find public functions in the same
   module that transitively call it (tracing through chains of private functions).
   Uses edges-by-target index (target-id → #{caller-ids}) for efficient lookup."
  [model module-descendants private-fn-id edges-by-target]
  (loop [queue [private-fn-id]
         visited #{}
         public-callers #{}]
    (if (empty? queue)
      public-callers
      (let [[current & rest-queue] queue]
        (if (contains? visited current)
          (recur (vec rest-queue) visited public-callers)
          (let [visited (conj visited current)
                callers (->> (get edges-by-target current)
                             (filter #(contains? module-descendants %)))
                public (filterv #(not (node-private? (get-in model [:nodes %]))) callers)
                private (filterv #(node-private? (get-in model [:nodes %])) callers)]
            (recur (into (vec rest-queue) private)
                   visited
                   (into public-callers public))))))))

(defn- private-inherited-edges
  "Compute synthetic edges where public callers inherit cross-module
   dependencies from their private callees. For each raw edge from a
   private function to a target in a different module, finds visible
   public callers and creates edges to the visible target.
   Endpoints may be modules (collapsed) or leaf nodes."
  [model all-visible]
  (let [raw-edges (:edges model)
        edges-by-target (reduce (fn [acc {:keys [from to]}]
                                  (update acc to (fnil conj #{}) from))
                                {} raw-edges)
        ;; Group private cross-module edges by parent module of the source
        private-cross-edges
        (->> raw-edges
             (filter (fn [{:keys [from]}]
                       (node-private? (get-in model [:nodes from])))))
        by-parent (group-by #(:parent (get-in model [:nodes (:from %)])) private-cross-edges)]
    (->> by-parent
         (mapcat (fn [[parent-id edges-in-module]]
                   (when parent-id
                     (let [module-descendants (all-descendants-of model parent-id)]
                       (mapcat (fn [{:keys [from to]}]
                                 ;; Skip if target is in the same parent module
                                 (when-not (contains? module-descendants to)
                                   (let [to-visible (find-visible-ancestor model to all-visible)]
                                     (when to-visible
                                       (let [callers (find-public-callers model module-descendants from edges-by-target)]
                                         (for [caller callers
                                               :when (contains? all-visible caller)]
                                           {:from caller :to to-visible}))))))
                               edges-in-module)))))
         distinct
         vec)))

;; -----------------------------------------------------------------------------
;; Module view computation

(defn- compute-io-layer
  "Compute IO nodes and data-flow edges for a module view.
   Returns {:nodes [...] :edges [...] :io {:inputs #{} :outputs #{}}}."
  [model entity-id all-node-ids]
  (let [io-data (compute-io-schemas model entity-id)
        owned-ns-ids (->> all-node-ids
                          (filter #(= :module (:kind (get-in model [:nodes %]))))
                          set)
        input-nodes (create-io-nodes model entity-id :input (:inputs io-data) owned-ns-ids)
        output-nodes (create-io-nodes model entity-id :output (:outputs io-data) owned-ns-ids)
        data-flow-edges (compute-io-flow-edges model entity-id io-data all-node-ids)]
    {:nodes (concat input-nodes output-nodes)
     :edges data-flow-edges
     :io io-data}))

(defn- compute-module-view
  "Compute view when the entity is a module (has children).
   expanded controls which modules show their children.
   show-private controls whether private children are visible.
   Returns pure domain data - no selected? or highlighted? fields."
  [model entity-id expanded show-private]
  (let [;; The viewed entity is always expanded (its children are shown)
        effective-expanded (conj (or expanded #{}) entity-id)
        visible (compute-visible-set model entity-id effective-expanded show-private)

        ;; Aggregate edges to visible node level + inherited from private callees
        ;; then drop subsumed edges (where a child already represents the connection)
        code-edges (aggregate-edges model visible)
        inherited (private-inherited-edges model visible)
        all-edges (vec (distinct (concat code-edges inherited)))
        final-edges (subsume-edges model all-edges)

        ;; Build view nodes
        ;; Module node (the viewed entity - no parent for strict bounding box)
        module-node (make-view-node model (get-in model [:nodes entity-id])
                                    :no-parent effective-expanded show-private)

        ;; Descendant nodes — each visible node uses its actual parent
        ;; (guaranteed visible by construction of compute-visible-set)
        descendant-nodes (for [nid visible
                               :let [node (get-in model [:nodes nid])]
                               :when node]
                           (make-view-node model node nil effective-expanded show-private))

        ;; All node IDs for IO layer
        all-node-ids (conj visible entity-id)

        ;; Build code-flow edges
        code-flow-edges (map-indexed
                         (fn [idx {:keys [from to]}]
                           {:id (str "e" idx)
                            :from from
                            :to to
                            :edge-type :code-flow})
                         final-edges)

        ;; Compute IO layer (synthetic IO nodes + data-flow edges)
        io-layer (compute-io-layer model entity-id all-node-ids)]

    {:nodes (vec (concat [module-node] descendant-nodes
                         (:nodes io-layer)))
     :edges (vec (concat code-flow-edges
                         (:edges io-layer)))
     :io (:io io-layer)}))

;; -----------------------------------------------------------------------------
;; Leaf view computation

(defn- compute-leaf-view
  "Compute view when the entity is a leaf (no children).
   Returns pure domain data - no selected? or highlighted? fields.

   For vars: shows related vars grouped by their parent namespace,
   with var-level edges."
  [model entity-id show-private]
  (let [raw-edges (:edges model)

        ;; Find all related vars via edges
        outgoing-var-ids (->> raw-edges
                              (filter #(= entity-id (:from %)))
                              (map :to)
                              set)
        incoming-var-ids (->> raw-edges
                              (filter #(= entity-id (:to %)))
                              (map :from)
                              set)
        related-var-ids (set/union outgoing-var-ids incoming-var-ids)

        ;; Get the parent namespaces for grouping
        entity-ns (:parent (get-in model [:nodes entity-id]))
        related-ns-ids (->> related-var-ids
                            (map #(:parent (get-in model [:nodes %])))
                            (remove nil?)
                            set)
        all-ns-ids (if entity-ns
                     (conj related-ns-ids entity-ns)
                     related-ns-ids)

        ;; Get edges at var level involving the selected entity
        relevant-edges (->> raw-edges
                            (filter (fn [{:keys [from to]}]
                                      (or (= from entity-id) (= to entity-id)))))

        ;; Build namespace nodes for grouping (no expand in leaf view)
        ns-nodes (for [nid all-ns-ids
                       :let [node (get-in model [:nodes nid])]
                       :when node]
                   (make-view-node model node (:parent node) #{} show-private))

        ;; Build var nodes (selected + related)
        entity-node (make-view-node model (get-in model [:nodes entity-id])
                                    entity-ns #{} show-private)
        related-var-nodes (for [vid related-var-ids
                                :let [node (get-in model [:nodes vid])]
                                :when node]
                            (make-view-node model node (:parent node) #{} show-private))

        ;; Get parent folders for grouping namespace nodes
        folder-ids (->> all-ns-ids
                        (map #(:parent (get-in model [:nodes %])))
                        (remove nil?)
                        set)
        folder-nodes (for [fid folder-ids
                           :let [node (get-in model [:nodes fid])]
                           :when node]
                       (make-view-node model node nil #{} show-private))

        ;; Build view edges (internal format, no highlighting)
        view-edges (map-indexed
                    (fn [idx {:keys [from to]}]
                      {:id (str "e" idx)
                       :from from
                       :to to
                       :edge-type :code-flow})
                    relevant-edges)]

    {:nodes (vec (concat folder-nodes ns-nodes related-var-nodes [entity-node]))
     :edges (vec view-edges)
     :io nil}))

;; -----------------------------------------------------------------------------
;; Public API

(defn entity-graph
  "Compute graph projection for any entity. Returns pure domain data.

   For modules (entities with children):
   - Shows visible children (filtered by expanded and show-private)
   - Aggregates edges to visible node level
   - Expanded modules show their children; collapsed modules are edge endpoints

   For leaves (entities without children):
   - Shows the entity and all related entities
   - Groups related entities by their parent module

   Returns {:nodes :edges :io} where:
   - nodes: vector of projection nodes (no :selected? field)
   - edges: vector of edges (no :highlighted? field)
   - io: {:inputs :outputs} schema sets for module views"
  {:malli/schema [:=> [:cat :Model :ProjectionOpts] :Projection]}
  [model {:keys [view-id expanded show-private]}]
  (let [;; Default to root if no view-id
        entity-id (or view-id (:id (path/find-root-node model)))
        expanded (or expanded #{})
        show-private (or show-private #{})

        children-ids (get-children model entity-id)]

    (if (seq children-ids)
      (compute-module-view model entity-id expanded show-private)
      (compute-leaf-view model entity-id show-private))))
