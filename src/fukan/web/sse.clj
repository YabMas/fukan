(ns fukan.web.sse
  "SSE handlers using Datastar SDK.
   Streams HTML fragments and graph data to the frontend."
  (:require [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [fukan.web.views.api :as views]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Request
  [:map
   [:query-params {:optional true} [:map-of :string :string]]])

(def ^:schema SSEResponse :any)

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
                                ;; Render all components
                                graph-data (views/render-graph model editor-state)]

                            ;; 1. Patch breadcrumb HTML
                            (d*/patch-elements! sse (views/render-breadcrumb model editor-state))

                            ;; 2. Patch sidebar HTML
                            (d*/patch-elements! sse (views/render-sidebar model editor-state))

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
                                editor-state {:selected-id node-id}]
                            (d*/patch-elements! sse (views/render-sidebar model editor-state))
                            ;; Update URL with selection
                            (d*/execute-script! sse
                                                (str "if(window.updateSelectUrl)updateSelectUrl("
                                                     (json/generate-string (or node-id ""))
                                                     ");")))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))

(defn schema-handler
  "SSE endpoint that streams the schema detail view.
   Used when clicking on a schema name to view its definition."
  {:malli/schema [:=> [:cat :Request] :SSEResponse]}
  [request]
  (hk/->sse-response request
                     {hk/on-open
                      (fn [sse]
                        (try
                          (let [params (:query-params request)
                                schema-id (get params "id")]
                            (d*/patch-elements! sse (views/render-sidebar nil {:schema-id schema-id}))
                            ;; Update URL with schema
                            (d*/execute-script! sse
                                                (str "if(window.updateSchemaUrl)updateSchemaUrl("
                                                     (json/generate-string (or schema-id ""))
                                                     ");")))
                          (catch Exception e
                            (println "SSE error:" (.getMessage e))))
                        (d*/close-sse! sse))}))
