(ns fukan.infra.model
  "Model lifecycle management.
   Handles loading, storing, and refreshing the model independently
   from the server lifecycle."
  (:require [fukan.model.pipeline :as pipeline]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn load-model
  "Build model from src path and store it. Returns the model."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src]
  (println "Analyzing" src "...")
  (let [m (pipeline/build-model src)]
    (println "Built" (count (:nodes m)) "nodes," (count (:edges m)) "edges")
    (reset! state {:model m :src src})
    m))

(defn get-model
  "Get the current model. Returns nil if not loaded."
  {:malli/schema [:=> [:cat] [:maybe :Model]]}
  []
  (:model @state))

(defn refresh-model
  "Rebuild model from the last src path. Returns the model.
   Returns nil if no src path was previously set."
  {:malli/schema [:=> [:cat] [:maybe :Model]]}
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do
      (println "No src path set. Use load-model first.")
      nil)))

(defn get-src
  "Get the current src path. Returns nil if not loaded."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (:src @state))
