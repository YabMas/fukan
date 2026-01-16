(ns fukan.infra.server
  "Server lifecycle management.
   Handles starting and stopping the HTTP server independently
   from the model lifecycle."
  (:require [fukan.web.handler :as handler]
            [org.httpkit.server :as http]))

(defonce ^:private state (atom nil))

(defn start-server
  "Start HTTP server.

   Options:
     :port         - Server port (default: 8080)
     :get-model-fn - Function that returns the current model (required)

   The get-model-fn is called on each request, allowing model refresh
   without server restart."
  [{:keys [port get-model-fn] :or {port 8080}}]
  (when-not get-model-fn
    (throw (ex-info "Missing required :get-model-fn option" {})))
  (if @state
    (println "Server already running on port" (:port @state))
    (let [h (handler/create-handler get-model-fn)
          stop-fn (http/run-server h {:port port})]
      (reset! state {:stop-fn stop-fn :port port})
      (println (str "\nFukan running at http://localhost:" port))
      {:port port})))

(defn stop-server
  "Stop the running server."
  []
  (if-let [{:keys [stop-fn port]} @state]
    (do
      (stop-fn)
      (reset! state nil)
      (println "Server stopped (was on port" (str port ")")))
    (println "No server running")))

(defn running?
  "Returns true if the server is currently running."
  []
  (some? @state))

(defn get-port
  "Returns the port the server is running on, or nil if not running."
  []
  (:port @state))
