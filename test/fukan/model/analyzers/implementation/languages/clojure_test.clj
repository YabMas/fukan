(ns fukan.model.analyzers.implementation.languages.clojure-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]))

;; ---------------------------------------------------------------------------
;; Keyword classification

(deftest keyword-classification
  (testing "uppercase-initial keywords become :ref"
    (is (= {:tag :ref :name :Model}
           (clj-lang/malli->type-expr :Model)))
    (is (= {:tag :ref :name :NodeId}
           (clj-lang/malli->type-expr :NodeId))))

  (testing "lowercase keywords become :primitive"
    (is (= {:tag :primitive :name "string"}
           (clj-lang/malli->type-expr :string)))
    (is (= {:tag :primitive :name "int"}
           (clj-lang/malli->type-expr :int)))
    (is (= {:tag :primitive :name "any"}
           (clj-lang/malli->type-expr :any)))
    (is (= {:tag :primitive :name "boolean"}
           (clj-lang/malli->type-expr :boolean)))))

;; ---------------------------------------------------------------------------
;; Basic type constructors

(deftest vector-type
  (is (= {:tag :vector :element {:tag :ref :name :Node}}
         (clj-lang/malli->type-expr [:vector :Node]))))

(deftest set-type
  (is (= {:tag :set :element {:tag :primitive :name "string"}}
         (clj-lang/malli->type-expr [:set :string]))))

(deftest maybe-type
  (is (= {:tag :maybe :inner {:tag :primitive :name "string"}}
         (clj-lang/malli->type-expr [:maybe :string]))))

(deftest map-of-type
  (is (= {:tag :map-of
          :key-type {:tag :ref :name :NodeId}
          :value-type {:tag :ref :name :Node}}
         (clj-lang/malli->type-expr [:map-of :NodeId :Node]))))

;; ---------------------------------------------------------------------------
;; Map type with entries

(deftest map-type
  (testing "simple map entries"
    (let [result (clj-lang/malli->type-expr [:map [:id :NodeId] [:kind :NodeKind]])]
      (is (= :map (:tag result)))
      (is (= 2 (count (:entries result))))
      (is (= {:key ":id" :optional false :type {:tag :ref :name :NodeId}}
             (first (:entries result))))
      (is (= {:key ":kind" :optional false :type {:tag :ref :name :NodeKind}}
             (second (:entries result))))))

  (testing "optional entries"
    (let [result (clj-lang/malli->type-expr [:map [:name {:optional true} :string]])]
      (is (= true (:optional (first (:entries result)))))))

  (testing "entry descriptions"
    (let [result (clj-lang/malli->type-expr
                   [:map [:name {:description "The name"} :string]])]
      (is (= "The name" (:description (first (:entries result)))))))

  (testing "map-level description"
    (let [result (clj-lang/malli->type-expr
                   [:map {:description "A node"} [:id :string]])]
      (is (= "A node" (:description result))))))

;; ---------------------------------------------------------------------------
;; Enum and = types

(deftest enum-type
  (is (= {:tag :enum :values [:module :function :schema]}
         (clj-lang/malli->type-expr [:enum :module :function :schema]))))

(deftest equals-type
  (testing "[:= value] becomes single-value enum"
    (is (= {:tag :enum :values [:module]}
           (clj-lang/malli->type-expr [:= :module])))))

;; ---------------------------------------------------------------------------
;; Or and And types

(deftest or-type
  (let [result (clj-lang/malli->type-expr [:or :string :int])]
    (is (= :or (:tag result)))
    (is (= [{:tag :primitive :name "string"} {:tag :primitive :name "int"}]
           (:variants result)))))

(deftest and-type
  (let [result (clj-lang/malli->type-expr [:and :string :int])]
    (is (= :and (:tag result)))
    (is (= [{:tag :primitive :name "string"} {:tag :primitive :name "int"}]
           (:types result)))))

;; ---------------------------------------------------------------------------
;; Tuple type

(deftest tuple-type
  (let [result (clj-lang/malli->type-expr [:tuple :string :int])]
    (is (= :tuple (:tag result)))
    (is (= [{:tag :primitive :name "string"} {:tag :primitive :name "int"}]
           (:elements result)))))

;; ---------------------------------------------------------------------------
;; Function type (:=>)

(deftest fn-type
  (testing "function schema with :cat inputs"
    (let [result (clj-lang/malli->type-expr [:=> [:cat :Model :ProjectionOpts] :Projection])]
      (is (= :fn (:tag result)))
      (is (= [{:tag :ref :name :Model} {:tag :ref :name :ProjectionOpts}]
             (:inputs result)))
      (is (= {:tag :ref :name :Projection}
             (:output result)))))

  (testing "function schema without :cat"
    (let [result (clj-lang/malli->type-expr [:=> :int :string])]
      (is (= :fn (:tag result)))
      (is (= [{:tag :primitive :name "int"}]
             (:inputs result)))
      (is (= {:tag :primitive :name "string"}
             (:output result))))))

;; ---------------------------------------------------------------------------
;; Predicate type (:fn)

(deftest predicate-type
  (is (= {:tag :predicate}
         (clj-lang/malli->type-expr [:fn (fn [x] (pos? x))]))))

;; ---------------------------------------------------------------------------
;; Description propagation

(deftest description-propagation
  (testing "description on vector form"
    (let [result (clj-lang/malli->type-expr [:vector {:description "list of nodes"} :Node])]
      (is (= "list of nodes" (:description result)))))

  (testing "description on or form"
    (let [result (clj-lang/malli->type-expr [:or {:description "union type"} :string :int])]
      (is (= "union type" (:description result))))))

;; ---------------------------------------------------------------------------
;; Nested forms

(deftest nested-forms
  (testing "map with nested vector"
    (let [result (clj-lang/malli->type-expr [:map [:nodes [:vector :Node]]])]
      (is (= :map (:tag result)))
      (is (= {:tag :vector :element {:tag :ref :name :Node}}
             (:type (first (:entries result)))))))

  (testing "map-of with nested map"
    (let [result (clj-lang/malli->type-expr [:map-of :NodeId [:map [:id :string]]])]
      (is (= :map-of (:tag result)))
      (is (= :map (:tag (:value-type result)))))))

;; ---------------------------------------------------------------------------
;; Unknown forms

(deftest unknown-forms
  (testing "non-keyword non-vector becomes unknown"
    (let [result (clj-lang/malli->type-expr 42)]
      (is (= :unknown (:tag result)))
      (is (= "42" (:original result))))))

;; ---------------------------------------------------------------------------
;; malli->fn-signature

(deftest fn-signature-conversion
  (testing "converts [:=> [:cat A B] C] to FunctionSignature"
    (is (= {:inputs [{:tag :ref :name :Model} {:tag :ref :name :ProjectionOpts}]
            :output {:tag :ref :name :Projection}}
           (clj-lang/malli->fn-signature [:=> [:cat :Model :ProjectionOpts] :Projection]))))

  (testing "single input without :cat"
    (is (= {:inputs [{:tag :primitive :name "int"}]
            :output {:tag :primitive :name "string"}}
           (clj-lang/malli->fn-signature [:=> :int :string]))))

  (testing "returns nil for non-function schemas"
    (is (nil? (clj-lang/malli->fn-signature [:map [:id :string]])))
    (is (nil? (clj-lang/malli->fn-signature :string)))))

;; ---------------------------------------------------------------------------
;; Ref extraction edge cases (ported from forms_test)

(deftest ref-extraction-via-type-expr
  (testing "refs from vector type"
    (let [te (clj-lang/malli->type-expr [:vector :Node])]
      (is (= :ref (:tag (:element te))))
      (is (= :Node (:name (:element te))))))

  (testing "map entries carry refs in type position, not key position"
    (let [te (clj-lang/malli->type-expr [:map [:id :NodeId] [:kind :NodeKind]])]
      ;; Keys are strings ":id", ":kind" — not refs
      ;; Types are refs :NodeId, :NodeKind
      (is (every? #(= :ref (:tag (:type %))) (:entries te)))))

  (testing "enum values are NOT refs"
    (let [te (clj-lang/malli->type-expr [:enum :module :function :schema])]
      (is (= :enum (:tag te)))
      (is (= [:module :function :schema] (:values te)))))

  (testing "fn predicates produce :predicate (no refs)"
    (let [te (clj-lang/malli->type-expr [:fn {:error/message "bad"} (fn [x] (pos? x))])]
      (is (= :predicate (:tag te)))))

  (testing "nested map-of refs"
    (let [te (clj-lang/malli->type-expr [:map [:nodes [:map-of :NodeId :Node]]])]
      (is (= :map-of (:tag (:type (first (:entries te))))))
      (is (= :NodeId (:name (:key-type (:type (first (:entries te)))))))
      (is (= :Node (:name (:value-type (:type (first (:entries te)))))))))

  (testing "or with map variants"
    (let [te (clj-lang/malli->type-expr [:or [:map [:name :string]] [:vector :string]])]
      (is (= :or (:tag te)))
      (is (= :map (:tag (first (:variants te)))))
      (is (= :vector (:tag (second (:variants te)))))))

  (testing "function schema refs"
    (let [te (clj-lang/malli->type-expr [:=> [:cat :Model :ProjectionOpts] :Projection])]
      (is (= :fn (:tag te)))
      (is (= :Model (:name (first (:inputs te)))))
      (is (= :Projection (:name (:output te))))))

  (testing "property maps are not confused with children"
    (let [te (clj-lang/malli->type-expr [:vector {:description "list"} :string])]
      (is (= :vector (:tag te)))
      (is (= "list" (:description te)))
      (is (= {:tag :primitive :name "string"} (:element te)))))

  (testing "optional map entries preserve optionality"
    (let [te (clj-lang/malli->type-expr [:map [:name {:optional true} :string]])]
      (is (= true (:optional (first (:entries te))))))))
