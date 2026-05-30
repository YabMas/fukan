(ns fukan.validation.rules-4d-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4d :as r4d]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- module-with-api [exports]
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
           :payload {:canvas/exported exports}}))))

(deftest exports-unresolved-entry-is-error
  (let [model (module-with-api ["NonexistentEntity"])
        violations (r4d/check model)
        relevant (filter #(= :4d/exports-unresolved (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest exports-listing-a-rule-is-error
  (let [model (-> (module-with-api ["MyRule"])
                  (build/add-primitive (p/make-rule {:id "m::MyRule" :label "MyRule"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Rule"}
                       :target {:case :target/primitive :id "m::MyRule"}})))
        violations (r4d/check model)
        relevant (filter #(= :4d/exports-disallowed-kind (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest multiple-module-api-tags-on-one-module-is-error
  (let [model (-> (module-with-api ["A"])
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Boundary" :name "ModuleApi"}
                       :target {:case :target/primitive :id "m"}
                       :payload {:canvas/exported ["B"]}})))
        violations (r4d/check model)
        relevant (filter #(= :4d/multiple-module-api-tags (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest clean-module-api-produces-no-4d-errors
  (let [model (-> (module-with-api ["Order"])
                  (build/add-primitive
                    (p/make-container {:id "m::Order" :label "Order"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Entity"}
                       :target {:case :target/primitive :id "m::Order"}})))
        errors (filter #(= :error (:severity %)) (r4d/check model))]
    (is (empty? errors))))
