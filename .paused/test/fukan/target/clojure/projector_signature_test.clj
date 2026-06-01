(ns fukan.target.clojure.projector-signature-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

(defn- module-with [primitive]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive primitive)))

(deftest operation-signature-from-parameters
  (let [op (p/make-operation
             {:id "m::Contract.submit" :label "submit"
              :parameters [(p/make-parameter "order-id" (t/make-scalar "String") false 0)
                           (p/make-parameter "qty"      (t/make-scalar "Integer") false 1)]})
        model (module-with op)
        ;; also tag the container as a Contract for completeness
        bp (projector/project model (registry/make-registry)
                              "m::Contract.submit" :projection-kind/operation)]
    (is (= ["order-id" "qty"] (-> bp :signature :arglist)))
    (is (= [:string :int]    (-> bp :signature :param-types)))))

(deftest operation-test-signature-is-arity-zero
  (let [op (p/make-operation
             {:id "m::Contract.go" :label "go" :parameters []})
        model (module-with op)
        bp (projector/project model (registry/make-registry)
                              "m::Contract.go" :projection-kind/test)]
    (is (= [] (-> bp :signature :arglist)))))

(deftest rule-signature-is-arity-zero
  (let [r (p/make-rule {:id "m::CheckIt" :label "CheckIt"})
        model (module-with r)
        bp (projector/project model (registry/make-registry)
                              "m::CheckIt" :projection-kind/rule)]
    (is (= [] (-> bp :signature :arglist)))
    (is (nil? (-> bp :signature :return-malli)))))

(deftest container-schema-signature-is-malli-map
  (let [c (p/make-container
            {:id "m::Order" :label "Order"
             :fields [(p/make-field "id"    (t/make-scalar "String") false)
                      (p/make-field "total" (t/make-scalar "Integer") false)]})
        model (-> (module-with c)
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Entity"}
                       :target {:case :target/primitive :id "m::Order"}})))
        bp (projector/project model (registry/make-registry)
                              "m::Order" :projection-kind/schema)]
    (is (= [:map [:id :string] [:total :int]]
           (-> bp :signature :malli-shape)))))

(deftest event-schema-signature-from-parameters
  (let [e (p/make-event
            {:id "m::events::OrderPlaced" :label "OrderPlaced"
             :parameters [(p/make-parameter "order-id" (t/make-scalar "String") false 0)]})
        model (module-with e)
        bp (projector/project model (registry/make-registry)
                              "m::events::OrderPlaced" :projection-kind/schema)]
    (is (= [:map [:order-id :string]]
           (-> bp :signature :malli-shape)))))

(deftest signature-for-is-publicly-callable-from-outside
  ;; Phase 7 Task 4 gap 1 — Layer A's `function-to-defn` projection needs to
  ;; call `signature-for` directly with a primitive + projection-kind to
  ;; derive arglist + return-hint without going through the full project
  ;; pipeline. Verifies the fn is reachable through the public alias.
  (let [op   (p/make-operation
               {:id "m::Contract.submit" :label "submit"
                :parameters [(p/make-parameter "order-id" (t/make-scalar "String") false 0)]})
        reg  (registry/make-registry)
        sig  (projector/signature-for reg op :projection-kind/operation)]
    (is (= ["order-id"] (:arglist sig)))
    (is (= [:string]    (:param-types sig)))))
