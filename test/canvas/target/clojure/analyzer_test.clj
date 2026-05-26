(ns canvas.target.clojure.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.target.clojure.analyzer :as port]
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
      (is (contains? names "target.clojure.analyzer")))
    (testing "invariants present"
      (is (contains? names "NonGating"))
      (is (contains? names "PurelyAdditive"))
      (is (contains? names "ProjectsEdgeValidity"))
      (is (contains? names "MaterialisesUnprojected"))
      (is (contains? names "DuplicateAddressViolations"))
      (is (contains? names "FunctionPrivacyDerivation")))
    (testing "function present"
      (is (contains? names "run")))))
