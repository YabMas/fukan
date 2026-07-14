(ns fukan.cozo.measure-test
  "The measure layer — aggregate rule heads + the inline (measure …) clause.
   Aggregates render as native Cozo head aggregates; plain head vars are the group keys."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.query :as cq]
            [fukan.cozo.build :as build]
            [fukan.cozo.law :as law]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            ;; module/operation vocab: the Module/Operation kind rules + the
            ;; :contains-derived `contains` rule the fixtures query at
            [fukan.common.vocab.code.module]
            [fukan.common.vocab.code.operation]))

;; ── fixture: two synthetic modules with :exposes/:owns/:child members ────────
;; m1 exposes o1,o2 + owns k1 + child c1 (iface 2, contains 4); m2 exposes o3 (iface 1, contains 1)
(defn- fixture []
  (build/maps->cozo
   [{:entity/id "m1" :structure/of :fukan.common.vocab.code.module/Module :entity/name "mod.one"}
    {:entity/id "m2" :structure/of :fukan.common.vocab.code.module/Module :entity/name "mod.two"}
    {:entity/id "o1" :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-1"}
    {:entity/id "o2" :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-2"}
    {:entity/id "o3" :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "op-3"}
    {:entity/id "k1" :structure/of :fukan.common.vocab.code.kind/Kind :entity/name "kind-1"}
    {:entity/id "c1" :structure/of :fukan.common.vocab.code.operation/Operation :entity/name "helper-1"}]
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

;; ── slice 2: the inline (measure …) clause ───────────────────────────────────

(deftest measure-groups-by-sibling-shared-vars
  (testing "group vars = body vars shared with sibling clauses"
    (let [db (fixture)]
      (is (= {"mod.one" 2 "mod.two" 1}
             (by-name db (cq/q '[:find ?m ?k :in $ %
                                 :where (Module ?m)
                                        (measure ?k (count ?op)
                                          [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op])]
                               db (s/vocab-rules))))))))

(deftest measure-groups-by-find-vars-alone
  (testing "a group var appearing only in the find spec (no sibling clause) still groups"
    (let [db (fixture)]
      (is (= {"mod.one" 2 "mod.two" 1}
             (by-name db (cq/q '[:find ?m ?k :in $ %
                                 :where (measure ?k (count ?op)
                                          [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op])]
                               db (s/vocab-rules))))))))

(deftest measure-with-no-shared-vars-is-a-global-aggregate
  (testing "zero shared vars ⇒ one key-less row"
    (let [db (fixture)]
      (is (= [4] (cq/q '[:find [?k ...] :in $ %
                         :where (measure ?k (count ?op) (Operation ?op))]
                       db (s/vocab-rules)))
          "o1 o2 o3 + the :child helper-1 are Operations"))))

(deftest two-measures-in-one-query
  (testing "two inline measures join on their shared group var"
    (let [db   (fixture)
          rows (cq/q '[:find ?m ?e ?c :in $ %
                       :where (measure ?e (count ?op)
                                [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op])
                              (measure ?c (count ?x) (contains ?m ?x))]
                     db (s/vocab-rules))]
      (is (= {"mod.one" [2 4] "mod.two" [1 1]}
             (into {} (map (fn [[m e c]] [(:entity/name (cq/entity db m)) [e c]])) rows))))))

(deftest measure-thresholds-into-a-predicate
  (testing "a comparison over a measure output — the re-entry point"
    (let [db (fixture)]
      (is (= #{"mod.one"}
             (set (map #(:entity/name (cq/entity db %))
                       (cq/q '[:find [?m ...] :in $ %
                               :where (measure ?k (count ?op)
                                        [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op])
                                      [(> ?k 1)]]
                             db (s/vocab-rules)))))))))

(deftest nested-measure-in-measure-body
  (testing "an inner measure grouped by a sibling inside the outer body; the outer folds it"
    (let [db (fixture)]
      (is (= [2] (cq/q '[:find [?mx ...] :in $ %
                         :where (measure ?mx (max ?k)
                                  (Module ?m)
                                  (measure ?k (count ?op)
                                    [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op]))]
                       db (s/vocab-rules)))
          "max per-module exposed count = mod.one's 2"))))

(deftest measure-inside-not-join-throws
  (testing "negated aggregation is not supported — clear error, not silent miscompile"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported inside"
          (cq/q '[:find [?m ...] :in $ %
                  :where (Module ?m)
                         (not-join [?m]
                           (measure ?k (count ?op)
                             [?r :rel/from ?m] [?r :rel/kind :exposes] [?r :rel/to ?op]))]
                (fixture) (s/vocab-rules))))))

(deftest malformed-measure-clauses-throw
  (let [db (fixture)]
    (testing "out-var appearing in the body is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not appear"
            (cq/q '[:find [?k ...] :in $ %
                    :where (measure ?k (count ?k) (Operation ?k))] db (s/vocab-rules)))))
    (testing "aggregate var absent from the body is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must appear"
            (cq/q '[:find [?k ...] :in $ %
                    :where (measure ?k (count ?zz) (Operation ?op))] db (s/vocab-rules)))))))

;; ── a measure inside a LAW body (the law-engine path) ────────────────────────
;; NOTE: `(law …)` is a syntactic form the defstructure macro recognizes — write it BARE
;; (not s/law); use `:refer [defstructure]` in the ns form, matching composition_test.clj.
(defstructure MtThing "measure-test: a contained thing")
(defstructure MtCrate
  "measure-test: a container whose law caps its content via an inline measure"
  {:holds [:* MtThing]}
  (law "an MtCrate holds at most 2 things"
    :offenders '[?c]
    :where '[(measure ?k (count ?x)
               [?r :rel/from ?c] [?r :rel/kind :holds] [?r :rel/to ?x])
             [(> ?k 2)]]))

(MtThing mt-t1)
(MtThing mt-t2)
(MtThing mt-t3)
(MtCrate mt-big   {:holds [mt-t1 mt-t2 mt-t3]})
(MtCrate mt-small {:holds [mt-t1]})

(deftest measure-in-a-law-body
  (testing "a self-scoped law using an inline measure fires on the over-full crate only"
    (let [db   (build/vars->cozo [#'mt-t1 #'mt-t2 #'mt-t3 #'mt-big #'mt-small])
          offs (->> (law/check db)
                    (filter #(= "an MtCrate holds at most 2 things" (:law %)))
                    (mapcat :offenders) (map first)
                    (map #(:entity/name (cq/entity db %))) set)]
      (is (= #{"mt-big"} offs)))))
