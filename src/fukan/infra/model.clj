(ns fukan.infra.model
  "Model lifecycle management.
   Handles loading, storing, and refreshing the model independently
   from the server lifecycle."
  (:require [fukan.model.build :as build]
            [fukan.model.languages.clojure :as clj-lang]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn- build-model
  "Build the complete model from Clojure source code."
  [src-path]
  (let [analysis (clj-lang/run-kondo src-path)
        schema-data (clj-lang/discover-schema-data)
        type-nodes-fn (fn [ns-index]
                        (clj-lang/build-schema-nodes ns-index schema-data))]
    (build/build-model analysis {:type-nodes-fn type-nodes-fn})))

(defn load-model
  "Build model from src path and store it. Returns the model."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src]
  (println "Analyzing" src "...")
  (let [m (build-model src)]
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
