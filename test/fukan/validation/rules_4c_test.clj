(ns fukan.validation.rules-4c-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.validation.rules-4c :as r4c]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]))

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

(deftest signature-match-uncertain-fires-when-rule-lacks-events
  (testing "binding to a Rule with no event-shaped when: clause and op with params produces signature-match-uncertain warning"
    (let [model (-> (build/empty-model)
                    (build/add-primitive
                      (p/make-operation
                        {:id "m::Contract.op" :label "op"
                         :parameters [(p/make-parameter "x"
                                                        (t/make-scalar "Integer")
                                                        false 0)]}))
                    (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                    (build/add-edge
                      (r/make-edge :relation/triggers
                                   (r/primitive-ref "m::Contract.op")
                                   (r/primitive-ref "m::R"))))
          violations (r4c/check model)
          relevant (filter #(= :4c/signature-match-uncertain (:kind %)) violations)]
      (is (= 1 (count relevant)))
      (is (= :warning (-> relevant first :severity))))))

(deftest signature-match-silent-on-zero-arg-operation
  (testing "binding to a zero-param Operation does NOT trigger signature-match warning"
    (let [model (-> (build/empty-model)
                    (build/add-primitive
                      (p/make-operation
                        {:id "m::Contract.zero_arg" :label "zero_arg"
                         :parameters []}))
                    (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                    (build/add-edge
                      (r/make-edge :relation/triggers
                                   (r/primitive-ref "m::Contract.zero_arg")
                                   (r/primitive-ref "m::R"))))
          violations (r4c/check model)
          relevant (filter #(= :4c/signature-match-uncertain (:kind %)) violations)]
      (is (empty? relevant)
          "zero-arg operations don't need signature-match warning"))))
