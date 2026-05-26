(ns canvas.vocabulary.allium.expression-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.vocabulary.allium.expression :as port]
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
    (is (contains? names "vocabulary.allium.expression") "module name present")
    (is (contains? names "PrecedenceOrder") "PrecedenceOrder invariant present")
    (is (contains? names "SupportedPrimaries") "SupportedPrimaries invariant present")
    (is (contains? names "CoversCanonicalisationPatterns") "CoversCanonicalisationPatterns invariant present")
    (is (contains? names "FallbackOnFailure") "FallbackOnFailure invariant present")
    (is (contains? names "PureOfText") "PureOfText invariant present")))
