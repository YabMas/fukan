(ns canvas.validation.rules-4a-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.validation.rules-4a :as port]
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
    (is (contains? names "validation.rules-4a") "module name present")
    (is (contains? names "check") "checker present")
    (is (contains? names "AtMostOneCompositeParent") "invariant present")
    (is (contains? names "NoSubsystemCompositionCycles") "cycle invariant present")
    (is (contains? names "CheckIsPure") "purity invariant present")))
