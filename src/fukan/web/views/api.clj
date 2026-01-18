(ns fukan.web.views.api
  "View rendering for the web interface.

   Public render functions accept pre-computed projections and return output.
   The handler (sse.clj) is responsible for fetching projections."
  (:require [hiccup2.core :as h]
            [fukan.web.cytoscape :as cytoscape]
            [fukan.web.views.graph :as views.graph]
            [fukan.web.views.sidebar :as views.sidebar]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema EditorState
  [:map
   [:view-id {:optional true} [:maybe :string]]
   [:selected-id {:optional true} [:maybe :string]]
   [:schema-id {:optional true} [:maybe :string]]
   [:expanded-containers {:optional true} :set]])

(def ^:schema GraphData
  [:map
   [:nodes [:vector :map]]
   [:edges [:vector :CytoscapeEdge]]
   [:selectedId {:optional true} [:maybe :string]]
   [:highlightedEdges {:optional true} [:vector :string]]])

(def ^:schema Html :string)

;; -----------------------------------------------------------------------------
;; CSS

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

;; -----------------------------------------------------------------------------
;; App Shell

(defn render-app-shell
  "Render the initial HTML page shell."
  {:malli/schema [:=> [:cat] :Html]}
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
;; Public Render API
;;
;; Render functions accept pre-computed projections and return output.
;; The handler (sse.clj) is responsible for calling projection.api.

(defn render-graph
  "Render graph data for Cytoscape.
   Takes pre-computed graph-projection, root-node, and editor-state.
   Returns Cytoscape-compatible {:nodes :edges :selectedId :highlightedEdges}.

   views.graph outputs domain-focused format with Clojure idioms.
   This function transforms to Cytoscape format at the boundary."
  {:malli/schema [:=> [:cat :Projection :Node :EditorState] :GraphData]}
  [graph-projection root-node {:keys [view-id selected-id] :as editor-state}]
  (let [;; Add UI state (selected?, highlighted?)
        graph (views.graph/add-ui-state graph-projection editor-state root-node)
        effective-selected-id (or selected-id view-id (:id root-node))
        highlighted-edges (views.graph/compute-highlighted-edges (:edges graph) effective-selected-id)]
    ;; Transform to Cytoscape format at the boundary
    (cytoscape/graph->cytoscape graph effective-selected-id highlighted-edges)))

(defn render-breadcrumb
  "Render the breadcrumb navigation HTML.
   Takes pre-computed path items (from proj/entity-path)."
  {:malli/schema [:=> [:cat :EntityPath] :Html]}
  [path-items]
  (render-breadcrumb-html path-items))

(defn render-sidebar
  "Render the sidebar HTML.
   Takes pre-computed sidebar-data.
   If :schema-id is present, renders schema detail view.
   Otherwise renders regular node sidebar."
  {:malli/schema [:=> [:cat :EntityDetails] :Html]}
  [sidebar-data]
  (if (:schema-id sidebar-data)
    (views.sidebar/render-schema-sidebar (:schema-id sidebar-data) (:schema-form sidebar-data))
    (views.sidebar/render-sidebar-html sidebar-data)))
