(ns user
  "Development helpers for REPL-driven workflow."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]
            [fukan.canvas.projection.canvas-source :as canvas-source]))

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

(def ^:private demo-namespaces
  {"event-driven" '[demo.event-driven.cart
                    demo.event-driven.order
                    demo.event-driven.payment
                    demo.event-driven.shipping
                    demo.event-driven.notification]
   "static-lib"   '[demo.static-lib.vec2
                    demo.static-lib.vec3
                    demo.static-lib.matrix
                    demo.static-lib.transform
                    demo.static-lib.operations]})

(defn load-demo
  "Load a demo paradigm into a fresh canvas db. Returns a map with :db and :modules.
   Requires that the :demo alias is active OR the demo namespaces are on the classpath.
   (Start REPL with: clj -M:dev:demo)

   Usage:
     (load-demo \"static-lib\")
     (load-demo \"event-driven\")"
  [name]
  (let [ns-syms (or (get demo-namespaces name)
                    (throw (ex-info (str "Unknown demo: " name
                                        ". Available: " (keys demo-namespaces))
                                    {:name name})))
        per-module-dbs (mapv (fn [ns-sym]
                               (require ns-sym)
                               (let [build-fn (ns-resolve (the-ns ns-sym) 'build-canvas)]
                                 (when-not build-fn
                                   (throw (ex-info (str "No build-canvas fn in " ns-sym)
                                                   {:namespace ns-sym})))
                                 (build-fn)))
                             ns-syms)
        unified-db (canvas-source/merge-module-dbs per-module-dbs)]
    {:db      unified-db
     :modules (count ns-syms)
     :name    name}))

(comment
  (go {})
  (halt)
  (reset)
  (refresh)
  (status)
  (load-demo "static-lib")
  (load-demo "event-driven"))
