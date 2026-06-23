(ns fukan.canvas.core.rules-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.rules :as rules]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure RuleThing
  "A fixture structure: a relation slot (→ a relation rule) + a law that reads over
   the vocab-derived rules (→ exercises check's auto-injection)."
  {:links [:* RuleThing]}
  (law "no rule-thing may be named \"forbidden\""
    :scope :global
    :offenders '[?s]
    :where '[(RuleThing ?s) (named ?s "forbidden")]))

(defstructure Mod
  "A grouping fixture — a node whose :child relations place its members in a module
   (so (in-module ?e \"t\") resolves)."
  {:child [:* Any]})

;; instances under test
(RuleThing ^{:name "a"} rt-a)
(RuleThing ^{:name "b"} rt-b)
(Mod ^{:name "t"} rt-t {:child [rt-a rt-b]})

(RuleThing ^{:name "ok"} rt2-ok)
(RuleThing ^{:name "forbidden"} rt2-forbidden)

;; a DERIVED RELATION: a custom-bodied rule (the op-twin generalization) — registered
;; into the live vocab and injected into every law/vocab-rules query, like a kind/relation rule
(s/defrelation :same-name
  "two distinct nodes that share a name"
  '[?a ?b]
  '[[?a :entity/name ?n] [?b :entity/name ?n] [(not= ?a ?b)]])

(RuleThing ^{:name "dup"} rt3-a)
(RuleThing ^{:name "dup"} rt3-b)
(RuleThing ^{:name "uniq"} rt3-c)

(deftest derives-kind-relation-and-substrate-rules
  (testing "the live vocab yields kind rules, relation rules, and the fixed substrate rules"
    (let [heads (set (map (comp first first) (s/vocab-rules)))]
      (is (contains? heads 'RuleThing) "a kind rule per structure tag")
      (is (contains? heads 'links)     "a relation rule per relation slot")
      (is (contains? heads 'in-module) "the fixed substrate rules are present")
      (is (contains? heads 'named)))))

(deftest domain-query-equals-substrate-query
  (testing "a query over domain rules returns the same nodes as the hand-written substrate query"
    (let [db (a/assemble-vars [#'rt-a #'rt-b #'rt-t])
          rs (s/vocab-rules)
          via-rules (sort (d/q '[:find [?n ...] :in $ %
                                 :where (RuleThing ?s) (in-module ?s "t") (named ?s ?n)]
                               db rs))
          via-substrate (sort (d/q '[:find [?n ...]
                                     :where [?s :structure/of ::RuleThing]
                                            [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?s]
                                            [?m :entity/name "t"] [?s :entity/name ?n]]
                                   db))]
      (is (= ["a" "b"] via-rules))
      (is (= via-substrate via-rules) "domain altitude ≡ substrate"))))

(deftest law-reads-via-injected-rules
  (testing "a law whose :where uses domain predicates fires correctly — check injects the rules"
    (let [db        (a/assemble-vars [#'rt2-ok #'rt2-forbidden])
          offenders (->> (s/check db)
                         (filter #(= "no rule-thing may be named \"forbidden\"" (:law %)))
                         (mapcat :offenders) (map first)
                         (map #(:entity/name (d/entity db %)))
                         set)]
      (is (= #{"forbidden"} offenders)
          "the law selected the offender purely through (RuleThing ?s) + (named ?s \"forbidden\")"))))

(deftest derive-rules-is-pure
  (testing "derive-rules is a pure fn of the structure defs it is handed"
    (let [rs (rules/derive-rules [{:tag :Foo :slots [{:rel :bar :target :Foo}]}]
                                 (constantly false))
          heads (set (map (comp first first) rs))]
      (is (contains? heads 'Foo))
      (is (contains? heads 'bar))
      (is (contains? heads 'in-module) "always includes the fixed substrate rules"))))

(deftest defrelation-emits-an-injected-custom-rule
  (testing "defrelation registers a custom-bodied derived relation, injected into vocab-rules"
    (is (contains? (set (map (comp first first) (s/vocab-rules))) 'same-name)
        "the derived relation is a vocab rule, available to every law and query"))
  (testing "the derived relation is callable at domain altitude over the injected rules"
    (let [db    (a/assemble-vars [#'rt3-a #'rt3-b #'rt3-c])
          pairs (d/q '[:find ?na ?nb :in $ %
                       :where (same-name ?a ?b) [?a :entity/name ?na] [?b :entity/name ?nb]]
                     db (s/vocab-rules))]
      (is (= #{["dup" "dup"]} (set pairs))
          "only the two distinct same-named nodes pair — the custom predicate body fired"))))

(deftest defrelation-gets-no-spurious-kind-rule
  (testing "a derived-relation entry yields ONLY its custom rule — no unary kind-rule, no instances"
    (let [rs    (rules/derive-rules [{:tag :twin :derived-rule {:head '[?a ?b]
                                                                 :where '[[?a :x ?v] [?b :x ?v]]}}]
                                    (constantly false))
          heads (map (comp first first) rs)]
      (is (= 1 (count (filter #{'twin} heads))) "exactly one `twin` rule (the custom body), no kind-rule")
      (is (= '[?a ?b] (rest (ffirst (filter #(= 'twin (ffirst %)) rs))))
          "the rule head carries the declared arity"))))
