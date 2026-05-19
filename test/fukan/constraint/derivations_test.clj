(ns fukan.constraint.derivations-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.derivations :as d]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(deftest primitive-tuples
  (let [m (-> (build/empty-model)
              (build/add-primitive (p/make-container {:id "m::Order" :label "Order"})))
        edb (d/model->edb m)]
    (is (= #{["m::Order"]} (:primitive edb)))
    (is (= #{["m::Order" :primitive/container]} (:primitive-kind edb)))))

(deftest tag-tuples
  (let [m (-> (build/empty-model)
              (build/add-primitive (p/make-container {:id "x" :label "x"}))
              (build/add-tag-application
                (v/make-tag-application
                  {:tag {:namespace "Allium" :name "Module"}
                   :target {:case :target/primitive :id "x"}})))
        edb (d/model->edb m)]
    (is (contains? (:has-tag edb) ["x" "Allium::Module"]))))

(deftest in-module-derivation
  (testing "primitives are placed in modules by id prefix"
    (let [m (-> (build/empty-model)
                (build/add-primitive (p/make-container {:id "m" :label "m"}))
                (build/add-tag-application
                  (v/make-tag-application
                    {:tag {:namespace "Allium" :name "Module"}
                     :target {:case :target/primitive :id "m"}}))
                (build/add-primitive (p/make-container {:id "m::Order" :label "Order"})))
          edb (d/model->edb m)]
      (is (contains? (:in-module edb) ["m::Order" "m"])))))

(deftest edge-tuples
  (let [m {:primitives {} :edges [{:kind :relation/triggers
                                   :from {:id "op-id"} :to {:id "rule-id"}}]
           :tag-apps [] :tag-defs [] :predicates [] :renderers [] :artifacts {}}
        edb (d/model->edb m)]
    (is (= #{["op-id" :relation/triggers "rule-id"]} (:edge edb)))))
