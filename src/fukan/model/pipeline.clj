(ns fukan.model.pipeline
  "Top-level model build orchestration.
   Coordinates language analyzers and feeds their merged analysis results
   through the language-agnostic build pipeline. This namespace exists
   to break the circular dependency: build.clj cannot require language
   namespaces because they depend on build helpers."
  (:require [fukan.model.build :as build]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.model.analyzers.specification.languages.allium :as allium]))

(defn build-model
  "Build complete model from a source path.
   Runs all language analyzers, merges results, discovers schemas,
   and produces the final Model."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src-path]
  (let [clj-result     (clj-lang/contribution src-path)
        allium-result  (allium/allium-contribution src-path)
        result         (build/merge-results clj-result allium-result)
        schema-data    (clj-lang/discover-schema-data)
        ;; Enrich result nodes with runtime metadata before build
        enriched       (-> result
                           (update :nodes clj-lang/enrich-with-runtime-metadata schema-data)
                           (update :nodes clj-lang/resolve-contracts))
        type-nodes-fn  (fn [ns-index]
                         (clj-lang/build-schema-nodes ns-index schema-data))]
    (build/build-model enriched {:type-nodes-fn type-nodes-fn})))
