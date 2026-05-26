(ns canvas.constraint.phase5-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.constraint.phase5 :as port]
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
    (is (contains? names "constraint.phase5") "module name present")
    (is (contains? names "NonGating") "NonGating invariant present")
    (is (contains? names "PurelyAdditive") "PurelyAdditive invariant present")
    (is (contains? names "EvaluatesAllRegistrations") "EvaluatesAllRegistrations invariant present")
    (is (contains? names "OneViolationPerHeadTuple") "OneViolationPerHeadTuple invariant present")
    (is (contains? names "ViolationLocationBindsHead") "ViolationLocationBindsHead invariant present")
    (is (contains? names "DeterministicAcrossRebuilds") "DeterministicAcrossRebuilds invariant present")
    (is (contains? names "run") "run function present")))
