(ns fukan.projection.graph
  "Graph projection: Model → visible subgraph for visualization.
   Given a view entity, a set of explicitly expanded modules, a set
   of show-private modules, and a set of visible edge types, computes
   which nodes and edges are visible and aggregates raw edges to the
   visible level. Returns pure domain data with no UI state."
  (:require [fukan.projection.path :as path]
            [clojure.set :as set]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema ProjectionNodeKind
  [:enum {:description "Node kinds in projections: module, function, or schema."}
   :module :function :schema])

(def ^:schema ProjectionNode
  [:map {:description "A node in the projected graph with display properties for visualization."}
   [:id :NodeId]
   [:kind :ProjectionNodeKind]
   [:label :string]
   [:parent {:optional true} [:maybe :NodeId]]
   [:expandable? :boolean]
   [:has-private-children? :boolean]
   [:expanded? :boolean]
   [:showing-private? :boolean]
   [:child-count :int]
   [:private? {:optional true} :boolean]
   [:schema-key {:optional true} :keyword]])

(def ^:schema ProjectionEdgeType
  [:enum {:description "Edge semantic types: code-flow (call graphs) and schema-reference (type refs)."}
   :code-flow :schema-reference])

(def ^:schema ProjectionEdge
  [:map {:description "A directed, typed edge in the projected graph."}
   [:id {:description "Synthetic sequential ID (e.g. e0, e1)."} :string]
   [:from :NodeId]
   [:to :NodeId]
   [:edge-type :ProjectionEdgeType]])

(def ^:schema Projection
  [:map {:description "Complete graph projection result: visible nodes and edges."}
   [:nodes [:vector :ProjectionNode]]
   [:edges [:vector :ProjectionEdge]]])

(def ^:schema ProjectionOpts
  [:map {:description "Options for graph projection: which entity to view, which modules are expanded, which show private children, and which edge types are visible."}
   [:view-id {:optional true} [:maybe :NodeId]]
   [:expanded {:optional true} [:set :NodeId]]
   [:show-private {:optional true} [:set :NodeId]]
   [:visible-edge-types {:optional true} [:set :ProjectionEdgeType]]])

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
   - Skip private nodes unless parent is in show-private
   - Include the child
   - If the child is a module AND in effective-expanded: recurse into its children"
  [model entity-id effective-expanded show-private]
  (let [children (get-children model entity-id)]
    (reduce
      (fn [visible child-id]
        (let [child-node (get-in model [:nodes child-id])]
          (cond
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
  "Aggregate leaf-level edges to the visible node level, filtering by edge kind.
   For each raw edge of the given kind, find which visible nodes it connects.
   Returns deduplicated edges between visible nodes.

   Excludes edges where one endpoint is an ancestor of the other -
   parent-child relationships are implicit in compound node structure."
  [model visible-set edge-kind]
  (let [raw-edges (->> (:edges model)
                       (filter #(= edge-kind (:kind %))))]
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
    (cond-> {:id id
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
             :private? (node-private? node)}
      (= :schema kind)
      (assoc :schema-key (get-in node [:data :schema-key])))))

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
   Endpoints may be modules (collapsed) or leaf nodes.
   Filters by edge-kind to only consider edges of the given model-level kind."
  [model all-visible edge-kind]
  (let [raw-edges (->> (:edges model)
                       (filter #(= edge-kind (:kind %))))
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

(defn- compute-module-view
  "Compute view when the entity is a module (has children).
   expanded controls which modules show their children.
   show-private controls whether private children are visible.
   visible-edge-types controls which edge types to include.
   Returns pure domain data - no selected? or highlighted? fields."
  [model entity-id expanded show-private visible-edge-types]
  (let [;; The viewed entity is always expanded (its children are shown)
        effective-expanded (conj (or expanded #{}) entity-id)
        visible (compute-visible-set model entity-id effective-expanded show-private)

        ;; Aggregate per edge kind, independently
        ;; code-flow aggregates both function-call and dispatches model edges
        edge-type->model-kinds {:code-flow [:function-call :dispatches]
                                :schema-reference [:schema-reference]}
        edges (vec (mapcat
                    (fn [edge-type]
                      (let [model-kinds (get edge-type->model-kinds edge-type [edge-type])
                            agg (mapcat #(aggregate-edges model visible %) model-kinds)
                            inherited (mapcat #(private-inherited-edges model visible %) model-kinds)
                            all (vec (distinct (concat agg inherited)))
                            final (subsume-edges model all)]
                        (map (fn [e] (assoc e :edge-type edge-type)) final)))
                    visible-edge-types))

        ;; Assign sequential IDs
        numbered-edges (map-indexed (fn [i e] (assoc e :id (str "e" i))) edges)

        ;; Build view nodes
        ;; Module node (the viewed entity - no parent for strict bounding box)
        module-node (make-view-node model (get-in model [:nodes entity-id])
                                    :no-parent effective-expanded show-private)

        ;; Descendant nodes — each visible node uses its actual parent
        ;; (guaranteed visible by construction of compute-visible-set)
        descendant-nodes (for [nid visible
                               :let [node (get-in model [:nodes nid])]
                               :when node]
                           (make-view-node model node nil effective-expanded show-private))]

    {:nodes (vec (concat [module-node] descendant-nodes))
     :edges (vec numbered-edges)}))

;; -----------------------------------------------------------------------------
;; Leaf view computation

(defn- compute-leaf-view
  "Compute view when the entity is a leaf (no children).
   Returns pure domain data - no selected? or highlighted? fields.

   Shows related functions grouped by their parent module,
   with leaf-level edges. Filters edges by visible-edge-types
   and maps model edge kinds to projection edge types."
  [model entity-id show-private visible-edge-types]
  (let [;; Map visible edge types to model-level kinds
        edge-type->model-kinds {:code-flow #{:function-call :dispatches}
                                :schema-reference #{:schema-reference}}
        visible-model-kinds (reduce (fn [acc et]
                                      (into acc (get edge-type->model-kinds et)))
                                    #{} visible-edge-types)
        raw-edges (->> (:edges model)
                       (filter #(contains? visible-model-kinds (:kind %))))

        ;; Find all related functions via edges
        outgoing-fn-ids (->> raw-edges
                             (filter #(= entity-id (:from %)))
                             (map :to)
                             set)
        incoming-fn-ids (->> raw-edges
                             (filter #(= entity-id (:to %)))
                             (map :from)
                             set)
        related-fn-ids (set/union outgoing-fn-ids incoming-fn-ids)

        ;; Get the parent modules for grouping
        entity-module (:parent (get-in model [:nodes entity-id]))
        related-module-ids (->> related-fn-ids
                                (map #(:parent (get-in model [:nodes %])))
                                (remove nil?)
                                set)
        all-module-ids (if entity-module
                         (conj related-module-ids entity-module)
                         related-module-ids)

        ;; Get edges at leaf level involving the selected entity
        relevant-edges (->> raw-edges
                            (filter (fn [{:keys [from to]}]
                                      (or (= from entity-id) (= to entity-id)))))

        ;; Map model kind to projection edge-type
        kind->edge-type {:function-call :code-flow
                         :dispatches :code-flow
                         :schema-reference :schema-reference}

        ;; Build module nodes for grouping (no expand in leaf view)
        module-nodes (for [mid all-module-ids
                           :let [node (get-in model [:nodes mid])]
                           :when node]
                       (make-view-node model node (:parent node) #{} show-private))

        ;; Build function nodes (selected + related)
        entity-node (make-view-node model (get-in model [:nodes entity-id])
                                    entity-module #{} show-private)
        related-fn-nodes (for [fid related-fn-ids
                               :let [node (get-in model [:nodes fid])]
                               :when node]
                           (make-view-node model node (:parent node) #{} show-private))

        ;; Get parent folders for grouping module nodes
        folder-ids (->> all-module-ids
                        (map #(:parent (get-in model [:nodes %])))
                        (remove nil?)
                        set)
        folder-nodes (for [fid folder-ids
                           :let [node (get-in model [:nodes fid])]
                           :when node]
                       (make-view-node model node nil #{} show-private))

        ;; Build view edges (internal format, no highlighting)
        view-edges (map-indexed
                    (fn [idx {:keys [from to kind]}]
                      {:id (str "e" idx)
                       :from from
                       :to to
                       :edge-type (get kind->edge-type kind :code-flow)})
                    relevant-edges)]

    {:nodes (vec (concat folder-nodes module-nodes related-fn-nodes [entity-node]))
     :edges (vec view-edges)}))

;; -----------------------------------------------------------------------------
;; Public API

(defn entity-graph
  "Compute graph projection for any entity. Returns pure domain data.

   For modules (entities with children):
   - Shows visible children (filtered by expanded and show-private)
   - Aggregates edges to visible node level per edge kind
   - Expanded modules show their children; collapsed modules are edge endpoints

   For leaves (entities without children):
   - Shows the entity and all related entities
   - Groups related entities by their parent module

   Returns {:nodes :edges} where:
   - nodes: vector of projection nodes (no :selected? field)
   - edges: vector of edges (no :highlighted? field)"
  {:malli/schema [:=> [:cat :Model :ProjectionOpts] :Projection]}
  [model {:keys [view-id expanded show-private visible-edge-types]}]
  (let [;; Default to root if no view-id
        entity-id (or view-id (:id (path/find-root-node model)))
        expanded (or expanded #{})
        show-private (or show-private #{})
        visible-edge-types (or visible-edge-types #{:code-flow :schema-reference})

        children-ids (get-children model entity-id)]

    (if (seq children-ids)
      (compute-module-view model entity-id expanded show-private visible-edge-types)
      (compute-leaf-view model entity-id show-private visible-edge-types))))
