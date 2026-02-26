(ns fukan.web.views.shell
  "Render the initial HTML page shell."
  (:require [hiccup2.core :as h]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Html
  [:string {:description "Rendered HTML string ready for SSE patch or HTTP response."}])

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
    width: 340px;
    border-left: 1px solid #e0e0e0;
    padding: 1.25rem;
    overflow-y: auto;
    background: #fff;
  }
  #node-info {
    display: flex;
    flex-direction: column;
    gap: 0.1rem;
  }
  #node-info h4 {
    margin: 0;
    color: #1a1a2e;
    font-size: 1.05rem;
    font-weight: 600;
    line-height: 1.35;
    word-break: break-word;
  }
  .kind-badge {
    display: inline-block;
    font-size: 0.7rem;
    font-weight: 500;
    color: #5a6c7d;
    background: #f0f1f3;
    padding: 0.1rem 0.45rem;
    border-radius: 3px;
    vertical-align: middle;
    margin-left: 0.35rem;
    text-transform: lowercase;
    letter-spacing: 0.02em;
  }
  #node-info h5 {
    margin: 0;
    padding-top: 1rem;
    border-top: 1px solid #eef0f2;
    color: #5a6c7d;
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  #node-info h6 {
    margin: 0.5rem 0 0.15rem 0;
    color: #5a6c7d;
    font-size: 0.82rem;
  }
  #node-info ul {
    list-style: none;
    padding-left: 0;
    margin: 0.35rem 0 0 0;
  }
  #node-info li {
    cursor: pointer;
    padding: 0.3rem 0.5rem;
    color: #2980b9;
    border-radius: 4px;
    font-size: 0.9rem;
    transition: background 0.1s;
  }
  #node-info li:hover {
    background: #f0f5fa;
  }
  .empty-state {
    color: #b0b8c1;
    font-style: italic;
    font-size: 0.85rem;
  }
  .dep-count {
    color: #95a0ab;
    font-weight: 400;
    font-size: 0.85rem;
  }
  .doc {
    margin: 0.5rem 0 0 0;
    padding: 0.6rem 0.75rem;
    background: #f7f8fa;
    border-left: 3px solid #c4d0dc;
    font-size: 0.84rem;
    line-height: 1.55;
    color: #3d4f5f;
    white-space: pre-wrap;
    border-radius: 0 4px 4px 0;
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
  .sig {
    padding-left: 0.5rem;
    font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
    font-size: 0.78rem;
    color: #95a0ab;
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
    margin: 0.35rem 0 0 0;
  }
  .schema-list li {
    padding: 0.3rem 0.5rem;
    margin: 0.15rem 0;
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
    font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
    font-size: 0.82rem;
    line-height: 1.6;
  }
  .schema-doc {
    margin: 0 0 0.75rem 0;
    padding: 0.5rem 0.65rem;
    background: #f7f8fa;
    border-left: 3px solid #c4d0dc;
    font-family: system-ui, -apple-system, sans-serif;
    font-size: 0.82rem;
    line-height: 1.5;
    color: #3d4f5f;
  }
  .schema-entries {
    padding: 0.5rem 0.65rem;
    background: #f8f9fa;
    border: 1px solid #eef0f2;
    border-radius: 4px;
  }
  .schema-detail .entry {
    padding: 0.2rem 0;
  }
  .schema-detail .key {
    color: #8e44ad;
  }
  .schema-detail .optional {
    color: #95a5a6;
    font-size: 0.9em;
  }
  .entry-sep {
    color: #95a5a6;
  }
  .entry-type {
    color: #2c3e50;
  }
  .entry-doc {
    padding-left: 1rem;
    font-family: system-ui, -apple-system, sans-serif;
    font-size: 0.78rem;
    color: #7f8c8d;
    font-style: italic;
    margin: 0.1rem 0 0.3rem 0;
  }
  .schema-name {
    color: #95a5a6;
    font-size: 0.85em;
  }
  .schema-or {
    display: inline;
  }
  .schema-sep {
    color: #95a5a6;
  }
  .schema-variants {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .schema-variant {
    padding: 0.5rem 0.65rem;
    background: #f8f9fa;
    border: 1px solid #eef0f2;
    border-radius: 4px;
  }
  .variant-label {
    font-family: system-ui, -apple-system, sans-serif;
    font-size: 0.7rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: #95a0ab;
    margin-bottom: 0.25rem;
  }
  .variant-doc {
    font-family: system-ui, -apple-system, sans-serif;
    font-size: 0.78rem;
    color: #7f8c8d;
    font-style: italic;
    margin-bottom: 0.35rem;
  }
  .schema-inline {
    padding: 0.5rem 0.65rem;
    background: #f8f9fa;
    border: 1px solid #eef0f2;
    border-radius: 4px;
  }
  .schema-drilldown {
    text-decoration: underline;
    text-decoration-style: dotted;
    text-underline-offset: 2px;
  }
  .schema-drilldown:hover {
    text-decoration-style: solid;
  }
  .schema-trail {
    display: flex;
    align-items: center;
    gap: 0.25rem;
    flex-wrap: wrap;
    margin-bottom: 0.5rem;
    font-size: 0.78rem;
  }
  .trail-item {
    color: #2980b9;
    cursor: pointer;
    padding: 0.15rem 0.35rem;
    border-radius: 3px;
    transition: background 0.1s;
  }
  .trail-item:hover {
    background: #e8f4f8;
  }
  .trail-sep {
    color: #bdc3c7;
    font-size: 0.8rem;
  }
  .back-link {
    display: inline-block;
    margin-bottom: 0.5rem;
    color: #95a0ab;
    cursor: pointer;
    font-size: 0.8rem;
    transition: color 0.1s;
  }
  .back-link:hover {
    color: #2980b9;
  }
  .edge-header {
    font-size: 1.1rem;
    font-weight: 600;
    margin-bottom: 0.5rem;
    color: #2c3e50;
  }
  .edge-endpoint {
    color: #2980b9;
    cursor: pointer;
  }
  .edge-endpoint:hover {
    text-decoration: underline;
  }
  .edge-arrow {
    color: #7f8c8d;
    margin: 0 0.25rem;
  }
  .edge-type {
    color: #7f8c8d;
    font-size: 0.85rem;
    margin-bottom: 1rem;
    text-transform: capitalize;
  }
  .edge-calls {
    list-style: none;
    padding-left: 0;
    margin: 0;
  }
  .edge-call {
    margin-bottom: 1rem;
    padding: 0.75rem;
    background: #f8f9fa;
    border-radius: 4px;
    border-left: 3px solid #2980b9;
  }
  .edge-call .caller,
  .edge-call .callee {
    color: #2980b9;
    cursor: pointer;
    font-weight: 500;
  }
  .edge-call .caller:hover,
  .edge-call .callee:hover {
    text-decoration: underline;
  }
  .edge-call .call-arrow {
    color: #95a5a6;
    font-size: 0.75rem;
    text-align: center;
    margin: 0.25rem 0;
  }
  .edge-call .signature {
    margin: 0.25rem 0;
    padding: 0.5rem;
    font-size: 0.8rem;
  }")

;; -----------------------------------------------------------------------------
;; Public API

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
        {"data-on:cy-select" "@get('/sse/sidebar?id=' + encodeURIComponent(evt.detail.id))"
         "data-on:cy-navigate" "@get('/sse/view?id=' + encodeURIComponent(evt.detail.id) + '&select=' + encodeURIComponent(evt.detail.select || '') + '&expanded=' + window.getExpandedParam())"
         "data-on:cy-toggle-private" "@get('/sse/view?id=' + encodeURIComponent(new URLSearchParams(window.location.search).get('id') || '') + '&expanded=' + window.getExpandedParam())"
         "data-on:cy-schema" "@get('/sse/schema?id=' + encodeURIComponent(evt.detail.id))"
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
