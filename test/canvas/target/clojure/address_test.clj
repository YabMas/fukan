(ns canvas.target.clojure.address-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.target.clojure.address :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db    (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (testing "module present"
      (is (contains? names "target.clojure.address")))
    (testing "value type present"
      (is (contains? names "CanonicalAddress")))
    (testing "invariants present"
      (is (contains? names "DeterministicResolution"))
      (is (contains? names "RootPrefixHonoured"))
      (is (contains? names "ProjectionKindPartition"))
      (is (contains? names "TestProjectionSuffix")))
    (testing "functions present"
      (is (contains? names "module_ns"))
      (is (contains? names "local_name"))
      (is (contains? names "canonical")))))
