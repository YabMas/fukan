(ns fukan.web.views.graph-test
  "Tests for graph view rendering.
   Verifies render determinism, selection defaults, and edge highlighting
   using view invariants from views.allium."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as tgen]
            [clojure.test.check.properties :as prop]
            [fukan.projection.graph :as graph]
            [fukan.web.views.graph :as views]
            [fukan.test-support.generators :as gen]
            [fukan.test-support.invariants.views :as inv]))

;; ---------------------------------------------------------------------------
;; Generative: render is deterministic

(defspec render-graph-deterministic 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (let [projection (graph/entity-graph model opts)
                           editor-state {:view-id (:view-id opts)
                                         :selected-id nil
                                         :schema-id nil
                                         :expanded (:expanded opts)
                                         :show-private (:show-private opts)}]
                       (true? (inv/render-pure? views/render-graph projection editor-state))))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Example-based: selection default

(def test-model
  {:nodes {"root"     {:id "root" :kind :module :label "root" :parent nil
                        :children #{"ns:a" "ns:b"}
                        :data {:kind :module :boundary {:description nil}}}
           "ns:a"     {:id "ns:a" :kind :module :label "a" :parent "root"
                        :children #{"ns:a/f"}
                        :data {:kind :module :boundary {:description nil}}}
           "ns:b"     {:id "ns:b" :kind :module :label "b" :parent "root"
                        :children #{"ns:b/g"}
                        :data {:kind :module :boundary {:description nil}}}
           "ns:a/f"   {:id "ns:a/f" :kind :function :label "f" :parent "ns:a"
                        :children #{}
                        :data {:kind :function :private? false}}
           "ns:b/g"   {:id "ns:b/g" :kind :function :label "g" :parent "ns:b"
                        :children #{}
                        :data {:kind :function :private? false}}}
   :edges [{:from "ns:a/f" :to "ns:b/g" :kind :function-call}]})

(deftest selection-defaults-to-view-id
  (testing "when selected-id is nil, selection defaults to view-id"
    (let [projection (graph/entity-graph test-model {:view-id "root" :expanded #{} :show-private #{}})
          editor-state {:view-id "root" :selected-id nil :schema-id nil :expanded #{} :show-private #{}}
          result (views/render-graph projection editor-state)]
      (is (true? (inv/selection-default? result editor-state))))))

(deftest explicit-selection-honored
  (testing "when selected-id is set, that node is selected"
    (let [projection (graph/entity-graph test-model {:view-id "root" :expanded #{} :show-private #{}})
          editor-state {:view-id "root" :selected-id "ns:a" :schema-id nil :expanded #{} :show-private #{}}
          result (views/render-graph projection editor-state)]
      (is (true? (inv/selection-default? result editor-state))))))

;; ---------------------------------------------------------------------------
;; Example-based: edge highlighting

(deftest regular-node-highlighting
  (testing "non-schema node highlights connected edges"
    (let [projection (graph/entity-graph test-model {:view-id "root" :expanded #{} :show-private #{}})
          editor-state {:view-id "root" :selected-id "ns:a" :schema-id nil :expanded #{} :show-private #{}}
          result (views/render-graph projection editor-state)
          effective-selected "ns:a"]
      ;; Verify the test actually exercises highlighting (non-empty edges)
      (is (pos? (count (:edges result))) "test should produce edges to highlight")
      (is (true? (inv/regular-highlighting? result effective-selected))))))

;; ---------------------------------------------------------------------------
;; Example-based: schema-key highlighting

(def schema-test-model
  "Model with schema nodes and schema-reference edges for testing schema-key highlighting."
  {:nodes {"root"       {:id "root" :kind :module :label "root" :parent nil
                          :children #{"ns:a" "ns:b"}
                          :data {:kind :module}}
           "ns:a"       {:id "ns:a" :kind :module :label "a" :parent "root"
                          :children #{"ns:a/MyType"}
                          :data {:kind :module}}
           "ns:b"       {:id "ns:b" :kind :module :label "b" :parent "root"
                          :children #{"ns:b/MyType"}
                          :data {:kind :module}}
           "ns:a/MyType" {:id "ns:a/MyType" :kind :schema :label "MyType" :parent "ns:a"
                           :children #{}
                           :data {:kind :schema :schema-key :MyType :private? false}}
           "ns:b/MyType" {:id "ns:b/MyType" :kind :schema :label "MyType" :parent "ns:b"
                           :children #{}
                           :data {:kind :schema :schema-key :MyType :private? false}}}
   :edges [{:from "ns:a/MyType" :to "ns:b/MyType" :kind :schema-reference}]})

(deftest schema-key-highlighting
  (testing "schema node highlights edges by schema-key, not by endpoint ID"
    (let [projection (graph/entity-graph schema-test-model
                       {:view-id "root"
                        :expanded #{"ns:a" "ns:b"}
                        :show-private #{}
                        :visible-edge-types #{:schema-reference}})
          editor-state {:view-id "root" :selected-id "ns:a/MyType"
                        :schema-id nil :expanded #{"ns:a" "ns:b"} :show-private #{}}
          result (views/render-graph projection editor-state)]
      ;; Should have edges to highlight
      (is (pos? (count (:edges result))) "test should produce schema-reference edges")
      ;; Schema-key highlighting invariant
      (is (true? (inv/schema-key-highlighting? result "ns:a/MyType")))
      ;; Both schema nodes share :MyType key, so selecting either should highlight the same edges
      (let [result-b (views/render-graph projection
                       (assoc editor-state :selected-id "ns:b/MyType"))]
        (is (true? (inv/schema-key-highlighting? result-b "ns:b/MyType")))
        (is (= (:highlightedEdges result) (:highlightedEdges result-b))
            "same schema-key should highlight same edges regardless of which endpoint is selected")))))

;; ---------------------------------------------------------------------------
;; Generative: showing-private consistency at view layer

(defspec showing-private-view-consistency 100
  (prop/for-all [model (gen/gen-model)]
    (let [opts-gen (gen/gen-projection-opts model)]
      (tgen/generate
        (tgen/fmap (fn [opts]
                     (let [projection (graph/entity-graph model opts)
                           editor-state {:view-id (:view-id opts)
                                         :selected-id nil
                                         :schema-id nil
                                         :expanded (:expanded opts)
                                         :show-private (:show-private opts)}
                           result (views/render-graph projection editor-state)]
                       (true? (inv/showing-private-consistent? result))))
                   opts-gen)))))
