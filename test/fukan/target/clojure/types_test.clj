(ns fukan.target.clojure.types-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.types :as types]
            [fukan.model.type :as t]
            [fukan.project-layer.registry :as r]))

(deftest scalar-builtins
  (let [reg (r/make-registry)]
    (is (= :string  (types/render reg (t/make-scalar "String"))))
    (is (= :int     (types/render reg (t/make-scalar "Integer"))))
    (is (= :boolean (types/render reg (t/make-scalar "Boolean"))))))

(deftest scalar-with-project-override
  (let [reg (r/with-type-override (r/make-registry) "Money" [:and :int [:>= 0]])]
    (is (= [:and :int [:>= 0]] (types/render reg (t/make-scalar "Money"))))))

(deftest scalar-unknown-falls-back-to-any
  (let [reg (r/make-registry)]
    (is (= :any (types/render reg (t/make-scalar "RandomCustomType"))))))

(deftest enum-rendering
  (let [reg (r/make-registry)
        ty (t/make-enum ["a" "b" "c"])]
    (is (= [:enum "a" "b" "c"] (types/render reg ty)))))

(deftest collection-sequential
  (let [reg (r/make-registry)
        ty (t/make-collection (t/make-scalar "String") :sequential)]
    (is (= [:vector :string] (types/render reg ty)))))

(deftest composite-named-ref
  (let [reg (r/make-registry)
        ty (t/make-composite-named "fukan/model/spec::Order")]
    (is (= {:fukan/composite-ref "fukan/model/spec::Order"}
           (types/render reg ty)))))
