(ns user
  "Development helpers for REPL-driven workflow.

   The HTTP server + web explorer are PAUSED during the lean-kernel rebuild
   (parked under .paused/), so the server-lifecycle helpers are gone. The
   kernel feedback loop is now: build a store with `with-canvas`, query it
   with `d/q`, run constraints — all in-process. `refresh` reloads code and
   rebuilds the held model; `status` reports the model."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
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
  "Build the held model headlessly. Option: :src (default \"src\").
   (The web explorer is paused — parked under .paused/.)"
  [{:keys [src] :or {src "src"}}]
  (infra-model/load-model src))

(defn reset
  "Reload changed code, then rebuild the held model from the last src."
  []
  (reload-code!)
  (if (infra-model/get-src)
    (infra-model/refresh-model)
    (println "No model loaded yet. Use (go) first.")))

(defn refresh
  "Reload changed code + rebuild the held model. Use after editing a spec."
  []
  (reload-code!)
  (if (infra-model/get-src)
    (do (infra-model/refresh-model)
        (println "Refreshed."))
    (println "No model loaded yet. Use (go) first.")))

(defn status []
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
  (reset)
  (refresh)
  (status)
  (load-demo "static-lib")
  (load-demo "event-driven"))
