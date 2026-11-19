(ns fukan.common.extraction.inline-signature-test
  "A function written with `malli.experimental/defn` states its signature INLINE (`x :- T`),
   where neither clj-kondo's metadata nor `:malli/schema` carries it. What this holds is that
   such a function is in the model at all, and that its signature is the one the same function
   would have written as `:malli/schema`."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.common.extraction.clojure.operation :as op]
            [fukan.common.extraction.core :as tc]
            [fukan.common.typing.malli :as tm]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]))

(deftest the-form-is-read-as-the-arrow-it-states
  (testing "one arity"
    (is (= [:=> [:cat :string] :int]
           (op/inline-signature '(mx/defn f :- :int "doc" [x :- :string] x)))))
  (testing "an unannotated parameter or result is :any, as the macro takes it"
    (is (= [:=> [:cat :any :int] :any]
           (op/inline-signature '(mx/defn f [x y :- :int] x)))))
  (testing "several arities are malli's own [:function …]"
    (is (= [:function [:=> [:cat :int] :any] [:=> [:cat :int :any] :string]]
           (op/inline-signature '(mx/defn f ([x :- :int] x) ([x :- :int y] :- :string x))))))
  (testing "a rest parameter's annotation is already its repetition"
    (is (= [:=> [:cat :any [:* :int]] :any] (op/inline-signature '(mx/defn f [x & r :- [:* :int]] x))))
    (is (= [:=> [:cat [:* :any]] :int] (op/inline-signature '(mx/defn f :- :int [& r] 1)))))
  (testing "a destructured parameter is one parameter"
    (is (= '[:=> [:cat Claim] :any] (op/inline-signature '(mx/defn f [{:keys [id]} :- Claim] id)))))
  (testing "annotating nothing writes no signature"
    (is (nil? (op/inline-signature '(mx/defn f "doc" [x] x))))))

(defn- model [] (build/model->cozo [] (tc/extract-roots ["test/fixtures/inline"])))

(defn- signatures [db]
  (into {} (for [[n sig] (cq/q '[:find ?n ?sig :in $ % :where (signature ?f ?sig) [?f :entity/name ?n]]
                               db (s/vocab-rules))]
             [n (tm/render db sig)])))

(deftest an-inline-annotated-function-is-in-the-model
  (let [db    (model)
        names (set (cq/q '[:find [?n ...] :where [?f :structure/of ::op/Fn] [?f :entity/name ?n]] db))]
    (testing "every one of them — clj-kondo reports none unless told what the macro is like"
      (is (= #{"one" "two" "rest*" "destructured" "bare" "stated" "computed" "plumatic" "caller"} names)))
    (testing "private by `^:private`, the macro having no `defn-`"
      (is (= #{"destructured"}
             (set (cq/q '[:find [?n ...] :where [?f :val/private true] [?f :entity/name ?n]] db)))))
    (testing "and its calls are edges like any other's"
      (is (= #{["caller" "one"] ["caller" "destructured"]}
             (set (cq/q '[:find ?a ?b :in $ % :where (calls ?f ?g) [?f :entity/name ?a] [?g :entity/name ?b]]
                        db (s/vocab-rules))))))))

(deftest its-signature-is-the-one-it-writes
  (let [sigs (signatures (model))]
    (is (= '[:=> [:cat :string] :int] (sigs "one")))
    (is (= '[:function [:=> [:cat :int] :any] [:=> [:cat :int :any] :string]] (sigs "two")))
    (is (= '[:=> [:cat :any [:* :int]] :any] (sigs "rest*")))
    (is (= [:=> [:cat :Claim] :any] (sigs "destructured")) "a named schema is a reference, rendered as malli spells one")
    (testing "an explicit :malli/schema wins over what is inline"
      (is (= '[:=> [:cat :boolean] :boolean] (sigs "stated"))))
    (testing "annotating nothing is no signature; nor is another library's `:-`"
      (is (not (contains? sigs "bare")))
      (is (not (contains? sigs "plumatic"))))))

(deftest a-signature-a-static-read-cannot-take-is-marked-not-guessed
  (let [db (model)]
    (is (not (contains? (signatures db) "computed")))
    (is (= #{"computed"}
           (set (cq/q '[:find [?n ...] :where [?f :val/signature-unread true] [?f :entity/name ?n]] db))))))
