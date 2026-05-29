(ns fukan.constraint.phase5-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.phase5 :as phase5]
            [fukan.model.build :as build]
            [fukan.model.pipeline :as model-pipeline]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

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

(deftest phase5-runs-one-registered-constraint
  (testing "a registered constraint with a matching rule produces violations"
    (let [reg {:namespace "test" :name "every-module-must-have-orders"
               :severity :error :kind :test
               :message-template "missing orders entity"
               :predicate
               {:head {:predicate :violation :args [:?m]}
                :body [{:kind :atom :predicate :has-tag :args [:?m "Allium::Module"]}
                       {:kind :negation
                        :inner {:kind :atom :predicate :in-module
                                :args [:?o :?m]}}]}}
          model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m" :label "m"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Module"}
                         :target {:case :target/primitive :id "m"}}))
                    (update :predicates (fnil conj []) reg))
          m1 (phase5/run model)
          phase5-vs (filter #(= :phase5 (:phase %)) (:violations m1))]
      (is (= 1 (count phase5-vs)))
      (is (= :error (-> phase5-vs first :severity))))))

(deftest combined-pipeline-with-phase5-runs-cleanly
  (testing "fukan-on-fukan loads through all phases — no errors, Phase 5 silent"
    (let [m (:model (model-pipeline/build-model "src"))]
      (is (map? m))
      (is (contains? m :violations))
      (let [errors    (filter #(= :error (:severity %)) (:violations m))
            phase5-vs (filter #(= :phase5 (:phase %)) (:violations m))]
        (is (empty? errors)
            (str "Pipeline produced unexpected errors: "
                 (pr-str (mapv (juxt :phase :sub-phase :kind :message) errors))))
        (is (zero? (count phase5-vs))
            (str "Phase 5 violations against corpus: "
                 (pr-str (mapv (juxt :kind :message) phase5-vs))))))))
