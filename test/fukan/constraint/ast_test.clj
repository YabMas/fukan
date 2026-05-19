(ns fukan.constraint.ast-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.ast :as ast]))

(deftest var-recognition
  (is (ast/var? :?x))
  (is (ast/var? :?module))
  (is (not (ast/var? :literal)))
  (is (not (ast/var? "?x")))
  (is (not (ast/var? 42))))

(deftest constant-is-anything-not-var
  (is (ast/constant? "foo"))
  (is (ast/constant? 42))
  (is (ast/constant? :keyword))
  (is (not (ast/constant? :?x))))

(deftest make-atom-shape
  (is (= {:kind :atom :predicate :depends-on :args [:?x :?y]}
         (ast/make-atom :depends-on [:?x :?y]))))

(deftest make-negation-shape
  (is (= {:kind :negation :inner {:kind :atom :predicate :has-tag :args [:?x "Boundary::Function"]}}
         (ast/make-negation (ast/make-atom :has-tag [:?x "Boundary::Function"])))))

(deftest make-comparison-shape
  (is (= {:kind :comparison :op := :left :?x :right :?y}
         (ast/make-comparison := :?x :?y))))

(deftest make-aggregation-shape
  (is (= {:kind :aggregation :op :count :var :?m :body [{:kind :atom :predicate :module :args [:?m]}] :result :?n}
         (ast/make-aggregation :count :?m
                               [(ast/make-atom :module [:?m])]
                               :?n))))

(deftest make-rule-shape
  (is (= {:head {:predicate :violates :args [:?x]}
          :body [{:kind :atom :predicate :is-module :args [:?x]}
                 {:kind :negation :inner {:kind :atom :predicate :has-tag :args [:?x "Boundary::Function"]}}]}
         (ast/make-rule {:predicate :violates :args [:?x]}
                        [(ast/make-atom :is-module [:?x])
                         (ast/make-negation (ast/make-atom :has-tag [:?x "Boundary::Function"]))]))))
