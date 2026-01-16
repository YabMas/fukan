(ns fukan.infra.model
  "Model lifecycle management.
   Handles loading, storing, and refreshing the model independently
   from the server lifecycle."
  (:require [fukan.model.api :as model]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn load-model
  "Build model from src path and store it. Returns the model."
  [src]
  (println "Analyzing" src "...")
  (let [m (model/build-model src)]
    (println "Built" (count (:nodes m)) "nodes," (count (:edges m)) "edges")
    (reset! state {:model m :src src})
    m))

(defn get-model
  "Get the current model. Returns nil if not loaded."
  []
  (:model @state))

(defn refresh-model
  "Rebuild model from the last src path. Returns the model.
   Returns nil if no src path was previously set."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do
      (println "No src path set. Use load-model first.")
      nil)))

(defn get-src
  "Get the current src path. Returns nil if not loaded."
  []
  (:src @state))
