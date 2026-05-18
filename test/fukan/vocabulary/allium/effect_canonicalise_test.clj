(ns fukan.vocabulary.allium.effect-canonicalise-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.effect-canonicalise :as ec]
            [fukan.vocabulary.allium.expression :as ae]
            [fukan.model.effect :as fx]))

(deftest write-pattern
  (testing "post.X.f = E becomes Write effect"
    (let [expr (ae/parse "post.order.total = pre.order.total + amount")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/write (:kind fx)))
      (is (= :endpoint/substrate (get-in fx [:target :case])))
      (is (= "order" (get-in fx [:target :container])))
      (is (= "total" (get-in fx [:target :path 0 :key])))
      (is (= "expr-1" (:source fx))))))

(deftest create-pattern
  (testing "post.X = T.created(...) becomes Create effect"
    (let [expr (ae/parse "post.order = Order.created(amount, status)")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/create (:kind fx)))
      (is (= :endpoint/primitive (get-in fx [:target :case]))))))

(deftest destroy-pattern
  (testing "not exists post.X becomes Destroy effect"
    (let [expr (ae/parse "not exists post.order")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/destroy (:kind fx)))
      (is (nil? (:value fx))))))

(deftest emit-pattern
  (testing "emitted(E, args...) becomes Emit effect"
    (let [expr (ae/parse "emitted(OrderShipped, order)")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/emit (:kind fx)))
      (is (= "OrderShipped" (get-in fx [:target :id]))))))

(deftest non-effect-bool-comparison-returns-nil
  (testing "post.X.total > 0 is a Bool assertion, not an effect"
    (let [expr (ae/parse "post.order.total > 0")
          fx (ec/canonicalise expr "expr-1")]
      (is (nil? fx)))))

(deftest non-effect-pure-comparison
  (let [expr (ae/parse "x = 5")
        fx (ec/canonicalise expr "expr-1")]
    (is (nil? fx))))

(deftest plan-1-stub-now-delegates
  (testing "fukan.model.effect/canonicalise calls into the analyzer module"
    (let [expr (ae/parse "post.order.total = 100")
          fx (fx/canonicalise expr)]
      (is (= :effect/write (:kind fx))
          "fx/canonicalise (Plan-1 entry) returns a real Effect, not nil"))))
