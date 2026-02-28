(ns fukan.model.lint-test
  "Tests for cross-module contract compliance linter."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.lint :as lint]
            [fukan.model.build :as build]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]))

;; ---------------------------------------------------------------------------
;; Helpers: hand-built models for unit tests

(defn- make-model
  "Build a minimal model with two modules, each with one namespace and functions.
   module-a (src/a) contains ns a.core with fn-a1.
   module-b (src/b) contains ns b.core with fn-b1 (contracted) and fn-b2 (not contracted).
   Returns {:nodes {...} :edges [...]}."
  []
  {:nodes
   {"src/a"
    {:id "src/a" :kind :module :label "a" :parent nil :children #{"a.core"}
     :data {:kind :module
            :contract {:source :declared
                       :description "Module A"
                       :functions [{:name "fn-a1" :id "a.core/fn-a1"}]}}}
    "a.core"
    {:id "a.core" :kind :module :label "a.core" :parent "src/a" :children #{"a.core/fn-a1"}
     :data {:kind :module}}
    "a.core/fn-a1"
    {:id "a.core/fn-a1" :kind :function :label "fn-a1" :parent "a.core" :children #{}
     :data {:kind :function :private? false}}

    "src/b"
    {:id "src/b" :kind :module :label "b" :parent nil :children #{"b.core"}
     :data {:kind :module
            :contract {:source :declared
                       :description "Module B"
                       :functions [{:name "fn-b1" :id "b.core/fn-b1"}]}}}
    "b.core"
    {:id "b.core" :kind :module :label "b.core" :parent "src/b" :children #{"b.core/fn-b1" "b.core/fn-b2"}
     :data {:kind :module}}
    "b.core/fn-b1"
    {:id "b.core/fn-b1" :kind :function :label "fn-b1" :parent "b.core" :children #{}
     :data {:kind :function :private? false}}
    "b.core/fn-b2"
    {:id "b.core/fn-b2" :kind :function :label "fn-b2" :parent "b.core" :children #{}
     :data {:kind :function :private? false}}}

   :edges []})

;; ---------------------------------------------------------------------------
;; Unit tests

(deftest detects-contract-violation
  (testing "Edge targeting a non-contracted function is reported"
    (let [model (update (make-model) :edges conj
                        {:from "a.core/fn-a1" :to "b.core/fn-b2"})
          report (lint/check-contracts model)]
      (is (= 1 (count (:violations report))))
      (let [v (first (:violations report))]
        (is (= "a.core/fn-a1" (:from v)))
        (is (= "b.core/fn-b2" (:to v)))
        (is (= "src/a" (:from-module v)))
        (is (= "src/b" (:to-module v)))))))

(deftest allows-contracted-function
  (testing "Edge targeting a contracted function is not reported"
    (let [model (update (make-model) :edges conj
                        {:from "a.core/fn-a1" :to "b.core/fn-b1"})
          report (lint/check-contracts model)]
      (is (zero? (count (:violations report))))
      (is (= 1 (:cross-module-edges (:stats report)))))))

(deftest ignores-intra-module-edges
  (testing "Edges within the same module are never violations"
    (let [model (update (make-model) :edges conj
                        {:from "b.core/fn-b1" :to "b.core/fn-b2"})
          report (lint/check-contracts model)]
      (is (zero? (count (:violations report))))
      (is (zero? (:cross-module-edges (:stats report)))))))

(deftest skips-module-targeting-edges
  (testing "Edges targeting module nodes are not checked — contracts are about functions"
    (let [model (update (make-model) :edges conj
                        {:from "a.core" :to "b.core"})
          report (lint/check-contracts model)]
      (is (zero? (count (:violations report))))
      (is (zero? (:cross-module-edges (:stats report)))))))

(deftest format-report-renders-violations
  (testing "format-report produces readable output"
    (let [report {:violations [{:from "a.core/fn-a1"
                                :to "b.core/fn-b2"
                                :from-module "src/a"
                                :to-module "src/b"
                                :contract-fns ["fn-b1"]}]
                  :stats {:total-edges 5
                          :cross-module-edges 3
                          :violations 1}}
          output (lint/format-report report)]
      (is (re-find #"1 violation" output))
      (is (re-find #"a\.core/fn-a1" output))
      (is (re-find #"b\.core/fn-b2" output))
      (is (re-find #"fn-b1" output)))))

;; ---------------------------------------------------------------------------
;; Integration: run against Fukan's own source

(deftest fukan-self-analysis-lint
  (testing "Lint report runs against Fukan's own source without errors"
    (let [contrib (clj-lang/contribution "src")
          schema-data (clj-lang/discover-schema-data)
          enriched (-> contrib
                       (update :nodes clj-lang/enrich-with-runtime-metadata schema-data)
                       (update :nodes clj-lang/resolve-contracts))
          model (build/build-model enriched
                  {:type-nodes-fn (fn [ns-index]
                                    (clj-lang/build-schema-nodes ns-index schema-data))})
          report (lint/check-contracts model)]
      (is (map? report))
      (is (vector? (:violations report)))
      (is (pos? (:total-edges (:stats report))))
      (is (pos? (:cross-module-edges (:stats report))))
      ;; Print violations for visibility (they may exist — this test verifies
      ;; the linter runs without crashing, not that violations = 0)
      (when (seq (:violations report))
        (println (lint/format-report report))))))
