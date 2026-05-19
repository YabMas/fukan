(ns fukan.validation.rules-4c-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4c :as r4c]
            [fukan.model.build :as build]))

(deftest unresolved-operation-becomes-violation
  (let [model (assoc-in (build/empty-model)
                        [:phase4-state :binding-issues]
                        [{:kind :unresolved-operation
                          :coord "m" :form :foreign-attach :alias "x"}])
        violations (r4c/check model)
        relevant (filter #(= :4c/unresolved-operation (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest unresolved-trigger-rule-becomes-violation
  (let [model (assoc-in (build/empty-model)
                        [:phase4-state :binding-issues]
                        [{:kind :unresolved-trigger-rule
                          :op "m::f" :trigger {:kind :local :name "R"}}])
        violations (r4c/check model)
        relevant (filter #(= :4c/unresolved-trigger-rule (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest attach-returns-without-triggers-is-warning
  (let [model (assoc-in (build/empty-model)
                        [:phase4-state :binding-issues]
                        [{:kind :attach-returns-without-triggers
                          :coord "m" :form :local-attach
                          :contract "C" :op "o"}])
        violations (r4c/check model)
        relevant (filter #(= :4c/attach-returns-without-triggers (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :warning (-> relevant first :severity)))))

(deftest clean-model-has-no-4c-violations
  (let [model (build/empty-model)
        violations (r4c/check model)]
    (is (empty? violations))))
