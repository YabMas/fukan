(ns fukan.projection.graph
  "Graph projection: Model → visible subgraph for visualization.
   Given a view entity and a set of expanded modules, computes which
   nodes and edges are visible, aggregates raw edges to the visible level,
   drills down into modules to reveal connected children, and generates
   synthetic IO nodes from contract boundaries. Returns pure domain data
   with no UI state."
  (:require [fukan.projection.schema :as schema]
            [fukan.projection.path :as path]
            [fukan.schema.forms :as forms]
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
   [:child-count :int]
   [:private? {:optional true} :boolean]
   [:root? {:optional true} :boolean]
   [:io-type {:optional true} [:enum :input :output]]
   [:schema-key {:optional true} :keyword]])

(def ^:schema ProjectionEdgeType
  [:enum {:description "Edge semantic types: code-flow (call graphs), schema-flow (var-to-schema), data-flow (contract IO), schema-composition (schema-to-schema references)."}
   :code-flow :schema-flow :data-flow :schema-composition])

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
  [:map {:description "Options for graph projection: which entity to view and which modules are expanded."}
   [:view-id {:optional true} [:maybe :string]]
   [:expanded-modules {:optional true} [:set :NodeId]]])

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

(defn- get-visible-children
  "Get the visible children of an entity, filtered by expanded-modules.
   Filters out schema nodes (they appear only as IO schemas)."
  [model entity-id expanded-modules]
  (let [children-ids (get-children model entity-id)
        ;; Filter out schema nodes - they appear only as IO schemas
        children-ids (into #{} (remove #(= :schema (:kind (get-in model [:nodes %])))) children-ids)]
    (if (or (nil? expanded-modules)
            (contains? expanded-modules entity-id))
      children-ids
      (into #{} (remove #(node-private? (get-in model [:nodes %]))) children-ids))))

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
                              ;; No edges between ancestor and descendant
                              (not (descendant-of? model to-visible from-visible))
                              (not (descendant-of? model from-visible to-visible)))
                     {:from from-visible :to to-visible}))))
         distinct
         vec)))

;; -----------------------------------------------------------------------------
;; IO Schema computation

(defn- extract-fn-schema-flow
  "Extract input and output schema references from a function schema.
   Expects schema in form [:=> [:cat input1 input2 ...] output].
   Returns {:inputs #{schema-keys...} :outputs #{schema-keys...}}
   or nil if not a function schema.
   Takes the model to determine which keywords are registered schemas."
  [model fn-schema]
  (when-let [{:keys [inputs output]} (forms/fn-schema-parts fn-schema)]
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
  "Compute input/output schemas for a module based on its contract.
   Returns {:inputs #{schema-keys} :outputs #{schema-keys}} or empty sets
   when no contract is present."
  [model module-id]
  (if-let [contract (get-in model [:nodes module-id :data :contract])]
    (contract->io model contract)
    {:inputs #{} :outputs #{}}))

(defn- compute-schema-flow-edges
  "Compute schema-flow edges for visible var nodes.
   Returns a vector of edges from vars to their output schemas,
   and from input schemas to vars.

   Only creates edges where both the var and schema node are visible."
  [model visible-var-ids visible-node-ids]
  (->> visible-var-ids
       (mapcat (fn [var-id]
                 (let [var-node (get-in model [:nodes var-id])
                       schema-info (compute-var-schema-info model var-node)]
                   (when schema-info
                     (concat
                      ;; Edges from var to output schemas
                      (->> (:outputs schema-info)
                           (keep (fn [schema-key]
                                   (let [schema-id (schema/find-schema-node-id model schema-key)]
                                     (when (and schema-id (contains? visible-node-ids schema-id))
                                       {:from var-id :to schema-id :schema-key schema-key})))))
                      ;; Edges from input schemas to var
                      (->> (:inputs schema-info)
                           (keep (fn [schema-key]
                                   (let [schema-id (schema/find-schema-node-id model schema-key)]
                                     (when (and schema-id (contains? visible-node-ids schema-id))
                                       {:from schema-id :to var-id :schema-key schema-key}))))))))))
       vec))

(defn- compute-schema-composition-edges
  "Compute edges between schema nodes based on composition.
   Returns edges from a schema to schemas it references.
   Derives refs on-demand from the schema form.
   Only creates edges where both schema nodes are visible."
  [model visible-schema-ids]
  (->> visible-schema-ids
       (mapcat (fn [schema-id]
                 (let [schema-node (get-in model [:nodes schema-id])
                       schema-form (get-in schema-node [:data :schema])
                       refs (when schema-form
                              (schema/extract-schema-refs model schema-form))]
                   (for [ref-key refs
                         :let [ref-id (schema/find-schema-node-id model ref-key)]
                         :when (and ref-id
                                    (not= ref-id schema-id)
                                    (contains? visible-schema-ids ref-id))]
                     {:from schema-id
                      :to ref-id
                      :edge-type :schema-composition}))))
       distinct
       vec))

;; -----------------------------------------------------------------------------
;; IO Nodes and Data-Flow Edges

(defn- create-io-nodes
  "Create IO module and schema nodes for a module view.
   Returns a vector of nodes: IO module + child schema nodes.
   io-type is :input or :output.
   owned-ns-ids is a set of namespace IDs inside the current module.
   IO modules are orphans - positioned by JS relative to main module."
  [model entity-id io-type schema-keys owned-ns-ids]
  (when (seq schema-keys)
    (let [module-id (str (name io-type) ":" entity-id)
          label (if (= io-type :input) "Inputs" "Outputs")]
      (into [{:id module-id
              :kind :io-container
              :io-type io-type
              :label label
              :parent nil  ;; IO modules are orphans, positioned by JS
              :expandable? false
              :has-private-children? false
              :expanded? false
              :child-count (count schema-keys)
              :private? false}]
            (for [schema-key schema-keys
                  :let [owner-id (schema/schema-owner-id model schema-key)
                        owned? (contains? owned-ns-ids owner-id)]]
              {:id (str (name io-type) "-schema:" (name schema-key))
               :kind :io-schema
               :io-type io-type
               :label (name schema-key)
               :parent module-id
               :schema-key schema-key
               :expandable? false
               :has-private-children? false
               :expanded? false
               :child-count 0
               :private? false
               :owned? owned?})))))

(defn- compute-io-flow-edges
  "Compute data-flow edges between IO schema nodes and visible nodes.
   For inputs: edge from input-schema to each visible node that consumes it.
   For outputs: edge from each visible node that produces it to output-schema."
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
;; View node construction

(defn- make-view-node
  "Convert a model node to a projection node with rendering properties.
   Returns pure domain data - NO selected? or highlighted? fields.

   parent-override can be:
   - nil: use the node's actual parent
   - :no-parent: explicitly set parent to nil (for bounding box root)
   - any other value: use that as the parent"
  [model node parent-override expanded-modules]
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
             :expanded? (contains? expanded-modules id)
             :child-count (count (:children node))
             :private? (node-private? node)}
      (get-in node [:data :root?]) (assoc :root? true))))

;; -----------------------------------------------------------------------------
;; Drill-down expansion helpers

(defn- all-descendants-of
  "Get all descendant node ids of a given node (not including the node itself)."
  [model node-id]
  (let [children (get-children model node-id)]
    (into children
          (mapcat #(all-descendants-of model %) children))))

(defn- find-direct-child-of
  "Given a descendant node-id of module-id, find the ancestor
   that is a direct child of module-id. Returns nil if node-id
   is not a descendant, or node-id itself if already a direct child."
  [model module-id node-id]
  (let [direct-children (get-in model [:nodes module-id :children])]
    (if (contains? direct-children node-id)
      node-id
      (loop [current node-id]
        (let [parent (:parent (get-in model [:nodes current]))]
          (cond
            (nil? parent) nil
            (= parent module-id) current
            :else (recur parent)))))))

(defn- find-targets-inside-module
  "Find direct children of module-id that contain actual edge targets
   from entities in external-visible-set (entities not inside the module).
   Uses raw edges to find actual targets, not aggregated ones.
   Only returns targets where the source and target have the same :kind."
  [model module-id external-visible-set]
  (let [raw-edges (:edges model)
        module-descendants (all-descendants-of model module-id)]
    (->> raw-edges
         (keep (fn [{:keys [from to]}]
                 ;; Target must be a descendant of the module
                 (when (contains? module-descendants to)
                   ;; Source must be reachable from external visible set
                   (let [from-ancestor (find-visible-ancestor model from external-visible-set)]
                     (when (and from-ancestor
                                ;; Source must not be inside the module
                                (not (contains? module-descendants from)))
                       ;; Find direct child containing the target
                       (let [target-child (find-direct-child-of model module-id to)
                             source-kind (get-in model [:nodes from-ancestor :kind])
                             target-child-kind (get-in model [:nodes target-child :kind])
                             target-kind (get-in model [:nodes to :kind])]
                         ;; Type filter: compare visible source with target-child kind
                         ;; Special case: if target-child is a module (nested modules),
                         ;; compare with raw target kind to handle module→module nesting
                         (when (or (= source-kind target-child-kind)
                                   (and (= target-child-kind :module)
                                        (= source-kind target-kind)))
                           target-child)))))))
         (remove nil?)
         set)))

(defn- find-sources-inside-module
  "Find direct children of module-id that contain actual edge sources
   going to entities in external-visible-set (entities not inside the module).
   Uses raw edges to find actual sources, not aggregated ones.
   Only returns sources where the source and target have the same :kind."
  [model module-id external-visible-set]
  (let [raw-edges (:edges model)
        module-descendants (all-descendants-of model module-id)]
    (->> raw-edges
         (keep (fn [{:keys [from to]}]
                 ;; Source must be a descendant of the module
                 (when (contains? module-descendants from)
                   ;; Target must be reachable from external visible set
                   (let [to-ancestor (find-visible-ancestor model to external-visible-set)]
                     (when (and to-ancestor
                                ;; Target must not be inside the module
                                (not (contains? module-descendants to)))
                       ;; Find direct child containing the source
                       (let [source-child (find-direct-child-of model module-id from)
                             source-kind (get-in model [:nodes from :kind])
                             source-child-kind (get-in model [:nodes source-child :kind])
                             target-kind (get-in model [:nodes to-ancestor :kind])]
                         ;; Type filter: compare source-child kind with visible target
                         ;; Special case: if source-child is a module (nested modules),
                         ;; compare raw source kind to handle module→module nesting
                         (when (or (= source-child-kind target-kind)
                                   (and (= source-child-kind :module)
                                        (= source-kind target-kind)))
                           source-child)))))))
         (remove nil?)
         set)))

(defn- expand-internal-deps
  "Expand a set of nodes to include their internal dependencies.
   Given an initial set of node ids inside a module, transitively add
   any nodes inside the same module that they depend on.
   This ensures drill-down captures complete dependency chains.

   Returns only direct children of module-id, not grandchildren."
  [model module-id initial-set]
  (let [module-descendants (all-descendants-of model module-id)]
    (loop [expanded initial-set]
      (let [new-deps (->> (:edges model)
                          (keep (fn [{:keys [from to]}]
                                  (let [from-parent (:parent (get-in model [:nodes from]))
                                        to-parent (:parent (get-in model [:nodes to]))]
                                    (when (and (or (contains? expanded from-parent)
                                                   (some #(descendant-of? model from-parent %) expanded))
                                               (contains? module-descendants to-parent))
                                      ;; Return direct child of module, not grandchild
                                      (find-direct-child-of model module-id to-parent)))))
                          (remove nil?)
                          ;; Filter out already-expanded to avoid infinite loop
                          (remove #(contains? expanded %))
                          set)]
        (if (empty? new-deps)
          expanded
          (recur (set/union expanded new-deps)))))))

;; -----------------------------------------------------------------------------
;; Root function injection

(defn- find-root-descendants
  "Find root function nodes that are descendants of entity-id."
  [model entity-id]
  (->> (all-descendants-of model entity-id)
       (filter (fn [nid]
                 (let [node (get-in model [:nodes nid])]
                   (and (= :function (:kind node))
                        (get-in node [:data :root?])))))
       set))

(defn- root-fn-drill-down
  "Build drill-down-map entries for root functions.
   For each root function, traces its ancestry back to entity-id,
   creating drill-down entries at each intermediate level."
  [model entity-id root-fn-ids]
  (let [direct-children (get-in model [:nodes entity-id :children])]
    (reduce
      (fn [acc fn-id]
        (loop [current fn-id
               entries acc]
          (let [parent (:parent (get-in model [:nodes current]))]
            (cond
              ;; No parent — shouldn't happen for valid descendants
              (nil? parent) entries
              ;; Parent is entity-id — current is a direct child, already visible
              (= parent entity-id) entries
              ;; Parent is a direct child of entity-id — add current under parent, done
              (contains? direct-children parent)
              (update entries parent (fnil conj #{}) current)
              ;; Intermediate module — add current under parent, continue up
              :else
              (recur parent (update entries parent (fnil conj #{}) current))))))
      {}
      root-fn-ids)))

;; -----------------------------------------------------------------------------
;; Module view computation

(defn- iterate-drill-down
  "Iteratively expand visible set until all edge endpoints are visible.

   Same-type filtering: Only drills into modules when the source and target
   are the same kind (namespace→namespace, var→var). This keeps the structural
   overview clean at each level of the hierarchy.

   Includes internal dep expansion at each step to ensure nested modules
   are discovered and processed.

   Returns {:visible-set :drill-down-map :edges} where:
   - visible-set: all entities that should be visible
   - drill-down-map: {module-id -> #{children}} for grouping (includes internal deps)
   - edges: final aggregated edges"
  [model initial-children-set]
  (loop [visible-set initial-children-set
         drill-down-map {}]
    (let [edges (aggregate-edges model visible-set)
          ;; Find modules that are edge targets
          module-targets (->> edges
                                 (map :to)
                                 (filter #(seq (get-in model [:nodes % :children])))
                                 set)
          ;; Find modules that are edge sources
          module-sources (->> edges
                                 (map :from)
                                 (filter #(seq (get-in model [:nodes % :children])))
                                 set)
          ;; For each module target, find actual targets inside
          targets-by-module
            (into {}
              (for [cid module-targets
                    :let [;; External sources are visible entities not inside this module
                          external (into #{} (remove #(descendant-of? model % cid) visible-set))
                          targets (find-targets-inside-module model cid external)]
                    :when (seq targets)]
                [cid targets]))
          ;; For each module source, find actual sources inside
          sources-by-module
            (into {}
              (for [cid module-sources
                    :let [;; External targets are visible entities not inside this module
                          external (into #{} (remove #(descendant-of? model % cid) visible-set))
                          sources (find-sources-inside-module model cid external)]
                    :when (seq sources)]
                [cid sources]))
          ;; Merge targets and sources
          new-entities-by-module (merge-with set/union targets-by-module sources-by-module)
          ;; Expand internal deps for each module - this may add sibling modules
          ;; that need drilling down in the next iteration
          expanded-entities-by-module
            (into {}
              (for [[cid entities] new-entities-by-module]
                [cid (expand-internal-deps model cid entities)]))
          ;; Combine with existing drill-down-map
          updated-drill-down-map (merge-with set/union drill-down-map expanded-entities-by-module)
          ;; Only consider entities that aren't already visible
          new-entities (set/difference (into #{} (mapcat val expanded-entities-by-module)) visible-set)]
      (if (empty? new-entities)
        {:visible-set visible-set
         :drill-down-map drill-down-map
         :edges edges}
        (recur (set/union visible-set new-entities)
               updated-drill-down-map)))))

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

(defn- compute-module-view-impl
  "Generic module view computation. Works for any module type.
   Returns pure domain data - no selected? or highlighted? fields."
  [model entity-id expanded-modules children-ids]
  (let [children-set (set children-ids)

        ;; Iteratively expand visible set until all edge targets are visible
        ;; (includes internal dep expansion at each step)
        {:keys [drill-down-map]} (iterate-drill-down model children-set)

        ;; Inject root function descendants (contract entry points with no code-level callers)
        root-fns (find-root-descendants model entity-id)
        root-drill (root-fn-drill-down model entity-id root-fns)
        drill-down-map (merge-with set/union drill-down-map root-drill)
        drill-down-entities (into #{} (mapcat val drill-down-map))

        ;; Build final visible set
        all-visible (set/union children-set drill-down-entities)

        ;; Compute final edges, then filter cross-level edges per SameTypeDrillDown:
        ;; 1. Drop edges to/from drilled modules — unresolved edges that
        ;;    should have gone to children at the same structural level.
        ;; 2. Drop edges between different kinds (e.g. function ↔ module)
        ;;    — structurally different levels should never be connected.
        raw-final-edges (aggregate-edges model all-visible)
        drilled-modules (->> all-visible
                                (filter (fn [nid]
                                          (let [children (get-in model [:nodes nid :children] #{})]
                                            (some all-visible children))))
                                set)
        final-edges (remove (fn [{:keys [from to]}]
                              (let [from-kind (:kind (get-in model [:nodes from]))
                                    to-kind (:kind (get-in model [:nodes to]))]
                                (or (contains? drilled-modules from)
                                    (contains? drilled-modules to)
                                    (not= from-kind to-kind))))
                            raw-final-edges)

        ;; Build view nodes
        ;; Module node (the viewed entity - no parent for strict bounding box)
        module-node (make-view-node model (get-in model [:nodes entity-id])
                                       :no-parent expanded-modules)

        ;; Direct children
        child-nodes (for [cid children-ids
                          :let [node (get-in model [:nodes cid])]
                          :when node]
                      (make-view-node model node entity-id expanded-modules))

        ;; Drill-down entities (inside module children)
        drill-down-nodes (for [did drill-down-entities
                               :let [node (get-in model [:nodes did])
                                     parent-module (some (fn [[cid entity-set]]
                                                              (when (contains? entity-set did) cid))
                                                            drill-down-map)]
                               :when (and node parent-module)]
                           (make-view-node model node parent-module expanded-modules))

        ;; Collect all view node IDs for schema-flow edge filtering
        all-node-ids (set (concat [entity-id]
                                  children-ids
                                  drill-down-entities))

        ;; Collect visible function IDs for schema-flow edges
        visible-var-ids (->> all-node-ids
                             (filter #(= :function (:kind (get-in model [:nodes %]))))
                             set)

        ;; Collect visible schema IDs for schema-composition edges
        visible-schema-ids (->> all-node-ids
                                (filter #(= :schema (:kind (get-in model [:nodes %]))))
                                set)

        ;; Build code-flow edges
        code-flow-edges (map-indexed
                         (fn [idx {:keys [from to]}]
                           {:id (str "e" idx)
                            :from from
                            :to to
                            :edge-type :code-flow})
                         final-edges)

        ;; Build schema-flow edges
        schema-flow-raw (compute-schema-flow-edges model visible-var-ids all-node-ids)
        schema-flow-edges (map-indexed
                           (fn [idx {:keys [from to schema-key]}]
                             {:id (str "se" idx)
                              :from from
                              :to to
                              :edge-type :schema-flow
                              :schema-key schema-key})
                           schema-flow-raw)

        ;; Build schema-composition edges
        schema-composition-raw (compute-schema-composition-edges model visible-schema-ids)
        schema-composition-edges (map-indexed
                                   (fn [idx {:keys [from to]}]
                                     {:id (str "sce" idx)
                                      :from from
                                      :to to
                                      :edge-type :schema-composition})
                                   schema-composition-raw)

        ;; Compute IO layer (synthetic IO nodes + data-flow edges)
        io-layer (compute-io-layer model entity-id all-node-ids)]

    {:nodes (vec (concat [module-node] child-nodes drill-down-nodes
                         (:nodes io-layer)))
     :edges (vec (concat code-flow-edges schema-flow-edges schema-composition-edges
                         (:edges io-layer)))
     :io (:io io-layer)}))

(defn- compute-module-view
  "Compute view when the entity is a module (has children).
   Returns pure domain data."
  [model entity-id expanded-modules]
  (let [children-ids (get-visible-children model entity-id expanded-modules)]
    (compute-module-view-impl model entity-id expanded-modules children-ids)))

;; -----------------------------------------------------------------------------
;; Leaf view computation

(defn- compute-leaf-view
  "Compute view when the entity is a leaf (no children).
   Returns pure domain data - no selected? or highlighted? fields.

   For vars: shows related vars grouped by their parent namespace,
   with var-level edges."
  [model entity-id expanded-modules]
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

        ;; Build namespace nodes for grouping
        ns-nodes (for [nid all-ns-ids
                       :let [node (get-in model [:nodes nid])]
                       :when node]
                   (make-view-node model node (:parent node) expanded-modules))

        ;; Build var nodes (selected + related)
        entity-node (make-view-node model (get-in model [:nodes entity-id])
                                    entity-ns expanded-modules)
        related-var-nodes (for [vid related-var-ids
                                :let [node (get-in model [:nodes vid])]
                                :when node]
                            (make-view-node model node (:parent node) expanded-modules))

        ;; Get parent folders for grouping namespace nodes
        folder-ids (->> all-ns-ids
                        (map #(:parent (get-in model [:nodes %])))
                        (remove nil?)
                        set)
        folder-nodes (for [fid folder-ids
                           :let [node (get-in model [:nodes fid])]
                           :when node]
                       (make-view-node model node nil expanded-modules))

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
   - Shows visible children (filtered by expanded-modules)
   - Shows edges between visible children
   - Shows drill-down to entities in sibling modules when related

   For leaves (entities without children):
   - Shows the entity and all related entities
   - Groups related entities by their parent module

   Returns {:nodes :edges :io} where:
   - nodes: vector of projection nodes (no :selected? field)
   - edges: vector of edges (no :highlighted? field)
   - io: {:inputs :outputs} schema sets for module views"
  {:malli/schema [:=> [:cat :Model :ProjectionOpts] :Projection]}
  [model {:keys [view-id expanded-modules]}]
  (let [;; Default to root if no view-id
        entity-id (or view-id (:id (path/find-root-node model)))
        expanded-modules (or expanded-modules #{})

        children-ids (get-children model entity-id)]

    (if (seq children-ids)
      (compute-module-view model entity-id expanded-modules)
      (compute-leaf-view model entity-id expanded-modules))))
