(ns fukan.canvas.core.bridge-test
  "A SECOND BRIDGE, between planes that are neither design nor code: authored `Concept`s against
   the Lexicon entities read from the fixture project. What it holds is that a correspondence is
   a statement about two planes of one graph and nothing narrower — it pairs and realizes through
   its own bridge, and the ambient design↔code bridge neither feeds it nor is fed by it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fukan.canvas.core.structure :as s :refer [defstructure correspond]]
            [fukan.canvas.projection.grammar :as grammar]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx :refer [Entity]]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.cozo.query :as cq]))

(defstructure Concept
  "A thing the design says the domain has, and the concepts it holds."
  {:holds [:* Concept]})

;; The bridge is NAMED. Its one component pairs a concept with the entity of the same name, and
;; says a `:holds` edge is realized by ONE step of containment between their entities.
(correspond :domain
  [Concept ?c Entity ?e]
  [(named ?c ?n) (named ?e ?n)]
  {:holds :part})

(Concept line "Realized by the entity of that name." {})
(Concept order "Holds lines, as the entity does." {:holds [line]})
(Concept customer
  "Holds orders, as the entity does — and claims to hold lines directly, which the domain realizes
   only through an order."
  {:holds [order line]})
(Concept ghost "Nothing in the domain answers to this name." {})

(defn- model []
  (binding [lx/*sources* '{:vocabulary ["shop.domain.lexicon"] :view #{shop.domain.lexicon/view}}]
    (let [p ["test/fixtures/lexicon"]]
      (build/model->cozo ['fukan.canvas.core.bridge-test]
                         {:planes {"code"   (:roots (tc/extract-roots p))
                                   "domain" (:roots (lx/extract-roots p))}}))))

(defn- names [db where]
  (set (cq/q (into '[:find ?an ?bn :in $ % :where] (conj where '[?a :entity/name ?an] '[?b :entity/name ?bn]))
             db (s/vocab-rules))))

(deftest a-produced-node-carries-the-plane-it-arrived-under
  (let [db    (model)
        plane (fn [tag] (set (cq/q '[:find [?p ...] :in $ % ?t :where [?n :structure/of ?t] (plane ?n ?p)]
                                   db (s/vocab-rules) tag)))]
    (is (= #{"domain"} (plane ::lx/Entity)))
    (is (= #{"domain"} (plane ::lx/View)))
    (is (= #{"code"} (plane :fukan.common.extraction.clojure.module/Ns)))
    (testing "an authored node is on no produced plane, and is still what `design` means"
      (is (= #{} (plane ::Concept)))
      (is (= 4 (count (cq/q '[:find ?c :in $ % :where [?c :structure/of ::Concept] (design ?c)]
                            db (s/vocab-rules))))))))

(deftest a-named-bridge-pairs-and-realizes-through-itself
  (let [db (model)]
    (testing "pairing: the three concepts an entity answers to, and not the fourth"
      (is (= #{["customer" "customer"] ["order" "order"] ["line" "line"]}
             (names db '[(corresponds-domain ?a ?b)]))))
    (testing "realization: a `:holds` edge the domain carries as one step of containment"
      (is (= #{["customer" "order"] ["order" "line"]}
             (names db '[(realized-domain-holds ?a ?b)]))))
    (testing "the edge the design claims and the domain does not carry is the difference"
      (is (= #{["customer" "line"]}
             (names db '[(holds ?a ?b) (not (realized-domain-holds ?a ?b))]))))))

(deftest the-two-bridges-do-not-read-each-other
  (let [db (model)]
    (testing "the ambient pairing holds no concept: a second bridge added nothing to the first"
      (is (= #{} (names db '[[?a :structure/of ::Concept] (corresponds ?a ?b)]))))
    (testing "and the named pairing holds nothing of the first bridge's sorts"
      (is (= #{} (names db '[[?b :structure/of :fukan.common.extraction.clojure.module/Ns]
                             (corresponds-domain ?a ?b)]))))
    (testing "the laws riding the ambient bridge are undisturbed"
      (is (= [] (filter #(some-> (:key %) namespace (= "correspondence")) (law/check db)))))))

(deftest a-bridge-is-read-back-through-its-own-pairing
  (let [card  (grammar/correspondence-card (model))
        entry (some #(when (str/includes? % "(correspond :domain ") %) (str/split card #"\n\n"))]
    (testing "the card names the bridge a correspondence is a component of"
      (is (some? entry)))
    (testing "and counts ITS unpaired design elements — `ghost`, through `corresponds-domain`. Read
              through the ambient pairing, all four concepts would count: none has a code partner."
      (is (str/includes? entry "unrealized: 1  ambiguous: 0")))))
