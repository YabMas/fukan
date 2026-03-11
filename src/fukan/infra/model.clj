(ns fukan.infra.model
  "Model lifecycle management.
   Handles loading, storing, and refreshing the model independently
   from the server lifecycle."
  (:require [fukan.model.lint :as lint]
            [fukan.model.build :as build]
            ;; Bare requires for defmethod registration side effects
            fukan.model.analyzers.implementation.languages.clojure
            fukan.model.analyzers.specification.languages.allium))

(defonce ^:private state (atom {:model nil :src nil :analyzers nil}))

(defn load-model
  "Build model from src path and store it. Returns the model."
  {:malli/schema [:=> [:cat :FilePath [:set :AnalyzerKey]] :Model]}
  [src analyzers]
  (println "Analyzing" src "with" analyzers "...")
  (let [m (build/build-model src analyzers)
        report (lint/check-contracts m)]
    (println "Built" (count (:nodes m)) "nodes," (count (:edges m)) "edges")
    (when (seq (:violations report))
      (println (lint/format-report report)))
    (reset! state {:model m :src src :analyzers analyzers :lint report})
    m))

(defn get-model
  "Get the current model. Returns nil if not loaded."
  {:malli/schema [:=> [:cat] [:maybe :Model]]}
  []
  (:model @state))

(defn refresh-model
  "Rebuild model from the last src path and analyzers. Returns the model.
   Returns nil if no src path was previously set."
  {:malli/schema [:=> [:cat] [:maybe :Model]]}
  []
  (let [{:keys [src analyzers]} @state]
    (if src
      (load-model src analyzers)
      (do
        (println "No src path set. Use load-model first.")
        nil))))

(defn get-src
  "Get the current src path. Returns nil if not loaded."
  {:malli/schema [:=> [:cat] [:maybe :FilePath]]}
  []
  (:src @state))

(defn get-analyzers
  "Get the current analyzer set. Returns nil if not loaded."
  {:malli/schema [:=> [:cat] [:maybe [:set :AnalyzerKey]]]}
  []
  (:analyzers @state))
