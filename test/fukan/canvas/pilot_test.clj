(ns fukan.canvas.pilot-test
  "Smoke tests for the four Phase 1 pilot ports.
   Each test verifies that build-canvas returns a db with at least one module.
   Verification depth is intentionally shallow — the goal is ergonomic
   stress-test, not functional coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.substrate.store :as store]
            [fukan.canvas.pilot.server :as server]
            [fukan.canvas.pilot.constraint-evaluator :as evaluator]
            [fukan.canvas.pilot.vocabulary-analyzer :as vocab-analyzer]
            [fukan.canvas.pilot.validation-phase4 :as phase4]))

(deftest lifecycle-pilot-builds
  (testing "infra/server canvas port produces a non-empty store"
    (let [db (server/build-canvas)]
      (is (seq (store/all-modules db))
          "Expected at least one module in infra/server canvas")
      (is (= "infra.server"
             (:name (first (store/all-modules db))))
          "Module should be named infra.server"))))

(deftest constraint-pilot-builds
  (testing "constraint/evaluator canvas port produces a non-empty store"
    (let [db (evaluator/build-canvas)]
      (is (seq (store/all-modules db))
          "Expected at least one module in constraint/evaluator canvas")
      (is (= "constraint.evaluator"
             (:name (first (store/all-modules db))))
          "Module should be named constraint.evaluator"))))

(deftest vocabulary-pilot-builds
  (testing "vocabulary/allium/analyzer canvas port produces a non-empty store"
    (let [db (vocab-analyzer/build-canvas)]
      (is (seq (store/all-modules db))
          "Expected at least one module in vocabulary/allium/analyzer canvas")
      (is (= "vocabulary.allium.analyzer"
             (:name (first (store/all-modules db))))
          "Module should be named vocabulary.allium.analyzer"))))

(deftest validation-pilot-builds
  (testing "validation/phase4 canvas port produces a non-empty store"
    (let [db (phase4/build-canvas)]
      (is (seq (store/all-modules db))
          "Expected at least one module in validation/phase4 canvas")
      (is (= "validation.phase4"
             (:name (first (store/all-modules db))))
          "Module should be named validation.phase4"))))
