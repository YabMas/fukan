(ns fukan.constraint.phase5-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.phase5 :as phase5]
            [fukan.model.build :as build]
            [fukan.model.pipeline :as model-pipeline]))

(deftest phase5-on-empty-model-passes
  (testing "an empty model produces no Phase 5 violations"
    (let [m (phase5/run (build/empty-model))]
      (is (map? m))
      (is (= [] (vec (or (:violations m) [])))))))

(deftest phase5-attaches-violations-non-destructively
  (testing "Phase 5 preserves existing Phase 4 violations"
    (let [m0 (assoc (build/empty-model)
                    :violations [{:phase :phase4 :sub-phase :4a :kind :test/x
                                  :severity :warning :message "synthetic"}])
          m1 (phase5/run m0)]
      (is (= 1 (count (:violations m1)))
          "Phase 5 stub didn't strip existing violations"))))

(deftest combined-pipeline-with-phase5-runs-cleanly
  (testing "fukan-on-fukan loads through Allium + Boundary + Phase 4 + Phase 5"
    (let [m (model-pipeline/load-source "src")]
      (is (map? m))
      (is (contains? m :violations))
      ;; Until Task 10 wires Phase 5 into the pipeline, :violations only
      ;; contains Phase 4 entries.
      (let [errors (filter #(= :error (:severity %)) (:violations m))]
        (is (empty? errors) "no errors expected against current corpus")))))
