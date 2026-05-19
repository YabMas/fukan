(ns fukan.constraint.evaluator-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.evaluator :as e]
            [fukan.constraint.ast :as ast]))

(deftest single-rule-positive-derivation
  (testing "rule with positive body derives correctly"
    (let [rule (ast/make-rule
                 {:predicate :parent :args [:?x :?y]}
                 [(ast/make-atom :direct-parent [:?x :?y])])
          edb {:direct-parent #{["a" "b"] ["b" "c"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["a" "b"] ["b" "c"]} (:parent result))))))

(deftest transitive-closure
  (testing "recursive rule computes transitive closure"
    (let [base-rule (ast/make-rule
                      {:predicate :ancestor :args [:?x :?z]}
                      [(ast/make-atom :direct-parent [:?x :?z])])
          trans-rule (ast/make-rule
                       {:predicate :ancestor :args [:?x :?z]}
                       [(ast/make-atom :direct-parent [:?x :?y])
                        (ast/make-atom :ancestor [:?y :?z])])
          edb {:direct-parent #{["a" "b"] ["b" "c"] ["c" "d"]}}
          result (e/evaluate-rules [base-rule trans-rule] edb)]
      (is (= #{["a" "b"] ["b" "c"] ["c" "d"]
               ["a" "c"] ["b" "d"]
               ["a" "d"]}
             (:ancestor result))))))

(deftest negation-stratified
  (testing "negation against EDB or earlier-stratum predicate"
    (let [rule (ast/make-rule
                 {:predicate :isolated :args [:?x]}
                 [(ast/make-atom :node [:?x])
                  (ast/make-negation (ast/make-atom :connected [:?x]))])
          edb {:node #{["a"] ["b"] ["c"]}
               :connected #{["b"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["a"] ["c"]} (:isolated result))))))

(deftest comparison-filters
  (testing "comparison atoms filter bindings"
    (let [rule (ast/make-rule
                 {:predicate :same :args [:?x :?y]}
                 [(ast/make-atom :node [:?x])
                  (ast/make-atom :node [:?y])
                  (ast/make-comparison := :?x :?y)])
          edb {:node #{["a"] ["b"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["a" "a"] ["b" "b"]} (:same result))))))

(deftest aggregation-count
  (testing "aggregation binds count to result var"
    (let [rule (ast/make-rule
                 {:predicate :module-child-count :args [:?m :?n]}
                 [(ast/make-atom :module [:?m])
                  (ast/make-aggregation :count :?c
                                        [(ast/make-atom :child-of [:?c :?m])]
                                        :?n)])
          edb {:module #{["m1"] ["m2"]}
               :child-of #{["a" "m1"] ["b" "m1"] ["c" "m2"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["m1" 2] ["m2" 1]} (:module-child-count result))))))

(deftest query-returns-bindings
  (testing "query takes single atom, returns variable bindings"
    (let [rule (ast/make-rule {:predicate :parent :args [:?x :?y]}
                              [(ast/make-atom :direct-parent [:?x :?y])])
          edb {:direct-parent #{["a" "b"]}}
          bindings (e/query [rule] edb (ast/make-atom :parent [:?x :?y]))]
      (is (= #{{:?x "a" :?y "b"}} bindings)))))
