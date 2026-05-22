(ns fukan.web.views.cytoscape
  "Transforms internal graph-view format to Cytoscape-compatible JSON.
   This is the boundary layer between domain-focused view data and
   the Cytoscape.js frontend library."
  (:require [malli.core :as m]))

;; -----------------------------------------------------------------------------
;; Cytoscape Output Schemas
;;
;; Canonical materialisations of the value types declared in
;; web/views/cytoscape.allium. Field names map mechanically from
;; underscore (spec) to kebab-case (Clojure).

(def SourceLocation
  "Source location payload. Materialises value SourceLocation from
   fukan/model/spec.allium."
  (m/schema
   [:map
    [:file :string]
    [:line {:optional true} :int]]))

(def CytoscapeNode
  "Cytoscape.js node payload. Materialises value CytoscapeNode
   from web/views/cytoscape.allium. Field names map mechanically
   from underscore (spec) to kebab-case (Clojure); the wire JSON
   uses camelCase, which Cheshire renders from these keys when
   they are explicitly camelCased below."
  (m/schema
   [:map
    [:id                              :string]
    ;; kind is one of "primitive/*" or "artifact/*", rendered from the
    ;; source keyword.
    [:kind                            :string]
    [:label                           :string]
    [:selected                        :boolean]
    [:parent          {:optional true} :string]
    ;; camelCase keys: emitted directly by the transformer so the
    ;; JSON output matches the spec's wire shape without re-keying.
    [:alliumKind      {:optional true} :string]
    [:sourceLocation  {:optional true} SourceLocation]]))

(def CytoscapeEdge
  "Cytoscape.js edge payload. Materialises value CytoscapeEdge
   from web/views/cytoscape.allium. `edgeType` is a legacy alias of
   `kind`; `projectionKind` and `drift` are emitted only for
   `:relation/projects` edges."
  (m/schema
   [:map
    [:id        :string]
    [:source    :string]
    [:target    :string]
    ;; kind is a "relation/*" keyword rendered as string.
    [:kind      :string]
    [:edgeType  :string]
    [:projectionKind {:optional true} :string]
    [:drift          {:optional true} :string]]))

(def CytoscapeGraph
  "Cytoscape.js graph payload. Materialises value CytoscapeGraph
   from web/views/cytoscape.allium."
  (m/schema
   [:map
    [:nodes             [:sequential CytoscapeNode]]
    [:edges             [:sequential CytoscapeEdge]]
    [:selectedId        {:optional true} [:maybe :string]]
    [:highlightedEdges  {:optional true} [:maybe [:sequential :string]]]]))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- kw->str
  "Render a keyword including its namespace prefix, e.g. :primitive/rule → \"primitive/rule\"."
  [k]
  (subs (str k) 1))

;; -----------------------------------------------------------------------------
;; Transformers

(defn- node->cytoscape
  "Transform a projection node to Cytoscape format.
   Node :kind is a keyword (kernel primitive or :artifact/*).
   Optional :allium-kind, :parent, :source-location, :selected? for UI state."
  [{:keys [id kind label parent allium-kind selected? source-location]}]
  (cond-> {:id       id
           :kind     (kw->str kind)
           :label    (or label id)
           :selected (boolean selected?)}
    parent          (assoc :parent parent)
    allium-kind     (assoc :alliumKind (kw->str allium-kind))
    source-location (assoc :sourceLocation source-location)))

(defn- edge->cytoscape
  "Transform a projection edge to Cytoscape format.
   Edge :kind is a relation keyword (:relation/X). :projection-kind +
   :validity are present for :relation/projects edges only."
  [{:keys [id from to kind projection-kind validity]}]
  (cond-> {:id       id
           :source   from
           :target   to
           :edgeType (kw->str kind)
           :kind     (kw->str kind)}
    projection-kind (assoc :projectionKind (kw->str projection-kind))
    (= :absent validity) (assoc :drift "absent")))

(defn graph->cytoscape
  "Transform internal graph-view format to Cytoscape-compatible output.
   This is the main entry point called at the web boundary."
  [{:keys [nodes edges]} selected-id highlighted-edges]
  {:nodes (mapv node->cytoscape nodes)
   :edges (mapv edge->cytoscape edges)
   :selectedId selected-id
   :highlightedEdges highlighted-edges})
