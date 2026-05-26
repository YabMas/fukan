(ns fukan.canvas.core.shape-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.shape :as shape]))

(deftest atomic-shape
  (is (= {:kind :atomic :name :String} (shape/parse :String))))

(deftest optional-shape
  (is (= {:kind :optional :inner {:kind :atomic :name :Integer}}
         (shape/parse '(optional :Integer)))))

(deftest list-shape
  (is (= {:kind :list :elem {:kind :atomic :name :Any}}
         (shape/parse '(list-of :Any)))))

(deftest set-shape
  (is (= {:kind :set :elem {:kind :atomic :name :String}}
         (shape/parse '(set-of :String)))))

(deftest sum-shape
  (is (= {:kind :sum :variants [{:kind :atomic :name :Integer}
                                {:kind :atomic :name :String}]}
         (shape/parse '(sum-of :Integer :String)))))

(deftest map-shape
  (is (= {:kind :map
          :key {:kind :atomic :name :String}
          :val {:kind :atomic :name :Any}}
         (shape/parse '(map-of :String :Any)))))

(deftest nested-map-shape
  (testing "map-of with cross-module ref values"
    (is (= {:kind :map
            :key {:kind :atomic :name :String}
            :val {:kind :ref :target :model/Value}}
           (shape/parse '(map-of :String :model/Value))))))

(deftest nested-shape
  (is (= {:kind :optional
          :inner {:kind :list :elem {:kind :atomic :name :Integer}}}
         (shape/parse '(optional (list-of :Integer))))))

(deftest ref-shape
  (is (= {:kind :ref :target :model/Model}
         (shape/parse '(ref-to :model/Model)))))

(deftest record-shape
  (is (= {:kind :record :fields [[:email {:kind :atomic :name :String}]
                                  [:age {:kind :optional :inner {:kind :atomic :name :Integer}}]]}
         (shape/parse '(record-of [:email :String]
                                   [:age (optional :Integer)])))))

(deftest namespaced-keyword-is-ref
  (testing "a namespaced keyword parses as :ref"
    (is (= {:kind :ref :target :ast/ConstraintRule}
           (shape/parse :ast/ConstraintRule)))))

(deftest plain-keyword-is-atomic
  (testing "an unnamespaced keyword parses as :atomic"
    (is (= {:kind :atomic :name :String}
           (shape/parse :String)))))

(deftest unknown-shape-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (shape/parse '(weird-form :T)))))
