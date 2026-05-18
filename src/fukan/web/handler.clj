(ns fukan.web.handler
  "HTTP routing and handler construction.
   Plan 1 stub: serves the placeholder shell page only.
   Full router is rewritten in Plan 6."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]
            [fukan.web.views.shell :as views.shell]))

(defn create-handler
  "Create a Ring handler. In Plan 1 this serves only GET / (placeholder page)."
  []
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [req]
                  {:status 200
                   :headers {"Content-Type" "text/html"}
                   :body (views.shell/render-shell req)})}]]
    {:data {:middleware [parameters/parameters-middleware]}})))
