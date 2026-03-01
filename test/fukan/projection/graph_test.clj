(ns fukan.projection.graph-test
  "Generative + integration tests for graph projection.
   Verifies projection invariants from projection.allium hold across
   randomly generated models and Fukan's own model."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as tgen]
            [clojure.test.check.properties :as prop]
            [fukan.model.build :as build]
            [fukan.projection.graph :as graph]
            [fukan.test-support.generators :as gen]
            [fukan.test-support.invariants.projection :as inv]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- is-leaf? [model view-id]
  (empty? (get-in model [:nodes view-id :children] #{})))

;; ---------------------------------------------------------------------------
;; Generative: module view invariants

(defspec module-view-bounding-box 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [projection (graph/entity-graph model opts)]
                         (true? (inv/strict-bounding-box? model opts projection)))
                       true))
                   opts-gen)))))

(defspec no-ancestor-descendant-edges 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (let [projection (graph/entity-graph model opts)]
                       (true? (inv/no-ancestor-descendant-edges? model opts projection))))
                   opts-gen)))))

(defspec schema-nodes-filtered 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [projection (graph/entity-graph model opts)]
                         (true? (inv/schema-filtering? model opts projection)))
                       true))
                   opts-gen)))))

(defspec private-nodes-hidden-unless-expanded 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [projection (graph/entity-graph model opts)]
                         (true? (inv/private-visibility? model opts projection)))
                       true))
                   opts-gen)))))

(defspec no-duplicate-edges-in-projection 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (let [projection (graph/entity-graph model opts)]
                       (true? (inv/no-duplicate-edges? model opts projection))))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Generative: leaf-to-leaf code-flow edges

(defspec leaf-to-leaf-code-flow-edges 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [projection (graph/entity-graph model opts)]
                         (true? (inv/leaf-to-leaf-edges? model opts projection)))
                       true))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Integration: Fukan's own model satisfies projection invariants

(deftest fukan-projection-invariants
  (testing "Fukan's own model satisfies projection invariants for every module"
    (let [model (build/build-model "src")
          module-ids (->> (vals (:nodes model))
                              (filter #(= :module (:kind %)))
                              (map :id))]
      (is (pos? (count module-ids)) "should have modules")
      (doseq [cid module-ids]
        (let [opts {:view-id cid :expanded-modules #{}}
              projection (graph/entity-graph model opts)]
          (is (true? (inv/strict-bounding-box? model opts projection))
              (str "bounding-box failed for " cid))
          (is (true? (inv/no-ancestor-descendant-edges? model opts projection))
              (str "ancestor-descendant-edges failed for " cid))
          (is (true? (inv/schema-filtering? model opts projection))
              (str "schema-filtering failed for " cid))
          (is (true? (inv/no-duplicate-edges? model opts projection))
              (str "duplicate-edges failed for " cid))
          (is (true? (inv/leaf-to-leaf-edges? model opts projection))
              (str "leaf-to-leaf-edges failed for " cid)))))))
