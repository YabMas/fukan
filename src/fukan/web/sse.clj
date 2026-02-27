(ns fukan.web.sse
  "SSE endpoint handlers using the Datastar SDK.
   Each handler orchestrates a request: calls projection functions to
   compute data from the model, then passes the result to view renderers
   that produce HTML fragments or JSON. Streams responses as SSE events
   for incremental UI updates. This is where projection meets rendering."
  (:require [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [fukan.web.views.graph :as views.graph]
            [fukan.web.views.breadcrumb :as views.breadcrumb]
            [fukan.web.views.sidebar :as views.sidebar]
            [fukan.projection.graph :as proj.graph]
            [fukan.projection.details :as proj.details]
            [fukan.projection.path :as proj.path]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Request
  [:map {:description "Ring request map with parsed query parameters."}
   [:query-params {:optional true} [:map-of :string :string]]])

(def ^:schema SSEResponse
  [:any {:description "HTTP-kit async SSE response (opaque channel handle)."}])

;; -----------------------------------------------------------------------------
;; Helpers

(defn- parse-expanded-containers
  "Parse the expanded query param into a set of container IDs.
   Format: comma-separated list of URL-encoded IDs."
  [expanded-param]
  (if (and expanded-param (not= expanded-param ""))
    (into #{} (str/split expanded-param #","))
    #{}))

;; -----------------------------------------------------------------------------
;; SSE Handlers

(defn- find-container-for-node
  "Find the container (parent) that would show this node in its view.
   For a var, returns its namespace. For a namespace, returns its parent folder.
   Returns nil if the node should be shown at root level."
  [model node-id]
  (when-let [node (get-in model [:nodes node-id])]
    (:parent node)))

(defn- compute-sidebar-data
  "Compute normalized entity detail for sidebar rendering.
   All kind-specific enrichment is handled by the projection layer."
  [model selected-id]
  (proj.details/entity-details model selected-id))

(defn main-view-handler
  "SSE endpoint that streams the full main view update.

   Parameters:
   - id: Entity to navigate to (show its children)
   - select: Node to select and highlight (also determines navigation if id not provided)
   - expanded: Comma-separated list of container IDs with visible private children

   When ?select=xxx is provided without ?id, navigates to the view containing that node.

   Patches:
   - Breadcrumb HTML
   - Sidebar HTML (node info for selected node)
   - Graph data via script tag"
  {:malli/schema [:=> [:cat :Model :Request] :SSEResponse]}
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
                                expanded-containers (parse-expanded-containers (get params "expanded"))
                                ;; If select is provided but id is not, navigate to the container of the selected node
                                entity-id (or entity-id
                                              (when select-id (find-container-for-node model select-id)))
                                ;; Build editor state
                                editor-state {:view-id entity-id
                                              :selected-id (or select-id entity-id)
                                              :expanded-containers expanded-containers}
                                ;; Get projections
                                graph-projection (proj.graph/entity-graph model {:view-id entity-id
                                                                                 :expanded-containers expanded-containers})
                                path-items (proj.path/entity-path model entity-id)
                                sidebar-data (compute-sidebar-data model (:selected-id editor-state))
                                ;; Render views
                                graph-data (views.graph/render-graph graph-projection editor-state)]

                            ;; 1. Patch breadcrumb HTML
                            (d*/patch-elements! sse (views.breadcrumb/render-breadcrumb path-items))

                            ;; 2. Patch sidebar HTML
                            (d*/patch-elements! sse (views.sidebar/render-sidebar-html sidebar-data))

                            ;; 3. Execute script to update graph and URL
                            (d*/execute-script! sse
                                                (str "if(window.renderGraph)renderGraph("
                                                     (json/generate-string graph-data)
                                                     ");"
                                                     "if(window.updateViewUrl)updateViewUrl(" (json/generate-string (or entity-id "")) ");")))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))

(defn sidebar-handler
  "SSE endpoint that streams just the sidebar content.
   Used when selecting a node without navigating."
  {:malli/schema [:=> [:cat :Model :Request] :SSEResponse]}
  [model request]
  (hk/->sse-response request
                     {hk/on-open
                      (fn [sse]
                        (try
                          (let [params (:query-params request)
                                node-id (let [id (get params "id")]
                                          (when (and id (not= id "")) id))
                                sidebar-data (compute-sidebar-data model node-id)]
                            (d*/patch-elements! sse (views.sidebar/render-sidebar-html sidebar-data))
                            ;; Update URL with selection
                            (d*/execute-script! sse
                                                (str "if(window.updateSelectUrl)updateSelectUrl("
                                                     (json/generate-string (or node-id ""))
                                                     ");")))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))

(defn- parse-trail
  "Parse the trail query param into a vector of schema ID strings.
   Trail is comma-separated list of schema IDs for drill-down navigation."
  [trail-param]
  (when (and trail-param (not= trail-param ""))
    (str/split trail-param #",")))

(defn schema-handler
  "SSE endpoint that streams the schema detail view.
   Used when clicking on a schema name to view its definition.
   Supports trail parameter for drill-down navigation breadcrumb."
  {:malli/schema [:=> [:cat :Model :Request] :SSEResponse]}
  [model request]
  (hk/->sse-response request
                     {hk/on-open
                      (fn [sse]
                        (try
                          (let [params (:query-params request)
                                schema-id (get params "id")
                                trail (parse-trail (get params "trail"))
                                schema-key (keyword schema-id)
                                node-id (proj.path/find-schema-node-id model schema-key)
                                sidebar-data (-> (compute-sidebar-data model node-id)
                                                 (assoc :nav {:trail (vec (or trail []))
                                                              :current schema-id}))]
                            (d*/patch-elements! sse (views.sidebar/render-sidebar-html sidebar-data))
                            ;; Update URL with schema
                            (d*/execute-script! sse
                                                (str "if(window.updateSchemaUrl)updateSchemaUrl("
                                                     (json/generate-string (or schema-id ""))
                                                     ");")))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))
