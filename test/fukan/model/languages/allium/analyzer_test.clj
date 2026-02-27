(ns fukan.model.languages.allium.analyzer-test
  "Unit + integration tests for the Allium analyzer.
   Verifies file→ns-sym conversion, type reference extraction,
   use path resolution, and that analyzing Fukan's own .allium files
   produces a valid model contribution."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.model.languages.allium.analyzer :as analyzer]
            [fukan.model.build :as build]
            [fukan.model.languages.clojure :as clj-lang]
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
           (analyzer/extract-type-refs {:kind :simple :name "Node"}))))
  (testing "builtin type ignored"
    (is (= #{}
           (analyzer/extract-type-refs {:kind :simple :name "String"}))))
  (testing "qualified type"
    (is (= #{{:alias "model" :name "Node"}}
           (analyzer/extract-type-refs {:kind :qualified :ns "model" :name "Node"})))))

(deftest extract-type-refs-compound
  (testing "generic with builtin container"
    (is (= #{{:name "Edge"}}
           (analyzer/extract-type-refs {:kind :generic :name "List"
                                        :params [{:kind :simple :name "Edge"}]}))))
  (testing "generic with non-builtin container"
    (is (= #{{:name "MyList"} {:name "Edge"}}
           (analyzer/extract-type-refs {:kind :generic :name "MyList"
                                        :params [{:kind :simple :name "Edge"}]}))))
  (testing "optional type"
    (is (= #{{:name "Contract"}}
           (analyzer/extract-type-refs {:kind :optional
                                        :inner {:kind :simple :name "Contract"}}))))
  (testing "union type"
    (is (= #{{:name "Container"} {:name "Function"}}
           (analyzer/extract-type-refs {:kind :union
                                        :members [{:kind :simple :name "Container"}
                                                   {:kind :simple :name "Function"}]}))))
  (testing "inline object with type refs"
    (is (= #{{:name "DepInfo"}}
           (analyzer/extract-type-refs {:kind :inline-obj
                                        :fields [{:name "count" :type-ref {:kind :simple :name "Integer"}}
                                                  {:name "info" :type-ref {:kind :simple :name "DepInfo"}}]}))))
  (testing "nil type-ref"
    (is (nil? (analyzer/extract-type-refs nil)))))

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
             (analyzer/extract-declaration-refs decl)))))
  (testing "variant with base type"
    (let [decl {:type :variant :name "Container"
                :base {:kind :simple :name "Node"}
                :fields [{:name "doc" :field-kind :typed
                           :type-ref {:kind :optional
                                      :inner {:kind :simple :name "String"}}}]}]
      (is (= #{{:name "Node"}}
             (analyzer/extract-declaration-refs decl)))))
  (testing "rule with when clause params"
    (let [decl {:type :rule :name "BuildModel"
                :clauses [{:clause-type :when
                            :trigger {:kind :call :name "BuildModel"
                                      :params [{:name "analysis"
                                                :type-ref {:kind :simple :name "AnalysisData"}}]}}]}]
      (is (= #{{:name "AnalysisData"}}
             (analyzer/extract-declaration-refs decl)))))
  (testing "enum has no type refs"
    (is (= #{}
           (analyzer/extract-declaration-refs {:type :enum :name "Status"
                                               :values ["active" "inactive"]})))))

;; ---------------------------------------------------------------------------
;; Unit: use path resolution

(deftest resolve-use-path-test
  (let [registry {"model" "src/fukan/model/spec.allium"
                  "projection" "src/fukan/projection/spec.allium"
                  "views" "src/fukan/web/views/spec.allium"}]
    (testing "resolves ./model.allium"
      (is (= "src/fukan/model/spec.allium"
             (analyzer/resolve-use-path "./model.allium" registry))))
    (testing "resolves ./projection.allium"
      (is (= "src/fukan/projection/spec.allium"
             (analyzer/resolve-use-path "./projection.allium" registry))))
    (testing "returns nil for unknown path"
      (is (nil? (analyzer/resolve-use-path "./unknown.allium" registry))))))

;; ---------------------------------------------------------------------------
;; Unit: spec registry building

(deftest build-spec-registry-test
  (let [files ["src/fukan/model/spec.allium"
               "src/fukan/projection/spec.allium"
               "src/fukan/web/views/spec.allium"]
        registry (analyzer/build-spec-registry files)]
    (is (= "src/fukan/model/spec.allium" (get registry "model")))
    (is (= "src/fukan/projection/spec.allium" (get registry "projection")))
    (is (= "src/fukan/web/views/spec.allium" (get registry "views")))))

;; ---------------------------------------------------------------------------
;; Integration: analyze Fukan's own .allium files

(deftest allium-contribution-test
  (testing "produces nodes and edges from Fukan's allium specs"
    (let [contrib (analyzer/allium-contribution "src")]
      (is (pos? (count (:source-files contrib)))
          "should discover allium files")
      (is (pos? (count (:nodes contrib)))
          "should produce nodes")
      ;; Each allium file should have a container node
      (let [containers (->> (vals (:nodes contrib))
                             (filter #(= :container (:kind %))))]
        (is (>= (count containers) 2)
            "should have at least 2 container nodes (model + views specs)"))
      ;; Each named declaration should have a function node
      (let [functions (->> (vals (:nodes contrib))
                            (filter #(= :function (:kind %))))]
        (is (pos? (count functions))
            "should have function (declaration) nodes"))
      ;; Should have edges from type references and use declarations
      (is (pos? (count (:edges contrib)))
          "should produce edges"))))

(deftest allium-model-integration-test
  (testing "merged Clojure + Allium contribution builds valid model"
    (let [clj-contrib (clj-lang/contribution "src")
          allium-contrib (analyzer/allium-contribution "src")
          contrib (build/merge-contributions clj-contrib allium-contrib)
          schema-data (clj-lang/discover-schema-data)
          model (build/build-model contrib
                  {:type-nodes-fn (fn [ns-index]
                                    (clj-lang/build-schema-nodes ns-index schema-data))})]
      (is (pos? (count (:nodes model))) "model should have nodes")
      (is (pos? (count (:edges model))) "model should have edges")
      ;; Allium containers should be in the model
      (let [allium-nodes (->> (keys (:nodes model))
                               (filter #(str/includes? % "allium")))]
        (is (pos? (count allium-nodes))
            "model should contain allium-related nodes"))
      ;; All model invariants should hold
      (is (true? (inv/tree-structure? model)) (str (inv/tree-structure? model)))
      (is (true? (inv/leaf-strictness? model)) (str (inv/leaf-strictness? model)))
      (is (true? (inv/no-empty-containers? model)) (str (inv/no-empty-containers? model)))
      (is (true? (inv/no-self-edges? model)) (str (inv/no-self-edges? model)))
      (is (true? (inv/edge-integrity? model)) (str (inv/edge-integrity? model)))
      (is (true? (inv/smart-root-pruning? model)) (str (inv/smart-root-pruning? model))))))
