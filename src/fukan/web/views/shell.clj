(ns fukan.web.views.shell
  "Render the initial HTML page shell.
   Produces the full-page HTML document with layout containers, CSS,
   and JavaScript dependencies. Loaded once on first navigation;
   all subsequent updates arrive as SSE fragments into this shell."
  (:require [hiccup2.core :as h]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Html
  [:string {:description "Rendered HTML string ready for SSE patch or HTTP response."}])

;; -----------------------------------------------------------------------------
;; CSS

(def ^:private css
  ":root {
    --font-sans: system-ui, -apple-system, sans-serif;
    --font-mono: 'SF Mono', Monaco, 'Cascadia Code', monospace;
    --color-primary: #2980b9;
    --color-green: #27ae60;
    --color-purple: #8e44ad;
    --color-text: #2c3e50;
    --color-text-heading: #1a1a2e;
    --color-text-section: #5a6c7d;
    --color-text-muted: #7f8c8d;
    --color-text-faint: #95a0ab;
    --color-text-doc: #3d4f5f;
    --color-border: #e0e0e0;
    --color-border-light: #eef0f2;
    --color-separator: #bdc3c7;
    --color-surface: #fff;
    --color-surface-alt: #f8f9fa;
    --color-doc-bg: #f7f8fa;
    --color-doc-accent: #c4d0dc;
    --color-primary-bg: #e8f4f8;
    --color-green-bg: #f0f7ee;
    --color-green-bg-hover: #e0f0dc;
    --color-hover-bg: #f0f5fa;
    --sidebar-width: 24vw;
    --header-height: 4vh;
  }
  *, *::before, *::after {
    box-sizing: border-box;
  }

  /* Base */
  body {
    margin: 0;
    font-family: var(--font-sans);
    background: #f5f5f5;
  }

  /* Layout — CSS Grid, viewport-unit columns so zoom doesn't shift proportions */
  #container {
    display: grid;
    grid-template-columns: minmax(0, 1fr) var(--sidebar-width);
    height: 100vh;
    height: 100dvh;
    overflow: hidden;
  }
  #graph-panel {
    display: flex;
    flex-direction: column;
    min-width: 0;
    position: relative;
    background: var(--color-surface);
  }

  /* Breadcrumb — fixed viewport height so zoom doesn't shift the header */
  #breadcrumb {
    height: var(--header-height);
    min-height: var(--header-height);
    padding: 0 0.7vw;
    background: var(--color-surface);
    border-bottom: 1px solid var(--color-border);
    font-size: 0.9rem;
    color: var(--color-text-muted);
    display: flex;
    align-items: center;
    gap: 0.5rem;
    overflow: hidden;
  }
  #breadcrumb .crumb {
    color: var(--color-primary);
    cursor: pointer;
    padding: 0.25rem 0.5rem;
    border-radius: 4px;
    transition: background 0.15s;
  }
  #breadcrumb .crumb:hover {
    background: var(--color-primary-bg);
  }
  #breadcrumb .crumb.current {
    color: var(--color-text);
    font-weight: 600;
    cursor: default;
  }
  #breadcrumb .crumb.current:hover {
    background: transparent;
  }
  #breadcrumb .separator {
    color: var(--color-separator);
    font-size: 0.8rem;
  }

  /* Graph container */
  #cy {
    flex: 1;
    min-height: 0;
    background: var(--color-surface);
  }

  /* Sidebar — viewport-unit padding keeps sidebar stable across zoom */
  #sidebar {
    border-left: 1px solid var(--color-border);
    padding: 1vh 0.9vw;
    overflow-y: auto;
    min-width: 0;
    background: var(--color-surface);
  }
  #node-info {
    display: flex;
    flex-direction: column;
    gap: 0.1rem;
  }
  #node-info h4 {
    margin: 0;
    color: var(--color-text-heading);
    font-size: 1.05rem;
    font-weight: 600;
    line-height: 1.35;
    word-break: break-word;
  }
  .kind-badge {
    display: inline-block;
    font-size: 0.7rem;
    font-weight: 500;
    color: var(--color-text-section);
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
    border-top: 1px solid var(--color-border-light);
    color: var(--color-text-section);
    font-size: 0.75rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }
  #node-info h6 {
    margin: 0.5rem 0 0.15rem 0;
    color: var(--color-text-section);
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
    color: var(--color-primary);
    border-radius: 4px;
    font-size: 0.9rem;
    transition: background 0.1s;
  }
  #node-info li:hover {
    background: var(--color-hover-bg);
  }
  .empty-state {
    color: #b0b8c1;
    font-style: italic;
    font-size: 0.85rem;
  }
  .dep-count {
    color: var(--color-text-faint);
    font-weight: 400;
    font-size: 0.85rem;
  }

  /* Documentation blocks */
  .doc {
    margin: 0.5rem 0 0 0;
    padding: 0.6rem 0.75rem;
    background: var(--color-doc-bg);
    border-left: 3px solid var(--color-doc-accent);
    font-size: 0.84rem;
    line-height: 1.55;
    color: var(--color-text-doc);
    white-space: pre-wrap;
    border-radius: 0 4px 4px 0;
  }
  .doc-label {
    font-size: 0.75rem;
    color: var(--color-text-muted);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-bottom: 0.25rem;
  }

  /* Loading indicator */
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
    color: var(--color-text-muted);
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
    border: 2px solid var(--color-border);
    border-top-color: var(--color-primary);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin-right: 0.5rem;
    vertical-align: middle;
  }

  /* Function signatures */
  .sig {
    padding-left: 0.5rem;
    font-family: var(--font-mono);
    font-size: 0.78rem;
    color: var(--color-text-faint);
  }
  .signature {
    margin: 0.75rem 0;
    padding: 0.75rem;
    background: var(--color-green-bg);
    border-left: 3px solid var(--color-green);
    font-family: var(--font-mono);
    font-size: 0.85rem;
    line-height: 1.5;
  }
  .signature .schema-ref {
    color: var(--color-green);
    cursor: pointer;
    text-decoration: underline;
    text-decoration-style: dotted;
  }
  .signature .schema-ref:hover {
    text-decoration-style: solid;
  }
  .signature .arrow {
    color: var(--color-text-muted);
    margin: 0 0.25rem;
  }

  /* Schema display */
  .schema-list {
    list-style: none;
    padding-left: 0;
    margin: 0.35rem 0 0 0;
  }
  .schema-list li {
    padding: 0.3rem 0.5rem;
    margin: 0.15rem 0;
    background: var(--color-green-bg);
    border-radius: 4px;
    font-family: var(--font-mono);
    font-size: 0.85rem;
  }
  .schema-list li:hover {
    background: var(--color-green-bg-hover);
  }
  .schema-detail {
    margin: 0.75rem 0;
    font-family: var(--font-mono);
    font-size: 0.82rem;
    line-height: 1.6;
  }
  .schema-doc {
    margin: 0 0 0.75rem 0;
    padding: 0.5rem 0.65rem;
    background: var(--color-doc-bg);
    border-left: 3px solid var(--color-doc-accent);
    font-family: var(--font-sans);
    font-size: 0.82rem;
    line-height: 1.5;
    color: var(--color-text-doc);
  }
  .schema-entries {
    padding: 0.5rem 0.65rem;
    background: var(--color-surface-alt);
    border: 1px solid var(--color-border-light);
    border-radius: 4px;
  }
  .schema-detail .entry {
    padding: 0.2rem 0;
  }
  .schema-detail .key {
    color: var(--color-purple);
  }
  .schema-detail .optional {
    color: var(--color-text-faint);
    font-size: 0.9em;
  }
  .entry-sep {
    color: var(--color-text-faint);
  }
  .entry-type {
    color: var(--color-text);
  }
  .entry-doc {
    padding-left: 1rem;
    font-family: var(--font-sans);
    font-size: 0.78rem;
    color: var(--color-text-muted);
    font-style: italic;
    margin: 0.1rem 0 0.3rem 0;
  }
  .schema-or {
    display: inline;
  }
  .schema-sep {
    color: var(--color-text-faint);
  }
  .schema-variants {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .schema-variant {
    padding: 0.5rem 0.65rem;
    background: var(--color-surface-alt);
    border: 1px solid var(--color-border-light);
    border-radius: 4px;
  }
  .variant-label {
    font-family: var(--font-sans);
    font-size: 0.7rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--color-text-faint);
    margin-bottom: 0.25rem;
  }
  .variant-doc {
    font-family: var(--font-sans);
    font-size: 0.78rem;
    color: var(--color-text-muted);
    font-style: italic;
    margin-bottom: 0.35rem;
  }
  .schema-inline {
    padding: 0.5rem 0.65rem;
    background: var(--color-surface-alt);
    border: 1px solid var(--color-border-light);
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

  /* Schema trail navigation */
  .schema-trail {
    display: flex;
    align-items: center;
    gap: 0.25rem;
    flex-wrap: wrap;
    margin-bottom: 0.5rem;
    font-size: 0.78rem;
  }
  .trail-item {
    color: var(--color-primary);
    cursor: pointer;
    padding: 0.15rem 0.35rem;
    border-radius: 3px;
    transition: background 0.1s;
  }
  .trail-item:hover {
    background: var(--color-primary-bg);
  }
  .trail-sep {
    color: var(--color-separator);
    font-size: 0.8rem;
  }
  .trail-current {
    color: var(--color-text);
    padding: 0.15rem 0.35rem;
  }

  /* Edge details */
  .edge-header {
    font-size: 1.1rem;
    font-weight: 600;
    margin-bottom: 0.5rem;
    color: var(--color-text);
  }
  .edge-endpoint {
    color: var(--color-primary);
    cursor: pointer;
  }
  .edge-endpoint:hover {
    text-decoration: underline;
  }
  .edge-arrow {
    color: var(--color-text-muted);
    margin: 0 0.25rem;
  }
  .edge-type {
    color: var(--color-text-muted);
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
    background: var(--color-surface-alt);
    border-radius: 4px;
    border-left: 3px solid var(--color-primary);
  }
  .edge-call .caller,
  .edge-call .callee {
    color: var(--color-primary);
    cursor: pointer;
    font-weight: 500;
  }
  .edge-call .caller:hover,
  .edge-call .callee:hover {
    text-decoration: underline;
  }
  .edge-call .call-arrow {
    color: var(--color-text-faint);
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
         "data-on:cy-navigate" "@get('/sse/view?id=' + encodeURIComponent(evt.detail.id) + '&select=' + encodeURIComponent(evt.detail.select || '') + '&expanded=' + window.getExpandedParam() + '&show_private=' + window.getShowPrivateParam() + '&visible_edge_types=' + window.getVisibleEdgeTypesParam())"
         "data-on:cy-toggle-private" "@get('/sse/view?id=' + encodeURIComponent(new URLSearchParams(window.location.search).get('id') || '') + '&expanded=' + window.getExpandedParam() + '&show_private=' + window.getShowPrivateParam() + '&visible_edge_types=' + window.getVisibleEdgeTypesParam())"
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
