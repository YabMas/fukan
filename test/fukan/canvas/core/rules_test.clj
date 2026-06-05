(ns fukan.canvas.core.rules-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.assemble :as a]
            [fukan.canvas.core.rules :as rules]
            [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure RuleThing
  "A fixture structure: a relation slot (→ a relation rule) + a law that reads over
   the vocab-derived rules (→ exercises check's auto-injection)."
  (slot :links (many RuleThing))
  (law "no rule-thing may be named \"forbidden\""
    :scope :global
    :offenders '[?s]
    :where '[(RuleThing ?s) (named ?s "forbidden")]))

(defstructure Mod
  "A grouping fixture — a node whose :child relations place its members in a module
   (so (in-module ?e \"t\") resolves)."
  (slot :child (many Any)))

;; instances under test
(def rt-a (RuleThing "a"))
(def rt-b (RuleThing "b"))
(def rt-t (Mod "t" (child rt-a rt-b)))

(def rt2-ok        (RuleThing "ok"))
(def rt2-forbidden (RuleThing "forbidden"))

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
                                     :where [?s :structure/of :RuleThing]
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
