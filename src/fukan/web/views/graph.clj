(ns fukan.web.views.graph
  "Enrich graph projection with UI state for Cytoscape rendering.
   Adds selection and highlight flags to projection nodes and edges,
   computes highlighted edges (those connected to the selected node),
   then delegates to cytoscape.clj for the final JSON transform."
  (:require [clojure.string :as str]
            [fukan.web.views.cytoscape :as cytoscape]))

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
;; Helpers

(defn- strip-schema-prefix
  "Strip a schema prefix from a node ID and return the keyword.
   Handles schema:, input-schema:, output-schema:, internal-schema: prefixes."
  [id]
  (some (fn [prefix]
          (when (str/starts-with? id prefix)
            (keyword (subs id (count prefix)))))
        ["input-schema:" "output-schema:" "internal-schema:" "schema:"]))

;; -----------------------------------------------------------------------------
;; UI State

(defn- add-ui-state
  "Add UI state to graph projection data.

   Takes a graph projection (from proj/entity-graph) and editor-state.
   Adds :selected? to nodes. Edge highlighting is driven entirely by
   the top-level highlightedEdges array computed by compute-highlighted-edges.

   Returns {:nodes :edges :io} where:
   - nodes: vector of view nodes with rendering properties + :selected?
   - edges: vector of edges (unmodified)
   - io: {:inputs :outputs} schema sets for module views"
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
   For schema nodes, highlights all edges with matching schema-key.
   For other nodes, highlights edges where node is from or to.

   Works with internal edge format (:from/:to, :schema-key)."
  [edges selected-id]
  (when selected-id
    (if-let [target-schema-key (strip-schema-prefix selected-id)]
      ;; Schema nodes - highlight by schema-key
      (->> edges
           (keep (fn [{:keys [id schema-key]}]
                   (when (= schema-key target-schema-key) id)))
           vec)
      ;; Regular nodes - highlight edges by from/to
      (->> edges
           (keep (fn [{:keys [id from to]}]
                   (when (or (= from selected-id) (= to selected-id))
                     id)))
           vec))))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema EditorState
  [:map {:description "Client-side UI state for the graph editor: current view, selection, and expanded modules."}
   [:view-id {:optional true, :description "Module being viewed (its children are shown)."} [:maybe :string]]
   [:selected-id {:optional true, :description "Currently selected/highlighted node."} [:maybe :string]]
   [:schema-id {:optional true, :description "Schema being inspected in the sidebar."} [:maybe :string]]
   [:show-private {:optional true, :description "Set of module IDs whose private children are visible."} :set]])

(def ^:schema GraphData :CytoscapeGraph)

;; -----------------------------------------------------------------------------
;; Render function

(defn render-graph
  "Render graph data for Cytoscape.
   Takes pre-computed graph-projection and editor-state.
   Returns Cytoscape-compatible {:nodes :edges :selectedId :highlightedEdges}.

   Adds UI state and transforms to Cytoscape format at the boundary."
  {:malli/schema [:=> [:cat :Projection :EditorState] :GraphData]}
  [graph-projection {:keys [view-id selected-id] :as editor-state}]
  (let [nodes (:nodes graph-projection)
        ;; Add UI state (selected?)
        graph (add-ui-state graph-projection editor-state)
        effective-selected-id (or selected-id view-id (find-projection-root nodes))
        highlighted-edges (compute-highlighted-edges (:edges graph) effective-selected-id)]
    ;; Transform to Cytoscape format at the boundary
    (cytoscape/graph->cytoscape graph effective-selected-id highlighted-edges)))
