(ns fukan.infra.server
  "Server lifecycle management.
   Handles starting and stopping the HTTP server independently
   from the model lifecycle."
  (:require [fukan.web.handler :as handler]
            [org.httpkit.server :as http]))

(def ^:schema ServerOpts
  [:map {:description "HTTP server configuration."}
   [:port {:optional true, :description "TCP port to bind (default: 8080)."} [:int {:min 1 :max 65535}]]])

(def ^:schema ServerInfo
  [:map {:description "Running server information."}
   [:port [:int {:min 1 :max 65535}]]])

(defonce ^:private state (atom nil))

(defn start-server
  "Start HTTP server.

   Options:
     :port - Server port (default: 8080)"
  {:malli/schema [:=> [:cat :ServerOpts] [:maybe :ServerInfo]]}
  [{:keys [port] :or {port 8080}}]
  (if @state
    (println "Server already running on port" (:port @state))
    (let [h (handler/create-handler)
          stop-fn (http/run-server h {:port port})]
      (reset! state {:stop-fn stop-fn :port port})
      (println (str "\nFukan running at http://localhost:" port))
      {:port port})))

(defn stop-server
  "Stop the running server."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (if-let [{:keys [stop-fn port]} @state]
    (do
      (stop-fn)
      (reset! state nil)
      (println "Server stopped (was on port" (str port ")")))
    (println "No server running")))

(defn running?
  "Returns true if the server is currently running."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? @state))

(defn get-port
  "Returns the port the server is running on, or nil if not running."
  {:malli/schema [:=> [:cat] [:maybe :int]]}
  []
  (:port @state))
