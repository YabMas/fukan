(ns fukan.web.views.cytoscape
  "Transforms internal graph-view format to Cytoscape-compatible JSON.
   This is the boundary layer between domain-focused view data and
   the Cytoscape.js frontend library.")

;; -----------------------------------------------------------------------------
;; Cytoscape Output Schemas (informational — not enforced at runtime)
;;
;; Node shape: {:id :kind :label :selected
;;              :parent? :alliumKind? :sourceLocation?}
;; Edge shape: {:id :source :target :edgeType :kind
;;              :projectionKind? :drift?}
;; Graph shape: {:nodes :edges :selectedId :highlightedEdges}

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
