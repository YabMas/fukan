(ns fukan.target.clojure.analyzer-function-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- model-with-operation [op-name]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive
        (p/make-operation {:id (str "m::Contract." op-name) :label op-name
                           :parameters []}))))

(deftest operation-with-matching-source-emits-valid-projects-edge
  (testing "operation→function emits :validity :valid when source exists"
    ;; Use a fixture: a Clojure file declaring (defn submit [] ...)
    ;; at namespace 'm' (using empty root-prefix).
    (let [model (model-with-operation "submit")
          reg (registry/make-registry)
          m1 (analyzer/run model reg "test/fixtures/clojure-projects/m-with-submit")
          edges (filter #(= :relation/projects (:kind %)) (:edges m1))]
      (is (>= (count edges) 1) "at least one projects edge for the operation")
      (let [op-edge (first (filter #(= "m::Contract.submit" (-> % :from :id)) edges))]
        (is (= :valid (:validity op-edge)))))))

(deftest operation-without-source-emits-absent-projects-edge
  (testing "operation→function emits :validity :absent when source missing"
    (let [model (model-with-operation "ghost")
          reg (registry/make-registry)
          m1 (analyzer/run model reg "test/fixtures/clojure-projects/empty")
          edges (filter #(and (= :relation/projects (:kind %))
                              (= "m::Contract.ghost" (-> % :from :id)))
                        (:edges m1))]
      (is (>= (count edges) 1))
      (is (some #(= :absent (:validity %)) edges)))))

(deftest rule-emits-projects-edge
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-rule {:id "m::ProcessOrder" :label "ProcessOrder"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Rule"}
                       :target {:case :target/primitive :id "m::ProcessOrder"}})))
        reg (registry/make-registry)
        m1 (analyzer/run model reg "test/fixtures/clojure-projects/empty")
        edges (filter #(and (= :relation/projects (:kind %))
                            (= "m::ProcessOrder" (-> % :from :id)))
                      (:edges m1))]
    (is (pos? (count edges)))
    ;; Rules also project to a test artifact, so we expect 2 edges per rule
    ;; (one for :rule, one for :test).
    (is (= 2 (count edges)))))
