(ns fukan.web.views.graph
  "Adds UI state (selected?, highlighted?) to projection data.
   Implements view-spec.md behavior for Cytoscape visualization."
  (:require [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; UI State application

(defn- add-node-selection
  "Add :selected? field to projection nodes based on selected-id."
  [nodes selected-id]
  (mapv (fn [n] (assoc n :selected? (= (:id n) selected-id))) nodes))

(defn- add-edge-highlighting
  "Add :highlighted? field to projection edges based on selected-id."
  [edges selected-id]
  (mapv (fn [e]
          (assoc e :highlighted?
                 (or (= (:from e) selected-id)
                     (= (:to e) selected-id))))
        edges))

;; -----------------------------------------------------------------------------
;; Public API

(defn add-ui-state
  "Add UI state to graph projection data.

   Takes a graph projection (from proj/entity-graph), editor-state, and root-node.
   Adds :selected? to nodes and :highlighted? to edges.

   Returns {:nodes :edges :io} where:
   - nodes: vector of view nodes with rendering properties + :selected?
   - edges: vector of edges with :highlighted?
   - io: {:inputs :outputs} schema sets for container views"
  [graph-projection {:keys [view-id selected-id]} root-node]
  (let [;; Determine effective selected-id (defaults to view-id or root)
        effective-selected-id (or selected-id
                                  view-id
                                  (:id root-node))]
    ;; Add UI state
    (-> graph-projection
        (update :nodes add-node-selection effective-selected-id)
        (update :edges add-edge-highlighting effective-selected-id))))

(defn compute-highlighted-edges
  "Compute which edges should be highlighted for a selected node.
   For schema nodes, highlights all edges with matching schema-key.
   For other nodes, highlights edges where node is from or to.

   Works with internal edge format (:from/:to, :schema-key)."
  [edges selected-id]
  (when selected-id
    (cond
      ;; Schema nodes - highlight by schema-key
      (or (str/starts-with? selected-id "schema:")
          (str/starts-with? selected-id "input-schema:")
          (str/starts-with? selected-id "output-schema:")
          (str/starts-with? selected-id "internal-schema:"))
       (let [;; Extract schema key from various prefixes
             target-schema-key (cond
                                 (str/starts-with? selected-id "input-schema:") (subs selected-id 13)
                                 (str/starts-with? selected-id "output-schema:") (subs selected-id 14)
                                 (str/starts-with? selected-id "internal-schema:") (subs selected-id 16)
                                 (str/starts-with? selected-id "schema:") (subs selected-id 7))
             target-schema-key (some-> target-schema-key keyword)]
         (->> edges
              (keep (fn [{:keys [id schema-key]}]
                      (when (= schema-key target-schema-key) id)))
              vec))

      ;; Regular nodes - highlight edges by from/to
      :else
      (->> edges
           (keep (fn [{:keys [id from to]}]
                   (when (or (= from selected-id) (= to selected-id))
                     id)))
           vec))))
