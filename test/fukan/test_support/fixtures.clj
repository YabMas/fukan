(ns fukan.test-support.fixtures
  "Shared test fixtures: default analyzer configuration and helpers
   for building models in tests."
  (:require [fukan.model.build :as build]
            ;; Bare requires for defmethod registration side effects
            fukan.model.analyzers.implementation.languages.clojure
            fukan.model.analyzers.specification.languages.allium))

(defn build-self-model
  "Build a model from Fukan's own source using both analyzers."
  []
  (build/build-model "src" #{:clojure :allium}))
