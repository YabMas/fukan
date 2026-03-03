(ns fukan.web.views.cytoscape
  "Transforms internal graph-view format to Cytoscape-compatible JSON.
   This is the boundary layer between domain-focused view data and
   the Cytoscape.js frontend library.")

;; -----------------------------------------------------------------------------
;; Cytoscape Output Schemas

(def ^:schema CytoscapeNode
  [:map {:description "A graph node in Cytoscape.js camelCase format with display and interaction state."}
   [:id :string]
   [:kind {:description "Node kind as a string: module, function, schema, io-container, io-schema."} :string]
   [:label :string]
   [:parent {:optional true} [:maybe :string]]
   [:selected :boolean]
   [:expandable :boolean]
   [:hasPrivateChildren :boolean]
   [:isExpanded :boolean]
   [:showingPrivate :boolean]
   [:childCount :int]
   [:private {:optional true} :boolean]])

(def ^:schema CytoscapeEdge
  [:map {:description "A directed edge in Cytoscape.js format with source/target node IDs and semantic type."}
   [:id :string]
   [:source :string]
   [:target :string]
   [:edgeType {:description "Edge type as a string: code-flow or data-flow."} :string]])

(def ^:schema CytoscapeGraph
  [:map {:description "Complete graph payload sent to Cytoscape.js: nodes, edges, and UI selection state."}
   [:nodes [:vector :CytoscapeNode]]
   [:edges [:vector :CytoscapeEdge]]
   [:selectedId {:optional true, :description "Currently selected node ID for highlight."} [:maybe :string]]
   [:highlightedEdges {:optional true, :description "Edge IDs to visually emphasize (connected to selection)."} [:vector :string]]])

;; -----------------------------------------------------------------------------
;; Transformers

(defn- node->cytoscape
  "Transform an internal view node to Cytoscape format.
   Converts internal format (kebab-case, ? predicates) to camelCase."
  [{:keys [id kind label parent selected? expandable?
           has-private-children? expanded? showing-private? child-count private?
           io-type schema-key owned?]}]
  (cond-> {:id id
           :kind (name kind)
           :label label
           :selected selected?
           :expandable expandable?
           :hasPrivateChildren has-private-children?
           :isExpanded expanded?
           :showingPrivate showing-private?
           :childCount child-count}
    parent (assoc :parent parent)
    private? (assoc :private private?)
    io-type (assoc :ioType (name io-type))
    schema-key (assoc :schemaKey (name schema-key))
    (some? owned?) (assoc :isOwned owned?)))

(defn- edge->cytoscape
  "Transform an internal view edge to Cytoscape format.
   Converts :from/:to to source/target, keyword edge-type to string.
   Includes schemaKey for data-flow edges.
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
  {:malli/schema [:=> [:cat :Projection :string [:vector :string]] :CytoscapeGraph]}
  [{:keys [nodes edges]} selected-id highlighted-edges]
  {:nodes (mapv node->cytoscape nodes)
   :edges (mapv edge->cytoscape edges)
   :selectedId selected-id
   :highlightedEdges highlighted-edges})
