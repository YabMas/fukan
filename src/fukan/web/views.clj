(ns fukan.web.views
  "View rendering for the web interface.

   All public render functions take the model and an editor-state map.
   See AGENTS.md for internal naming conventions."
  (:require [hiccup2.core :as h]
            [cheshire.core :as json]
            [fukan.cytoscape :as cytoscape]
            [fukan.model :as model]
            [fukan.schema :as schema]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:private EditorState
  [:map
   [:view-id {:optional true} [:maybe :string]]
   [:selected-id {:optional true} [:maybe :string]]
   [:expanded-containers {:optional true} :set]])

(def ^:private CytoscapeEdge
  [:map
   [:id :string]
   [:source :string]
   [:target :string]])

(def ^:private GraphData
  [:map
   [:nodes [:vector :map]]
   [:edges [:vector CytoscapeEdge]]
   [:selectedId {:optional true} [:maybe :string]]
   [:highlightedEdges {:optional true} [:vector :string]]])

(def ^:private Html :string)

;; Register for sidebar display
(schema/register! :fukan.web.views/EditorState EditorState)
(schema/register! :fukan.web.views/CytoscapeEdge CytoscapeEdge)
(schema/register! :fukan.web.views/GraphData GraphData)
(schema/register! :fukan.web.views/Html Html)

(def ^:private css
  "body {
    margin: 0;
    font-family: system-ui, -apple-system, sans-serif;
    background: #f5f5f5;
  }
  #container {
    display: flex;
    height: 100vh;
  }
  #graph-panel {
    flex: 1;
    display: flex;
    flex-direction: column;
    background: #fff;
  }
  #breadcrumb {
    padding: 0.75rem 1rem;
    background: #fff;
    border-bottom: 1px solid #e0e0e0;
    font-size: 0.9rem;
    color: #7f8c8d;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  #breadcrumb .crumb {
    color: #2980b9;
    cursor: pointer;
    padding: 0.25rem 0.5rem;
    border-radius: 4px;
    transition: background 0.15s;
  }
  #breadcrumb .crumb:hover {
    background: #e8f4f8;
  }
  #breadcrumb .crumb.current {
    color: #2c3e50;
    font-weight: 600;
    cursor: default;
  }
  #breadcrumb .crumb.current:hover {
    background: transparent;
  }
  #breadcrumb .separator {
    color: #bdc3c7;
    font-size: 0.8rem;
  }
  #cy {
    flex: 1;
    background: #fff;
  }
  #sidebar {
    width: 320px;
    border-left: 1px solid #ddd;
    padding: 1rem;
    overflow-y: auto;
    background: #fafafa;
  }
  #sidebar h3 {
    margin: 0 0 1rem 0;
    color: #333;
    font-size: 1.1rem;
  }
  #node-info {
    margin-top: 1rem;
  }
  #node-info h4 {
    margin: 0 0 0.25rem 0;
    color: #2c3e50;
  }
  #node-info .node-type {
    font-size: 0.85rem;
    color: #7f8c8d;
    margin-bottom: 0.75rem;
  }
  #node-info h5 {
    margin: 1rem 0 0.5rem 0;
    color: #34495e;
    font-size: 0.9rem;
  }
  #node-info ul {
    padding-left: 1.2rem;
    margin: 0;
  }
  #node-info li {
    cursor: pointer;
    padding: 0.2rem 0;
    color: #2980b9;
  }
  #node-info li:hover {
    text-decoration: underline;
  }
  .empty-state {
    color: #95a5a6;
    font-style: italic;
  }
  .dep-count {
    color: #7f8c8d;
    font-weight: normal;
  }
  .doc {
    margin: 0.75rem 0;
    padding: 0.75rem;
    background: #f8f9fa;
    border-left: 3px solid #3498db;
    font-size: 0.85rem;
    line-height: 1.5;
    color: #2c3e50;
    white-space: pre-wrap;
  }
  .doc-label {
    font-size: 0.75rem;
    color: #7f8c8d;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-bottom: 0.25rem;
  }
  .loading-indicator {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    background: rgba(255, 255, 255, 0.9);
    padding: 1rem 1.5rem;
    border-radius: 8px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.1);
    display: none;
    z-index: 1000;
    font-size: 0.9rem;
    color: #7f8c8d;
  }
  .loading-indicator.htmx-request {
    display: block;
  }
  @keyframes spin {
    to { transform: rotate(360deg); }
  }
  .spinner {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid #e0e0e0;
    border-top-color: #2980b9;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin-right: 0.5rem;
    vertical-align: middle;
  }
  .signature {
    margin: 0.75rem 0;
    padding: 0.75rem;
    background: #f0f7ee;
    border-left: 3px solid #27ae60;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
    font-size: 0.85rem;
    line-height: 1.5;
  }
  .signature .schema-ref {
    color: #27ae60;
    cursor: pointer;
    text-decoration: underline;
    text-decoration-style: dotted;
  }
  .signature .schema-ref:hover {
    text-decoration-style: solid;
  }
  .signature .arrow {
    color: #7f8c8d;
    margin: 0 0.25rem;
  }
  .schema-list {
    list-style: none;
    padding-left: 0;
    margin: 0;
  }
  .schema-list li {
    padding: 0.3rem 0.5rem;
    margin: 0.25rem 0;
    background: #f0f7ee;
    border-radius: 4px;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
    font-size: 0.85rem;
  }
  .schema-list li:hover {
    background: #e0f0dc;
  }
  .schema-detail {
    margin: 0.75rem 0;
    padding: 0.75rem;
    background: #f8f9fa;
    border: 1px solid #e0e0e0;
    border-radius: 4px;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
    font-size: 0.85rem;
    line-height: 1.6;
  }
  .schema-detail .entry {
    padding: 0.2rem 0;
    padding-left: 1rem;
  }
  .schema-detail .key {
    color: #8e44ad;
  }
  .schema-detail .optional {
    color: #95a5a6;
    font-size: 0.75rem;
  }
  .back-link {
    display: inline-block;
    margin-bottom: 0.75rem;
    color: #7f8c8d;
    cursor: pointer;
    font-size: 0.85rem;
  }
  .back-link:hover {
    color: #2980b9;
  }")

(defn render-app-shell
  "Render the initial HTML page shell."
  {:malli/schema [:=> [:cat] :fukan.web.views/Html]}
  []
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "Fukan"]
      [:script {:src "https://unpkg.com/cytoscape@3.28.1/dist/cytoscape.min.js"}]
      [:script {:src "https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"}]
      [:script {:src "https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"}]
      [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.7/bundles/datastar.js"
                :type "module"}]
      [:style css]]
     [:body
      [:div#container
        ;; Graph panel with Cytoscape event listeners and loading indicator
       [:div#graph-panel
        {"data-on:cy-select" "@get('/sse/sidebar?id=' + evt.detail.id)"
         "data-on:cy-navigate" "@get('/sse/view?id=' + evt.detail.id + '&select=' + (evt.detail.select || '') + '&expanded=' + window.getExpandedParam())"
         "data-on:cy-toggle-private" "@get('/sse/view?id=' + (new URLSearchParams(window.location.search).get('id') || '') + '&expanded=' + window.getExpandedParam())"
         "data-on:cy-schema" "@get('/sse/schema?id=' + evt.detail.id)"
         "data-indicator" "#loading"}
        [:div#breadcrumb]
        [:div#cy]
        [:div#loading.loading-indicator
         [:span.spinner]
         "Loading..."]]
       [:div#sidebar
        [:div#node-info
         [:p.empty-state "Click a node to see details"]]]]
      ;; Initial load trigger - handled by app.js initPage()
      [:div#init-trigger]
      [:script {:src "/public/app.js"}]]])))

;; -----------------------------------------------------------------------------
;; Breadcrumb

(defn- render-breadcrumb-html
  "Render breadcrumb items to HTML.
   Dispatches cy-navigate event for consistent navigation handling."
  [items]
  (str
   (h/html
    [:div#breadcrumb
     (for [[idx {:keys [id label]}] (map-indexed vector items)
           :let [is-last (= idx (dec (count items)))
                 ;; Dispatch cy-navigate event via vanilla JS - bubbles up to graph-panel
                 dispatch-js (str "evt.target.dispatchEvent(new CustomEvent('cy-navigate', {bubbles: true, detail: {id: '" (or id "") "'}}))")]]
       (list
        (when (pos? idx)
          [:span.separator (h/raw "&rsaquo;")])
        (if is-last
          [:span.crumb.current label]
          [:span.crumb {"data-on:click" dispatch-js} label])))])))

;; -----------------------------------------------------------------------------
;; Sidebar

(defn- render-empty-sidebar
  "Render empty sidebar state."
  []
  (str
   (h/html
    [:div#node-info
     [:p.empty-state "Click a node to see details"]])))

;; -----------------------------------------------------------------------------
;; Formatting helpers

(defn- format-var-id
  "Format a var ID for display (extract ns/name from var:ns/name)."
  [var-id]
  (if (and var-id (.startsWith var-id "var:"))
    (subs var-id 4)
    var-id))

(defn- format-ns-id
  "Format a namespace ID for display."
  [ns-id]
  (if (and ns-id (.startsWith ns-id "ns:"))
    (subs ns-id 3)
    ns-id))

;; -----------------------------------------------------------------------------
;; Schema rendering helpers

(defn- get-var-schema
  "Get the malli schema from a var's metadata, if present."
  [ns-sym var-sym]
  (try
    (when-let [v (ns-resolve (find-ns ns-sym) var-sym)]
      (:malli/schema (meta v)))
    (catch Exception _ nil)))

;; -----------------------------------------------------------------------------
;; Schema Rendering - Direct rendering of Malli schemas to hiccup

(declare render-schema-form)

(defn- render-schema-ref
  "Render a clickable schema reference."
  [schema-key]
  (let [k (if (keyword? schema-key) schema-key (keyword schema-key))]
    [:span.schema-ref
     {"data-on:click" (str "@get('/sse/schema?id=" (namespace k) "/" (name k) "')")}
     (name k)]))

(defn- render-schema-form
  "Render a Malli schema form directly to hiccup.
   Handles qualified keywords as clickable refs, and various schema types."
  [schema-form]
  (cond
    ;; Qualified keyword - clickable schema reference
    (qualified-keyword? schema-form)
    (render-schema-ref schema-form)

    ;; Simple keyword (built-in type like :string, :int)
    (keyword? schema-form)
    [:span.type (name schema-form)]

    ;; Vector form like [:vector X], [:map ...], [:=> ...]
    (vector? schema-form)
    (let [[type & args] schema-form]
      (case type
        :vector [:span "[" (render-schema-form (first args)) ", ...]"]
        :set [:span "#{" (render-schema-form (first args)) ", ...}"]
        :map-of [:span "{" (render-schema-form (first args)) " -> " (render-schema-form (second args)) "}"]
        :maybe [:span (render-schema-form (first args)) "?"]
        :or (interpose " | " (map render-schema-form args))
        :and (interpose " & " (map render-schema-form args))
        :enum [:span (str/join " | " (map pr-str args))]
        :tuple [:span "[" (interpose ", " (map render-schema-form args)) "]"]
        :cat (interpose ", " (map render-schema-form args))
        :map [:span "map"]
        :fn [:span "fn"]
        ;; Default
        [:span (str type)]))

    ;; Fallback
    :else
    [:span (pr-str schema-form)]))

(defn- render-fn-signature
  "Render a function signature from its schema directly to hiccup."
  [fn-schema]
  (when (and (vector? fn-schema) (= :=> (first fn-schema)))
    (let [[_ input output] fn-schema
          in-schemas (if (and (vector? input) (= :cat (first input)))
                       (rest input)
                       [input])]
      [:div.signature
       "("
       (interpose ", " (map render-schema-form in-schemas))
       ")"
       [:span.arrow " → "]
       (render-schema-form output)])))

(defn- render-var-info
  "Render the sidebar fragment for a var node."
  [{:keys [id label ns-sym var-sym doc]} deps dependents]
  (let [fn-schema (get-var-schema ns-sym var-sym)]
    (str
     (h/html
      [:div#node-info
       [:h4 label]

       (when doc
         [:div.doc doc])

       ;; Function signature from schema
       (render-fn-signature fn-schema)

       [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
       (if (seq deps)
         [:ul
          (for [{:keys [to]} deps]
            [:li {"data-on:click" (str "@get('/sse/view?select=" to "')")}
             (format-var-id to)])]
         [:p.empty-state "None"])

       [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
       (if (seq dependents)
         [:ul
          (for [{:keys [from]} dependents]
            [:li {"data-on:click" (str "@get('/sse/view?select=" from "')")}
             (format-var-id from)])]
         [:p.empty-state "None"])]))))

(defn- render-namespace-info
  "Render the sidebar fragment for a namespace node."
  [{:keys [id label children doc]} deps dependents]
  (let [;; Get schemas defined in this namespace
        ns-schemas (schema/schemas-for-ns label)]
    (str
     (h/html
      [:div#node-info
       [:h4 label]

       (when doc
         [:div.doc doc])

       ;; Schemas section
       (when (seq ns-schemas)
         (list
          [:h5 "Schemas " [:span.dep-count (str "(" (count ns-schemas) ")")]]
          [:ul.schema-list
           (for [schema-key (sort ns-schemas)]
             [:li {"data-on:click" (str "@get('/sse/schema?id=" (namespace schema-key) "/" (name schema-key) "')")}
              (name schema-key)])]))

       [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
       (if (seq deps)
         [:ul
          (for [[target-id edge-count] deps]
            [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
             (format-ns-id target-id)
             (when (> edge-count 1)
               [:span.dep-count (str " (" edge-count ")")])])]
         [:p.empty-state "None"])

       [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
       (if (seq dependents)
         [:ul
          (for [[source-id edge-count] dependents]
            [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
             (format-ns-id source-id)
             (when (> edge-count 1)
               [:span.dep-count (str " (" edge-count ")")])])]
         [:p.empty-state "None"])]))))

(defn- render-folder-info
  "Render the sidebar fragment for a folder node."
  [{:keys [id label children]} deps dependents]
  (str
   (h/html
    [:div#node-info
     [:h4 label]

     [:h5 "Dependencies " [:span.dep-count (str "(" (count deps) ")")]]
     (if (seq deps)
       [:ul
        (for [[target-id edge-count] deps]
          [:li {"data-on:click" (str "@get('/sse/view?select=" target-id "')")}
           target-id
           (when (> edge-count 1)
             [:span.dep-count (str " (" edge-count ")")])])]
       [:p.empty-state "None"])

     [:h5 "Dependents " [:span.dep-count (str "(" (count dependents) ")")]]
     (if (seq dependents)
       [:ul
        (for [[source-id edge-count] dependents]
          [:li {"data-on:click" (str "@get('/sse/view?select=" source-id "')")}
           source-id
           (when (> edge-count 1)
             [:span.dep-count (str " (" edge-count ")")])])]
       [:p.empty-state "None"])])))

(defn- render-map-schema-entries
  "Render the entries of a map schema.
   Handles [:map [:key opts? schema] ...] format."
  [entries]
  (for [entry entries
        :when (vector? entry)]
    (let [[k second-elem & rest] entry
          [opts child-schema] (if (map? second-elem)
                                [second-elem (first rest)]
                                [{} second-elem])]
      [:div.entry
       [:span.key (str k)]
       (when (:optional opts) [:span.optional " (optional)"])
       " : "
       (render-schema-form child-schema)])))

(defn- render-schema-detail-view
  "Render the full detail view for a schema form."
  [schema-form]
  (if (and (vector? schema-form) (= :map (first schema-form)))
    ;; Map schema - show entries
    (render-map-schema-entries (rest schema-form))
    ;; Other schema types - show inline
    [:div (render-schema-form schema-form)]))

;; -----------------------------------------------------------------------------
;; Schema Detail View

(defn render-schema-detail
  "Render the sidebar for viewing a schema definition.
   Takes a schema key like 'fukan.model/Model'."
  [schema-key-str]
  (let [[ns-part name-part] (str/split schema-key-str #"/" 2)
        schema-key (keyword ns-part name-part)
        schema-form (schema/get-schema schema-key)]
    (str
     (h/html
      (if schema-form
        [:div#node-info
         ;; Back link to namespace
         [:span.back-link
          {"data-on:click" (str "@get('/sse/view?id=ns:" ns-part "')")}
          (str "← " ns-part)]

         [:h4 name-part]

         [:div.schema-detail
          (render-schema-detail-view schema-form)]]
        [:div#node-info
         [:p.empty-state (str "Schema not found: " schema-key-str)]])))))

(defn- render-schema-node-info
  "Render the sidebar fragment for a schema node.
   Delegates to render-schema-detail."
  [{:keys [schema-key]}]
  (render-schema-detail (str (namespace schema-key) "/" (name schema-key))))

(defn- render-sidebar-html
  "Render the sidebar content for a node.
   Returns empty state if node is nil."
  [{:keys [node deps dependents]}]
  (if node
    (case (:kind node)
      :var (render-var-info node deps dependents)
      :namespace (render-namespace-info node deps dependents)
      :folder (render-folder-info node deps dependents)
      :schema (render-schema-node-info node)
      (str (h/html [:div#node-info [:p.empty-state "Unknown node type"]])))
    (render-empty-sidebar)))

;; -----------------------------------------------------------------------------
;; View Computation - Unified view generation for any entity

(defn- compute-namespace-deps
  "Compute aggregated dependencies for a namespace.
   Returns {target-ns-id -> edge-count}."
  [ns-id model]
  (let [children (get-in model [:nodes ns-id :children] #{})
        ;; Get all edges from vars in this namespace
        edges (mapcat #(get (:edges-by-from model) %) children)]
    ;; Group by target namespace and count
    (->> edges
         (map (fn [{:keys [to]}]
                (let [target-node (get-in model [:nodes to])
                      target-ns (:parent target-node)]
                  target-ns)))
         (remove #{ns-id}) ; exclude self-references
         (frequencies))))

(defn- compute-namespace-dependents
  "Compute aggregated dependents for a namespace.
   Returns {source-ns-id -> edge-count}."
  [ns-id model]
  (let [children (get-in model [:nodes ns-id :children] #{})
        ;; Get all edges to vars in this namespace
        edges (mapcat #(get (:edges-by-to model) %) children)]
    ;; Group by source namespace and count
    (->> edges
         (map (fn [{:keys [from]}]
                (let [source-node (get-in model [:nodes from])
                      source-ns (:parent source-node)]
                  source-ns)))
         (remove #{ns-id}) ; exclude self-references
         (frequencies))))

(defn- edges-for-entity
  "Get the appropriate edge indexes for an entity based on its kind."
  [m entity-id]
  (case (get-in m [:nodes entity-id :kind])
    :var {:by-from (:edges-by-from m)
          :by-to (:edges-by-to m)}
    :namespace {:by-from (:ns-edges-by-from m)
                :by-to (:ns-edges-by-to m)}
    :folder {:by-from (:folder-edges-by-from m)
             :by-to (:folder-edges-by-to m)}
    ;; Default fallback
    {:by-from {} :by-to {}}))

(defn- breadcrumb-label
  "Get a short label for breadcrumb display.
   For namespaces, returns just the last segment (e.g. 'handler' instead of 'fukan.web.handler')."
  [node]
  (let [label (:label node)
        kind (:kind node)]
    (if (= kind :namespace)
      ;; Extract last segment of namespace
      (last (str/split label #"\."))
      label)))

(defn- find-root-node
  "Find the root node (node with parent = nil).
   Root should be a folder or namespace, not a var or schema."
  [m]
  (->> (vals (:nodes m))
       (filter #(nil? (:parent %)))
       (filter #(#{:folder :namespace} (:kind %)))  ; Root should be folder or namespace
       first))

(defn- compute-breadcrumb-
  "Compute breadcrumb path from root to entity.
   Returns a list of {:id :label} maps."
  [m entity-id]
  (let [root-node (find-root-node m)
        root-id (:id root-node)]
    (if (or (nil? entity-id) (= entity-id root-id))
      ;; At root - just show root label
      [{:id nil :label (or (breadcrumb-label root-node) "root")}]
      ;; Build path from root to entity
      (let [path (loop [current-id entity-id
                        acc []]
                   (let [node (get-in m [:nodes current-id])]
                     (if (or (nil? node) (nil? (:parent node)))
                       acc
                       (recur (:parent node)
                              (cons {:id current-id :label (breadcrumb-label node)} acc)))))]
        (cons {:id nil :label (or (breadcrumb-label root-node) "root")} path)))))

(defn- node-visible?
  "Check if a node is visible given the expanded-containers set.
   A node is visible if it's not private, or if its parent is expanded."
  [m node-id expanded-containers]
  (let [node (get-in m [:nodes node-id])]
    (or (not (:private? node))
        (contains? expanded-containers (:parent node)))))

(defn- get-children
  "Get the children of an entity (entities whose parent is this entity)."
  [m entity-id]
  (get-in m [:nodes entity-id :children] #{}))

(defn- get-visible-children
  "Get the visible children of an entity, filtered by expanded-containers.
   If expanded-containers is nil or entity-id is in it, all children are returned.
   Otherwise, only public children are returned."
  [m entity-id expanded-containers]
  (let [children-ids (get-children m entity-id)]
    (if (or (nil? expanded-containers)
            (contains? expanded-containers entity-id))
      children-ids
      (into #{}
            (remove #(:private? (get-in m [:nodes %])))
            children-ids))))

(defn- has-private-children?
  "Check if a container has any private children."
  [m entity-id]
  (let [children-ids (get-children m entity-id)]
    (some #(:private? (get-in m [:nodes %])) children-ids)))

(defn- get-child-edges
  "Get edges at the child level (one level down from entity).
   For a folder, these are namespace-level edges.
   For a namespace, these are var-level edges."
  [m entity-id]
  (let [entity-kind (get-in m [:nodes entity-id :kind])]
    (case entity-kind
      :folder (:ns-edges m)
      :namespace (:edges m)
      ;; vars have no children, so no child-level edges
      [])))

(defn- compute-container-view
  "Compute view when the selected entity is a container (has children).

   Shows:
   - The selected container
   - Visible children of the container (filtered by expanded-containers)
   - Edges between visible children (sibling relationships)
   - External entities that visible children relate to (grouped in their own containers)
   - Cross-container edges from visible children to external entities
   - Schema nodes and data flow edges (when viewing namespace-level)"
  [m entity-id expanded-containers]
  (let [children-ids (get-visible-children m entity-id expanded-containers)
        children-set (set children-ids)

        ;; Get edges at the child level
        child-edges (get-child-edges m entity-id)

        ;; Find edges between children (sibling edges)
        sibling-edges (->> child-edges
                           (filter (fn [{:keys [from to]}]
                                     (and (contains? children-set from)
                                          (contains? children-set to)))))

        ;; Find edges from children to entities outside this container
        outgoing-edges (->> child-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? children-set from)
                                           (not (contains? children-set to))))))

        incoming-edges (->> child-edges
                            (filter (fn [{:keys [from to]}]
                                      (and (contains? children-set to)
                                           (not (contains? children-set from))))))

        cross-container-edges (concat outgoing-edges incoming-edges)

        ;; Helper to check if an entity is a hidden child of this container
        hidden-child? (fn [eid]
                        (and (= (:parent (get-in m [:nodes eid])) entity-id)
                             (not (contains? children-set eid))))

        ;; Filter cross-container edges to exclude edges to/from hidden private children
        cross-container-edges (->> cross-container-edges
                                   (remove (fn [{:keys [from to]}]
                                             (or (hidden-child? from)
                                                 (hidden-child? to)))))

        ;; Collect the external related entities (entities outside this container)
        external-entities (->> cross-container-edges
                               (mapcat (fn [{:keys [from to]}]
                                         [(when-not (contains? children-set from) from)
                                          (when-not (contains? children-set to) to)]))
                               (remove nil?)
                               (into #{}))

        ;; Find parent containers for external entities
        ;; These become top-level compound nodes (siblings of the selected container)
        ;; Exclude containers that are already children of the selected entity
        external-containers (->> external-entities
                                 (map #(:parent (get-in m [:nodes %])))
                                 (remove nil?)
                                 (remove #{entity-id}) ; don't include self
                                 (remove children-set) ; don't include children (they're already shown)
                                 (into #{}))

        ;; The selected container (root of this view)
        selected-node {:id entity-id
                       :label (:label (get-in m [:nodes entity-id]))
                       :kind (name (:kind (get-in m [:nodes entity-id])))
                       :originalId entity-id
                       :childCount (count children-ids)
                       :hasPrivateChildren (boolean (has-private-children? m entity-id))
                       :isExpanded (contains? expanded-containers entity-id)}

        ;; All children are simple nodes inside the selected container
        child-nodes (for [cid children-ids
                          :let [node (get-in m [:nodes cid])]
                          :when node]
                      (-> (cytoscape/node->cytoscape node)
                          (assoc :parent entity-id)
                          (assoc :hasPrivateChildren (boolean (has-private-children? m cid)))
                          (assoc :isExpanded (contains? expanded-containers cid))))

        ;; External container nodes (top-level, not nested)
        external-container-nodes (for [ecid external-containers
                                       :let [node (get-in m [:nodes ecid])]
                                       :when node]
                                   {:id ecid
                                    :label (:label node)
                                    :kind (name (:kind node))
                                    :originalId ecid
                                    :childCount (count (get-children m ecid))
                                    :hasPrivateChildren (boolean (has-private-children? m ecid))
                                    :isExpanded (contains? expanded-containers ecid)})

        ;; External entity nodes (inside their parent containers)
        external-entity-nodes (for [eid external-entities
                                    :let [node (get-in m [:nodes eid])]
                                    :when node]
                                (-> (cytoscape/node->cytoscape node)
                                    (assoc :parent (:parent node))))

        ;; Edges between external entities (within the same external container)
        ;; TODO: This is a tactical fix. The approach of "partially showing" contents
        ;; of child containers is complex and may need rethinking for a cleaner model.
        internal-external-edges (->> child-edges
                                     (filter (fn [{:keys [from to]}]
                                               (and (contains? external-entities from)
                                                    (contains? external-entities to)))))

        ;; All code-flow edges to show
        all-code-edges (distinct (concat sibling-edges cross-container-edges internal-external-edges))
        code-edges (->> all-code-edges
                        (map-indexed (fn [idx {:keys [from to]}]
                                       {:id (str "e" idx)
                                        :source from
                                        :target to
                                        :edgeType "code-flow"})))

        ;; Schema flow: Get schema edges that connect to this container or descendants
        ;; Include edges from nested namespaces to show full data flow at folder level
        entity-kind (get-in m [:nodes entity-id :kind])

        ;; Helper: check if a node is a descendant of the container
        descendant? (fn [node-id]
                      (loop [current node-id]
                        (let [parent (:parent (get-in m [:nodes current]))]
                          (cond
                            (nil? parent) false
                            (= parent entity-id) true
                            :else (recur parent)))))

        ;; Helper: find the nearest visible ancestor for a node
        nearest-visible-ancestor (fn [node-id all-visible]
                                   (loop [current node-id]
                                     (let [parent (:parent (get-in m [:nodes current]))]
                                       (cond
                                         (nil? parent) entity-id
                                         (= parent entity-id) current
                                         (contains? all-visible parent) parent
                                         :else (recur parent)))))

        schema-edges-raw (when (= :folder entity-kind)
                           (->> (:schema-edges m)
                                (filter (fn [{:keys [from to]}]
                                          (or (contains? children-set from)
                                              (contains? children-set to)
                                              ;; Include edges from descendants
                                              (descendant? from)
                                              ;; Include edges to schema nodes in descendants
                                              (and (str/starts-with? (str to) "schema:")
                                                   (descendant? (:parent (get-in m [:nodes to])))))))))

        ;; Collect schema node IDs from the schema-key of the edges
        schema-node-ids (when schema-edges-raw
                          (->> schema-edges-raw
                               (map (fn [{:keys [schema-key]}]
                                      (str "schema:" (namespace schema-key) "/" (name schema-key))))
                               (into #{})))

        ;; Collect schema owner namespaces that need to be visible
        ;; These are namespaces that own schemas being displayed
        schema-owner-ns-ids (when schema-node-ids
                              (->> schema-node-ids
                                   (map (fn [sid]
                                          (:parent (get-in m [:nodes sid]))))
                                   (remove nil?)
                                   (into #{})))

        ;; Extended external entities: code-flow externals + schema owner namespaces
        extended-external-entities (into external-entities schema-owner-ns-ids)

        ;; External containers now include parents of schema owner namespaces
        extended-external-containers (->> extended-external-entities
                                          (map #(:parent (get-in m [:nodes %])))
                                          (remove nil?)
                                          (remove #{entity-id})
                                          (remove children-set)
                                          (into #{}))

        ;; All visible node IDs (direct children + all external entities)
        all-visible-ids (into children-set extended-external-entities)

        ;; Build schema nodes - place inside owning namespace if visible, otherwise nearest ancestor
        schema-nodes (for [sid schema-node-ids
                           :let [node (get-in m [:nodes sid])
                                 original-parent (:parent node)
                                 ;; Use original parent if it's visible in this view
                                 visible-parent (if (contains? all-visible-ids original-parent)
                                                  original-parent
                                                  (nearest-visible-ancestor original-parent all-visible-ids))]
                           :when node]
                       (-> (cytoscape/node->cytoscape node)
                           (assoc :parent visible-parent)
                           (assoc :originalParent original-parent)))

        ;; Build schema flow edges - remap source/target to visible nodes when needed
        schema-edges (when schema-edges-raw
                       (->> schema-edges-raw
                            (map-indexed (fn [idx {:keys [from to schema-key]}]
                                           (let [;; Remap from/to to visible nodes
                                                 visible-from (if (contains? all-visible-ids from)
                                                                from
                                                                (nearest-visible-ancestor from all-visible-ids))
                                                 visible-to (if (or (contains? all-visible-ids to)
                                                                    (contains? schema-node-ids to))
                                                              to
                                                              (nearest-visible-ancestor to all-visible-ids))]
                                             {:id (str "sf" idx)
                                              :source visible-from
                                              :target visible-to
                                              :edgeType "schema-flow"
                                              :schemaKey (str (namespace schema-key) "/" (name schema-key))})))
                            ;; Remove self-loops created by remapping
                            (remove (fn [{:keys [source target]}] (= source target)))
                            ;; Remove edges from container to schema inside it (ownership shown by containment)
                            (remove (fn [{:keys [source target]}]
                                      (when-let [schema-node (get-in m [:nodes target])]
                                        (= source (:parent schema-node)))))
                            ;; Deduplicate edges with same source/target/schemaKey
                            (distinct)))

        ;; Additional schema owner namespace nodes (not in children-set or external entities)
        schema-owner-nodes-extra (when schema-owner-ns-ids
                                   (for [ns-id schema-owner-ns-ids
                                         :when (and (not (contains? children-set ns-id))
                                                    (not (contains? external-entities ns-id)))
                                         :let [node (get-in m [:nodes ns-id])]
                                         :when node]
                                     (-> (cytoscape/node->cytoscape node)
                                         (assoc :parent (:parent node)))))

        ;; Additional container nodes for schema owners (not in children-set or external containers)
        schema-owner-container-nodes-extra (when extended-external-containers
                                             (for [ecid extended-external-containers
                                                   :when (and (not (contains? children-set ecid))
                                                              (not (contains? external-containers ecid)))
                                                   :let [node (get-in m [:nodes ecid])]
                                                   :when node]
                                               {:id ecid
                                                :label (:label node)
                                                :kind (name (:kind node))
                                                :originalId ecid
                                                :childCount (count (get-children m ecid))
                                                :hasPrivateChildren (boolean (has-private-children? m ecid))
                                                :isExpanded (contains? expanded-containers ecid)}))

        ;; Combine all nodes and edges
        all-nodes (concat [selected-node]
                          child-nodes
                          external-container-nodes
                          external-entity-nodes
                          schema-owner-nodes-extra
                          schema-owner-container-nodes-extra
                          schema-nodes)
        all-edges (concat code-edges schema-edges)]

    {:nodes (vec all-nodes)
     :edges (vec all-edges)}))

(defn- compute-leaf-view
  "Compute view when the selected entity is a leaf (no children).

   Shows:
   - The selected entity
   - All visible entities it has relationships with (both directions)
   - Grouped by their parent container
   - Only edges involving the selected entity where both endpoints are visible"
  [m entity-id expanded-containers]
  (let [{:keys [by-from by-to]} (edges-for-entity m entity-id)

        ;; Get related entities via edges (both directions)
        ;; Filter to only include edges where both endpoints are visible
        outgoing (->> (get by-from entity-id [])
                      (filter #(node-visible? m (:to %) expanded-containers)))
        incoming (->> (get by-to entity-id [])
                      (filter #(node-visible? m (:from %) expanded-containers)))
        outgoing-ids (map :to outgoing)
        incoming-ids (map :from incoming)
        related-ids (into #{} (concat outgoing-ids incoming-ids))

        ;; All visible entities
        visible-ids (conj related-ids entity-id)
        visible-nodes (keep #(get-in m [:nodes %]) visible-ids)

        ;; Group by parent for compound nodes
        parent-ids (->> visible-nodes
                        (map :parent)
                        (remove nil?)
                        (into #{}))

        ;; Container nodes for grouping
        container-nodes (for [pid parent-ids
                              :let [pnode (get-in m [:nodes pid])]
                              :when pnode]
                          {:id pid
                           :label (:label pnode)
                           :kind (name (:kind pnode))
                           :originalId pid
                           :childCount (count (get-children m pid))
                           :hasPrivateChildren (boolean (has-private-children? m pid))
                           :isExpanded (contains? expanded-containers pid)})

        ;; Entity nodes
        entity-nodes (for [n visible-nodes]
                       (-> (cytoscape/node->cytoscape n)
                           (assoc :parent (:parent n))))

        ;; Only edges involving the selected entity
        all-edges (concat outgoing incoming)
        edges (->> all-edges
                   distinct
                   (map-indexed (fn [idx {:keys [from to]}]
                                  {:id (str "e" idx)
                                   :source from
                                   :target to
                                   :edgeType "code-flow"})))]

    {:nodes (vec (concat container-nodes entity-nodes))
     :edges (vec edges)}))

(defn- compute-graph-
  "Compute graph data for any entity.

   For containers (entities with children):
   - Shows visible children (filtered by expanded-containers)
   - Shows edges between visible children (sibling relationships)
   - If children relate to entities in sibling containers, shows those explicitly

   For leaves (entities without children):
   - Shows the entity and all its visible relationships
   - Groups related entities by their parent container

   Returns {:nodes :edges :selectedId} in Cytoscape format."
  [m entity-id expanded-containers]
  (let [;; If no entity-id, use root
        entity-id (or entity-id (:id (find-root-node m)))
        children-ids (get-children m entity-id)

        ;; Dispatch based on whether entity has children
        base-view (if (seq children-ids)
                    (compute-container-view m entity-id expanded-containers)
                    (compute-leaf-view m entity-id expanded-containers))]

    (assoc base-view :selectedId entity-id)))

(defn- compute-sidebar-
  "Compute the sidebar data for a node.
   Returns {:node :deps :dependents} map."
  [model node-id]
  (let [node (get-in model [:nodes node-id])
        deps (when node
               (case (:kind node)
                 :var (get (:edges-by-from model) node-id [])
                 :namespace (compute-namespace-deps node-id model)
                 nil))
        dependents (when node
                     (case (:kind node)
                       :var (get (:edges-by-to model) node-id [])
                       :namespace (compute-namespace-dependents node-id model)
                       nil))]
    {:node node :deps deps :dependents dependents}))

(defn- compute-highlighted-edges-
  "Compute which edges should be highlighted for a selected node.
   For schema nodes, highlights all edges with matching schemaKey.
   For other nodes, highlights edges where node is source or target (code-flow only)."
  [edges selected-id]
  (when selected-id
    (if (str/starts-with? selected-id "schema:")
      ;; Schema node - highlight by schemaKey
      (let [schema-key (subs selected-id 7)] ; Remove "schema:" prefix
        (->> edges
             (keep (fn [{:keys [id schemaKey]}]
                     (when (= schemaKey schema-key)
                       id)))
             vec))
      ;; Other nodes - highlight code-flow edges by source/target
      (->> edges
           (keep (fn [{:keys [id source target edgeType]}]
                   (when (and (not= edgeType "schema-flow")
                              (or (= source selected-id)
                                  (= target selected-id)))
                     id)))
           vec))))

;; -----------------------------------------------------------------------------
;; Public Render API
;;
;; All public render functions take (model, editor-state) and return output.
;; editor-state is a map with :view-id, :selected-id, and/or :expanded-containers

(defn render-graph
  "Render graph data for Cytoscape.
   Takes model and editor-state with :view-id, :selected-id, and :expanded-containers.
   Returns {:nodes :edges :selectedId :highlightedEdges}."
  {:malli/schema [:=> [:cat :fukan.model/Model :fukan.web.views/EditorState] :fukan.web.views/GraphData]}
  [model {:keys [view-id selected-id expanded-containers]}]
  (let [graph (compute-graph- model view-id (or expanded-containers #{}))
        selected-id (or selected-id (:selectedId graph))
        highlighted-edges (compute-highlighted-edges- (:edges graph) selected-id)]
    {:nodes (:nodes graph)
     :edges (:edges graph)
     :selectedId selected-id
     :highlightedEdges highlighted-edges}))

(defn render-breadcrumb
  "Render the breadcrumb navigation HTML.
   Takes model and editor-state with :view-id."
  {:malli/schema [:=> [:cat :fukan.model/Model :fukan.web.views/EditorState] :fukan.web.views/Html]}
  [model {:keys [view-id]}]
  (let [items (compute-breadcrumb- model view-id)]
    (render-breadcrumb-html items)))

(defn render-sidebar
  "Render the sidebar HTML.
   Takes model and editor-state with :selected-id."
  {:malli/schema [:=> [:cat :fukan.model/Model :fukan.web.views/EditorState] :fukan.web.views/Html]}
  [model {:keys [selected-id]}]
  (let [data (compute-sidebar- model selected-id)]
    (render-sidebar-html data)))
