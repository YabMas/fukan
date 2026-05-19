(ns fukan.vocabulary.boundary.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.boundary.analyzer :as analyzer]
            [fukan.model.build :as build]))

(defn- analyze [model decls]
  (analyzer/analyze-file model
                         {:boundary-version 1 :declarations decls}
                         "test/module"
                         {}))

(deftest empty-file-returns-model-unchanged
  (testing "an empty declarations vector produces no kernel changes"
    (let [m0    (build/empty-model)
          model (analyze m0 [])]
      (is (= (:primitives m0) (:primitives model)))
      (is (= (:edges m0) (:edges model)))
      (is (= (:tag-apps m0) (:tag-apps model))))))

(deftest use-decl-is-noop
  (testing "use declarations don't produce kernel content"
    (let [m0    (build/empty-model)
          model (analyze m0 [{:type :use :path "x.allium" :alias "x"}])]
      (is (= (:primitives m0) (:primitives model)))
      (is (= (:tag-apps m0) (:tag-apps model))))))

(deftest mixed-shape-throws
  (testing "a file mixing :fn and :subsystem at top level is rejected"
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :fn :form :declare-new :name "f"
                            :params [] :return-type nil :prose nil :body nil}
                           {:type :subsystem :name "X"
                            :contains [] :exports [] :rules []}])))))
