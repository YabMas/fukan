(ns fukan.web.handler
  "HTTP routing — Phase-6 Model surface.

   Routes:
     GET /          — shell SPA frame
     GET /graph     — cytoscape JSON (projection.core/project-model)
     GET /projector — Blueprint JSON for primitive-id + projection-kind
     GET /sidebar   — entity detail HTML"
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]
            [cheshire.core :as json]
            [clojure.string :as str]
            [fukan.infra.model :as infra-model]
            [fukan.projection.core :as projection]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.defaults :as project-defaults]
            [fukan.web.views.shell :as views.shell]
            [fukan.web.views.graph :as views.graph]
            [fukan.web.views.sidebar :as views.sidebar]
            [fukan.web.agent-handlers :as agent-handlers]))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- bad-request [msg]
  {:status 400 :headers {"Content-Type" "text/plain"} :body msg})

(defn- handle-graph [_req]
  (if-let [m (infra-model/get-model)]
    (json-response (views.graph/render-graph (projection/project-model m)
                                             {:selected-id nil}))
    (bad-request "no model loaded; call infra-model/load-model")))

(defn- handle-projector [req]
  (let [{:strs [primitive-id projection-kind]} (or (:query-params req) {})
        m (infra-model/get-model)]
    (cond
      (nil? m) (bad-request "no model loaded")
      (or (str/blank? primitive-id) (str/blank? projection-kind))
      (bad-request "missing primitive-id and/or projection-kind query params")
      :else
      (json-response
        (projector/project m (project-defaults/fukan-on-fukan)
                           primitive-id (keyword projection-kind))))))

(defn- handle-sidebar [req]
  (let [id (get-in req [:query-params "id"])
        m  (infra-model/get-model)]
    (if (and m id)
      (let [primitive (get-in m [:primitives id])]
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (views.sidebar/render-entity primitive)})
      (bad-request "missing id or model"))))

(defn create-handler
  []
  (ring/ring-handler
    (ring/router
      [["/"          {:get (fn [req]
                             {:status 200
                              :headers {"Content-Type" "text/html"}
                              :body (views.shell/render-shell req)})}]
       ["/graph"     {:get handle-graph}]
       ["/projector"   {:get  handle-projector}]
       ["/sidebar"     {:get  handle-sidebar}]
       ["/agent/eval"   {:post agent-handlers/handle-eval}]
       ["/agent/status" {:get  agent-handlers/handle-status}]]
      {:data {:middleware [parameters/parameters-middleware]}})))
