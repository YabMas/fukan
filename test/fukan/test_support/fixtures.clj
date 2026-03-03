(ns fukan.test-support.fixtures
  "Shared test fixtures: default analyzer configuration and helpers
   for building models in tests."
  (:require [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.model.analyzers.specification.languages.allium :as allium]
            [fukan.model.build :as build]))

(def analyzers
  "Standard analyzer list for testing."
  [clj-lang/analyze allium/analyze])

(defn build-self-model
  "Build a model from Fukan's own source using default analyzers."
  []
  (build/build-model "src" analyzers))
