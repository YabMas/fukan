(ns fukan.vocabulary.allium.expression-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.expression :as ae]
            [fukan.model.expression :as e]
            [malli.core :as m]))

(deftest literal-integer
  (let [v (ae/parse "42")]
    (is (m/validate e/Expression v))
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= 42 (get-in v [:form :value])))))

(deftest literal-boolean
  (let [v (ae/parse "true")]
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= true (get-in v [:form :value])))))

(deftest literal-string
  (let [v (ae/parse "\"hello\"")]
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= "hello" (get-in v [:form :value])))))

(deftest variable
  (let [v (ae/parse "order")]
    (is (= :expr/var (get-in v [:form :case])))
    (is (= "order" (get-in v [:form :name])))))

(deftest dotted-navigation
  (testing "post.order.total navigation"
    (let [v (ae/parse "post.order.total")]
      (is (m/validate e/Expression v))
      (is (some? v)))))

(deftest function-call
  (let [v (ae/parse "Order.created(amount, status)")]
    (is (m/validate e/Expression v))
    (is (= :expr/apply (get-in v [:form :case])))))

(deftest comparison
  (let [v (ae/parse "x > 0")]
    (is (= :expr/apply (get-in v [:form :case])))
    (is (= ">" (get-in v [:form :op])))
    (is (= 2 (count (get-in v [:form :args]))))))

(deftest boolean-not
  (let [v (ae/parse "not exists Order")]
    (is (m/validate e/Expression v))))

(deftest arithmetic
  (let [v (ae/parse "balance + amount")]
    (is (= :expr/apply (get-in v [:form :case])))
    (is (= "+" (get-in v [:form :op]))))
  (testing "division"
    (let [v (ae/parse "total / count")]
      (is (= :expr/apply (get-in v [:form :case])))
      (is (= "/" (get-in v [:form :op]))))))

(deftest unknown-falls-back-to-opaque-text-lit
  (testing "unparseable text becomes a Scalar('AlliumText') literal"
    (let [v (ae/parse "for x in Y where p: q")]
      (is (= :expr/lit (get-in v [:form :case])))
      (is (= "AlliumText" (get-in v [:form :type :name])))
      (is (string? (get-in v [:form :value]))))))
