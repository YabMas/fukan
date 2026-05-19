(ns fukan.validation.rules-4f-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4f :as r4f]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

(defn- module [exports]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Boundary" :name "ModuleApi"}
           :target {:case :target/primitive :id "m"}
           :payload {:exported exports}}))))

(defn- with-entity [model id field-name field-type-id]
  (-> model
      (build/add-primitive
        (p/make-container
          {:id id :label id
           :fields [(p/make-field field-name
                                  (t/make-composite-named field-type-id)
                                  false)]}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Entity"}
           :target {:case :target/primitive :id id}}))))

(defn- with-bare-entity [model id]
  (-> model
      (build/add-primitive (p/make-container {:id id :label id}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Entity"}
           :target {:case :target/primitive :id id}}))))

(deftest exported-entity-with-non-exported-field-type-is-error
  ;; Module exports Order; Order has a field of type Customer; Customer is in
  ;; the same module but NOT exported.
  (let [model (-> (module ["Order"])
                  (with-entity "m::Order" "customer" "m::Customer")
                  (with-bare-entity "m::Customer"))
        violations (r4f/check model)
        relevant (filter #(= :4f/closure-violation (:kind %)) violations)]
    (is (pos? (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest closure-satisfied-when-referenced-type-is-exported
  (let [model (-> (module ["Order" "Customer"])
                  (with-entity "m::Order" "customer" "m::Customer")
                  (with-bare-entity "m::Customer"))
        errors (filter #(= :error (:severity %)) (r4f/check model))]
    (is (empty? errors))))

(deftest closure-satisfied-via-external-entity
  ;; Order has a field of type Address; Address is declared external_entity.
  (let [model (-> (module ["Order"])
                  (with-entity "m::Order" "address" "m::Address")
                  (build/add-primitive (p/make-container {:id "m::Address" :label "Address"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "ExternalEntity"}
                       :target {:case :target/primitive :id "m::Address"}})))
        errors (filter #(= :error (:severity %)) (r4f/check model))]
    (is (empty? errors))))

(deftest closure-satisfied-via-foreign-module-export
  ;; Order in module m has a field of type a::Foo; a exports Foo.
  (let [model (-> (module ["Order"])
                  (with-entity "m::Order" "foo" "a::Foo")
                  ;; Add module a with Foo exported
                  (build/add-primitive (p/make-container {:id "a" :label "a"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "a"}}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Boundary" :name "ModuleApi"}
                       :target {:case :target/primitive :id "a"}
                       :payload {:exported ["Foo"]}}))
                  (with-bare-entity "a::Foo"))
        errors (filter #(= :error (:severity %)) (r4f/check model))]
    (is (empty? errors))))
