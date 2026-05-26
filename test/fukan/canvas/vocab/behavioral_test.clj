(ns fukan.canvas.vocab.behavioral-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [datascript.core :as d]))

(deftest invariant-creates-affordance-with-role
  (testing "(invariant …) produces an Affordance with :canvas/invariant role and a formal-expression"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (invariant "StratifiedFixedPoint"
                   "Evaluation reaches a fixed point at each stratum."
                   (holds-that "After fixed-point iteration within stratum s, no rule fires again."))))
          rows (d/q '[:find ?n ?r
                      :where [?a :entity/type :Affordance]
                             [?a :entity/name ?n]
                             [?a :affordance/role ?r]]
                    db)]
      (is (= 1 (count rows)))
      (is (= ["StratifiedFixedPoint" :canvas/invariant] (first rows))))))

(deftest invariant-persists-formal-expression
  (testing "the holds-that prose is stored in :affordance/formal-expression"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (invariant "SingleServerInstance"
                   "Only one server runs at a time."
                   (holds-that "At most one HTTP server process bound to the configured port."))))
          rows (d/q '[:find ?fe
                      :where [?a :entity/name "SingleServerInstance"]
                             [?a :affordance/formal-expression ?fe]]
                    db)]
      (is (= 1 (count rows)))
      (is (re-find #"At most one" (first (first rows)))))))

(deftest invariant-without-holds-that
  (testing "(invariant …) is valid with only name + doc; holds-that is optional"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (invariant "MentionedButUnspecified"
                   "Documented but not formally expressed.")))
          rows (d/q '[:find ?n
                      :where [?a :entity/type :Affordance]
                             [?a :affordance/role :canvas/invariant]
                             [?a :entity/name ?n]]
                    db)]
      (is (= ["MentionedButUnspecified"] (mapv first rows))))))

(deftest invariant-persists-doc
  (let [db (h/with-canvas
             (h/within-module "constraint.evaluator"
               (invariant "FixedPoint" "Evaluation reaches a fixed point.")))
        rows (d/q '[:find ?n ?doc
                    :where [?e :affordance/role :canvas/invariant]
                           [?e :entity/name ?n]
                           [?e :affordance/doc ?doc]]
                  db)]
    (is (= [["FixedPoint" "Evaluation reaches a fixed point."]] (vec rows)))))

(deftest rule-creates-affordance-with-role
  (testing "(rule …) produces an Affordance with :canvas/rule role and formal-expression"
    (let [db (h/with-canvas
               (h/within-module "vocabulary.allium.pipeline"
                 (rule "LoadSource"
                   "Source loading rule — triggered by load_source."
                   (when LoadSource (source_root :String)))))
          rows (d/q '[:find ?n ?r
                      :where [?a :entity/type :Affordance]
                             [?a :entity/name ?n]
                             [?a :affordance/role ?r]]
                    db)]
      (is (= 1 (count rows)))
      (is (= ["LoadSource" :canvas/rule] (first rows))))))

(deftest rule-persists-when-clause-as-edn
  (testing "the (when ...) clause is stored as edn in formal-expression"
    (let [db (h/with-canvas
               (h/within-module "validation.phase4"
                 (rule "RunPhase4"
                   "Phase 4 entry point."
                   (when RunPhase4 (model :model/Model)))))
          rows (d/q '[:find ?fe
                      :where [?a :entity/name "RunPhase4"]
                             [?a :affordance/formal-expression ?fe]]
                    db)
          parsed (edn/read-string (first (first rows)))]
      (is (= 1 (count rows)))
      (is (contains? parsed :when))
      (is (= 'RunPhase4 (first (:when parsed)))))))
