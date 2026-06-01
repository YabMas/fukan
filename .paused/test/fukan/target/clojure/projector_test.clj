(ns fukan.target.clojure.projector-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(deftest project-operation-as-operation
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-operation
                                         {:id "m::Contract.submit" :label "submit"
                                          :parameters []})))
        bp (projector/project model (registry/make-registry)
                              "m::Contract.submit" :projection-kind/operation)]
    (is (= "m::Contract.submit" (:primitive-id bp)))
    (is (= :projection-kind/operation (:projection-kind bp)))
    (is (= {:ns "m" :name "submit"} (:address bp)))
    (is (= :code/function (:artifact-kind bp)))))

(deftest project-rule-as-test
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-rule {:id "m::ProcessOrder" :label "ProcessOrder"})))
        bp (projector/project model (registry/make-registry)
                              "m::ProcessOrder" :projection-kind/test)]
    (is (= {:ns "m-test" :name "process-order-test"} (:address bp)))
    (is (= :code/function (:artifact-kind bp)))))

(deftest project-entity-as-schema
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
        bp (projector/project model (registry/make-registry)
                              "m::Order" :projection-kind/schema)]
    (is (= {:ns "m" :name "Order"} (:address bp)))
    (is (= :code/data-structure (:artifact-kind bp)))))

(deftest project-unknown-primitive-throws
  (let [model (build/empty-model)]
    (is (thrown? Exception
                 (projector/project model (registry/make-registry)
                                    "missing::F" :projection-kind/rule)))))
