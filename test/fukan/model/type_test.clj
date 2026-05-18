(ns fukan.model.type-test
  "Tests for the Type vocabulary (MODEL.md §3.3). Substrate stays generic at the
   target-language level: Types commit to structural shape, not target-language
   rendering. Identity is structural equality (case + key fields)."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest scalar
  (testing "Scalar carries an opaque methodology-supplied name"
    (let [v (t/make-scalar "Money")]
      (is (= :type/scalar (:case v)))
      (is (= "Money" (:name v)))
      (is (m/validate t/Type v)))))

(deftest enum
  (testing "Enum is a closed set of literal values (distinct from Union)"
    (let [v (t/make-enum ["pending" "shipped" "cancelled"])]
      (is (= :type/enum (:case v)))
      (is (= ["pending" "shipped" "cancelled"] (:values v)))
      (is (m/validate t/Type v)))))

(deftest composite-named
  (testing "Composite Named reuses a value-typed Container's fields"
    (let [v (t/make-composite-named "address-1")]
      (is (= :type/composite (:case v)))
      (is (= :shape/named (get-in v [:shape :case])))
      (is (= "address-1" (get-in v [:shape :container])))
      (is (m/validate t/Type v)))))

(deftest composite-inline
  (testing "Composite Inline declares anonymous fields; optionality on FieldSpec"
    (let [v (t/make-composite-inline
              [(t/make-field-spec "amount" (t/make-scalar "Integer") false)
               (t/make-field-spec "memo"   (t/make-scalar "String")  true)])]
      (is (= :type/composite (:case v)))
      (is (= :shape/inline (get-in v [:shape :case])))
      (is (= 2 (count (get-in v [:shape :fields]))))
      (is (true? (get-in v [:shape :fields 1 :optional])))
      (is (m/validate t/Type v)))))

(deftest collection-sequential
  (let [v (t/make-collection (t/make-scalar "String") :sequential)]
    (is (= :type/collection (:case v)))
    (is (= :sequential (:semantics v)))))

(deftest collection-unique
  (let [v (t/make-collection (t/make-scalar "String") :unique)]
    (is (= :unique (:semantics v)))))

(deftest collection-keyed
  (testing "Keyed semantics carries a key Type"
    (let [v (t/make-collection (t/make-composite-named "ShippingMethod")
                                (t/keyed (t/make-scalar "String")))]
      (is (= :type/collection (:case v)))
      (is (map? (:semantics v)))
      (is (= :semantics/keyed (get-in v [:semantics :case])))
      (is (= "String" (get-in v [:semantics :key-type :name]))))))

(deftest union
  (testing "Union is a sum of distinct Types"
    (let [v (t/make-union [(t/make-scalar "String")
                            (t/make-scalar "Integer")])]
      (is (= :type/union (:case v)))
      (is (= 2 (count (:types v)))))))

(deftest ref-kernel-primitive
  (testing "Ref reaches kernel primitive kinds with optional tag constraint"
    (let [v (t/make-ref-kernel-primitive #{:container} {:where #{"Allium::Entity"}})]
      (is (= :type/ref (:case v)))
      (is (= :ref-target/kernel-primitive (get-in v [:target :case])))
      (is (= #{:container} (get-in v [:target :kinds])))
      (is (= #{"Allium::Entity"} (:where v))))))

(deftest ref-substrate
  (testing "Ref Substrate addresses a specific Field/Parameter on a Container"
    (let [v (t/make-ref-substrate :container #{:field})]
      (is (= :type/ref (:case v)))
      (is (= :ref-target/substrate (get-in v [:target :case])))
      (is (= :container (get-in v [:target :within-kind])))
      (is (= #{:field} (get-in v [:target :slot-kinds]))))))

(deftest identity-is-structural
  (testing "Two Types with the same case + key fields are equal"
    (is (= (t/make-scalar "Money") (t/make-scalar "Money")))
    (is (not= (t/make-scalar "Money") (t/make-scalar "Currency")))
    (is (= (t/make-collection (t/make-scalar "String") :sequential)
           (t/make-collection (t/make-scalar "String") :sequential)))
    (is (not= (t/make-collection (t/make-scalar "String") :sequential)
              (t/make-collection (t/make-scalar "String") :unique)))))

(deftest schema-rejects-malformed-values
  (testing "malli schema rejects non-Type maps"
    (is (false? (m/validate t/Type {})))
    (is (false? (m/validate t/Type {:case :type/bogus})))
    (is (false? (m/validate t/Type {:case :type/scalar}))
        "missing required :name slot on Scalar")))
