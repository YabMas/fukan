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

(deftest fn-declare-new-produces-operation
  (testing "fn name(params) -> R creates an Operation primitive on the module-Container's Boundary"
    (let [m0      (build/empty-model)
          fn-decl {:type :fn
                   :form :declare-new
                   :name "render_app_shell"
                   :params []
                   :return-type {:kind :simple :name "Html"}
                   :prose nil
                   :body nil}
          model (analyze m0 [fn-decl])
          op-id "test/module::render_app_shell"
          op    (build/get-primitive model op-id)]
      (is (some? op) "Operation primitive created")
      (is (= :primitive/operation (:kind op)))
      (is (= "render_app_shell" (:label op)))
      ;; Operation is referenced from the module-Container's Boundary
      (let [container (build/get-primitive model "test/module")]
        (is (some? container) "module-Container created or pre-existing")
        (is (some #(= op-id %) (-> container :boundary :operations))
            "Operation id appears in module-Container.boundary.operations")))))

(deftest fn-declare-new-applies-Function-tag
  (testing "fn declare-new produces a Boundary::Function tag application on the Operation"
    (let [fn-decl {:type :fn :form :declare-new :name "f"
                   :params [] :return-type nil :prose nil :body nil}
          model   (analyze (build/empty-model) [fn-decl])
          fn-tags (filter (fn [ta]
                            (and (= "Boundary" (-> ta :tag :namespace))
                                 (= "Function" (-> ta :tag :name))))
                          (:tag-apps model))]
      (is (= 1 (count fn-tags)))
      (is (= "test/module::f" (-> fn-tags first :target :id))))))

(deftest fn-declare-new-with-typed-params
  (testing "fn parameters land as Parameter records on the Operation"
    (let [fn-decl {:type :fn :form :declare-new :name "load_model"
                   :params [{:name "src" :type-ref {:kind :simple :name "FilePath"}}
                            {:name "analyzers"
                             :type-ref {:kind :generic :name "Set"
                                        :params [{:kind :simple :name "AnalyzerKey"}]}}]
                   :return-type {:kind :simple :name "Model"}
                   :prose nil :body nil}
          model (analyze (build/empty-model) [fn-decl])
          op    (build/get-primitive model "test/module::load_model")]
      (is (= 2 (count (:parameters op))))
      (is (= "src" (-> op :parameters first :name)))
      (is (= "analyzers" (-> op :parameters second :name)))
      (is (some? (:return-type op)) "return type was captured"))))
