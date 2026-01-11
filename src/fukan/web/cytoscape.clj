(ns fukan.web.cytoscape
  "Transforms internal graph-view format to Cytoscape-compatible JSON.
   This is the boundary layer between domain-focused view data and
   the Cytoscape.js frontend library."
  (:require [fukan.schema :as schema]))

;; -----------------------------------------------------------------------------
;; Cytoscape Output Schemas

(def ^:private CytoscapeNode
  [:map
   [:id :string]
   [:kind :string]
   [:label :string]
   [:parent {:optional true} [:maybe :string]]
   [:selected :boolean]
   [:expandable :boolean]
   [:hasPrivateChildren :boolean]
   [:isExpanded :boolean]
   [:childCount :int]])

(def ^:private CytoscapeEdge
  [:map
   [:id :string]
   [:source :string]
   [:target :string]
   [:edgeType :string]
   [:highlighted :boolean]])

(def ^:private CytoscapeGraph
  [:map
   [:nodes [:vector CytoscapeNode]]
   [:edges [:vector CytoscapeEdge]]
   [:selectedId {:optional true} [:maybe :string]]
   [:highlightedEdges {:optional true} [:vector :string]]])

(schema/register! :fukan.web.cytoscape/CytoscapeNode CytoscapeNode)
(schema/register! :fukan.web.cytoscape/CytoscapeEdge CytoscapeEdge)
(schema/register! :fukan.web.cytoscape/CytoscapeGraph CytoscapeGraph)

;; -----------------------------------------------------------------------------
;; Transformers

(defn node->cytoscape
  "Transform an internal view node to Cytoscape format.
   Converts Clojure idioms (kebab-case, ? predicates) to camelCase."
  [{:keys [id kind label parent selected? expandable?
           has-private-children? expanded? child-count]}]
  (cond-> {:id id
           :kind (name kind)
           :label label
           :selected selected?
           :expandable expandable?
           :hasPrivateChildren has-private-children?
           :isExpanded expanded?
           :childCount child-count}
    parent (assoc :parent parent)))

(defn edge->cytoscape
  "Transform an internal view edge to Cytoscape format.
   Converts :from/:to to source/target, keyword edge-type to string."
  [{:keys [id from to edge-type highlighted?]}]
  {:id id
   :source from
   :target to
   :edgeType (name edge-type)
   :highlighted highlighted?})

(defn graph->cytoscape
  "Transform internal graph-view format to Cytoscape-compatible output.
   This is the main entry point called at the web boundary."
  {:malli/schema [:=> [:cat :map :string [:vector :string]] :fukan.web.cytoscape/CytoscapeGraph]}
  [{:keys [nodes edges]} selected-id highlighted-edges]
  {:nodes (mapv node->cytoscape nodes)
   :edges (mapv edge->cytoscape edges)
   :selectedId selected-id
   :highlightedEdges highlighted-edges})
