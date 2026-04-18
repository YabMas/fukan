(ns fukan.model.analyzers.specification.languages.allium-test
  "Unit + integration tests for the Allium analyzer.
   Allium is the enrichment layer over the .boundary spine: it emits
   private Schema nodes from entity/value/variant/enum declarations and
   carries a module description, but does not emit Function nodes or
   call-graph edges (rules are not yet first-class graph entities)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.model.analyzers.specification.languages.allium :as analyzer]
            [fukan.test-support.fixtures :as fix]
            [fukan.test-support.invariants.model :as inv]))

;; ---------------------------------------------------------------------------
;; Unit: type-ref → TypeExpr conversion

(deftest type-ref->type-expr-simple
  (testing "non-builtin simple type becomes a ref"
    (is (= {:tag :ref :name :Node}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "Node"}))))
  (testing "builtin simple type becomes a primitive"
    (is (= {:tag :primitive :name "String"}
           (#'analyzer/type-ref->type-expr {:kind :simple :name "String"}))))
  (testing "qualified type becomes a namespaced ref"
    (is (= {:tag :ref :name :model/Node}
           (#'analyzer/type-ref->type-expr {:kind :qualified :ns "model" :name "Node"})))))

(deftest type-ref->type-expr-compound
  (testing "List<T> becomes :vector"
    (is (= {:tag :vector :element {:tag :ref :name :Node}}
           (#'analyzer/type-ref->type-expr {:kind :generic :name "List"
                                            :params [{:kind :simple :name "Node"}]}))))
  (testing "Map<K,V> becomes :map-of"
    (is (= {:tag :map-of
            :key-type {:tag :primitive :name "String"}
            :value-type {:tag :ref :name :Node}}
           (#'analyzer/type-ref->type-expr {:kind :generic :name "Map"
                                            :params [{:kind :simple :name "String"}
                                                     {:kind :simple :name "Node"}]}))))
  (testing "optional becomes :maybe"
    (is (= {:tag :maybe :inner {:tag :ref :name :Contract}}
           (#'analyzer/type-ref->type-expr {:kind :optional
                                            :inner {:kind :simple :name "Contract"}}))))
  (testing "union becomes :or"
    (is (= {:tag :or :variants [{:tag :ref :name :A} {:tag :ref :name :B}]}
           (#'analyzer/type-ref->type-expr {:kind :union
                                            :members [{:kind :simple :name "A"}
                                                      {:kind :simple :name "B"}]})))))

;; ---------------------------------------------------------------------------
;; Unit: declaration → schema TypeExpr

(deftest decl->schema-type-expr-test
  (testing "entity fields become :map entries"
    (let [decl {:type :entity :name "Edge"
                :fields [{:name "from" :field-kind :typed
                          :type-ref {:kind :simple :name "Node"}}
                         {:name "to" :field-kind :typed
                          :type-ref {:kind :simple :name "Node"}}]}]
      (is (= {:tag :map
              :entries [{:key "from" :optional false :type {:tag :ref :name :Node}}
                        {:key "to"   :optional false :type {:tag :ref :name :Node}}]}
             (#'analyzer/decl->schema-type-expr decl)))))

  (testing "non-typed fields are dropped"
    (let [decl {:type :value :name "X"
                :fields [{:name "tag" :field-kind :literal :value "x"}
                         {:name "n"   :field-kind :typed
                          :type-ref {:kind :simple :name "Integer"}}]}]
      (is (= {:tag :map
              :entries [{:key "n" :optional false :type {:tag :primitive :name "Integer"}}]}
             (#'analyzer/decl->schema-type-expr decl))))))

;; ---------------------------------------------------------------------------
;; Integration: analyze Fukan's own .allium files

(deftest allium-analyze-self
  (testing "produces module nodes plus private Schema nodes"
    (let [result (analyzer/analyze "src")]
      (is (pos? (count (:source-files result))) "discovers .allium files")
      (is (pos? (count (:nodes result))) "produces nodes")

      (let [nodes (vals (:nodes result))
            modules (filter #(= :module (:kind %)) nodes)
            schemas (filter #(= :schema (:kind %)) nodes)
            functions (filter #(= :function (:kind %)) nodes)]
        (is (every? #(str/starts-with? (:id %) "src/") modules)
            "module ids are folder paths")
        (is (zero? (count functions))
            "allium emits no Function nodes (rules are not yet first-class)")
        (is (pos? (count schemas))
            "allium emits Schema nodes from entity/value/variant/enum")
        (is (every? #(true? (get-in % [:data :private?])) schemas)
            "all allium-emitted schemas are private (boundary publicizes via merge)"))

      (is (zero? (count (:edges result)))
          "allium emits no edges; call edges come from impl, schema-ref edges from build"))))

(deftest allium-extracts-variants
  (testing "variant declarations from model/spec.allium become Schema nodes"
    (let [result (analyzer/analyze "src")
          ;; model/spec.allium declares many TypeExpr variants
          model-schema-keys (->> (vals (:nodes result))
                                 (filter #(= :schema (:kind %)))
                                 (filter #(= "src/fukan/model" (:parent %)))
                                 (map :label)
                                 set)]
      (is (contains? model-schema-keys "RefExpr") "RefExpr variant captured")
      (is (contains? model-schema-keys "MapExpr") "MapExpr variant captured")
      (is (contains? model-schema-keys "OrExpr")  "OrExpr variant captured"))))

;; ---------------------------------------------------------------------------
;; Integration: full build still satisfies model invariants

(deftest allium-model-integration
  (testing "merged Clojure + Allium + Boundary result builds a valid model"
    (let [model (fix/build-self-model)]
      (is (pos? (count (:nodes model))) "model has nodes")
      (is (pos? (count (:edges model))) "model has edges")
      (is (true? (inv/tree-structure? model))     (str (inv/tree-structure? model)))
      (is (true? (inv/leaf-strictness? model))    (str (inv/leaf-strictness? model)))
      (is (true? (inv/no-empty-modules? model))   (str (inv/no-empty-modules? model)))
      (is (true? (inv/no-self-edges? model))      (str (inv/no-self-edges? model)))
      (is (true? (inv/edge-integrity? model))     (str (inv/edge-integrity? model)))
      (is (true? (inv/smart-root-pruning? model)) (str (inv/smart-root-pruning? model))))))
