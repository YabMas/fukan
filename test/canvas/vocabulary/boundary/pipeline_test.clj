(ns canvas.vocabulary.boundary.pipeline-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.boundary.pipeline :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "vocabulary.boundary.pipeline") "module name present")
    (is (contains? names "DeterministicFileOrder") "DeterministicFileOrder invariant present")
    (is (contains? names "PathCanonicalisation") "PathCanonicalisation invariant present")
    (is (contains? names "DefaultsRegisteredBeforeAnalysis") "DefaultsRegisteredBeforeAnalysis invariant present")
    (is (contains? names "ComposesOntoAllium") "ComposesOntoAllium invariant present")
    (is (contains? names "PipelinePurity") "PipelinePurity invariant present")))
