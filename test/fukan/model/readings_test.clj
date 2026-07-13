(ns fukan.model.readings-test
  "The READINGS — Projections whose target artifact is a Finding, rendered from their resolved
   focus (each reading's own inline :select) by materialize/render-finding. The defining property:
   a reading renders ONLY its resolved focus — it never re-selects, so the focus cannot drift from
   the reading.

   Patterns/Consistency are the shipped readings, exercised through ad-hoc instances carrying the
   same names the render-finding methods dispatch on — folded onto the model for these tests."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.cozo.build :as build]
            [fukan.cozo.query :as cq]
            [fukan.canvas.core.lens :refer [Projection]]
            [fukan.model.pipeline :as pipeline]
            ;; composition root: registers the FACT extractor + the Cozo check engine
            [fukan.infra.model]
            [fukan.model.materialize :as m]))

;; ad-hoc reading instances — the names match the registered render-finding methods
(Projection ^{:name "Patterns"} rt-patterns
  "Recurring structures — structural triplets borne by more than one relation (a reading)."
  {:select '[[?n :rel/kind _]]})
(Projection ^{:name "Consistency"} rt-consistency
  "Operation-name ambiguity — names borne by more than one module (a reading)."
  {:select '[(Operation ?n)]})

(defn- model+readings
  "The design model with the two ad-hoc reading instances folded on."
  []
  (build/fold-vars->cozo (pipeline/build-model nil)
                         [#'rt-patterns #'rt-consistency]))

(defn- proj [db nm]
  (ffirst (cq/q '[:find ?e :in $ ?n
                  :where [?e :structure/of :fukan.canvas.core.lens/Projection] [?e :entity/name ?n]] db nm)))

(deftest read-all-runs-the-reading-projections
  (testing "read-all renders every reading projection present in the db into a Finding, keyed by name"
    (let [db  (model+readings)
          all (m/read-all db)]
      (is (= #{"Patterns" "Consistency"} (set (keys all)))
          "the reading projections")
      (is (seq (:observations (all "Patterns"))) "patterns: recurring structural triplets")
      (is (every? (fn [o] (and (set? (:focus o)) (keyword? (:as o)) (string? (:note o))))
                  (mapcat :observations (vals all)))
          "every observation is {focus tag note}"))))

(deftest a-plain-build-ships-no-readings
  (testing "with the principles layer cut, a plain build carries no reading projections"
    (is (empty? (m/read-all (pipeline/build-model nil))))))

(deftest a-reading-renders-only-its-focus
  (testing "Patterns renders the relation nodes its inline :select focuses — and ONLY them"
    (let [db     (model+readings)
          result (m/read-projection db (proj db "Patterns"))]
      (is (= "Patterns" (:lens result)))
      (is (seq (:observations result)) "the self-model has recurring structures")
      (let [o (first (:observations result))]
        (is (= :pattern (:as o)))
        (is (set? (:focus o)))
        (is (seq (:focus o))))
      ;; the anti-drift property: render reads ONLY its focus — an empty focus yields nothing, even
      ;; though the whole db is full of relations. (A relation-less focus ⇒ no patterns.)
      (is (empty? (:observations (m/render-finding db "Patterns" #{})))
          "empty focus ⇒ no patterns — the render never re-queries past its focus"))))

(deftest render-finding-rejects-an-unknown-projection
  (testing "render-finding's :default throws for a projection with no reading renderer"
    (is (thrown? clojure.lang.ExceptionInfo
                 (m/render-finding (pipeline/build-model nil) "Nonesuch" #{})))))

