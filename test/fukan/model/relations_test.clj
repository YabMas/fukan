(ns fukan.model.relations-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.relations :as r]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest the-thirteen-relations
  (is (= #{:relation/triggers :relation/observes :relation/reads
            :relation/writes :relation/creates :relation/destroys
            :relation/emits :relation/realises :relation/specialises
            :relation/uses :relation/exposes :relation/provides
            :relation/projects}
         r/relation-kinds)))

(deftest endpoint-primitive
  (let [ep (r/primitive-ref "order")]
    (is (= :endpoint/primitive (:case ep)))
    (is (= "order" (:id ep)))
    (is (m/validate r/Endpoint ep))))

(deftest endpoint-substrate-field
  (testing "Field address — single-segment path"
    (let [ep (r/substrate-address "order" [{:slot "field" :key "total"}])]
      (is (= :endpoint/substrate (:case ep)))
      (is (= "order" (:container ep)))
      (is (= [{:slot "field" :key "total"}] (:path ep)))
      (is (m/validate r/Endpoint ep)))))

(deftest endpoint-substrate-parameter
  (testing "Parameter address — multi-segment path (deferred but kernel-shaped)"
    (let [ep (r/substrate-address "order"
                                  [{:slot "boundary"}
                                   {:slot "operation" :key "place_order"}
                                   {:slot "parameter" :key "order"}])]
      (is (m/validate r/Endpoint ep)))))

(deftest triggers-edge
  (let [edge (r/make-edge
               :relation/triggers
               (r/primitive-ref "order-placed-event")
               (r/primitive-ref "process-submission-rule"))]
    (is (= :relation/triggers (:kind edge)))
    (is (m/validate r/Edge edge))))

(deftest writes-edge-with-condition
  (let [cond-expr (e/make-apply ">"
                                [(e/make-var "pre.order.total")
                                 (e/make-lit (t/make-scalar "Integer") 0)])
        edge (r/make-edge :relation/writes
                          (r/primitive-ref "process-submission")
                          (r/substrate-address "order" [{:slot "field" :key "total"}])
                          {:condition cond-expr})]
    (is (= cond-expr (:condition edge)))
    (is (m/validate r/Edge edge))))

(deftest projects-edge-with-projection-kind
  (let [edge (r/make-edge :relation/projects
                          (r/primitive-ref "process-submission-rule")
                          (r/primitive-ref "code/auth/process-submission")
                          {:projection-kind :projection-kind/rule})]
    (is (= :projection-kind/rule (:projection-kind edge)))
    (is (m/validate r/Edge edge))))

(deftest edge-identity
  (let [from (r/primitive-ref "rule-1")
        to   (r/substrate-address "order" [{:slot "field" :key "total"}])
        c1   (e/make-apply ">" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 0)])
        c2   (e/make-apply "<" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 0)])
        a (r/make-edge :relation/writes from to {:condition c1})
        b (r/make-edge :relation/writes from to {:condition c1})
        c (r/make-edge :relation/writes from to {:condition c2})]
    (is (= (r/edge-identity a) (r/edge-identity b))
        "Same identifying metadata ⇒ same edge identity")
    (is (not= (r/edge-identity a) (r/edge-identity c))
        "Different :condition ⇒ different edges (multi-edge per §4.4)")))

(deftest edge-identity-drops-non-identifying
  (testing "Non-identifying metadata mutates without changing identity"
    (let [from (r/primitive-ref "rule-1")
          to   (r/primitive-ref "container-x")
          a (r/make-edge :relation/uses from to {:source-file "a.allium"})
          b (r/make-edge :relation/uses from to {:source-file "b.allium"})]
      (is (= (r/edge-identity a) (r/edge-identity b))))))

(deftest identifying-slots-table
  (testing "Per-relation identifying metadata per §4.4"
    (is (= #{}                                  (r/identifying-slots :relation/triggers)))
    (is (= #{:condition}                        (r/identifying-slots :relation/observes)))
    (is (= #{:condition}                        (r/identifying-slots :relation/reads)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/writes)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/creates)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/destroys)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/emits)))
    (is (= #{}                                  (r/identifying-slots :relation/realises)))
    (is (= #{}                                  (r/identifying-slots :relation/specialises)))
    (is (= #{}                                  (r/identifying-slots :relation/uses)))
    (is (= #{}                                  (r/identifying-slots :relation/exposes)))
    (is (= #{}                                  (r/identifying-slots :relation/provides)))
    (is (= #{:projection-kind}                  (r/identifying-slots :relation/projects)))))
