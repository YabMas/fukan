(ns fukan.projection.graph-test
  "Generative + integration tests for graph projection.
   Verifies projection invariants from projection.allium hold across
   randomly generated models and Fukan's own model."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as tgen]
            [clojure.test.check.properties :as prop]
            [fukan.projection.graph :as graph]
            [fukan.projection.path :as path]
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
;; Generative: leaf view invariants

(defspec leaf-view-invariants 100
  (prop/for-all [model (gen/gen-model)]
    (let [;; Pick a leaf node (function or schema) as view-id
          leaf-ids (->> (vals (:nodes model))
                        (filter #(empty? (:children % #{})))
                        (map :id)
                        vec)]
      (if (empty? leaf-ids)
        true
        (tgen/generate
          (tgen/fmap (fn [[leaf-idx edge-type-choice perspective]]
                       (let [leaf-id (nth leaf-ids leaf-idx)
                             visible-edge-types (case edge-type-choice
                                                  0 #{:code-flow :schema-reference}
                                                  1 #{:code-flow}
                                                  2 #{:schema-reference})
                             opts {:view-id leaf-id
                                   :expanded #{}
                                   :show-private #{}
                                   :visible-edge-types visible-edge-types
                                   :perspective (if (zero? perspective) :call-graph :dependency-graph)}
                             projection (graph/entity-graph model opts)]
                         (true? (inv/valid-leaf-projection? model opts projection))))
                     (tgen/tuple (tgen/choose 0 (dec (count leaf-ids)))
                                 (tgen/choose 0 2)
                                 (tgen/choose 0 1))))))))

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
;; Finding 2: Default visible-edge-types should be #{:code-flow :schema-reference}

(deftest default-visible-edge-types-test
  (testing "When visible-edge-types is nil, defaults to #{:code-flow :schema-reference}"
    (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                  :children #{"ns-a"}
                                  :data {:kind :module}}
                          "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                                  :children #{"fn-a" "schema-a"}
                                  :data {:kind :module}}
                          "fn-a" {:id "fn-a" :kind :function :label "fn-a" :parent "ns-a"
                                  :children #{} :data {:kind :function :private? false}}
                          "schema-a" {:id "schema-a" :kind :schema :label "MySchema" :parent "ns-a"
                                      :children #{} :data {:kind :schema :schema-key :MySchema}}}
                  :edges []}
          opts {:view-id "root" :expanded #{"ns-a"}}
          projection (graph/entity-graph model opts)
          node-ids (set (map :id (:nodes projection)))]
      ;; Both function and schema nodes should be visible by default
      (is (contains? node-ids "fn-a") "function nodes visible by default")
      (is (contains? node-ids "schema-a") "schema nodes visible by default"))))

;; ---------------------------------------------------------------------------
;; Finding 3: ViewEdge should preserve model-level :kind

(deftest view-edge-preserves-model-kind
  (testing "Projected edges preserve the model-level edge kind"
    (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                  :children #{"ns-a" "ns-b"}
                                  :data {:kind :module}}
                          "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                                  :children #{"fn-a"} :data {:kind :module}}
                          "ns-b" {:id "ns-b" :kind :module :label "ns-b" :parent "root"
                                  :children #{"fn-b"} :data {:kind :module}}
                          "fn-a" {:id "fn-a" :kind :function :label "fn-a" :parent "ns-a"
                                  :children #{} :data {:kind :function :private? false}}
                          "fn-b" {:id "fn-b" :kind :function :label "fn-b" :parent "ns-b"
                                  :children #{} :data {:kind :function :private? false}}}
                  :edges [{:from "fn-a" :to "fn-b" :kind :function-call}]}
          opts {:view-id "root" :expanded #{} :show-private #{}
                :visible-edge-types #{:code-flow}}
          projection (graph/entity-graph model opts)
          edge (first (:edges projection))]
      (is (some? edge))
      (is (= :function-call (:kind edge)) "edge should have model-level :kind"))))

;; ---------------------------------------------------------------------------
;; Finding 1: Perspective control — dispatches edges flip in dependency-graph

(deftest perspective-dispatches-flip
  (testing "dependency-graph perspective flips dispatches edges"
    (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                  :children #{"ns-a" "ns-b"}
                                  :data {:kind :module}}
                          "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                                  :children #{"dispatch-point"} :data {:kind :module}}
                          "ns-b" {:id "ns-b" :kind :module :label "ns-b" :parent "root"
                                  :children #{"handler"} :data {:kind :module}}
                          "dispatch-point" {:id "dispatch-point" :kind :function :label "dispatch-point"
                                            :parent "ns-a" :children #{}
                                            :data {:kind :function :private? false}}
                          "handler" {:id "handler" :kind :function :label "handler"
                                     :parent "ns-b" :children #{}
                                     :data {:kind :function :private? false}}}
                  ;; dispatches: dispatch-point → handler (canonical call_graph direction)
                  :edges [{:from "dispatch-point" :to "handler" :kind :dispatches}]}]

      (testing "call-graph perspective: dispatch-point → handler (canonical)"
        (let [opts {:view-id "root" :expanded #{} :show-private #{}
                    :visible-edge-types #{:code-flow} :perspective :call-graph}
              projection (graph/entity-graph model opts)
              edge-pairs (set (map (juxt :from :to) (:edges projection)))]
          (is (contains? edge-pairs ["ns-a" "ns-b"]))))

      (testing "dependency-graph perspective: handler → dispatch-point (flipped)"
        (let [opts {:view-id "root" :expanded #{} :show-private #{}
                    :visible-edge-types #{:code-flow} :perspective :dependency-graph}
              projection (graph/entity-graph model opts)
              edge-pairs (set (map (juxt :from :to) (:edges projection)))]
          (is (contains? edge-pairs ["ns-b" "ns-a"])))))))

;; ---------------------------------------------------------------------------
;; Finding 1+8: Perspective in leaf view

(deftest leaf-view-perspective-dispatches-flip
  (testing "leaf view respects perspective for dispatches edges"
    (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                  :children #{"ns-a" "ns-b"}
                                  :data {:kind :module}}
                          "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                                  :children #{"dispatch-point"} :data {:kind :module}}
                          "ns-b" {:id "ns-b" :kind :module :label "ns-b" :parent "root"
                                  :children #{"handler"} :data {:kind :module}}
                          "dispatch-point" {:id "dispatch-point" :kind :function :label "dispatch-point"
                                            :parent "ns-a" :children #{}
                                            :data {:kind :function :private? false}}
                          "handler" {:id "handler" :kind :function :label "handler"
                                     :parent "ns-b" :children #{}
                                     :data {:kind :function :private? false}}}
                  :edges [{:from "dispatch-point" :to "handler" :kind :dispatches}]}]

      (testing "call-graph: dispatch-point → handler"
        (let [opts {:view-id "dispatch-point" :perspective :call-graph
                    :visible-edge-types #{:code-flow}}
              projection (graph/entity-graph model opts)
              edge-pairs (set (map (juxt :from :to) (:edges projection)))]
          (is (contains? edge-pairs ["dispatch-point" "handler"]))))

      (testing "dependency-graph: handler → dispatch-point (flipped)"
        (let [opts {:view-id "dispatch-point" :perspective :dependency-graph
                    :visible-edge-types #{:code-flow}}
              projection (graph/entity-graph model opts)
              edge-pairs (set (map (juxt :from :to) (:edges projection)))]
          (is (contains? edge-pairs ["handler" "dispatch-point"])))))))

;; ---------------------------------------------------------------------------
;; Finding 9: ConsistentAggregation invariant

(defspec consistent-aggregation 50
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (if (and (:view-id opts) (not (is-leaf? model (:view-id opts))))
                       (let [;; Pick a child module to expand then collapse
                             children (get-in model [:nodes (:view-id opts) :children] #{})
                             child-modules (->> children
                                                (filter #(= :module (:kind (get-in model [:nodes %]))))
                                                vec)]
                         (if (seq child-modules)
                           (let [mod-to-toggle (first child-modules)
                                 ;; Collapsed
                                 proj-before (graph/entity-graph model opts)
                                 ;; Expanded
                                 expanded-opts (update opts :expanded conj mod-to-toggle)
                                 _ (graph/entity-graph model expanded-opts)
                                 ;; Collapsed again
                                 proj-after (graph/entity-graph model opts)]
                             ;; Same nodes and same edge connectivity (modulo edge IDs)
                             (and (= (set (map :id (:nodes proj-before)))
                                     (set (map :id (:nodes proj-after))))
                                  (= (set (map (juxt :from :to :edge-type) (:edges proj-before)))
                                     (set (map (juxt :from :to :edge-type) (:edges proj-after))))))
                           true))
                       true))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; EntityPath returns vector

(deftest entity-path-returns-vector
  (testing "entity-path returns a vector, not a lazy seq"
    (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                  :children #{"child"} :data {:kind :module}}
                          "child" {:id "child" :kind :module :label "child" :parent "root"
                                   :children #{} :data {:kind :module}}}}]
      (is (vector? (path/entity-path model nil)) "root path should be a vector")
      (is (vector? (path/entity-path model "child")) "non-root path should be a vector"))))

;; ---------------------------------------------------------------------------
;; AggregateEdges dedup preserves multiple model kinds

(deftest aggregate-edges-preserves-distinct-kinds
  (testing "When both function-call and dispatches aggregate to same [from to], both edges appear"
    (let [model {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                                  :children #{"ns-a" "ns-b"} :data {:kind :module}}
                          "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                                  :children #{"caller" "dp"} :data {:kind :module}}
                          "ns-b" {:id "ns-b" :kind :module :label "ns-b" :parent "root"
                                  :children #{"callee" "handler"} :data {:kind :module}}
                          "caller" {:id "caller" :kind :function :label "caller" :parent "ns-a"
                                    :children #{} :data {:kind :function :private? false}}
                          "dp" {:id "dp" :kind :function :label "dp" :parent "ns-a"
                                :children #{} :data {:kind :function :private? false}}
                          "callee" {:id "callee" :kind :function :label "callee" :parent "ns-b"
                                    :children #{} :data {:kind :function :private? false}}
                          "handler" {:id "handler" :kind :function :label "handler" :parent "ns-b"
                                     :children #{} :data {:kind :function :private? false}}}
                  :edges [{:from "caller" :to "callee" :kind :function-call}
                          {:from "dp" :to "handler" :kind :dispatches}]}
          opts {:view-id "root" :expanded #{} :show-private #{}
                :visible-edge-types #{:code-flow}}
          projection (graph/entity-graph model opts)
          code-edges (filter #(= :code-flow (:edge-type %)) (:edges projection))
          edge-kinds (set (map :kind code-edges))]
      ;; Both kinds should be preserved as separate edges
      (is (contains? edge-kinds :function-call))
      (is (contains? edge-kinds :dispatches)))))

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
