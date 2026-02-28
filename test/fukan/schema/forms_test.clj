(ns fukan.schema.forms-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.schema.forms :as forms]))

;; -----------------------------------------------------------------------------
;; Structural accessors

(deftest form-tag-test
  (testing "returns tag from vector forms"
    (is (= :map (forms/form-tag [:map [:id :string]])))
    (is (= :=> (forms/form-tag [:=> [:cat :int] :string]))))
  (testing "returns nil for non-vectors"
    (is (nil? (forms/form-tag :string)))
    (is (nil? (forms/form-tag "hello")))
    (is (nil? (forms/form-tag 42)))))

(deftest form-props-test
  (testing "returns property map when present"
    (is (= {:description "A thing"} (forms/form-props [:map {:description "A thing"} [:id :string]]))))
  (testing "returns nil when no props"
    (is (nil? (forms/form-props [:map [:id :string]])))
    (is (nil? (forms/form-props :string)))))

(deftest form-children-test
  (testing "skips props map"
    (is (= '([:id :string]) (forms/form-children [:map {:description "x"} [:id :string]]))))
  (testing "returns all children when no props"
    (is (= '(:int) (forms/form-children [:vector :int]))))
  (testing "nil for non-vectors"
    (is (nil? (forms/form-children :string)))))

(deftest form-description-test
  (testing "extracts description"
    (is (= "A node" (forms/form-description [:map {:description "A node"} [:id :string]]))))
  (testing "nil when missing"
    (is (nil? (forms/form-description [:map [:id :string]])))))

;; -----------------------------------------------------------------------------
;; Function schema helpers

(deftest fn-schema?-test
  (is (true? (forms/fn-schema? [:=> [:cat :int] :string])))
  (is (false? (forms/fn-schema? [:map [:id :string]])))
  (is (false? (forms/fn-schema? :string))))

(deftest fn-schema-parts-test
  (testing "destructures :cat inputs"
    (is (= {:inputs [:Model :ProjectionOpts] :output :Projection}
           (forms/fn-schema-parts [:=> [:cat :Model :ProjectionOpts] :Projection]))))
  (testing "single input without :cat"
    (is (= {:inputs [:int] :output :string}
           (forms/fn-schema-parts [:=> :int :string]))))
  (testing "returns nil for non-fn schemas"
    (is (nil? (forms/fn-schema-parts [:map [:id :string]])))))

;; -----------------------------------------------------------------------------
;; Keyword reference extraction

(deftest extract-keyword-refs-test
  (testing "extracts refs from simple types"
    (is (= #{:Node} (forms/extract-keyword-refs :Node))))

  (testing "extracts refs from vector schemas"
    (is (= #{:Node} (forms/extract-keyword-refs [:vector :Node]))))

  (testing "extracts refs from map-of"
    (is (= #{:NodeId :Node} (forms/extract-keyword-refs [:map-of :NodeId :Node]))))

  (testing "extracts refs from map entries (type position only)"
    (is (= #{:NodeId :NodeKind}
           (forms/extract-keyword-refs [:map [:id :NodeId] [:kind :NodeKind]]))))

  (testing "skips :enum values"
    (is (= #{} (forms/extract-keyword-refs [:enum :container :function :schema]))))

  (testing "skips := values"
    (is (= #{} (forms/extract-keyword-refs [:= :container]))))

  (testing "skips :fn predicates"
    (is (= #{} (forms/extract-keyword-refs [:fn {:error/message "bad"} (fn [x] (pos? x))]))))

  (testing "skips :map field keys"
    ;; :id and :kind are field keys, not schema refs
    ;; :NodeId and :NodeKind are in type position
    (is (= #{:NodeId :NodeKind}
           (forms/extract-keyword-refs [:map [:id :NodeId] [:kind :NodeKind]]))))

  (testing "handles nested forms"
    (is (= #{:NodeId :Node}
           (forms/extract-keyword-refs [:map [:nodes [:map-of :NodeId :Node]]]))))

  (testing "handles :or with :map variants"
    (is (= #{:string}
           (forms/extract-keyword-refs [:or [:map [:name :string]] [:vector :string]]))))

  (testing "handles function schemas"
    (is (= #{:Model :ProjectionOpts :Projection}
           (forms/extract-keyword-refs [:=> [:cat :Model :ProjectionOpts] :Projection]))))

  (testing "skips property maps in nested forms"
    (is (= #{:string}
           (forms/extract-keyword-refs [:vector {:description "list of strings"} :string]))))

  (testing "handles optional map entries"
    (is (= #{:string}
           (forms/extract-keyword-refs [:map [:name {:optional true} :string]])))))
