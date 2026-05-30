(ns demo.static-lib.vec2-test
  "Smoke tests for the vec2 canvas port.
   Load + structural non-empty checks only."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.static-lib.vec2 :as vec2]
            [fukan.canvas.core.classification :as classification]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (let [db (vec2/build-canvas)]
      (is (some? db)))))

(deftest build-canvas-has-module
  (testing "db contains the static-lib.vec2 module"
    (let [db (vec2/build-canvas)
          modules (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/module)]
      (is (= ["static-lib.vec2"] modules)))))

(deftest build-canvas-has-vec2-type
  (testing "db contains the Vec2 type"
    (let [db (vec2/build-canvas)
          types (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/type)]
      (is (contains? (set types) "Vec2")))))

(deftest build-canvas-has-functions
  (testing "db contains at least 4 affordances (functions)"
    (let [db (vec2/build-canvas)
          affs (d/q '[:find [?n ...] :in $ % ?fam :where (kind-of ?e ?fam) [?e :entity/name ?n]] db classification/rules :family/affordance)]
      (is (>= (count affs) 4)))))
