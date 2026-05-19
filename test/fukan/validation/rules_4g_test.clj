(ns fukan.validation.rules-4g-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4g :as r4g]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

(defn- module
  "Add a module-Container; if exports is non-nil, also add Boundary::ModuleApi."
  ([model coord] (module model coord nil))
  ([model coord exports]
   (cond-> (-> model
               (build/add-primitive (p/make-container {:id coord :label coord}))
               (build/add-tag-application
                 (v/make-tag-application
                   {:tag {:namespace "Allium" :name "Module"}
                    :target {:case :target/primitive :id coord}})))
     exports
     (build/add-tag-application
       (v/make-tag-application
         {:tag {:namespace "Boundary" :name "ModuleApi"}
          :target {:case :target/primitive :id coord}
          :payload {:exported exports}})))))

(defn- with-entity [model id]
  (-> model
      (build/add-primitive (p/make-container {:id id :label id}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Entity"}
           :target {:case :target/primitive :id id}}))))

(defn- with-entity-referencing [model id field-name target-id]
  (-> model
      (build/add-primitive
        (p/make-container
          {:id id :label id
           :fields [(p/make-field field-name (t/make-composite-named target-id) false)]}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Entity"}
           :target {:case :target/primitive :id id}}))))

(deftest cross-module-reference-to-private-is-error
  ;; Module A is closed: exports ["Foo"]. Module B has an Entity with a
  ;; field of type Composite-named("a::Bar") — Bar is in A but not exported.
  (let [model (-> (build/empty-model)
                  (module "a" ["Foo"])
                  (with-entity "a::Foo")
                  (with-entity "a::Bar")
                  (module "b")
                  (with-entity-referencing "b::Order" "bar" "a::Bar"))
        violations (r4g/check model)
        relevant (filter #(= :4g/cross-module-private-reference (:kind %)) violations)]
    (is (pos? (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest cross-module-reference-to-exported-is-ok
  (let [model (-> (build/empty-model)
                  (module "a" ["Foo"])
                  (with-entity "a::Foo")
                  (module "b")
                  (with-entity-referencing "b::Order" "foo" "a::Foo"))
        errors (filter #(= :error (:severity %)) (r4g/check model))]
    (is (empty? errors))))

(deftest references-to-open-module-allowed
  ;; Module A is open (no Boundary::ModuleApi); all top-level decls visible.
  (let [model (-> (build/empty-model)
                  (module "a")
                  (with-entity "a::AnyThing")
                  (with-entity-referencing "b::Other" "x" "a::AnyThing"))
        errors (filter #(= :error (:severity %)) (r4g/check model))]
    (is (empty? errors))))

(deftest same-module-references-allowed
  ;; A primitive referencing another primitive in the SAME module is never a violation.
  (let [model (-> (build/empty-model)
                  (module "a" ["Foo"])
                  (with-entity "a::Bar")
                  (with-entity-referencing "a::Foo" "bar" "a::Bar"))
        errors (filter #(= :error (:severity %)) (r4g/check model))]
    (is (empty? errors) "same-module refs don't trigger 4g")))
