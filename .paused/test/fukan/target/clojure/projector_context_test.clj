(ns fukan.target.clojure.projector-context-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.relations :as r]))

(defn- module-with [primitive]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive primitive)))

(deftest rule-context-includes-description
  (let [r (p/make-rule {:id "m::CheckIt" :label "CheckIt"
                        :description "checks that the thing is ok"})
        model (module-with r)
        bp (projector/project model (registry/make-registry)
                              "m::CheckIt" :projection-kind/rule)]
    (is (= "checks that the thing is ok" (-> bp :context :description)))))

(deftest rule-context-related-edges-filters-projects
  (let [r1 (p/make-rule {:id "m::R1" :label "R1"})
        e1 (p/make-event {:id "m::events::E1" :label "E1" :parameters []})
        model (-> (module-with r1)
                  (build/add-primitive e1)
                  (build/add-edge (r/make-edge :relation/emits
                                               (r/primitive-ref "m::R1")
                                               (r/primitive-ref "m::events::E1"))))
        bp (projector/project model (registry/make-registry)
                              "m::R1" :projection-kind/rule)
        rel (:related-edges (:context bp))]
    (is (= 1 (count rel)))
    (is (= :relation/emits (-> rel first :kind)))
    (is (= "E1" (-> rel first :to-label)))))
