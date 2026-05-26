(ns fukan.canvas.core.defquery-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.defquery :as dq]))

(deftest primitive-name-expansion
  (testing "(Module ?x) expands to [?x :entity/type :Module]"
    (is (= '[[?x :entity/type :Module]]
           (dq/expand '[(Module ?x)])))))

(deftest tag-name-expansion
  (testing "(tag :X ?e) expands to [?e :entity/tag :X]"
    (is (= '[[?e :entity/tag :X]]
           (dq/expand '[(tag :X ?e)])))))

(deftest defquery-registers-and-expands
  (testing "a defquery'd operator expands into its body"
    (dq/defquery test-op [?x ?y]
      "Test operator."
      '[[?x :foo ?y]])
    (is (= '[[?a :foo ?b]]
           (dq/expand '[(test-op ?a ?b)])))))

(deftest recursive-expansion
  (testing "defquery bodies can reference other defquery'd operators"
    (dq/defquery base-op [?x]
      "base"
      '[[?x :entity/type :Module]])
    (dq/defquery outer-op [?x]
      "outer"
      '[(base-op ?x) [?x :entity/name ?n]])
    (is (= '[[?a :entity/type :Module] [?a :entity/name ?n]]
           (dq/expand '[(outer-op ?a)])))))
