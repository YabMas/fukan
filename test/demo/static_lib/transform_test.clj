(ns demo.static-lib.transform-test
  "Smoke tests for the transform canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.static-lib.transform :as transform]
            [fukan.canvas.core.classification :as classification]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (transform/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the static-lib.transform module"
    (let [db (transform/build-canvas)
          modules (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/module)]
      (is (= ["static-lib.transform"] modules)))))

(deftest build-canvas-has-transform-type
  (testing "db contains Transform type"
    (let [db (transform/build-canvas)
          types (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/type)]
      (is (contains? (set types) "Transform")))))

(deftest build-canvas-has-compose-and-invert
  (testing "db contains compose and invert affordances"
    (let [db (transform/build-canvas)
          affs (set (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/affordance))]
      (is (contains? affs "compose"))
      (is (contains? affs "invert")))))
