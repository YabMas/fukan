(ns demo.static-lib.vec3-test
  "Smoke tests for the vec3 canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.static-lib.vec3 :as vec3]
            [fukan.canvas.core.classification :as classification]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (vec3/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the static-lib.vec3 module"
    (let [db (vec3/build-canvas)
          modules (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/module)]
      (is (= ["static-lib.vec3"] modules)))))

(deftest build-canvas-has-vec3-type
  (testing "db contains Vec3 type"
    (let [db (vec3/build-canvas)
          types (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/type)]
      (is (contains? (set types) "Vec3")))))

(deftest build-canvas-has-cross-function
  (testing "db contains the cross affordance"
    (let [db (vec3/build-canvas)
          affs (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/affordance)]
      (is (contains? (set affs) "cross")))))
