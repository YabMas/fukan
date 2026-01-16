(ns user
  "Development helpers for REPL-driven workflow."
  (:require [clj-reload.core :as reload]
            [fukan.model.api :as model]
            [fukan.schema :as schema]
            [fukan.web.handler :as handler]
            [org.httpkit.server :as http]))

;; Initialize clj-reload - tracks src and dev directories
;; The 'user namespace is excluded from reloading
(reload/init
  {:dirs ["src" "dev"]
   :no-reload '#{user}})

(defonce ^:private server-state (atom nil))

(defn start
  "Start the web server.

   Options:
     :src  - Path to Clojure source directory (required)
     :port - Server port (default: 8080)

   Examples:
     (start {:src \"src\"})
     (start {:src \"/path/to/project/src\" :port 3000})"
  [{:keys [src port] :or {port 8080}}]
  (when-not src
    (throw (ex-info "Missing required :src option" {})))
  (if (:server @server-state)
    (println "Server already running on port" (:port @server-state))
    (do
      (println "Analyzing" src "...")
      (let [m (model/build-model src)
            _ (println "Built" (count (:nodes m)) "nodes," (count (:edges m)) "edges")
            _ (schema/clear-schemas!)
            _ (schema/discover-schemas!)
            h (handler/create-handler m)
            server (http/run-server h {:port port})]
        (reset! server-state {:server server
                              :port port
                              :src src
                              :model m})
        (println (str "\nFukan running at http://localhost:" port))))))

(defn stop
  "Stop the running server."
  []
  (if-let [{:keys [server port]} @server-state]
    (do
      (server) ; http-kit stop fn
      (reset! server-state nil)
      (println "Server stopped (was on port" (str port ")")))
    (println "No server running")))

(defn restart
  "Restart the server with the same configuration.
   Reloads the analysis from disk."
  []
  (if-let [{:keys [src port]} @server-state]
    (do
      (stop)
      (start {:src src :port port}))
    (println "No previous configuration. Use (start {:src \"path\"}) instead.")))

(comment
  ;; Quick start for this project
  (start {:src "src"})
  (stop)
  (restart))
