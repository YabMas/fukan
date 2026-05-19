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

(deftest fn-local-attach-emits-binding-against-existing-operation
  (testing "fn Contract.op { triggers: Rule } finds the local Operation and emits an edge"
    (let [m0      (-> (build/empty-model)
                      ;; Pre-seed the Allium-declared Operation (Plan 2b's id shape):
                      (build/add-primitive
                        (p/make-operation
                          {:id "test/module::OrderSubmission.submit"
                           :label "submit" :parameters []}))
                      (build/add-primitive
                        (p/make-rule
                          {:id "test/module::ProcessOrder" :label "ProcessOrder"})))
          fn-decl {:type :fn :form :local-attach
                   :contract "OrderSubmission" :op "submit"
                   :prose nil
                   :body {:triggers {:kind :local :name "ProcessOrder"}
                          :returns "Confirmation(post.order.id)"}}
          model (analyze m0 [fn-decl])
          edges (filter #(= :relation/triggers (:kind %)) (:edges model))]
      (is (= 1 (count edges)))
      (is (= "test/module::OrderSubmission.submit" (-> edges first :from :id)))
      (is (= "test/module::ProcessOrder"           (-> edges first :to :id))))))

(deftest fn-foreign-attach-resolves-through-use-alias
  (testing "fn alias/Contract.op { ... } resolves via use-aliases"
    (let [m0      (-> (build/empty-model)
                      (build/add-primitive
                        (p/make-operation
                          {:id "other/coord::PaymentRequested.charge"
                           :label "charge" :parameters []}))
                      (build/add-primitive
                        (p/make-rule
                          {:id "test/module::HandleCharge" :label "HandleCharge"})))
          fn-decl {:type :fn :form :foreign-attach
                   :alias "c" :contract "PaymentRequested" :op "charge"
                   :prose nil
                   :body {:triggers {:kind :local :name "HandleCharge"}
                          :returns nil}}
          model (analyzer/analyze-file m0
                                       {:boundary-version 1 :declarations [fn-decl]}
                                       "test/module"
                                       {"c" "other/coord"})
          edges (filter #(= :relation/triggers (:kind %)) (:edges model))]
      (is (= 1 (count edges)))
      (is (= "other/coord::PaymentRequested.charge" (-> edges first :from :id))))))

(deftest fn-attach-empty-body-throws
  (testing "attach form with no body or empty body is a structural error"
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :fn :form :local-attach
                            :contract "X" :op "y"
                            :prose nil :body nil}])))
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :fn :form :local-attach
                            :contract "X" :op "y" :prose nil
                            :body {:triggers nil :returns nil}}])))))

(deftest exports-applies-module-api-tag
  (testing "exports: produces a Boundary::ModuleApi tag on the module-Container"
    (let [decl  {:type :exports
                 :entries ["ViewState" "NavigationState" "OrderSubmission.submit"]}
          model (analyze (build/empty-model) [decl])
          tags  (filter (fn [ta]
                          (and (= "Boundary" (-> ta :tag :namespace))
                               (= "ModuleApi" (-> ta :tag :name))))
                        (:tag-apps model))]
      (is (= 1 (count tags)))
      (let [ta (first tags)]
        (is (= :target/primitive (-> ta :target :case)))
        (is (= "test/module" (-> ta :target :id))
            "tag targets the module-Container at the file's coord")
        (is (= ["ViewState" "NavigationState" "OrderSubmission.submit"]
               (-> ta :payload :exported)))))))

(deftest multiple-exports-clauses-rejected
  (testing "more than one :exports declaration in one file is a structural error"
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :exports :entries ["A"]}
                           {:type :exports :entries ["B"]}])))))

(deftest subsystem-creates-composite-container
  (testing "subsystem block produces a composite Container + Boundary::Subsystem tag"
    (let [decl  {:type :subsystem :name "Auth"
                 :contains ["./oauth/spec.allium" "./password/spec.allium"]
                 :exports  ["oauth/OAuthLogin"]
                 :rules    []}
          model (analyzer/analyze-file (build/empty-model)
                                       {:boundary-version 1 :declarations [decl]}
                                       "test/auth"
                                       {})
          composite (build/get-primitive model "test/auth")]
      (is (some? composite) "composite Container created at file coord")
      (is (= :primitive/container (:kind composite)))
      (is (= 2 (count (:children composite))))
      (let [sub-tag (->> (:tag-apps model)
                         (filter (fn [ta]
                                   (and (= "Boundary" (-> ta :tag :namespace))
                                        (= "Subsystem" (-> ta :tag :name)))))
                         first)]
        (is (some? sub-tag))
        (is (= "Auth" (-> sub-tag :payload :name)))))))

(deftest subsystem-exports-tag-applied
  (testing "subsystem's exports: produces a Boundary::Exports tag on the composite"
    (let [decl  {:type :subsystem :name "Auth"
                 :contains ["./oauth/spec.allium"]
                 :exports  ["oauth/OAuthLogin" "oauth/SessionRevoked"]
                 :rules    []}
          model (analyzer/analyze-file (build/empty-model)
                                       {:boundary-version 1 :declarations [decl]}
                                       "test/auth"
                                       {})
          exports-tag (->> (:tag-apps model)
                           (filter (fn [ta]
                                     (and (= "Boundary" (-> ta :tag :namespace))
                                          (= "Exports"  (-> ta :tag :name)))))
                           first)]
      (is (some? exports-tag))
      (is (= ["oauth/OAuthLogin" "oauth/SessionRevoked"]
             (-> exports-tag :payload :exported))))))

(deftest subsystem-rules-produce-predicate-registrations
  (testing "subsystem rules: clause produces PredicateRegistrations on the model"
    (let [decl  {:type :subsystem :name "Auth"
                 :contains ["./oauth/spec.allium"]
                 :exports  ["oauth/OAuthLogin"]
                 :rules    [{:name "no_dependency"
                             :args [{:key "from" :value "oauth"}
                                    {:key "to"   :value "password"}]}]}
          model (analyzer/analyze-file (build/empty-model)
                                       {:boundary-version 1 :declarations [decl]}
                                       "test/auth"
                                       {})
          regs (:predicates model)]
      (is (>= (count regs) 1) "registration lands in kernel :predicates slot")
      (let [reg (first regs)]
        (is (= "no_dependency" (:name reg))
            "predicate name preserved from rule entry")
        (is (= :scope/tag (:scope reg))
            "scope is scope/tag per MODEL.md §5.3")
        (is (= [{:key "from" :value "oauth"}
                {:key "to" :value "password"}]
               (-> reg :predicate :args))
            "rule args preserved in predicate field")))))
