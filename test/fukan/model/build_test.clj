(ns fukan.model.build-test
  "Generative + integration tests for the model build pipeline.
   Verifies that all model invariants from model.allium hold across
   randomly generated contributions and Fukan's own source code."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [fukan.model.build :as build]
            [fukan.test-support.generators :as gen]
            [fukan.test-support.invariants.model :as inv]))

;; ---------------------------------------------------------------------------
;; Generative: build-model satisfies all model invariants

(defspec build-model-satisfies-all-invariants 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/valid-model? model)))))

;; ---------------------------------------------------------------------------
;; Individual invariant defspecs for clearer failure messages

(defspec build-model-tree-structure 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/tree-structure? model)))))

(defspec build-model-leaf-strictness 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/leaf-strictness? model)))))

(defspec build-model-no-empty-modules 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/no-empty-modules? model)))))

(defspec build-model-no-self-edges 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/no-self-edges? model)))))

(defspec build-model-edge-integrity 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/edge-integrity? model)))))

(defspec build-model-no-unconsumed-provides 100
  (prop/for-all [contrib (gen/gen-analysis-result)]
    (let [model (build/run-pipeline contrib)]
      (true? (inv/no-unconsumed-provides? model)))))

;; ---------------------------------------------------------------------------
;; Integration: Fukan self-analysis satisfies all model invariants

(deftest fukan-self-analysis-invariants
  (testing "Fukan's own source code satisfies all model invariants"
    (let [model (build/build-model "src")]
      (is (pos? (count (:nodes model))) "model should have nodes")
      (is (pos? (count (:edges model))) "model should have edges")
      (is (true? (inv/tree-structure? model)) (str (inv/tree-structure? model)))
      (is (true? (inv/leaf-strictness? model)) (str (inv/leaf-strictness? model)))
      (is (true? (inv/schema-replaces-function? model)) (str (inv/schema-replaces-function? model)))
      (is (true? (inv/no-empty-modules? model)) (str (inv/no-empty-modules? model)))
      (is (true? (inv/no-self-edges? model)) (str (inv/no-self-edges? model)))
      (is (true? (inv/edge-integrity? model)) (str (inv/edge-integrity? model)))
      (is (true? (inv/smart-root-pruning? model)) (str (inv/smart-root-pruning? model)))
      (is (true? (inv/no-unconsumed-provides? model)) (str (inv/no-unconsumed-provides? model))))))
