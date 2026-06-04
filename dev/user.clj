(ns user
  "Development helpers for REPL-driven workflow.

   The HTTP server + web explorer are PAUSED during the lean-kernel rebuild
   (parked under .paused/), so the server-lifecycle helpers are gone. The
   kernel feedback loop is now: build a store with `with-canvas`, query it
   with `d/q`, run constraints — all in-process. `refresh` reloads code and
   rebuilds the held model; `status` reports the model."
  (:require [clojure.string :as str]
            [clj-reload.core :as reload]
            [datascript.core :as d]
            [fukan.infra.model :as infra-model]
            [fukan.canvas.projection.probes :as probe]
            [fukan.model.materialize :as mat]
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

(defn probes
  "Run the implemented probes against the held model, printing each finding.
   patterns → recurring structures (a View); integrity → law violations (a gating
   Signal: empty ⇔ the model's laws all hold)."
  []
  (if-let [m (infra-model/get-model)]
    (doseq [[nm finding] (probe/run-all m)]
      (println (str "── probe " nm " (gating " (:gating finding) ") ──"))
      (if (empty? (:finding finding))
        (println "   (nothing)")
        (doseq [x (:finding finding)] (println "  •" x))))
    (println "No model loaded yet. Use (go) first.")))

(defn materialize
  "Project the implementation spec for module `module-name` from the held model:
   compose the render of every Stage that module owns (its :calls/Effects/Shapes
   inline). E.g. (materialize \"target.clojure\")."
  [module-name]
  (if-let [m (infra-model/get-model)]
    (let [spec (mat/materialize-module m module-name)]
      (if (str/blank? spec)
        (println "No Stages found in module" (pr-str module-name))
        (println spec)))
    (println "No model loaded yet. Use (go) first.")))

(defn status []
  (if-let [m (infra-model/get-model)]
    (println "Model:"
             (count (d/q '[:find ?e :where [?e :structure/of _]] m)) "structures,"
             (count (d/q '[:find ?r :where [?r :rel/kind _]] m)) "relations"
             (str "(src: " (infra-model/get-src) ")"))
    (println "Model: not loaded")))

(comment
  (go {})
  (reset)
  (refresh)
  (drift)
  (probes)
  (materialize "target.clojure")
  (status))
