(ns fukan.canvas.inspect.integrity-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.construction :refer [function record value]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.inspect.integrity :as integrity]
            [fukan.canvas.vocab.behavioral :refer [rule]]
            [fukan.canvas.vocab.event :refer [event handler]]))

;; ---------------------------------------------------------------------------
;; Clean baseline
;; ---------------------------------------------------------------------------

(deftest clean-canvas-returns-empty
  (testing "a canvas with no broken references returns no findings"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "f"
                   "An identity function."
                   (takes [x :String])
                   (gives :String))))]
      (is (= [] (integrity/check db))
          "clean canvas with only atomic types must be integrity-clean"))))

;; ---------------------------------------------------------------------------
;; Check 1 — unresolved :references
;; ---------------------------------------------------------------------------

(deftest unresolved-references-detected
  (testing "a :references datom pointing at a non-existent module produces one finding"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "f"
                   "Refs a non-existent type."
                   (takes [x :missing/Nope])
                   (gives :String))))
          findings (integrity/check db)
          unresolved (filter #(= :inspect.integrity/unresolved-reference (:check %))
                             findings)]
      (is (= 1 (count unresolved))
          "expected exactly one unresolved-reference finding")
      (let [f (first unresolved)]
        (is (= :error (:severity f)))
        (is (= :missing/Nope (-> f :detail :target)))
        (is (= 1 (count (:offenders f))))
        (is (string? (-> f :offenders first :stable-id))
            "offender must carry a resolvable stable-id")))))

(deftest resolved-references-pass
  (testing "a :references whose target exists produces no finding"
    (let [db (h/with-canvas
               (h/within-module "model.spec"
                 (value "Model" "The model."))
               (h/within-module "demo"
                 (function "f"
                   "Refs an existing cross-module type."
                   (takes [x :model/Model])
                   (gives :String))))
          findings (integrity/check db)]
      (is (empty? (filter #(= :inspect.integrity/unresolved-reference (:check %))
                          findings))
          "all references should resolve via segment-matching"))))

;; ---------------------------------------------------------------------------
;; Check 2 — :triggers target role
;; ---------------------------------------------------------------------------

(deftest triggers-pointing-at-event-detected
  (testing ":triggers ref bound at construction to an event entity produces a finding"
    ;; Set up: same module contains an event named X and a function (triggers X).
    ;; The function-lift's resolution finds entity named X but with role
    ;; :canvas/event instead of :canvas/rule — integrity must catch this.
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (event "X" "An event happens.")
                 (function "f"
                   "Triggers something that is actually an event."
                   (takes [a :String])
                   (gives :String)
                   (triggers X))))
          findings (integrity/check db)
          mismatch (filter #(= :inspect.integrity/triggers-target-not-a-rule (:check %))
                           findings)]
      (is (= 1 (count mismatch))
          "expected one triggers-target-not-a-rule finding")
      (let [f (first mismatch)]
        (is (= :error (:severity f)))
        (is (= :canvas/event (-> f :detail :actual-role)))
        (is (= :canvas/rule  (-> f :detail :expected-role)))))))

(deftest triggers-pointing-at-rule-passes
  (testing ":triggers ref bound to a proper rule produces no finding"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (rule "X"
                   "A real rule."
                   (when (X (a :String))))
                 (function "f"
                   "Triggers the rule properly."
                   (takes [a :String])
                   (gives :String)
                   (triggers X))))
          findings (integrity/check db)]
      (is (empty? (filter #(= :inspect.integrity/triggers-target-not-a-rule (:check %))
                          findings))))))

;; ---------------------------------------------------------------------------
;; Check 3 — :emits target role
;; ---------------------------------------------------------------------------

(deftest emits-pointing-at-rule-detected
  (testing ":emits ref bound at construction to a non-event entity produces a finding"
    ;; To exercise check 3, we manually transact an :emits ref pointing at a
    ;; non-event entity. The `function` lift's `emits` form only binds to
    ;; :canvas/event entities, so we go below the lift directly.
    (let [base (h/with-canvas
                 (h/within-module "demo"
                   ;; A rule with the name "X" — wrong role for an emits target.
                   (rule "X"
                     "A rule, not an event."
                     (when (X (a :String))))
                   (function "f"
                     "Emits something."
                     (takes [a :String])
                     (gives :String))))
          ;; Find the eids of "f" (the function) and "X" (the rule), then add
          ;; a synthetic :emits ref from f → X bypassing the construction-time
          ;; guard. This simulates a rename-drift scenario.
          f-eid (ffirst (d/q '[:find ?e :where [?e :entity/name "f"]] base))
          x-eid (ffirst (d/q '[:find ?e :where [?e :entity/name "X"]
                                                [?e :affordance/role :canvas/rule]]
                              base))
          db    (d/db-with base [[:db/add f-eid :emits x-eid]])
          findings (integrity/check db)
          mismatch (filter #(= :inspect.integrity/emits-target-not-an-event (:check %))
                           findings)]
      (is (= 1 (count mismatch))
          "expected one emits-target-not-an-event finding")
      (let [v (first mismatch)]
        (is (= :error (:severity v)))
        (is (= :canvas/rule  (-> v :detail :actual-role)))
        (is (= :canvas/event (-> v :detail :expected-role)))))))

(deftest emits-pointing-at-event-passes
  (testing ":emits bound to an event entity produces no finding"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (event "X" "An event.")
                 (function "f"
                   "Emits the event properly."
                   (takes [a :String])
                   (gives :String)
                   (emits X))))
          findings (integrity/check db)]
      (is (empty? (filter #(= :inspect.integrity/emits-target-not-an-event (:check %))
                          findings))))))

;; ---------------------------------------------------------------------------
;; Check 4 — shape-target keyword resolution
;; ---------------------------------------------------------------------------

(deftest unresolved-shape-target-detected
  (testing "a function whose `gives` references a non-existent cross-module type yields a finding"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "f"
                   "Returns a non-existent type."
                   (takes [x :String])
                   (gives :missing/Nope))))
          findings (integrity/check db)
          shape-misses (filter #(= :inspect.integrity/unresolved-shape-target (:check %))
                               findings)]
      (is (pos? (count shape-misses))
          "expected at least one unresolved-shape-target finding")
      (is (some #(= :missing/Nope (-> % :detail :target)) shape-misses)
          "finding must surface the offending target keyword"))))

(deftest record-field-unresolved-type-detected
  (testing "a record field whose type references a non-existent cross-module type yields a finding"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (record "R"
                   "A record with a bad field type."
                   (field foo :missing/Nope))))
          findings (integrity/check db)
          shape-misses (filter #(= :inspect.integrity/unresolved-shape-target (:check %))
                               findings)]
      (is (pos? (count shape-misses))
          "expected at least one unresolved-shape-target finding for the record field"))))

(deftest atomic-types-not-flagged
  (testing "atomic types (:String, :Integer, :Unit) do not produce findings"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (function "f"
                   "Only atomic types."
                   (takes [x :String] [y :Integer])
                   (gives :Unit))))]
      (is (= [] (integrity/check db))
          "atomic types must be filtered out of shape-target checking"))))

;; ---------------------------------------------------------------------------
;; All-checks integration
;; ---------------------------------------------------------------------------

(deftest check-returns-all-finding-categories
  (testing "check aggregates findings across all four categories"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (event "X" "An event.")
                 (function "f1"
                   "Bad shape target."
                   (takes [x :String])
                   (gives :missing/Nope))
                 (function "f2"
                   "Triggers an event instead of a rule."
                   (takes [a :String])
                   (gives :String)
                   (triggers X))))
          findings (integrity/check db)
          checks   (into #{} (map :check) findings)]
      (is (every? #(= :error (:severity %)) findings)
          "every trust-tier finding is an error")
      (is (contains? checks :inspect.integrity/triggers-target-not-a-rule))
      (is (contains? checks :inspect.integrity/unresolved-shape-target)))))

;; ---------------------------------------------------------------------------
;; Handler-on references (covered by check 1, via vocab.event/handler)
;; ---------------------------------------------------------------------------

(deftest handler-on-unresolved-detected
  (testing "vocab.event/handler emits :references for its `on` and `emits` keywords"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (handler "h"
                   "Handles a non-existent event."
                   (on :missing/Nope))))
          findings (integrity/check db)
          unresolved (filter #(= :inspect.integrity/unresolved-reference (:check %))
                             findings)]
      (is (some #(= :missing/Nope (-> % :detail :target)) unresolved)
          "handler's `on` keyword must surface as an unresolved-reference finding"))))
