(ns fukan.model.effect-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.effect :as fx]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest write-effect
  (let [tgt {:case :endpoint/substrate
             :container "order"
             :path [{:slot "field" :key "total"}]}
        v   (e/make-apply "+" [(e/make-var "pre.order.total")
                                (e/make-var "delta")])
        fx  (fx/make-effect :effect/write tgt v "expr-1")]
    (is (= :effect/write (:kind fx)))
    (is (= tgt (:target fx)))
    (is (= "expr-1" (:source fx)))
    (is (m/validate fx/Effect fx))))

(deftest create-effect
  (let [fx (fx/make-effect :effect/create
                            {:case :endpoint/primitive :id "order"}
                            (e/make-var "params.order")
                            "expr-1")]
    (is (= :effect/create (:kind fx)))
    (is (m/validate fx/Effect fx))))

(deftest destroy-effect-has-no-value
  (let [fx (fx/make-effect :effect/destroy
                            {:case :endpoint/primitive :id "order"}
                            nil
                            "expr-1")]
    (is (= :effect/destroy (:kind fx)))
    (is (nil? (:value fx)))
    (is (m/validate fx/Effect fx))))

(deftest emit-effect
  (let [fx (fx/make-effect :effect/emit
                            {:case :endpoint/primitive :id "interview/slot-confirmed"}
                            (e/make-apply "tuple" [(e/make-var "params.viewer")
                                                    (e/make-var "params.slot")])
                            "expr-2")]
    (is (= :effect/emit (:kind fx)))
    (is (m/validate fx/Effect fx))))

(deftest identity-stable-over-value-rewrite
  (testing "(rule-id, kind, target) — independent of :value content"
    (let [tgt {:case :endpoint/substrate
               :container "order"
               :path [{:slot "field" :key "total"}]}
          a (fx/make-effect :effect/write tgt
              (e/make-apply "+" [(e/make-var "pre.order.total") (e/make-lit (t/make-scalar "Integer") 1)])
              "expr-A")
          b (fx/make-effect :effect/write tgt
              (e/make-apply "+" [(e/make-lit (t/make-scalar "Integer") 1) (e/make-var "pre.order.total")])
              "expr-B")]
      (is (= (fx/effect-identity "rule-1" a)
             (fx/effect-identity "rule-1" b)))
      (is (not= (fx/effect-identity "rule-1" a)
                (fx/effect-identity "rule-2" a))))))

(deftest identity-differs-on-target
  (let [a (fx/make-effect :effect/write
            {:case :endpoint/substrate :container "order" :path [{:slot "field" :key "total"}]}
            (e/make-var "x") "e1")
        b (fx/make-effect :effect/write
            {:case :endpoint/substrate :container "order" :path [{:slot "field" :key "status"}]}
            (e/make-var "x") "e1")]
    (is (not= (fx/effect-identity "rule-1" a) (fx/effect-identity "rule-1" b)))))

(deftest canonicalise-is-deferred-to-plan-2
  (testing "Plan 1 ships only the data shape; canonicalisation lives in Plan 2"
    (is (nil? (fx/canonicalise (e/make-var "post.X.f"))))))
