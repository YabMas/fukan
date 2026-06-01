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
                    (build/add-primitive (p/make-container {:id "m/Order" :label "Order"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Entity"}
                         :target {:case :target/primitive :id "m/Order"}})))
          m1 (analyzer/run model (registry/make-registry) "test/fixtures/clojure-projects/empty")
          edges (filter #(and (= :relation/projects (:kind %))
                              (= "m/Order" (-> % :from :id)))
                        (:edges m1))]
      (is (= 1 (count edges))
          "exactly one schema edge per entity")
      (is (= :absent (:validity (first edges)))))))

(deftest canvas-style-container-projects-to-data-structure
  (testing "container with canvas-style /type/ id projects without Allium::Entity tag"
    ;; Gap 3: canvas-source emits records/values as :primitive/container
    ;; WITHOUT the legacy Allium::Entity tag. The analyzer's selector must
    ;; recognise them via primitive kind + id shape rather than the tag.
    (let [model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m" :label "m"}))
                    (build/add-primitive (p/make-container {:id "m/type/ServerOpts"
                                                            :label "ServerOpts"})))
          m1 (analyzer/run model (registry/make-registry)
                           "test/fixtures/clojure-projects/empty")
          edges (filter #(and (= :relation/projects (:kind %))
                              (= "m/type/ServerOpts" (-> % :from :id)))
                        (:edges m1))]
      (is (= 1 (count edges))
          "exactly one schema edge per canvas-style type primitive")
      (is (some? (first edges))))))

(deftest event-projects-to-data-structure
  (testing "event → Code.DataStructure with schema projection-kind"
    (let [model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m" :label "m"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Module"}
                         :target {:case :target/primitive :id "m"}}))
                    (build/add-primitive (p/make-event {:id "m/events/OrderPlaced"
                                                        :label "OrderPlaced"})))
          m1 (analyzer/run model (registry/make-registry) "test/fixtures/clojure-projects/empty")
          edges (filter #(and (= :relation/projects (:kind %))
                              (= "m/events/OrderPlaced" (-> % :from :id)))
                        (:edges m1))]
      (is (= 1 (count edges))))))

;; The analyzer no longer emits :projection-kind/invariant or
;; :projection-kind/test edges (verification projections were removed —
;; see feat(target-clojure): stop emitting verification projection
;; expectations). A future verification story will reintroduce these
;; with concrete consumers driving the design.
