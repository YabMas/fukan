(ns fukan.infra.model
  "Model lifecycle management.
   Handles loading, storing, and refreshing the model independently
   from the server lifecycle."
  (:require [fukan.model.lint :as lint]
            [fukan.model.build :as build]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.model.analyzers.specification.languages.allium :as allium]))

(defonce ^:private state (atom {:model nil :src nil}))

(def ^:private analyzers
  [clj-lang/analyze allium/analyze])

(defn load-model
  "Build model from src path and store it. Returns the model."
  {:malli/schema [:=> [:cat :FilePath] :Model]}
  [src]
  (println "Analyzing" src "...")
  (let [m (build/build-model src analyzers)
        report (lint/check-contracts m)]
    (println "Built" (count (:nodes m)) "nodes," (count (:edges m)) "edges")
    (when (seq (:violations report))
      (println (lint/format-report report)))
    (reset! state {:model m :src src :lint report})
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
  {:malli/schema [:=> [:cat] [:maybe :FilePath]]}
  []
  (:src @state))
