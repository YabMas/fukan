(ns fukan.canvas.core.rules-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            ;; loaded for its side-effect: registers the Cozo check engine so s/check dispatches to it
            [fukan.cozo.law]
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
    (let [db (build/vars->cozo [#'rt-a #'rt-b #'rt-t])
          rs (s/vocab-rules)
          via-rules (sort (cq/q '[:find [?n ...] :in $ %
                                 :where (RuleThing ?s) (in-module ?s "t") (named ?s ?n)]
                               db rs))
          via-substrate (sort (cq/q '[:find [?n ...]
                                     :where [?s :structure/of ::RuleThing]
                                            [?r :rel/kind :child] [?r :rel/from ?m] [?r :rel/to ?s]
                                            [?m :entity/name "t"] [?s :entity/name ?n]]
                                   db))]
      (is (= ["a" "b"] via-rules))
      (is (= via-substrate via-rules) "domain altitude ≡ substrate"))))

(deftest law-reads-via-injected-rules
  (testing "a law whose :where uses domain predicates fires correctly — check injects the rules"
    (let [db        (build/vars->cozo [#'rt2-ok #'rt2-forbidden])
          offenders (->> (s/check db)
                         (filter #(= "no rule-thing may be named \"forbidden\"" (:law %)))
                         (mapcat :offenders) (map first)
                         (map #(:entity/name (cq/entity db %)))
                         set)]
      (is (= #{"forbidden"} offenders)
          "the law selected the offender purely through (RuleThing ?s) + (named ?s \"forbidden\")"))))

(deftest defrelation-emits-an-injected-custom-rule
  (testing "defrelation registers a custom-bodied derived relation, injected into vocab-rules"
    (is (contains? (set (map (comp first first) (s/vocab-rules))) 'same-name)
        "the derived relation is a vocab rule, available to every law and query"))
  (testing "yields ONLY its custom rule — no spurious unary kind-rule (a derived relation has no instances)"
    (let [same-name-rules (filter #(= 'same-name (ffirst %)) (s/vocab-rules))]
      (is (seq same-name-rules))
      (is (every? #(= 2 (count (rest (first %)))) same-name-rules)
          "every `same-name` rule is the binary custom body; no unary (kind) rule slipped in")))
  (testing "the derived relation is callable at domain altitude over the injected rules"
    (let [db    (build/vars->cozo [#'rt3-a #'rt3-b #'rt3-c])
          pairs (cq/q '[:find ?na ?nb :in $ %
                       :where (same-name ?a ?b) [?a :entity/name ?na] [?b :entity/name ?nb]]
                     db (s/vocab-rules))]
      (is (= #{["dup" "dup"]} (set pairs))
          "only the two distinct same-named nodes pair — the custom predicate body fired"))))

(deftest twin-pairs-design-and-fact-across-the-containment-ladder
  (testing "root kinds twin by bridge; nested kinds twin by name within twinned containers"
    (let [db (build/maps->cozo
              [{:entity/id "cm" :structure/of :canvas.vocab.code.module/Module :entity/name "infra-model"}
               {:entity/id "km" :structure/of :canvas.vocab.code.module/Module :entity/name "fukan.infra.model" :val/extracted true}
               {:entity/id "co" :structure/of :canvas.vocab.code.operation/Operation :entity/name "load-model"}
               {:entity/id "ko" :structure/of :canvas.vocab.code.operation/Operation :entity/name "load-model" :val/extracted true}
               {:entity/id "stray" :structure/of :canvas.vocab.code.operation/Operation :entity/name "load-model" :val/extracted true}]
              [{:rel/id "r1" :rel/from [:entity/id "cm"] :rel/kind :exposes :rel/to [:entity/id "co"]}
               {:rel/id "r2" :rel/from [:entity/id "km"] :rel/kind :child   :rel/to [:entity/id "ko"]}])
          twins (set (cq/q '[:find ?an ?bn :in $ %
                             :where (twin ?a ?b) [?a :entity/name ?an] [?b :entity/name ?bn]]
                           db (s/vocab-rules)))]
      (is (contains? twins ["infra-model" "fukan.infra.model"]) "module pair twins via the bridge")
      (is (contains? twins ["load-model" "load-model"]) "op pair twins by name within the twinned containers")
      (is (= 2 (count twins)) "the un-contained same-named stray op does NOT twin"))))

(deftest strata-rules-classify-provenance
  (testing "(fact ?n) / (design ?n) split nodes by the kernel provenance attribute"
    (let [db (build/maps->cozo
              [{:entity/id "d" :structure/of :canvas.vocab.code.operation/Operation :entity/name "d-op"}
               {:entity/id "f" :structure/of :canvas.vocab.code.operation/Operation :entity/name "f-op"
                :val/extracted true}]
              [])]
      (is (= #{"f-op"}
             (set (cq/q '[:find [?n ...] :in $ % :where (fact ?e) [?e :entity/name ?n]]
                        db rules/substrate-rules))))
      (is (= #{"d-op"}
             (set (cq/q '[:find [?n ...] :in $ % :where (design ?e) [?e :entity/name ?n]]
                        db rules/substrate-rules)))))))
