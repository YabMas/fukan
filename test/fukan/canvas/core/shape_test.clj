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

(deftest type-names-atomic
  (is (= #{:String} (shape/type-names (shape/parse :String)))))

(deftest type-names-ref
  (is (= #{:model/Model} (shape/type-names (shape/parse :model/Model)))))

(deftest type-names-optional
  (is (= #{:Integer}
         (shape/type-names (shape/parse '(optional :Integer))))))

(deftest type-names-list
  (is (= #{:String}
         (shape/type-names (shape/parse '(list-of :String))))))

(deftest type-names-nested
  (testing "(optional (list-of :T)) collects :T"
    (is (= #{:Integer}
           (shape/type-names (shape/parse '(optional (list-of :Integer))))))))

(deftest type-names-record-with-cross-module-refs
  (testing "(record-of [m :model/Model] [v (list-of :agent/Violation)]) collects both refs"
    (is (= #{:model/Model :agent/Violation}
           (shape/type-names (shape/parse '(record-of
                                             [:m :model/Model]
                                             [:v (list-of :agent/Violation)])))))))

(deftest type-names-arrow
  (testing "an arrow shape collects both inputs and outputs"
    (is (= #{:String :Integer}
           (shape/type-names
             {:kind :arrow
              :inputs {:kind :record :fields [["x" {:kind :atomic :name :String}]]}
              :outputs {:kind :atomic :name :Integer}})))))

(deftest type-names-sum
  (is (= #{:A :B :C}
         (shape/type-names {:kind :sum
                            :variants [{:kind :atomic :name :A}
                                       {:kind :atomic :name :B}
                                       {:kind :atomic :name :C}]}))))

(deftest type-names-map
  (is (= #{:String :model/Value}
         (shape/type-names {:kind :map
                            :key {:kind :atomic :name :String}
                            :val {:kind :ref :target :model/Value}}))))

(deftest tuple-shape
  (testing "(tuple-of :String :String) parses to a :tuple shape"
    (is (= {:kind :tuple :elems [{:kind :atomic :name :String}
                                  {:kind :atomic :name :String}]}
           (shape/parse '(tuple-of :String :String))))))

(deftest tuple-shape-with-cross-module-ref
  (testing "(tuple-of :ArtifactSubCase :String :String) handles refs and atomics"
    (is (= {:kind :tuple :elems [{:kind :ref :target :model/ArtifactSubCase}
                                  {:kind :atomic :name :String}
                                  {:kind :atomic :name :String}]}
           (shape/parse '(tuple-of :model/ArtifactSubCase :String :String))))))

(deftest type-names-tuple
  (testing "(tuple-of :String :model/Foo) collects both element type names"
    (is (= #{:String :model/Foo}
           (shape/type-names (shape/parse '(tuple-of :String :model/Foo)))))))
