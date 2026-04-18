(ns fukan.model.analyzers.specification.languages.boundary-test
  "Unit + integration tests for the Boundary analyzer.
   .boundary is the spec spine: its `fn` declarations become Function
   nodes, its `exposes` declarations become public Schema nodes, and
   its `guarantee` declarations land on the module's surface for
   downstream collapse into the Boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.model.analyzers.specification.languages.boundary :as analyzer]))

;; ---------------------------------------------------------------------------
;; Unit: type-ref → TypeExpr

(deftest type-ref->type-expr-test
  (testing "non-builtin simple becomes a ref"
    (is (= {:tag :ref :name :Model}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "Model"}))))
  (testing "primitive simple becomes a primitive (with mapped name)"
    (is (= {:tag :primitive :name "string"}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "String"})))
    (is (= {:tag :primitive :name "int"}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "Integer"})))
    (is (= {:tag :primitive :name "boolean"}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "Boolean"}))))
  (testing "FilePath / Unit map to primitives"
    (is (= {:tag :primitive :name "FilePath"}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "FilePath"})))
    (is (= {:tag :primitive :name "nil"}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "Unit"}))))
  (testing "Set<T> becomes :set"
    (is (= {:tag :set :element {:tag :ref :name :AnalyzerKey}}
           (#'analyzer/type-ref->type-expr
             {:kind :generic :name "Set"
              :params [{:kind :simple :name "AnalyzerKey"}]}))))
  (testing "optional becomes :maybe"
    (is (= {:tag :maybe :inner {:tag :ref :name :Model}}
           (#'analyzer/type-ref->type-expr
             {:kind :optional :inner {:kind :simple :name "Model"}})))))

;; ---------------------------------------------------------------------------
;; Unit: fn-decl → signature

(deftest fn-decl->signature-test
  (testing "decl with params and return produces inputs + output"
    (is (= {:inputs [{:tag :primitive :name "FilePath"}
                     {:tag :set :element {:tag :ref :name :AnalyzerKey}}]
            :output {:tag :ref :name :Model}}
           (#'analyzer/fn-decl->signature
             {:type :fn :name "load_model"
              :params [{:name "src" :type {:kind :simple :name "FilePath"}}
                       {:name "analyzers"
                        :type {:kind :generic :name "Set"
                               :params [{:kind :simple :name "AnalyzerKey"}]}}]
              :return {:kind :simple :name "Model"}}))))
  (testing "decl with no params and no return produces nil"
    (is (nil? (#'analyzer/fn-decl->signature {:type :fn :name "noop"})))))

;; ---------------------------------------------------------------------------
;; Unit: function nodes

(deftest build-fn-nodes-test
  (testing "underscored fn name is hyphenated for label and id"
    (let [decls [{:type :fn :name "load_model"
                  :params [{:name "src" :type {:kind :simple :name "FilePath"}}]
                  :return {:kind :simple :name "Model"}
                  :description "Load and build the model from source."}]
          nodes (#'analyzer/build-fn-nodes "src/fukan/infra" decls)]
      (is (= 1 (count nodes)))
      (let [node (get nodes "src/fukan/infra/load-model")]
        (is (some? node) "node id uses kebab-case")
        (is (= "load-model" (:label node)))
        (is (= "src/fukan/infra" (:parent node)))
        (is (= :function (:kind node)))
        (is (false? (get-in node [:data :private?])))
        (is (= "Load and build the model from source." (get-in node [:data :doc])))
        (is (some? (get-in node [:data :signature])))))))

;; ---------------------------------------------------------------------------
;; Unit: schema nodes

(deftest build-schema-nodes-test
  (testing "exposes decl becomes a public Schema node with placeholder ref"
    (let [decls [{:type :exposes :name "AnalysisResult"}]
          nodes (#'analyzer/build-schema-nodes "src/fukan/model" decls)
          node (get nodes "src/fukan/model/AnalysisResult")]
      (is (some? node))
      (is (= :schema (:kind node)))
      (is (= "AnalysisResult" (:label node)))
      (is (= :AnalysisResult (get-in node [:data :schema-key])))
      (is (false? (get-in node [:data :private?])))
      (is (= {:tag :ref :name :AnalysisResult}
             (get-in node [:data :schema]))
          "boundary emits a placeholder ref; allium fills in real structure"))))

;; ---------------------------------------------------------------------------
;; Integration: analyze Fukan's own .boundary files

(deftest boundary-analyze-self
  (let [result (analyzer/analyze "src")
        nodes (vals (:nodes result))
        modules (filter #(= :module (:kind %)) nodes)
        functions (filter #(= :function (:kind %)) nodes)
        schemas (filter #(= :schema (:kind %)) nodes)]

    (testing "discovers boundary files"
      (is (pos? (count (:source-files result)))))

    (testing "emits modules, functions, and schemas"
      (is (pos? (count modules)))
      (is (pos? (count functions)))
      (is (pos? (count schemas))))

    (testing "all module ids are folder paths"
      (is (every? #(str/starts-with? (:id %) "src/") modules)))

    (testing "infra fn `load_model` becomes a Function node `load-model`"
      (let [load-model (->> functions
                            (filter #(= "src/fukan/infra/load-model" (:id %)))
                            first)]
        (is (some? load-model))
        (is (false? (get-in load-model [:data :private?])))
        (is (some? (get-in load-model [:data :signature])))))

    (testing "model/pipeline `exposes AnalysisResult` becomes a public Schema"
      (let [s (->> schemas
                   (filter #(= "src/fukan/model/AnalysisResult" (:id %)))
                   first)]
        (is (some? s))
        (is (false? (get-in s [:data :private?])))))

    (testing "no edges (schema-ref edges come from build pipeline)"
      (is (zero? (count (:edges result)))))

    (testing "boundary does not carry guarantees (those live in .allium now)"
      (let [infra (->> modules
                       (filter #(= "src/fukan/infra" (:id %)))
                       first)]
        (is (nil? (get-in infra [:data :surface :guarantees]))
            "guarantees are emitted by the allium analyzer, not boundary")))))
