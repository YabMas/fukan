(ns fukan.target.clojure.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.pipeline :as model-pipeline]))

(deftest analyzer-on-empty-model-passes
  (testing "an empty model with empty registry produces no changes"
    (let [m (analyzer/run (build/empty-model) (registry/make-registry) nil)]
      (is (map? m))
      ;; Empty model has no primitives, so no projects edges produced.
      (is (= [] (:edges m))))))

(deftest combined-pipeline-with-phase6-runs-cleanly
  (testing "fukan-on-fukan loads through all phases (4–6, canvas is sole spec source)"
    (let [m (model-pipeline/build-model "src")]
      (is (map? m))
      (is (contains? m :violations))
      (let [errors (filter #(= :error (:severity %)) (:violations m))]
        (is (empty? errors)
            (str "Phase 4/5/6 produced unexpected errors: "
                 (pr-str (mapv (juxt :phase :sub-phase :kind :message) errors))))))))
