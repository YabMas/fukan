(ns fukan.model.pipeline
  "Top-level model build orchestration.
   Coordinates language analyzers and feeds their merged analysis results
   through the language-agnostic build pipeline."
  (:require [fukan.model.build :as build]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.model.analyzers.specification.languages.allium :as allium]))

(defn build-model
  "Build complete model from a source path.
   Runs all language analyzers, merges results, and produces the final Model."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src-path]
  (let [clj-result    (clj-lang/analyze src-path)
        allium-result (allium/analyze src-path)
        merged        (build/merge-results clj-result allium-result)]
    (build/build-model merged)))
