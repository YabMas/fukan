(ns fukan.web.views.graph
  "Enrich graph projection with UI state for Cytoscape rendering.
   Adds selection and highlight flags to projection nodes and edges,
   computes highlighted edges (those connected to the selected node),
   then delegates to cytoscape.clj for the final JSON transform."
  (:require [fukan.web.views.cytoscape :as cytoscape]))

;; -----------------------------------------------------------------------------
;; UI State application

(defn- find-projection-root
  "Find the root node id from projection nodes (node with no parent)."
  [nodes]
  (->> nodes (filter #(nil? (:parent %))) first :id))

(defn- add-node-selection
  "Add :selected? field to projection nodes based on selected-id."
  [nodes selected-id]
  (mapv (fn [n] (assoc n :selected? (= (:id n) selected-id))) nodes))

;; -----------------------------------------------------------------------------
;; UI State

(defn- add-ui-state
  "Add UI state to graph projection data.

   Takes a graph projection (from proj/entity-graph) and editor-state.
   Adds :selected? to nodes. Edge highlighting is driven entirely by
   the top-level highlightedEdges array computed by compute-highlighted-edges.

   Returns {:nodes :edges} where:
   - nodes: vector of view nodes with rendering properties + :selected?
   - edges: vector of edges (unmodified)"
  [graph-projection {:keys [view-id selected-id]}]
  (let [nodes (:nodes graph-projection)
        ;; Determine effective selected-id (defaults to view-id or root)
        effective-selected-id (or selected-id
                                  view-id
                                  (find-projection-root nodes))]
    ;; Add UI state - only node selection; edge highlighting via highlightedEdges
    (update graph-projection :nodes add-node-selection effective-selected-id)))

(defn- compute-highlighted-edges
  "Compute which edges should be highlighted for a selected node.
   SchemaKeyHighlighting: when the selected node is a schema node,
   edges are highlighted by matching schema-key on either endpoint.
   RegularHighlighting: for non-schema nodes, highlights edges where
   node is source or target.
   Works with internal edge format (:from/:to)."
  [nodes edges selected-id]
  (when selected-id
    (let [nodes-by-id (into {} (map (fn [n] [(:id n) n])) nodes)
          selected-node (get nodes-by-id selected-id)]
      (if (and selected-node (= :schema (:kind selected-node)))
        ;; Schema node: match by schema-key on either endpoint
        (let [sk (:schema-key selected-node)]
          (->> edges
               (keep (fn [{:keys [id from to]}]
                       (when (or (= sk (:schema-key (get nodes-by-id from)))
                                 (= sk (:schema-key (get nodes-by-id to))))
                         id)))
               vec))
        ;; Regular node: match by from/to endpoint
        (->> edges
             (keep (fn [{:keys [id from to]}]
                     (when (or (= from selected-id) (= to selected-id))
                       id)))
             vec)))))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema EditorState
  [:map {:description "Client-side UI state for the graph editor: current view, selection, expanded modules, and visible edge types."}
   [:view-id {:optional true, :description "Module being viewed (its children are shown)."} [:maybe :NodeId]]
   [:selected-id {:optional true, :description "Currently selected/highlighted node."} [:maybe :NodeId]]
   [:schema-id {:optional true, :description "Schema being inspected in the sidebar."} [:maybe :NodeId]]
   [:expanded {:optional true, :description "Set of explicitly expanded module IDs."} [:set :NodeId]]
   [:show-private {:optional true, :description "Set of module IDs whose private children are visible."} [:set :NodeId]]
   [:visible-edge-types {:optional true, :description "Edge types to include in projection."} [:set :ProjectionEdgeType]]])

;; -----------------------------------------------------------------------------
;; Render function

(defn render-graph
  "Render graph data for Cytoscape.
   Takes pre-computed graph-projection and editor-state.
   Returns Cytoscape-compatible {:nodes :edges :selectedId :highlightedEdges}.

   Adds UI state and transforms to Cytoscape format at the boundary."
  {:malli/schema [:=> [:cat :Projection :EditorState] :CytoscapeGraph]}
  [graph-projection {:keys [view-id selected-id] :as editor-state}]
  (let [nodes (:nodes graph-projection)
        ;; Add UI state (selected?)
        graph (add-ui-state graph-projection editor-state)
        effective-selected-id (or selected-id view-id (find-projection-root nodes))
        highlighted-edges (compute-highlighted-edges (:nodes graph-projection) (:edges graph) effective-selected-id)]
    ;; Transform to Cytoscape format at the boundary
    (cytoscape/graph->cytoscape graph effective-selected-id highlighted-edges)))
