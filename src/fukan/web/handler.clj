(ns fukan.web.handler
  "HTTP request handling and routing.
   Delegates view computation and rendering to specialized namespaces."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]
            [fukan.infra.model :as model]
            [fukan.web.views.shell :as views.shell]
            [fukan.web.sse :as sse]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Schemas

(def ^:schema Handler
  [:fn {:description "Ring handler function: takes a request map, returns a response map."}])

(defn- get-content-type
  "Determine content type based on file extension."
  [path]
  (cond
    (str/ends-with? path ".js") "application/javascript"
    (str/ends-with? path ".css") "text/css"
    (str/ends-with? path ".json") "application/json"
    :else "text/plain"))

(defn- serve-static
  "Serve static files from resources/public."
  [path]
  (let [resource-path (str "public/" path)]
    (if-let [resource (io/resource resource-path)]
      {:status 200
       :headers {"Content-Type" (get-content-type path)}
       :body (slurp resource)}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not found"})))

(defn create-handler
  "Create a Ring handler with all routes.
   Calls `fukan.infra.model/get-model` on each request, allowing model
   refresh without server restart."
  {:malli/schema [:=> [:cat] :Handler]}
  []
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [_]
                  {:status 200
                   :headers {"Content-Type" "text/html"}
                   :body (views.shell/render-app-shell)})}]

     ;; SSE endpoints (Datastar)
     ["/sse/view" {:get (fn [req]
                          (sse/main-view-handler (model/get-model) req))}]

     ["/sse/sidebar" {:get (fn [req]
                             (sse/sidebar-handler (model/get-model) req))}]

     ["/sse/schema" {:get (fn [req]
                            (sse/schema-handler (model/get-model) req))}]

     ["/public/*path" {:get (fn [{{:keys [path]} :path-params}]
                              (serve-static path))}]]

    {:data {:middleware [parameters/parameters-middleware]}})))
