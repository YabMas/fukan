(ns fukan.model.analyzers.specification.languages.allium-test
  "Unit + integration tests for the Allium analyzer.
   Verifies file→ns-sym conversion, type reference extraction,
   use path resolution, and that analyzing Fukan's own .allium files
   produces a valid model analysis result."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.model.analyzers.specification.languages.allium :as analyzer]
            [fukan.model.build :as build]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]
            [fukan.test-support.invariants.model :as inv]))

;; ---------------------------------------------------------------------------
;; Unit: file->ns-sym

(deftest file->ns-sym-test
  (testing "standard allium file path"
    (is (= 'fukan.model.spec-allium
           (analyzer/file->ns-sym "src/fukan/model/spec.allium" "src"))))
  (testing "nested path"
    (is (= 'fukan.web.views.spec-allium
           (analyzer/file->ns-sym "src/fukan/web/views/spec.allium" "src"))))
  (testing "src-path with trailing slash"
    (is (= 'fukan.model.spec-allium
           (analyzer/file->ns-sym "src/fukan/model/spec.allium" "src/")))))

;; ---------------------------------------------------------------------------
;; Unit: type reference extraction

(deftest extract-type-refs-simple
  (testing "simple non-builtin type"
    (is (= #{{:name "Node"}}
           (#'analyzer/extract-type-refs {:kind :simple :name "Node"}))))
  (testing "builtin type ignored"
    (is (= #{}
           (#'analyzer/extract-type-refs {:kind :simple :name "String"}))))
  (testing "qualified type"
    (is (= #{{:alias "model" :name "Node"}}
           (#'analyzer/extract-type-refs {:kind :qualified :ns "model" :name "Node"})))))

(deftest extract-type-refs-compound
  (testing "generic with builtin container"
    (is (= #{{:name "Edge"}}
           (#'analyzer/extract-type-refs {:kind :generic :name "List"
                                        :params [{:kind :simple :name "Edge"}]}))))
  (testing "generic with non-builtin container"
    (is (= #{{:name "MyList"} {:name "Edge"}}
           (#'analyzer/extract-type-refs {:kind :generic :name "MyList"
                                        :params [{:kind :simple :name "Edge"}]}))))
  (testing "optional type"
    (is (= #{{:name "Contract"}}
           (#'analyzer/extract-type-refs {:kind :optional
                                        :inner {:kind :simple :name "Contract"}}))))
  (testing "union type"
    (is (= #{{:name "Container"} {:name "Function"}}
           (#'analyzer/extract-type-refs {:kind :union
                                        :members [{:kind :simple :name "Container"}
                                                   {:kind :simple :name "Function"}]}))))
  (testing "inline object with type refs"
    (is (= #{{:name "DepInfo"}}
           (#'analyzer/extract-type-refs {:kind :inline-obj
                                        :fields [{:name "count" :type-ref {:kind :simple :name "Integer"}}
                                                  {:name "info" :type-ref {:kind :simple :name "DepInfo"}}]}))))
  (testing "nil type-ref"
    (is (nil? (#'analyzer/extract-type-refs nil)))))

;; ---------------------------------------------------------------------------
;; Unit: declaration ref extraction

(deftest extract-declaration-refs-test
  (testing "entity with typed fields"
    (let [decl {:type :entity :name "Edge"
                :fields [{:name "from" :field-kind :typed
                           :type-ref {:kind :simple :name "String"}}
                          {:name "to" :field-kind :typed
                           :type-ref {:kind :simple :name "Node"}}]}]
      (is (= #{{:name "Node"}}
             (#'analyzer/extract-declaration-refs decl)))))
  (testing "variant with base type"
    (let [decl {:type :variant :name "Container"
                :base {:kind :simple :name "Node"}
                :fields [{:name "doc" :field-kind :typed
                           :type-ref {:kind :optional
                                      :inner {:kind :simple :name "String"}}}]}]
      (is (= #{{:name "Node"}}
             (#'analyzer/extract-declaration-refs decl)))))
  (testing "rule with when clause params"
    (let [decl {:type :rule :name "BuildModel"
                :clauses [{:clause-type :when
                            :trigger {:kind :call :name "BuildModel"
                                      :params [{:name "analysis"
                                                :type-ref {:kind :simple :name "AnalysisData"}}]}}]}]
      (is (= #{{:name "AnalysisData"}}
             (#'analyzer/extract-declaration-refs decl)))))
  (testing "enum has no type refs"
    (is (= #{}
           (#'analyzer/extract-declaration-refs {:type :enum :name "Status"
                                               :values ["active" "inactive"]})))))

;; ---------------------------------------------------------------------------
;; Unit: use path resolution

(deftest resolve-use-path-test
  (let [registry {"model" "src/fukan/model/spec.allium"
                  "projection" "src/fukan/projection/spec.allium"
                  "views" "src/fukan/web/views/spec.allium"}]
    (testing "resolves ./model.allium"
      (is (= "src/fukan/model/spec.allium"
             (#'analyzer/resolve-use-path "./model.allium" registry))))
    (testing "resolves ./projection.allium"
      (is (= "src/fukan/projection/spec.allium"
             (#'analyzer/resolve-use-path "./projection.allium" registry))))
    (testing "returns nil for unknown path"
      (is (nil? (#'analyzer/resolve-use-path "./unknown.allium" registry))))))

;; ---------------------------------------------------------------------------
;; Unit: spec registry building

(deftest build-spec-registry-test
  (let [files ["src/fukan/model/spec.allium"
               "src/fukan/projection/spec.allium"
               "src/fukan/web/views/spec.allium"]
        registry (#'analyzer/build-spec-registry files)]
    (is (= "src/fukan/model/spec.allium" (get registry "model")))
    (is (= "src/fukan/projection/spec.allium" (get registry "projection")))
    (is (= "src/fukan/web/views/spec.allium" (get registry "views")))))

;; ---------------------------------------------------------------------------
;; Integration: analyze Fukan's own .allium files

(deftest allium-analyze-test
  (testing "produces module nodes enriched with spec data"
    (let [result (analyzer/analyze "src")]
      (is (pos? (count (:source-files result)))
          "should discover allium files")
      (is (pos? (count (:nodes result)))
          "should produce nodes")
      ;; Modules use folder-path IDs, not spec-allium
      (let [modules (->> (vals (:nodes result))
                             (filter #(= :module (:kind %))))]
        (is (>= (count modules) 2)
            "should have at least 2 module nodes")
        (is (every? #(str/starts-with? (:id %) "src/") modules)
            "all modules should use folder-path IDs")
        (is (every? #(not (str/includes? (:id %) "allium")) modules)
            "no module should have allium in its ID"))
      ;; No function children — spec data lives on modules only
      (let [functions (->> (vals (:nodes result))
                            (filter #(= :function (:kind %))))]
        (is (zero? (count functions))
            "should not produce function (declaration) nodes"))
      ;; No edges — use declarations are spec-level, not code dependencies
      (is (zero? (count (:edges result)))
          "should not produce edges (spec imports are not code deps)"))))

(deftest allium-enrichment-test
  (testing "module uses folder-path ID matching build pipeline"
    (let [result (analyzer/analyze "src")
          model-module (get (:nodes result) "src/fukan/model")]
      (is (some? model-module) "model folder module should exist")
      (is (nil? (get-in model-module [:data :spec]))
          "module should not carry raw :spec data")
      (is (nil? (get-in model-module [:data :fields]))
          "module should not carry :fields")
      (is (= :module (:kind model-module))
          "module should have kind :module"))))

(deftest allium-model-integration-test
  (testing "merged Clojure + Allium result builds valid model"
    (let [clj-result (clj-lang/analyze "src")
          allium-result (analyzer/analyze "src")
          result (build/merge-results clj-result allium-result)
          model (build/build-model result)]
      (is (pos? (count (:nodes model))) "model should have nodes")
      (is (pos? (count (:edges model))) "model should have edges")
      ;; Allium result should merge into model modules
      (let [allium-dirs (->> (vals (:nodes (analyzer/analyze "src")))
                              (map :id)
                              set)]
        (is (some #(contains? allium-dirs %) (keys (:nodes model)))
            "model should contain modules from allium result"))
      ;; No spec-allium modules should exist
      (is (empty? (->> (keys (:nodes model))
                        (filter #(str/includes? % "spec-allium"))))
          "no spec-allium nodes should exist in the model")
      ;; All model invariants should hold
      (is (true? (inv/tree-structure? model)) (str (inv/tree-structure? model)))
      (is (true? (inv/leaf-strictness? model)) (str (inv/leaf-strictness? model)))
      (is (true? (inv/no-empty-modules? model)) (str (inv/no-empty-modules? model)))
      (is (true? (inv/no-self-edges? model)) (str (inv/no-self-edges? model)))
      (is (true? (inv/edge-integrity? model)) (str (inv/edge-integrity? model)))
      (is (true? (inv/smart-root-pruning? model)) (str (inv/smart-root-pruning? model))))))
