(ns demo.static-lib.operations-test
  "Smoke tests for the operations canvas port."
  (:require [clojure.test :refer [deftest is testing]]
            [demo.static-lib.operations :as ops]
            [datascript.core :as d]))

(deftest build-canvas-loads
  (testing "build-canvas returns a non-nil Datascript db"
    (is (some? (ops/build-canvas)))))

(deftest build-canvas-has-module
  (testing "db contains the static-lib.operations module"
    (let [db (ops/build-canvas)
          modules (d/q '[:find [?n ...] :where [?e :entity/type :Module] [?e :entity/name ?n]] db)]
      (is (= ["static-lib.operations"] modules)))))

(deftest build-canvas-has-expected-functions
  (testing "db contains all 6 cross-cutting operations"
    (let [db (ops/build-canvas)
          affs (set (d/q '[:find [?n ...] :where [?e :entity/type :Affordance] [?e :entity/name ?n]] db))]
      (is (contains? affs "apply_transform_to_vec3"))
      (is (contains? affs "project_vec3_to_vec2"))
      (is (contains? affs "lerp_vec2"))
      (is (= 6 (count affs))))))

(deftest build-canvas-emits-cross-module-refs
  (testing "db emits :references relations to cross-module types"
    (let [db (ops/build-canvas)
          refs (set (map first (d/q '[:find ?t :where [_ :references ?t]] db)))]
      (is (contains? refs :static-lib.vec3/Vec3))
      (is (contains? refs :static-lib.vec2/Vec2)))))
