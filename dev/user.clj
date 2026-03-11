(ns user
  "Development helpers for REPL-driven workflow."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

;; Initialize clj-reload once — defonce prevents re-init when the
;; user reloads this buffer, which would reset the change baseline
;; and cause reload/reload to miss file changes.
(defonce ^:private _reload-init
  (reload/init
    {:dirs ["src" "dev"]
     :no-reload '#{user}}))

(defn- reload-code!
  "Reload all loaded namespaces that have changed on disk.
   Prints what was reloaded so failures are visible."
  []
  (let [result (reload/reload {:only :loaded})]
    (when (seq (:loaded result))
      (println "Reloaded:" (count (:loaded result)) "namespaces")
      (doseq [ns-sym (:loaded result)]
        (println " " ns-sym)))
    (when (seq (:unloaded result))
      (println "Unloaded:" (:unloaded result)))
    result))

(defn go
  "Start the system: load model and start the web server.

   Options:
     :src       - Path to source directory (default: \"src\")
     :port      - Server port (default: 8080)
     :analyzers - Set of analyzer keys (required, e.g. #{:clojure :allium})

   Examples:
     (go {:analyzers #{:clojure :allium}})
     (go {:src \"/path/to/project/src\" :port 3000 :analyzers #{:clojure}})"
  [{:keys [src port analyzers] :or {src "src" port 8080}}]
  (assert analyzers ":analyzers is required (e.g. #{:clojure :allium})")
  (if (infra-server/running?)
    (println "Server already running on port" (infra-server/get-port))
    (do
      (infra-model/load-model src analyzers)
      (infra-server/start-server {:port port}))))

(defn halt
  "Stop the running server."
  []
  (infra-server/stop-server))

(defn reset
  "Halt the system, force-reload all code, and restart.
   Use after editing any source files."
  []
  (if-let [src (infra-model/get-src)]
    (let [port (or (infra-server/get-port) 8080)
          analyzers (infra-model/get-analyzers)]
      (halt)
      (reload-code!)
      (go {:src src :port port :analyzers analyzers}))
    (println "No previous configuration. Use (go) first.")))

(defn refresh
  "Reload code and rebuild model without restarting server.
   Use this after editing source files.
   Note: route/handler changes require (reset) instead."
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
  (go {:analyzers #{:clojure :allium}})
  (halt)
  (reset)

  ;; Rebuild model without restarting server
  (refresh)

  ;; Check what's running
  (status))
