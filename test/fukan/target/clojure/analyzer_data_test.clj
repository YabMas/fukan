(ns fukan.target.clojure.analyzer-data-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(deftest entity-projects-to-data-structure
  (testing "entity → Code.DataStructure with schema projection-kind"
    (let [model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m" :label "m"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Module"}
                         :target {:case :target/primitive :id "m"}}))
                    (build/add-primitive (p/make-container {:id "m::Order" :label "Order"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Entity"}
                         :target {:case :target/primitive :id "m::Order"}})))
          m1 (analyzer/run model (registry/make-registry) "test/fixtures/clojure-projects/empty")
          edges (filter #(and (= :relation/projects (:kind %))
                              (= "m::Order" (-> % :from :id)))
                        (:edges m1))]
      (is (= 1 (count edges))
          "exactly one schema edge per entity")
      (is (= :absent (:validity (first edges)))))))

(deftest event-projects-to-data-structure
  (testing "event → Code.DataStructure with schema projection-kind"
    (let [model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m" :label "m"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Module"}
                         :target {:case :target/primitive :id "m"}}))
                    (build/add-primitive (p/make-event {:id "m::events::OrderPlaced"
                                                        :label "OrderPlaced"})))
          m1 (analyzer/run model (registry/make-registry) "test/fixtures/clojure-projects/empty")
          edges (filter #(and (= :relation/projects (:kind %))
                              (= "m::events::OrderPlaced" (-> % :from :id)))
                        (:edges m1))]
      (is (= 1 (count edges))))))

(deftest invariant-with-label-projects-from-substrate-address
  ;; Construct a Container with an invariant assertion (Bool Expression
  ;; with non-nil :label) tagged Allium::Invariant.
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  ;; Add an intent.assertions[0] with label "NoNegativeBalance"
                  (update-in [:primitives "m"]
                             assoc-in [:intent :assertions]
                             [{:label "NoNegativeBalance" :form {}}])
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Invariant"}
                       :target {:case :target/substrate :container "m"
                                :path [{:slot "intent"} {:slot "assertions" :key "0"}]}})))
        m1 (analyzer/run model (registry/make-registry) "test/fixtures/clojure-projects/empty")
        edges (filter #(and (= :relation/projects (:kind %))
                            (= :projection-kind/invariant (:projection-kind %)))
                      (:edges m1))]
    (is (= 1 (count edges)))
    (is (= :absent (:validity (first edges))))))
