(ns user
  "Development helpers for REPL-driven workflow.

   The HTTP server + web explorer are PAUSED during the lean-kernel rebuild
   (parked under .paused/), so the server-lifecycle helpers are gone. The
   kernel feedback loop is now: build a store with `with-canvas`, query it
   with `d/q`, run constraints — all in-process. `refresh` reloads code and
   rebuilds the held model; `status` reports the model."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
            ;; loads the model↔code correspondence laws into the dev session so a
            ;; `check`/`(drift)` over the unified held model surfaces drift
            [fukan.target.correspondence :as corr]))

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

(defn drift
  "Model↔code drift in the held (unified) model: modelled Stages with no realizing
   function of the same name. Empty ⇔ the implementation fully realizes every
   modelled capability. (Build with a code-root — `(go)` defaults to \"src\" — so
   the held model carries the extracted code.)"
  []
  (if-let [m (infra-model/get-model)]
    (let [d (corr/unrealized-stages m)]
      (if (empty? d)
        (println "No drift — every modelled Stage is realized in code.")
        (println "Drift —" (count d) "modelled Stage(s) with no realizing function:" d)))
    (println "No model loaded yet. Use (go) first.")))

(defn status []
  (println "Model:" (if-let [m (infra-model/get-model)]
                      (str (count (:primitives m)) " primitives, "
                           (count (:edges m)) " edges"
                           " (src: " (infra-model/get-src) ")")
                      "not loaded")))

(comment
  (go {})
  (reset)
  (refresh)
  (drift)
  (status))
