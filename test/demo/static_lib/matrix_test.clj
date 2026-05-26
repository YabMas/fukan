(ns static-lib.matrix-test
  "Smoke tests for the matrix canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.static-lib.matrix :as matrix]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (matrix/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the static-lib.matrix module"
    (let [db (matrix/build-canvas)
          modules (d/q '[:find [?n ...] :where [?e :entity/type :Module] [?e :entity/name ?n]] db)]
      (is (= ["static-lib.matrix"] modules)))))

(deftest build-canvas-has-matrix-type
  (testing "db contains Matrix3x3 type"
    (let [db (matrix/build-canvas)
          types (d/q '[:find [?n ...] :where [?e :entity/type :Type] [?e :entity/name ?n]] db)]
      (is (contains? (set types) "Matrix3x3")))))

(deftest build-canvas-has-mul-function
  (testing "db contains the mul and determinant affordances"
    (let [db (matrix/build-canvas)
          affs (set (d/q '[:find [?n ...] :where [?e :entity/type :Affordance] [?e :entity/name ?n]] db))]
      (is (contains? affs "mul"))
      (is (contains? affs "determinant")))))
