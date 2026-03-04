(ns fukan.projection.graph-test
  "Generative + integration tests for graph projection.
   Verifies projection invariants from projection.allium hold across
   randomly generated models and Fukan's own model."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as tgen]
            [clojure.test.check.properties :as prop]
            [fukan.projection.graph :as graph]
            [fukan.test-support.fixtures :as fix]
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
;; Generative: visible-node edges (all edge types)

(defspec visible-node-edges 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [projection (graph/entity-graph model opts)]
                         (true? (inv/visible-node-edges? model opts projection)))
                       true))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Generative: showing-private consistency

(defspec showing-private-consistency 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (let [projection (graph/entity-graph model opts)]
                       (true? (inv/showing-private-consistent? model opts projection))))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Generative: no subsumed edges (all edge types)

(defspec no-subsumed-edges-in-projection 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [projection (graph/entity-graph model opts)]
                         (true? (inv/no-subsumed-edges? model opts projection)))
                       true))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Example-based: private inheritance

(deftest private-inheritance-test
  (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                :children #{"ns-a" "ns-b"} :data {:boundary {:description nil}}}
                        "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                                :children #{"dispatch" "cmd-find"} :data {:boundary {:description nil}}}
                        "ns-b" {:id "ns-b" :kind :module :label "ns-b" :parent "root"
                                :children #{"navigate"} :data {:boundary {:description nil}}}
                        "dispatch" {:id "dispatch" :kind :function :label "dispatch" :parent "ns-a"
                                    :children #{} :data {:private? false}}
                        "cmd-find" {:id "cmd-find" :kind :function :label "cmd-find" :parent "ns-a"
                                    :children #{} :data {:private? true}}
                        "navigate" {:id "navigate" :kind :function :label "navigate" :parent "ns-b"
                                    :children #{} :data {:private? false}}}
                :edges [{:from "dispatch" :to "cmd-find" :kind :function-call}
                        {:from "cmd-find" :to "navigate" :kind :function-call}]}]

    (testing "With collapsed modules, edges aggregate to module nodes"
      (let [opts {:view-id "root" :expanded #{} :show-private #{}}
            projection (graph/entity-graph model opts)
            node-ids (set (map :id (:nodes projection)))
            code-edges (set (map (juxt :from :to)
                                 (filter #(= :code-flow (:edge-type %))
                                         (:edges projection))))]
        ;; Only module-level nodes visible (collapsed)
        (is (contains? node-ids "ns-a"))
        (is (contains? node-ids "ns-b"))
        (is (not (contains? node-ids "dispatch")))
        (is (not (contains? node-ids "navigate")))
        ;; Edge aggregates to module level
        (is (contains? code-edges ["ns-a" "ns-b"]))))

    (testing "With expanded modules, inherited edge appears between functions"
      (let [opts {:view-id "root" :expanded #{"ns-a" "ns-b"} :show-private #{}}
            projection (graph/entity-graph model opts)
            node-ids (set (map :id (:nodes projection)))
            code-edges (set (map (juxt :from :to)
                                 (filter #(= :code-flow (:edge-type %))
                                         (:edges projection))))]
        ;; Functions now visible
        (is (contains? node-ids "dispatch"))
        (is (contains? node-ids "navigate"))
        ;; cmd-find still hidden (private, not show-private)
        (is (not (contains? node-ids "cmd-find")))
        ;; Inherited edge from dispatch to navigate
        (is (contains? code-edges ["dispatch" "navigate"]))))))

;; ---------------------------------------------------------------------------
;; Integration: Fukan's own model satisfies projection invariants

(deftest fukan-projection-invariants
  (testing "Fukan's own model satisfies projection invariants for every module"
    (let [model (fix/build-self-model)
          module-ids (->> (vals (:nodes model))
                              (filter #(= :module (:kind %)))
                              (map :id))]
      (is (pos? (count module-ids)) "should have modules")
      (doseq [cid module-ids]
        (let [opts {:view-id cid :expanded #{} :show-private #{}}
              projection (graph/entity-graph model opts)]
          (is (true? (inv/strict-bounding-box? model opts projection))
              (str "bounding-box failed for " cid))
          (is (true? (inv/no-ancestor-descendant-edges? model opts projection))
              (str "ancestor-descendant-edges failed for " cid))
          (is (true? (inv/no-duplicate-edges? model opts projection))
              (str "duplicate-edges failed for " cid))
          (is (true? (inv/visible-node-edges? model opts projection))
              (str "visible-node-edges failed for " cid))
          (is (true? (inv/no-subsumed-edges? model opts projection))
              (str "no-subsumed-edges failed for " cid)))))))
