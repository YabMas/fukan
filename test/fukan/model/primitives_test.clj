(ns fukan.model.primitives-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.primitives :as p]
            [fukan.model.type :as t]
            [malli.core :as m]))

;; -- Container ---------------------------------------------------------------

(deftest container-minimal
  (testing "Container needs only :id and :label; all faces are optional"
    (let [c (p/make-container {:id "c1" :label "Order"})]
      (is (= :primitive/container (:kind c)))
      (is (m/validate p/Container c)))))

(deftest container-with-all-faces
  (let [c (p/make-container
            {:id "order-aggregate"
             :label "Order"
             :description "Customer order aggregate root"
             :intent {:kind :primitive/intent :id "i1" :clauses [] :assertions []}
             :children #{"order-line"}
             :fields [(p/make-field "id" (t/make-scalar "String") false)
                      (p/make-field "total" (t/make-scalar "Money") false)]
             :events #{"order-placed"}
             :behaviour {:kind :primitive/behaviour :id "b1" :label "OrderBehaviour" :rules []}
             :boundary  {:kind :primitive/boundary  :id "bd1" :label "OrderBoundary" :operations []}})]
    (is (m/validate p/Container c))
    (is (= 2 (count (:fields c))))
    (is (= #{"order-line"} (:children c)))))

(deftest container-children-depth-unconstrained
  (testing "Substrate imposes no depth limit on Container.children"
    (let [root (p/make-container
                 {:id "root" :label "Root" :children #{"a"}})
          a    (p/make-container
                 {:id "a" :label "A" :children #{"b"}})
          b    (p/make-container
                 {:id "b" :label "B" :children #{"c"}})
          c    (p/make-container {:id "c" :label "C"})]
      (is (every? #(m/validate p/Container %) [root a b c])))))

;; -- Field -------------------------------------------------------------------

(deftest field-identity
  (testing "Field identity is (container-id, field-name)"
    (let [f (p/make-field "priority" (t/make-scalar "Integer") true)]
      (is (= ["order-1" "priority"] (p/field-identity "order-1" f))))))

;; -- Actor -------------------------------------------------------------------

(deftest actor
  (let [a (p/make-actor {:id "candidate" :label "Candidate"})]
    (is (= :primitive/actor (:kind a)))
    (is (m/validate p/Actor a))))

;; -- Event -------------------------------------------------------------------

(deftest event
  (testing "Event is owned by its qualifying Container; the qualification edge
            lives in (relation), not in Event substrate"
    (let [e (p/make-event
              {:id "interview/slot-confirmed"
               :label "SlotConfirmed"
               :parameters [(p/make-parameter "viewer"
                                              (t/make-ref-kernel-primitive #{:actor})
                                              false 0)
                            (p/make-parameter "slot"
                                              (t/make-ref-kernel-primitive #{:container})
                                              false 1)]})]
      (is (= :primitive/event (:kind e)))
      (is (m/validate p/Event e))
      (is (= 2 (count (:parameters e)))))))

;; -- Parameter ---------------------------------------------------------------

(deftest parameter-identity
  (testing "Parameter identity is (parent-id, parameter-name)"
    (let [pr (p/make-parameter "order" (t/make-ref-kernel-primitive #{:container}) false 0)]
      (is (= ["place-order" "order"] (p/parameter-identity "place-order" pr))))))

;; -- Behaviour & Rule --------------------------------------------------------

(deftest behaviour
  (let [b (p/make-behaviour {:id "b" :label "OrderBehaviour" :rules ["r1" "r2"]})]
    (is (= :primitive/behaviour (:kind b)))
    (is (m/validate p/Behaviour b))))

(deftest rule-with-body
  (let [r (p/make-rule
            {:id "process-submission"
             :label "ProcessSubmission"
             :description "Handles an inbound submission event"
             :intent {:kind :primitive/intent :id "ri" :clauses [] :assertions []}
             :body   {:definitions [] :effects []}})]
    (is (= :primitive/rule (:kind r)))
    (is (m/validate p/Rule r))))

(deftest definition-identity
  (let [d (p/make-definition "fee" {:case :expr/var :name "rate"})]
    (is (= ["rule-1" "fee"] (p/definition-identity "rule-1" d)))))

;; -- Boundary & Operation ----------------------------------------------------

(deftest boundary
  (let [b (p/make-boundary {:id "bd" :label "OrderBoundary" :operations []})]
    (is (= :primitive/boundary (:kind b)))
    (is (m/validate p/Boundary b))))

(deftest operation-with-return
  (let [o (p/make-operation
            {:id "submit"
             :label "submit"
             :parameters [(p/make-parameter "key"
                                            (t/make-scalar "String") false 0)]
             :return-type (t/make-composite-named "EventSubmission")})]
    (is (= :primitive/operation (:kind o)))
    (is (some? (:return-type o)))
    (is (m/validate p/Operation o))))

(deftest operation-fire-and-forget
  (testing "Operation with absent return-type is fire-and-forget (command-shaped)"
    (let [o (p/make-operation
              {:id "ping" :label "ping" :parameters []})]
      (is (nil? (:return-type o)))
      (is (m/validate p/Operation o)))))

;; -- Intent & Clause ---------------------------------------------------------

(deftest intent
  (let [i (p/make-intent
            {:id "i" :clauses [{:kind :primitive/clause :id "c1" :body "Order must be paid in full"}]
             :assertions []})]
    (is (= :primitive/intent (:kind i)))
    (is (m/validate p/Intent i))))

(deftest clause-label-optional-not-identity
  (testing "Clause label is optional; identity is the id only (K15a)"
    (let [c1 (p/make-clause {:id "c1" :body "X holds"})
          c2 (p/make-clause {:id "c2" :label "labelled-one" :body "X holds"})]
      (is (every? #(m/validate p/Clause %) [c1 c2])))))

;; -- Kind discriminator ------------------------------------------------------

(deftest every-primitive-carries-kind
  (testing ":kind is a closed enum; constructors set it; nothing else does"
    (is (= #{:primitive/container :primitive/actor :primitive/behaviour
             :primitive/rule :primitive/boundary :primitive/operation
             :primitive/intent :primitive/clause :primitive/event}
           p/primitive-kinds))))
