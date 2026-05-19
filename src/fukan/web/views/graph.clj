(ns fukan.web.views.graph
  "Enrich graph projection with UI state for Cytoscape rendering.
   Adds selection and highlight flags to projection nodes and edges,
   computes highlighted edges (those connected to the selected node),
   then delegates to cytoscape.clj for the final JSON transform."
  (:require [fukan.web.views.cytoscape :as cytoscape]))

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

(defn render-graph
  "Render graph data for Cytoscape.
   Takes a graph projection (from projection.core/project-model) and editor-state.
   Returns Cytoscape-compatible {:nodes :edges :selectedId :highlightedEdges}."
  [graph-projection {:keys [selected-id] :as _editor-state}]
  (let [nodes (:nodes graph-projection)
        graph (-> graph-projection
                  (update :nodes (fn [ns]
                                   (mapv (fn [n] (assoc n :selected?
                                                          (= (:id n) selected-id)))
                                         ns))))
        highlighted-edges (compute-highlighted-edges nodes (:edges graph) selected-id)]
    (cytoscape/graph->cytoscape graph selected-id highlighted-edges)))
