(ns fukan.model.vocabulary-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest tag-definition-pure-label
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Module"
              :applies-to :target/container})]
    (is (= "Allium" (:namespace td)))
    (is (= "Module" (:name td)))
    (is (= :target/container (:applies-to td)))
    (is (nil? (:payload-schema td)))
    (is (m/validate v/TagDefinition td))))

(deftest tag-definition-with-payload
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Surface"
              :applies-to :target/container
              :payload-schema
              (t/make-composite-inline
                [(t/make-field-spec
                   "facing"
                   (t/make-union [(t/make-ref-kernel-primitive #{:actor})
                                  (t/make-ref-kernel-primitive #{:container})])
                   true)])})]
    (is (m/validate v/TagDefinition td))))

(deftest tag-definition-relational
  (testing "RelationalSpec: directed, canonical_side, optional coherence_query"
    (let [td (v/make-tag-definition
               {:namespace "DDD" :name "CustomerSupplier"
                :applies-to :target/container
                :payload-schema (t/make-composite-inline
                                  [(t/make-field-spec "upstream"
                                                       (t/make-ref-kernel-primitive #{:container})
                                                       false)
                                   (t/make-field-spec "downstream"
                                                       (t/make-ref-kernel-primitive #{:container})
                                                       false)])
                :relational {:endpoints ["upstream" "downstream"]
                             :symmetry :directed
                             :canonical-side "upstream"}})]
      (is (= :directed (get-in td [:relational :symmetry])))
      (is (m/validate v/TagDefinition td)))))

(deftest tag-application
  (let [ta (v/make-tag-application
             {:tag {:namespace "Allium" :name "Module"}
              :target {:case :target/primitive :id "user-module"}
              :payload {}})]
    (is (m/validate v/TagApplication ta))))

(deftest tag-application-on-edge
  (testing "Per V10, stored kernel edges accept tag applications"
    (let [edge-id [{:case :endpoint/primitive :id "r"} {:case :endpoint/primitive :id "e"} :relation/emits {}]
          ta (v/make-tag-application
               {:tag {:namespace "Allium" :name "Trigger"}
                :target {:case :target/edge :edge-identity edge-id}
                :payload {:kind "external_stimulus"}})]
      (is (m/validate v/TagApplication ta)))))

(deftest tag-presence-implication
  (testing "V9: carrying child tag implies parent tag (`has-tag-with-ancestors?`)"
    (let [parent (v/make-tag-definition
                   {:namespace "DDD" :name "Aggregate" :applies-to :target/container})
          child  (v/make-tag-definition
                   {:namespace "DDD" :name "EventSourcedAggregate"
                    :applies-to :target/container
                    :parent-tag {:namespace "DDD" :name "Aggregate"}})
          registry {:tag-definitions [parent child]
                    :tag-applications [(v/make-tag-application
                                         {:tag {:namespace "DDD" :name "EventSourcedAggregate"}
                                          :target {:case :target/primitive :id "order"}})]}]
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "Aggregate"})))
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "EventSourcedAggregate"}))))))

(deftest tag-presence-implication-grandparent
  (testing "V9 tag-presence implication walks multi-hop ancestor chains"
    (let [grandparent (v/make-tag-definition
                        {:namespace "DDD" :name "BoundedContext"
                         :applies-to :target/container})
          parent      (v/make-tag-definition
                        {:namespace "DDD" :name "Aggregate"
                         :applies-to :target/container
                         :parent-tag {:namespace "DDD" :name "BoundedContext"}})
          child       (v/make-tag-definition
                        {:namespace "DDD" :name "EventSourcedAggregate"
                         :applies-to :target/container
                         :parent-tag {:namespace "DDD" :name "Aggregate"}})
          registry    {:tag-definitions [grandparent parent child]
                       :tag-applications [(v/make-tag-application
                                            {:tag {:namespace "DDD" :name "EventSourcedAggregate"}
                                             :target {:case :target/primitive :id "order"}})]}]
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "EventSourcedAggregate"}))
          "direct application of child tag")
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "Aggregate"}))
          "immediate parent reached via one hop")
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "BoundedContext"}))
          "grandparent reached via two hops"))))

(deftest predicate-registration
  (let [pr (v/make-predicate-registration
             {:namespace "Allium" :name "VR30-trigger-defined"
              :severity :error :kind "integrity"
              :scope :scope/model
              :message-template "Trigger {{event}} referenced in Surface {{surface}} is not defined."
              :predicate {:opaque true :note "Datalog AST in Plan 4"}})]
    (is (= :error (:severity pr)))
    (is (= "integrity" (:kind pr)))
    (is (m/validate v/PredicateRegistration pr))))

(deftest renderer-registration-seam
  (testing "Plan 1 ships the seam shape only; treatments are opaque"
    (let [rr (v/make-renderer-registration
               {:tag {:namespace "Allium" :name "Surface"}
                :node-treatment {:shape :pill :colour-family "blue"}
                :sidebar-treatment {:component :allium/surface-sidebar}})]
      (is (m/validate v/RendererRegistration rr)))))
