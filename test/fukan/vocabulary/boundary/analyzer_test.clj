(ns fukan.vocabulary.boundary.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.boundary.analyzer :as analyzer]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]))

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

(deftest fn-declare-new-with-triggers
  (testing "fn body's triggers: clause produces an R4 edge + Boundary::Binding tag"
    (let [m0      (-> (build/empty-model)
                      ;; Pre-seed the local Rule the fn references — analyzer
                      ;; doesn't create it, just emits an edge against it
                      ;; (Allium runs first in the real pipeline).
                      (build/add-primitive
                        (p/make-rule
                          {:id "test/module::SelectNode" :label "SelectNode"})))
          fn-decl {:type :fn :form :declare-new :name "select_node"
                   :params [{:name "node_id"
                             :type-ref {:kind :simple :name "NodeId"}}]
                   :return-type nil :prose nil
                   :body {:triggers {:kind :local :name "SelectNode"}
                          :returns nil}}
          model (analyze m0 [fn-decl])
          triggers-edges (filter #(= :relation/triggers (:kind %))
                                 (:edges model))]
      (is (= 1 (count triggers-edges))
          "one R4 edge emitted")
      (let [edge (first triggers-edges)]
        (is (= "test/module::select_node" (-> edge :from :id)))
        (is (= "test/module::SelectNode"  (-> edge :to :id))))
      (let [binding-tags (filter (fn [ta]
                                   (and (= "Boundary" (-> ta :tag :namespace))
                                        (= "Binding"  (-> ta :tag :name))))
                                 (:tag-apps model))]
        (is (= 1 (count binding-tags)) "one Boundary::Binding tag on the edge")
        (is (= :target/edge (-> binding-tags first :target :case)))))))

(deftest fn-body-returns-captured-in-binding-payload
  (testing "returns: clause stored as Boundary::Binding payload"
    (let [m0      (-> (build/empty-model)
                      (build/add-primitive
                        (p/make-rule
                          {:id "test/module::ProcessOrder" :label "ProcessOrder"})))
          fn-decl {:type :fn :form :declare-new :name "submit_order"
                   :params [{:name "order"
                             :type-ref {:kind :simple :name "Order"}}]
                   :return-type {:kind :simple :name "SubmissionReceipt"}
                   :prose nil
                   :body {:triggers {:kind :local :name "ProcessOrder"}
                          :returns "SubmissionReceipt(order.id, post.order.created_at)"}}
          model (analyze m0 [fn-decl])
          binding-tag (->> (:tag-apps model)
                           (filter (fn [ta]
                                     (and (= "Boundary" (-> ta :tag :namespace))
                                          (= "Binding"  (-> ta :tag :name)))))
                           first)]
      (is (= "SubmissionReceipt(order.id, post.order.created_at)"
             (-> binding-tag :payload :returns_expression))))))

(deftest fn-body-without-triggers-no-edge
  (testing "fn with body but no triggers: emits no R4 edge"
    (let [fn-decl {:type :fn :form :declare-new :name "get_view_state"
                   :params [] :return-type {:kind :simple :name "ViewState"}
                   :prose nil
                   :body {:triggers nil :returns "current_view_state"}}
          model (analyze (build/empty-model) [fn-decl])]
      (is (empty? (filter #(= :relation/triggers (:kind %)) (:edges model)))
          "no triggers edge produced when :triggers is nil"))))
