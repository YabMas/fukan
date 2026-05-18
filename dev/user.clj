(ns user
  "Development helpers for REPL-driven workflow."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

(defonce ^:private _reload-init
  (reload/init {:dirs ["src" "dev"], :no-reload '#{user}}))

(defn- reload-code! []
  (let [result (reload/reload {:only :loaded})]
    (when (seq (:loaded result))
      (println "Reloaded:" (count (:loaded result)) "namespaces")
      (doseq [ns-sym (:loaded result)] (println " " ns-sym)))
    (when (seq (:unloaded result)) (println "Unloaded:" (:unloaded result)))
    result))

(defn go
  "Start the system. Options: :src (default \"src\"), :port (default 8080)."
  [{:keys [src port] :or {src "src" port 8080}}]
  (if (infra-server/running?)
    (println "Server already running on port" (infra-server/get-port))
    (do
      (infra-model/load-model src)
      (infra-server/start-server {:port port}))))

(defn halt [] (infra-server/stop-server))

(defn reset []
  (if-let [src (infra-model/get-src)]
    (let [port (or (infra-server/get-port) 8080)]
      (halt)
      (reload-code!)
      (go {:src src :port port}))
    (println "No previous configuration. Use (go) first.")))

(defn refresh []
  (if (infra-server/running?)
    (do (reload-code!) (infra-model/refresh-model)
        (println "Refreshed. Browser will see changes on next request."))
    (println "Server not running. Use (go) first.")))

(defn status []
  (println "Server:" (if (infra-server/running?)
                       (str "running on port " (infra-server/get-port))
                       "stopped"))
  (println "Model:" (if-let [m (infra-model/get-model)]
                      (str (count (:primitives m)) " primitives, "
                           (count (:edges m)) " edges"
                           " (src: " (infra-model/get-src) ")")
                      "not loaded")))

(comment
  (go {})
  (halt)
  (reset)
  (refresh)
  (status))
