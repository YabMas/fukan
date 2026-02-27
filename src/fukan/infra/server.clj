(ns fukan.infra.server
  "HTTP server lifecycle: start and stop the Ring server.
   The :fukan.infra/server Integrant component starts http-kit and returns
   a map with the stop function and port. Decoupled from the model
   lifecycle so the server can be restarted without re-analyzing
   the codebase."
  (:require [org.httpkit.server :as http]
            [integrant.core :as ig]))

(defmethod ig/init-key :fukan.infra/server [_ {:keys [port handler]
                                                :or {port 8080}}]
  (let [stop-fn (http/run-server handler {:port port})]
    (println (str "\nFukan running at http://localhost:" port))
    {:stop-fn stop-fn :port port}))

(defmethod ig/halt-key! :fukan.infra/server [_ {:keys [stop-fn port]}]
  (stop-fn)
  (println "Server stopped (was on port" (str port ")")))
