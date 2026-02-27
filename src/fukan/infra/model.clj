(ns fukan.infra.model
  "Model lifecycle: load, store, and refresh the graph model.
   The :fukan.infra/model Integrant component owns a model-state atom —
   the single source of truth for the current codebase graph.
   Decoupled from the server lifecycle so the model can be refreshed
   (re-analyzed from source) without restarting HTTP."
  (:require [fukan.model.build :as build]
            [fukan.model.languages.clojure :as clj-lang]
            [integrant.core :as ig]
            [clojure.java.io :as io]))

(defn- build-model
  "Build the complete model from Clojure source code."
  [src-path]
  (let [analysis (clj-lang/run-kondo src-path)
        ;; Augment with Integrant config deps (wired via #ig/ref, invisible to static analysis)
        ig-config (some-> (io/resource "fukan/system.edn") slurp)
        analysis (cond-> analysis
                   ig-config (update :namespace-usages
                                     into (clj-lang/extract-integrant-deps ig-config)))
        schema-data (clj-lang/discover-schema-data)
        type-nodes-fn (fn [ns-index]
                        (clj-lang/build-schema-nodes ns-index schema-data))]
    (build/build-model analysis {:type-nodes-fn type-nodes-fn})))

(defn load-model!
  "Build model from src path and store it in the model-state atom.
   Returns the model."
  {:malli/schema [:=> [:cat :atom :string] :Model]}
  [model-state src]
  (println "Analyzing" src "...")
  (let [m (build-model src)]
    (println "Built" (count (:nodes m)) "nodes," (count (:edges m)) "edges")
    (reset! model-state {:model m :src src})
    m))

(defn refresh!
  "Rebuild model from the last src path. Returns the model.
   Returns nil if no src path was previously set."
  {:malli/schema [:=> [:cat :atom] [:maybe :Model]]}
  [model-state]
  (if-let [src (:src @model-state)]
    (load-model! model-state src)
    (do
      (println "No src path set. Use load-model! first.")
      nil)))

(defmethod ig/init-key :fukan.infra/model [_ {:keys [src]}]
  (let [state (atom {:model nil :src nil})]
    (when src
      (load-model! state src))
    state))
