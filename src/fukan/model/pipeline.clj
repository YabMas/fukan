(ns fukan.model.pipeline
  "Top-level model build orchestration.
   Coordinates language analyzers and feeds their merged contributions
   through the language-agnostic build pipeline. This namespace exists
   to break the circular dependency: build.clj cannot require language
   namespaces because they depend on build helpers."
  (:require [fukan.model.build :as build]
            [fukan.model.languages.clojure :as clj-lang]
            [fukan.model.languages.allium.analyzer :as allium]))

(defn build-model
  "Build complete model from a source path.
   Runs all language analyzers, merges contributions, discovers schemas,
   and produces the final Model."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src-path]
  (let [clj-contrib    (clj-lang/contribution src-path)
        allium-contrib (allium/allium-contribution src-path)
        contrib        (build/merge-contributions clj-contrib allium-contrib)
        schema-data    (clj-lang/discover-schema-data)
        type-nodes-fn  (fn [ns-index]
                         (clj-lang/build-schema-nodes ns-index schema-data))]
    (build/build-model contrib {:type-nodes-fn type-nodes-fn})))
