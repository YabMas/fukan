(ns fukan.infra.model
  "Model lifecycle: hold the structure substrate db (the model — design decision
   (ii)), offering load / refresh / get. `load-model` invokes the build pipeline,
   which ingests the defstructure canvas specs into one structure db."
  (:require [datascript.core :as d]
            [fukan.model.pipeline :as pipeline]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn load-model
  "Build (or reload) the model — the merged structure substrate db — for `src`."
  [src]
  (let [db (pipeline/build-model src)]
    (reset! state {:model db :src src})
    (println "Loaded model:"
             (count (d/q '[:find ?e :where [?e :structure/of _]] db)) "structures,"
             (count (d/q '[:find ?r :where [?r :rel/kind _]] db)) "relations")
    db))

(defn get-model
  "The current model (structure substrate db), or nil if not loaded."
  []
  (:model @state))

(defn get-src [] (:src @state))

(defn refresh-model
  "Rebuild the model from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))

(defn set-model-for-test!
  "Test helper — directly set the held model. Never call from production code."
  [m]
  (reset! state {:model m :src "test"}))
