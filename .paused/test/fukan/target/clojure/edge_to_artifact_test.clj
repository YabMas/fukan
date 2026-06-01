(ns fukan.target.clojure.edge-to-artifact-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.build :as build]
            [fukan.model.relations :as r]
            [fukan.model.artifact :as a]
            [fukan.model.primitives :as p]))

(deftest projects-edge-from-primitive-to-artifact-resolves
  (testing "an :artifact-endpoint addressing an artifact in :artifacts resolves cleanly"
    (let [artifact (a/make-code-function "clojure" "ns/foo")
          art-id   (a/artifact-identity artifact)
          model    (-> (build/empty-model)
                       (build/add-primitive
                         (p/make-rule {:id "m::R" :label "R"}))
                       (assoc-in [:artifacts art-id] artifact))
          edge     (r/make-edge :relation/projects
                                (r/primitive-ref "m::R")
                                (r/artifact-ref art-id))
          m1       (build/add-edge model edge)]
      (is (some #(= edge %) (:edges m1))))))

(deftest projects-edge-to-missing-artifact-throws
  (testing "edge to an artifact id NOT in :artifacts is rejected"
    (let [edge (r/make-edge :relation/projects
                            (r/primitive-ref "m::R")
                            (r/artifact-ref [:code/function "clojure" "ns/missing"]))
          model (-> (build/empty-model)
                    (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))]
      (is (thrown? Exception
                   (build/add-edge model edge))))))
