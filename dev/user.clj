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

(defn go
  "Start the system: load model and start the web server.

   Options:
     :src  - Path to Clojure source directory (default: \"src\")
     :port - Server port (default: 8080)

   Examples:
     (go)
     (go {:src \"/path/to/project/src\" :port 3000})"
  ([] (go {}))
  ([{:keys [src port] :or {src "src" port 8080}}]
   (if (infra-server/running?)
     (println "Server already running on port" (infra-server/get-port))
     (do
       (infra-model/load-model src)
       (infra-server/start-server {:port port})))))

(defn halt
  "Stop the running server."
  []
  (infra-server/stop-server))

(defn reset
  "Halt the system, force-reload all code, and restart.
   Use after editing any source files."
  []
  (if-let [src (infra-model/get-src)]
    (let [port (or (infra-server/get-port) 8080)]
      (halt)
      (reload-code!)
      (go {:src src :port port}))
    (println "No previous configuration. Use (go) first.")))

(defn refresh-model
  "Reload code and rebuild model without restarting server.
   Use this after editing the codebase being analyzed."
  []
  (if (infra-server/running?)
    (do
      (reload-code!)
      (infra-model/refresh-model)
      (println "Refreshed. Browser will see changes on next request."))
    (println "Server not running. Use (go) first.")))

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
  (go)
  (halt)
  (reset)

  ;; Rebuild model without restarting server
  (refresh-model)

  ;; Check what's running
  (status))
