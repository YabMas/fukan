(ns fukan.projection.core-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.projection.core :as proj]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]))

(deftest project-model-on-empty-returns-empty-graph
  (let [g (proj/project-model (build/empty-model))]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))

(deftest project-model-includes-primitives-as-nodes
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))
        g (proj/project-model model)
        ids (set (map :id (:nodes g)))]
    (is (contains? ids "m"))
    (is (contains? ids "m::R"))
    (is (= 2 (count (:nodes g))))))

(deftest project-model-includes-artifacts-as-nodes
  (let [artifact {:case :artifact/code
                  :language "clojure"
                  :sub {:case :code/function :qualified-name "m/foo"}}
        model (-> (build/empty-model)
                  (assoc-in [:artifacts [:code/function "clojure" "m/foo"]] artifact))
        g (proj/project-model model)
        ids (set (map :id (:nodes g)))]
    (is (contains? ids "artifact:code/function:clojure:m/foo"))
    (is (= 1 (count (:nodes g))))))

(deftest project-model-edges-include-relation-kind
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-primitive (p/make-event {:id "m::E" :label "E" :parameters []}))
                  (build/add-edge (r/make-edge :relation/emits
                                               (r/primitive-ref "m::R")
                                               (r/primitive-ref "m::E"))))
        g (proj/project-model model)]
    (is (= 1 (count (:edges g))))
    (is (= :relation/emits (-> g :edges first :kind)))
    (is (= "m::R" (-> g :edges first :from)))
    (is (= "m::E" (-> g :edges first :to)))))
