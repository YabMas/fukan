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
                                         :expanded-modules (:expanded-modules opts)}]
                       (true? (inv/render-pure? views/render-graph projection editor-state))))
                   opts-gen)))))

;; ---------------------------------------------------------------------------
;; Example-based: selection default

(def test-model
  {:nodes {"root"     {:id "root" :kind :module :label "root" :parent nil
                        :children #{"ns:a"}
                        :data {:kind :module}}
           "ns:a"     {:id "ns:a" :kind :module :label "a" :parent "root"
                        :children #{"ns:a/f"}
                        :data {:kind :module}}
           "ns:a/f"   {:id "ns:a/f" :kind :function :label "f" :parent "ns:a"
                        :children #{}
                        :data {:kind :function :private? false}}}
   :edges [{:from "ns:a/f" :to "ns:a/f"}]})

(deftest selection-defaults-to-view-id
  (testing "when selected-id is nil, selection defaults to view-id"
    (let [projection (graph/entity-graph test-model {:view-id "root" :expanded-modules #{}})
          editor-state {:view-id "root" :selected-id nil :schema-id nil :expanded-modules #{}}
          result (views/render-graph projection editor-state)]
      (is (true? (inv/selection-default? result editor-state))))))

(deftest explicit-selection-honored
  (testing "when selected-id is set, that node is selected"
    (let [projection (graph/entity-graph test-model {:view-id "root" :expanded-modules #{}})
          editor-state {:view-id "root" :selected-id "ns:a" :schema-id nil :expanded-modules #{}}
          result (views/render-graph projection editor-state)]
      (is (true? (inv/selection-default? result editor-state))))))

;; ---------------------------------------------------------------------------
;; Example-based: edge highlighting

(deftest regular-node-highlighting
  (testing "non-schema node highlights connected edges"
    (let [projection (graph/entity-graph test-model {:view-id "root" :expanded-modules #{}})
          editor-state {:view-id "root" :selected-id "ns:a" :schema-id nil :expanded-modules #{}}
          result (views/render-graph projection editor-state)
          effective-selected "ns:a"]
      (is (true? (inv/regular-highlighting? result effective-selected))))))
