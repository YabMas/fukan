(ns demo.static-lib.transform-test
  "Smoke tests for the transform canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.static-lib.transform :as transform]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (transform/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the static-lib.transform module"
    (let [db (transform/build-canvas)
          modules (d/q '[:find [?n ...] :where [?e :entity/type :Module] [?e :entity/name ?n]] db)]
      (is (= ["static-lib.transform"] modules)))))

(deftest build-canvas-has-transform-type
  (testing "db contains Transform type"
    (let [db (transform/build-canvas)
          types (d/q '[:find [?n ...] :where [?e :entity/type :Type] [?e :entity/name ?n]] db)]
      (is (contains? (set types) "Transform")))))

(deftest build-canvas-has-compose-and-invert
  (testing "db contains compose and invert affordances"
    (let [db (transform/build-canvas)
          affs (set (d/q '[:find [?n ...] :where [?e :entity/type :Affordance] [?e :entity/name ?n]] db))]
      (is (contains? affs "compose"))
      (is (contains? affs "invert")))))
