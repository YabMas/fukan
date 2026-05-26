(ns canvas.vocabulary.allium.pipeline-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.allium.pipeline :as port]
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
    (is (contains? names "vocabulary.allium.pipeline") "module name present")
    (is (contains? names "DeterministicFileOrder") "DeterministicFileOrder invariant present")
    (is (contains? names "PathCanonicalisation") "PathCanonicalisation invariant present")
    (is (contains? names "DefaultsRegisteredBeforeAnalysis") "DefaultsRegisteredBeforeAnalysis invariant present")
    (is (contains? names "StubUnification") "StubUnification invariant present")
    (is (contains? names "AmbiguousStubsLeftAlone") "AmbiguousStubsLeftAlone invariant present")
    (is (contains? names "InlineLiftIdempotence") "InlineLiftIdempotence invariant present")
    (is (contains? names "PipelinePurity") "PipelinePurity invariant present")))
