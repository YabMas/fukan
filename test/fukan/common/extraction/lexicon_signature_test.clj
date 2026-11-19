(ns fukan.common.extraction.lexicon-signature-test
  "ONE EXPRESSION, THREE ALTITUDES. The domain is declared in Lexicon; a design signature and a
   code signature are both written in what it declares — an attribute where one value passes, a
   view where a shape does. What this holds is that fukan reads the same thing off both, in the
   domain's terms, and that a disagreement between them is reported in those terms too."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.structure :as s]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx]
            [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]))

(Module checkout "The fixture's `shop.app.checkout`, as designed."
  (Kind Clock "A named shape that is no part of the domain: a Kind still names it.")
  (Operation receipt "An order, as a receipt."
    {:signature [:=> [:cat :order/id] Receipt]})
  (Operation place "A customer places an order for some products."
    {:signature [:=> [:cat :customer/id [:vector :line/product]] Placement]})
  (Operation label "WRONG ON PURPOSE: the design says a label is a Receipt; the code gives a Label."
    {:signature [:=> [:cat :customer/id] Receipt]})
  (Operation render "A receipt, as text."
    {:signature [:=> [:cat Receipt] :string]})
  (Operation print-receipt "An order's receipt, as text."
    {:signature [:=> [:cat :order/id] :string]
     :delegates [receipt render]})
  (Operation stamp "Names a Kind, a view nobody declared and an attribute the vocabulary lacks."
    {:signature [:=> [:cat Clock Missing] :order/colour]}))

(def ^:private design :fukan.common.vocab.code.operation/Operation)
(def ^:private code   :fukan.common.extraction.clojure.operation/Fn)

(defn- model []
  (binding [lx/*sources* '{:vocabulary ["shop.domain.lexicon"] :view #{shop.domain.lexicon/view}}]
    (let [p ["test/fixtures/lexicon" "test/fixtures/lexicon_signatures"]]   ; the vocabulary, and code speaking it
      (build/model->cozo ['fukan.common.extraction.lexicon-signature-test]
                         {:planes {"code"   (:roots (tc/extract-roots p))
                                   "domain" (:roots (lx/extract-roots p))}}))))

(defn- found [db k] (filter #(= k (:key %)) (law/check db)))

(defn- pairs [db tag rel]
  (set (cq/q (list :find '?xn '?an :in '$ '% :where (list 'is '?x tag) (list rel '?x '?a)
                   '[?x :entity/name ?xn] '[?a :entity/name ?an])
             db (s/vocab-rules))))

(deftest a-signature-is-read-in-the-domains-terms
  (let [db (model)]
    (testing "what it takes: a named attribute, an attribute inside a collection, a view's selection"
      (is (= #{["receipt" "order/id"] ["print-receipt" "order/id"] ["label" "customer/id"]
               ["place" "customer/id"] ["place" "line/product"]
               ["render" "order/id"] ["render" "order/total"] ["render" "customer/name"]}
             (pairs db code 'takes-attribute))))
    (testing "what it gives"
      (is (= #{["receipt" "order/id"] ["receipt" "order/total"] ["receipt" "customer/name"]
               ["place" "order/id"] ["place" "order/customer"]
               ["label" "customer/id"] ["label" "customer/name"]}
             (pairs db code 'gives-attribute))))))

(deftest design-and-code-say-the-same-thing-in-the-same-words
  (let [db    (model)
        named #{"receipt" "place" "render" "print-receipt"}
        only  (fn [rows] (set (filter (comp named first) rows)))]
    (is (= (only (pairs db design 'takes-attribute)) (only (pairs db code 'takes-attribute))))
    (is (= (only (pairs db design 'gives-attribute)) (only (pairs db code 'gives-attribute))))
    (testing "a shape one operation gives and another takes, named at both ends"
      (is (contains? (set (cq/q '[:find ?xn ?vn ?yn :in $ % ?t
                                  :where (is ?x ?t) (is ?y ?t) (hands-to ?x ?v ?y)
                                  [?x :entity/name ?xn] [?v :entity/name ?vn] [?y :entity/name ?yn]]
                                db (s/vocab-rules) code))
                     ["receipt" "Receipt" "render"])))))

(deftest a-disagreement-is-reported-in-the-domains-terms
  (let [db (model)]
    (testing "the ordinary signature law sees it, with no law of the domain's own"
      (is (= 1 (count (mapcat :offenders (found db :correspondence/signature-disagrees))))))
    (testing "and what the design promises that the code does not give is two attributes"
      (is (= #{["label" "order/id"] ["label" "order/total"]}
             (set (cq/q '[:find ?on ?an :in $ %
                          :where (corresponds ?o ?f) (gives-attribute ?o ?a) (not (gives-attribute ?f ?a))
                          [?o :entity/name ?on] [?a :entity/name ?an]]
                        db (s/vocab-rules))))))))

(deftest a-reference-resolves-to-a-kind-a-view-or-an-attribute-and-to-nothing-else
  (let [unresolved (->> (found (model) :typing/reference-unresolved)
                        (mapcat :offenders) (map first) set)]
    (is (= #{"Missing" "order/colour"} unresolved))))

(deftest a-function-reaches-what-it-reads-through-what-it-calls
  (let [db    (model)
        reach (fn [rel] (set (cq/q (list :find '?fn '?en :in '$ '% :where (list rel '?f '?e)
                                         '[?f :entity/name ?fn] '[?e :entity/name ?en])
                                   db (s/vocab-rules))))]
    (testing "one function names the view in its body — a signature naming it is not a use"
      (is (= #{["load-receipt" "order"] ["load-receipt" "customer"]}
             (disj (reach 'fn-reads) ["pair" "customer"]))))
    (testing "its callers reach the same entities, at any depth"
      (is (= #{["load-receipt" "order"] ["load-receipt" "customer"]
               ["receipt" "order"] ["receipt" "customer"]
               ["print-receipt" "order"] ["print-receipt" "customer"]}
             (disj (reach 'reaches-entity) ["pair" "customer"]))))
    (testing "a function declaring a view inline uses it"
      (is (contains? (reach 'fn-reads) ["pair" "customer"])
          "from the vocabulary fixture's `shop.domain.orders/pair`")
      (is (contains? (lx/footprint db) ["shop.domain.orders" "customer"])))
    (testing "by namespace: how far the domain's words travel"
      (is (contains? (lx/footprint db) ["shop.app.checkout" "order"])))))

(deftest a-signature-claiming-what-nothing-explains
  (testing "`place` gives an order, takes nothing of one and reads none; `label` is handed the
            customer it gives, and `receipt` reaches the order it gives"
    (is (= #{["shop.app.checkout" "place" "order"]} (lx/unread-claims (model))))))
