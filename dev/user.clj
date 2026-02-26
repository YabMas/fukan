(ns user
  "Development helpers for REPL-driven workflow."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

;; Initialize clj-reload - tracks src and dev directories
;; The 'user namespace is excluded from reloading
(reload/init
  {:dirs ["src" "dev"]
   :no-reload '#{user}})

(defn- reload-code!
  "Force-reload all loaded namespaces regardless of file timestamps.
   Uses clj-reload's :only :loaded to avoid timestamp detection issues."
  []
  (reload/reload {:only :loaded}))

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
  (if (infra-server/running?)
    (println "Server already running on port" (infra-server/get-port))
    (do
      (infra-model/load-model src)
      (infra-server/start-server {:port port}))))

(defn stop
  "Stop the running server."
  []
  (infra-server/stop-server))

(defn restart
  "Restart the server with the same configuration.
   Force-reloads all code and rebuilds the model."
  []
  (if-let [src (infra-model/get-src)]
    (let [port (or (infra-server/get-port) 8080)]
      (reload-code!)
      (stop)
      (start {:src src :port port}))
    (println "No previous configuration. Use (start {:src \"path\"}) instead.")))

(defn refresh
  "Reload code and rebuild model without restarting server.
   Use this after editing any source files."
  []
  (if (infra-server/running?)
    (do
      (reload-code!)
      (infra-model/refresh-model)
      (println "Refreshed. Browser will see changes on next request."))
    (println "Server not running. Use (start {:src \"path\"}) first.")))

(defn status
  "Print current development environment status."
  []
  (println "Server:" (if (infra-server/running?)
                       (str "running on port " (infra-server/get-port))
                       "stopped"))
  (println "Model:" (if-let [m (infra-model/get-model)]
                      (str (count (:nodes m)) " nodes, "
                           (count (:edges m)) " edges"
                           " (src: " (infra-model/get-src) ")")
                      "not loaded")))

(comment
  ;; Quick start for this project
  (start {:src "src"})
  (stop)
  (restart)

  ;; After changing source code
  (refresh)

  ;; Check what's running
  (status))
