(ns fukan.cozo.measure-test
  "The measure layer — aggregate rule heads + the inline (measure …) clause.
   Aggregates render as native Cozo head aggregates; plain head vars are the group keys."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law]
            [fukan.canvas.core.structure :as s]   ; Task 2 adds :refer [defstructure]
            ;; module/operation vocab: the Module/Operation kind rules + the
            ;; :contains-derived `contains` rule the fixtures query at
            [canvas.vocab.code.module]
            [canvas.vocab.code.operation]))

;; ── fixture: two synthetic modules with :exposes/:owns/:child members ────────
;; m1 exposes o1,o2 + owns k1 + child c1 (iface 2, contains 4); m2 exposes o3 (iface 1, contains 1)
(defn- fixture []
  (build/maps->cozo
   [{:entity/id "m1" :structure/of :canvas.vocab.code.module/Module :entity/name "mod.one"}
    {:entity/id "m2" :structure/of :canvas.vocab.code.module/Module :entity/name "mod.two"}
    {:entity/id "o1" :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-1"}
    {:entity/id "o2" :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-2"}
    {:entity/id "o3" :structure/of :canvas.vocab.code.operation/Operation :entity/name "op-3"}
    {:entity/id "k1" :structure/of :canvas.vocab.code.kind/Kind :entity/name "kind-1"}
    {:entity/id "c1" :structure/of :canvas.vocab.code.operation/Operation :entity/name "helper-1"}]
   [{:rel/id "r1" :rel/from [:entity/id "m1"] :rel/kind :exposes :rel/to [:entity/id "o1"]}
    {:rel/id "r2" :rel/from [:entity/id "m1"] :rel/kind :exposes :rel/to [:entity/id "o2"]}
    {:rel/id "r3" :rel/from [:entity/id "m1"] :rel/kind :owns    :rel/to [:entity/id "k1"]}
    {:rel/id "r4" :rel/from [:entity/id "m1"] :rel/kind :child   :rel/to [:entity/id "c1"]}
    {:rel/id "r5" :rel/from [:entity/id "m2"] :rel/kind :exposes :rel/to [:entity/id "o3"]}]))

(defn- by-name
  "eid→count tuples → {entity-name count}."
  [db rows]
  (into {} (map (fn [[m k]] [(:entity/name (cq/entity db m)) k]) rows)))

;; ── slice 1: aggregate heads on rules ────────────────────────────────────────

(deftest aggregate-rule-head-groups-by-plain-head-vars
  (testing "a % rule with a (count ?v) head yields one row per group key"
    (let [db (fixture)]
      (is (= {"mod.one" 2 "mod.two" 1}
             (by-name db (cq/q '[:find ?m ?k :in $ %
                                 :where (exposed-count ?m ?k)]
                               db
                               '[[(exposed-count ?m (count ?op))
                                  [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op]]])))))))

(s/defrelation :mt-exposed-count "test measure: a module's exposed-op count"
  '[?m (count ?op)]
  '[[?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op]])

(deftest aggregate-headed-defrelation-is-vocab-injected
  (testing "a defrelation with an aggregate head rides vocab-rules like any derived relation"
    (let [db (fixture)]
      (is (= {"mod.one" 2 "mod.two" 1}
             (by-name db (cq/q '[:find ?m ?k :in $ %
                                 :where (mt-exposed-count ?m ?k)]
                               db (s/vocab-rules))))))))

(deftest unknown-aggregate-in-head-throws
  (testing "a rule head with an unsupported aggregate is rejected with a clear error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported rule-head term"
          (cq/q '[:find ?m ?k :in $ %
                  :where (bad-agg ?m ?k)]
                (fixture)
                '[[(bad-agg ?m (median ?op))
                   [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op]]])))))
