(ns fukan.web.sse
  "SSE endpoint handlers using the Datastar SDK.
   Each handler orchestrates a request: calls projection functions to
   compute data from the model, then passes the result to view renderers
   that produce HTML fragments or JSON. Streams responses as SSE events
   for incremental UI updates. This is where projection meets rendering.

   Graph data is delivered via patch-signals (reactive signal), not
   execute-script (script injection). The GraphViewer web component
   observes the signal through a data-attr binding and renders
   the graph when the signal changes."
  (:require [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [fukan.web.views.graph :as views.graph]
            [fukan.web.views.breadcrumb :as views.breadcrumb]
            [fukan.web.views.sidebar :as views.sidebar]
            [fukan.projection.api :as proj]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema AsyncChannel
  [:fn {:description "HTTP-kit AsyncChannel: opaque streaming handle used as a Ring-compatible response. Created by http-kit's `as-channel`. Docs: https://http-kit.github.io/http-kit/org.httpkit.server.html#var-as-channel"}
   #(instance? org.httpkit.server.AsyncChannel %)])

(def ^:schema RingRequest
  [:map {:description "Ring HTTP request map with parsed query parameters."}
   [:query-params {:optional true} [:map-of :string :string]]
   [:request-method {:optional true} :keyword]
   [:uri {:optional true} :string]])

(def ^:schema RingResponse
  [:map {:description "Ring HTTP response map."}
   [:status :int]
   [:headers {:optional true} [:map-of :string :string]]
   [:body {:optional true} :any]])

;; -----------------------------------------------------------------------------
;; Helpers

(defn- parse-id-set
  "Parse a comma-separated query param into a set of IDs.
   Format: comma-separated list of URL-encoded IDs."
  [param]
  (if (and param (not= param ""))
    (into #{} (str/split param #","))
    #{}))

(defn- find-module-for-node
  "Find the module (parent) that would show this node in its view.
   For a function, returns its parent module. For a module, returns its parent folder.
   Returns nil if the node should be shown at root level."
  [model node-id]
  (when-let [node (get-in model [:nodes node-id])]
    (:parent node)))

(defn- send-full-view!
  "Send a complete view update: signal + sidebar + breadcrumb.
   Order matters: Datastar aborts the SSE stream when the element that
   triggered @get() is removed by a morph. Send the graph signal first
   (it never removes elements), then sidebar, then breadcrumb last
   (breadcrumb clicks originate here, so its morph must come last)."
  [sse path details graph-data]
  ;; 1. Graph data signal first — never removes the triggering element
  (d*/patch-signals! sse (json/generate-string {"_graphData" graph-data}))
  ;; 2. Sidebar HTML — safe for breadcrumb-triggered requests
  (d*/patch-elements! sse (views.sidebar/render-sidebar-html details))
  ;; 3. Breadcrumb HTML last — its morph may remove the triggering element
  (d*/patch-elements! sse (views.breadcrumb/render-breadcrumb path)))

;; -----------------------------------------------------------------------------
;; SSE Handlers

(defn main-view-handler
  "SSE endpoint that streams the full main view update.

   Parameters:
   - id: Entity to navigate to (show its children)
   - select: Node to select and highlight (also determines navigation if id not provided)
   - expanded: Comma-separated list of explicitly expanded module IDs
   - show_private: Comma-separated list of module IDs with visible private children
   - visible_edge_types: Comma-separated list of edge types to show

   When ?select=xxx is provided without ?id, navigates to the view containing that node.

   Sends:
   - Breadcrumb HTML (patch-elements)
   - Sidebar HTML (patch-elements)
   - Graph data signal (patch-signals)"
  {:malli/schema [:=> [:cat :Model :RingRequest] :AsyncChannel]}
  [model request]
  (hk/->sse-response request
                     {hk/on-open
                      (fn [sse]
                        (try
                          (let [params (:query-params request)
                                entity-id (let [id (get params "id")]
                                            (when (and id (not= id "")) id))
                                select-id (let [id (get params "select")]
                                            (when (and id (not= id "")) id))
                                expanded (parse-id-set (get params "expanded"))
                                show-private (parse-id-set (get params "show_private"))
                                visible-edge-types (let [raw (parse-id-set (get params "visible_edge_types"))]
                                                     (when (seq raw) (set (map keyword raw))))
                                ;; If select is provided but id is not, navigate to the module of the selected node
                                entity-id (or entity-id
                                              (when select-id (find-module-for-node model select-id)))
                                ;; Build editor state
                                editor-state {:view-id entity-id
                                              :selected-id (or select-id entity-id)
                                              :expanded expanded
                                              :show-private show-private
                                              :visible-edge-types visible-edge-types}
                                ;; Get projections
                                {:keys [graph path details]}
                                (proj/navigate model {:view-id entity-id
                                                      :expanded expanded
                                                      :show-private show-private
                                                      :selected (:selected-id editor-state)
                                                      :visible-edge-types visible-edge-types})
                                ;; Render graph data with viewId for URL sync
                                graph-data (assoc (views.graph/render-graph graph editor-state)
                                                  :viewId (or entity-id ""))]
                            (send-full-view! sse path details graph-data))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))

(defn sidebar-handler
  "SSE endpoint that streams just the sidebar content.
   Used when selecting a node without navigating (graph click)."
  {:malli/schema [:=> [:cat :Model :RingRequest] :AsyncChannel]}
  [model request]
  (hk/->sse-response request
                     {hk/on-open
                      (fn [sse]
                        (try
                          (let [params (:query-params request)
                                node-id (let [id (get params "id")]
                                          (when (and id (not= id "")) id))
                                sidebar-data (proj/inspect model node-id)]
                            (d*/patch-elements! sse (views.sidebar/render-sidebar-html sidebar-data)))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))
