(ns fukan.web.views.cytoscape
  "Transforms internal graph-view format to Cytoscape-compatible JSON.
   This is the boundary layer between domain-focused view data and
   the Cytoscape.js frontend library.")

;; -----------------------------------------------------------------------------
;; Cytoscape Output Schemas

(def ^:schema CytoscapeNode
  [:map
   [:id :string]
   [:kind :string]
   [:label :string]
   [:parent {:optional true} [:maybe :string]]
   [:selected :boolean]
   [:expandable :boolean]
   [:hasPrivateChildren :boolean]
   [:isExpanded :boolean]
   [:childCount :int]
   [:private {:optional true} :boolean]])

(def ^:schema CytoscapeEdge
  [:map
   [:id :string]
   [:source :string]
   [:target :string]
   [:edgeType :string]])

(def ^:schema CytoscapeGraph
  [:map
   [:nodes [:vector CytoscapeNode]]
   [:edges [:vector CytoscapeEdge]]
   [:selectedId {:optional true} [:maybe :string]]
   [:highlightedEdges {:optional true} [:vector :string]]])

;; -----------------------------------------------------------------------------
;; Transformers

(defn- node->cytoscape
  "Transform an internal view node to Cytoscape format.
   Converts Clojure idioms (kebab-case, ? predicates) to camelCase."
  [{:keys [id kind label parent selected? expandable?
           has-private-children? expanded? child-count private?
           io-type schema-key owned?]}]
  (cond-> {:id id
           :kind (name kind)
           :label label
           :selected selected?
           :expandable expandable?
           :hasPrivateChildren has-private-children?
           :isExpanded expanded?
           :childCount child-count}
    parent (assoc :parent parent)
    private? (assoc :private private?)
    io-type (assoc :ioType (name io-type))
    schema-key (assoc :schemaKey (name schema-key))
    (some? owned?) (assoc :isOwned owned?)))

(defn- edge->cytoscape
  "Transform an internal view edge to Cytoscape format.
   Converts :from/:to to source/target, keyword edge-type to string.
   Includes schemaKey for schema-flow edges.
   Edge highlighting is driven by the top-level highlightedEdges array."
  [{:keys [id from to edge-type schema-key]}]
  (cond-> {:id id
           :source from
           :target to
           :edgeType (name edge-type)}
    schema-key (assoc :schemaKey (name schema-key))))

(defn graph->cytoscape
  "Transform internal graph-view format to Cytoscape-compatible output.
   This is the main entry point called at the web boundary."
  {:malli/schema [:=> [:cat :map :string [:vector :string]] :CytoscapeGraph]}
  [{:keys [nodes edges]} selected-id highlighted-edges]
  {:nodes (mapv node->cytoscape nodes)
   :edges (mapv edge->cytoscape edges)
   :selectedId selected-id
   :highlightedEdges highlighted-edges})
