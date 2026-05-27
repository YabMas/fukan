(ns fukan.canvas.inspect.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.construction :refer [function value exports]]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.inspect.coverage :as coverage]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.event :refer [event handler]]))

;; ---------------------------------------------------------------------------
;; Clean baseline
;; ---------------------------------------------------------------------------

(deftest clean-canvas-produces-no-findings
  (testing "a minimal canvas with full coverage produces []"
    ;; Construct a two-module loop:
    ;;   - module a exports A and uses :b/B internally
    ;;   - module b exports B and uses :a/A internally
    ;; Each exported entity is cross-module-referenced; each internal
    ;; entity is internally referenced (the using-function references the
    ;; type). The using-functions themselves are referenced cross-module
    ;; via shape-target keywords (`:a/use_b` etc. would be required to
    ;; close the loop). Below we tag every entity :exported and have each
    ;; module reference every entity in the other, closing the graph.
    (let [db (h/with-canvas
               (h/within-module "a"
                 (value "A" "Type A.")
                 (function "use_b"
                   "Uses :b/B and :b/use_a."
                   (takes [x :b/B] [y :b/use_a])
                   (gives :Unit))
                 (exports A use_b))
               (h/within-module "b"
                 (value "B" "Type B.")
                 (function "use_a"
                   "Uses :a/A and :a/use_b."
                   (takes [x :a/A] [y :a/use_b])
                   (gives :Unit))
                 (exports B use_a)))]
      (is (= [] (coverage/check db))
          "tight two-module cyclic canvas should be coverage-clean"))))

;; ---------------------------------------------------------------------------
;; Check 1 — orphan entities
;; ---------------------------------------------------------------------------

(deftest orphan-entity-detected
  (testing "an unreferenced, unexported entity surfaces as :warning orphan"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "Lonely" "Nothing references this type.")))
          findings  (coverage/check db)
          orphans   (filter #(= :inspect.coverage/orphan-entity (:check %)) findings)]
      (is (= 1 (count orphans)))
      (let [f (first orphans)]
        (is (= :warning (:severity f)))
        (is (= 1 (count (:offenders f))))
        (is (string? (-> f :offenders first :stable-id)))
        (is (= :Type (-> f :detail :entity-type)))))))

(deftest referenced-entity-is-not-orphan
  (testing "an entity with an incoming :references datom is not flagged"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "Thing" "A thing.")
                 (function "use_thing"
                   "Uses Thing."
                   (takes [t :demo/Thing])
                   (gives :Unit))))
          orphans (filter #(= :inspect.coverage/orphan-entity (:check %))
                          (coverage/check db))
          orphan-names (set (map #(-> % :offenders first :stable-id) orphans))]
      ;; "Thing" is referenced by "use_thing" via :demo/Thing → not orphan.
      ;; "use_thing" however is itself orphan (no inbound refs) — that's fine
      ;; for this check; we just assert Thing isn't flagged.
      (is (not (some #(re-find #"/type/Thing$" %) orphan-names))
          "Thing must not be flagged as orphan"))))

(deftest exported-entity-not-flagged-as-orphan
  (testing ":exported tag suppresses the orphan finding"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "ApiSurface" "Declared API.")
                 (exports ApiSurface)))
          orphans (filter #(= :inspect.coverage/orphan-entity (:check %))
                          (coverage/check db))]
      (is (empty? (filter #(re-find #"ApiSurface" (-> % :offenders first :stable-id))
                          orphans))
          ":exported tag must suppress orphan finding"))))

(deftest mechanism-driven-roles-exempt-from-orphan
  (testing "affordances wired by mechanism rather than ref graph are not flagged"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (rule "UnshippedRule"
                   "A rule with no triggering function."
                   (when UnshippedRule (model :model/Model)))
                 (invariant "UnshippedInvariant"
                   "Timeless commitment, no incoming ref."
                   (holds-that "always-true"))))
          orphans (filter #(= :inspect.coverage/orphan-entity (:check %))
                          (coverage/check db))
          orphan-names (set (map #(-> % :offenders first :stable-id) orphans))]
      (is (not (some #(re-find #"UnshippedRule" %) orphan-names))
          ":canvas/rule role must be exempt from orphan check")
      (is (not (some #(re-find #"UnshippedInvariant" %) orphan-names))
          ":canvas/invariant role must be exempt from orphan check"))))

;; ---------------------------------------------------------------------------
;; Check 2 — unreached entities
;; ---------------------------------------------------------------------------

(deftest unreached-entity-detected
  (testing "an entity floating outside any module surfaces as :error"
    ;; Build a clean canvas, then transact a synthetic entity not owned by
    ;; any module — this simulates a substrate bug.
    (let [base (h/with-canvas
                 (h/within-module "demo"
                   (value "Owned" "A normally-owned type.")))
          orphan-uuid (random-uuid)
          db   (d/db-with base [{:entity/id orphan-uuid
                                 :entity/type :Type
                                 :entity/name "Floating"
                                 :entity/tag []}])
          findings  (coverage/check db)
          unreached (filter #(= :inspect.coverage/unreached-entity (:check %)) findings)]
      (is (= 1 (count unreached)))
      (let [f (first unreached)]
        (is (= :error (:severity f)))
        (is (= :Type (-> f :detail :entity-type)))))))

(deftest owned-entities-are-reached
  (testing "all entities under (within-module …) are reachable"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "T" "A type.")
                 (function "f"
                   "."
                   (takes [x :String])
                   (gives :String))))]
      (is (empty? (filter #(= :inspect.coverage/unreached-entity (:check %))
                          (coverage/check db)))))))

;; ---------------------------------------------------------------------------
;; Check 3 — exported but unreferenced
;; ---------------------------------------------------------------------------

(deftest exported-but-unreferenced-detected
  (testing "an :exported entity with no external referrer surfaces as :warning"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "Api" "Exported but unused.")
                 (exports Api)))
          findings (coverage/check db)
          unrefs   (filter #(= :inspect.coverage/exported-but-unreferenced (:check %))
                           findings)]
      (is (= 1 (count unrefs)))
      (is (= :warning (-> unrefs first :severity))))))

(deftest exported-and-cross-module-referenced-passes
  (testing "an :exported entity used by another module is NOT flagged"
    (let [db (h/with-canvas
               (h/within-module "model"
                 (value "Api" "Exported API.")
                 (exports Api))
               (h/within-module "consumer"
                 (function "use_api"
                   "Consumes Api."
                   (takes [a :model/Api])
                   (gives :Unit))))
          findings (coverage/check db)
          unrefs   (filter #(= :inspect.coverage/exported-but-unreferenced (:check %))
                           findings)]
      (is (empty? (filter #(re-find #"Api" (-> % :offenders first :stable-id))
                          unrefs))
          "cross-module reference must clear the exported-but-unreferenced flag"))))

(deftest exported-and-same-module-only-referenced-still-flagged
  (testing "same-module references don't count toward exported-but-unreferenced"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "Api" "Exported but used only same-module.")
                 (function "use_api"
                   "Consumes Api in the same module."
                   (takes [a :demo/Api])
                   (gives :Unit))
                 (exports Api)))
          findings (coverage/check db)
          unrefs   (filter #(= :inspect.coverage/exported-but-unreferenced (:check %))
                           findings)]
      (is (some #(re-find #"Api" (-> % :offenders first :stable-id)) unrefs)
          "exports are for cross-module consumption — same-module refs shouldn't clear the flag"))))

;; ---------------------------------------------------------------------------
;; Check 4 — modules without exports
;; ---------------------------------------------------------------------------

(deftest module-without-exports-detected
  (testing "a module whose children carry no :exported tag surfaces as :info"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "Internal" "Nothing exported.")))
          findings (coverage/check db)
          modless  (filter #(= :inspect.coverage/module-without-exports (:check %))
                           findings)]
      (is (= 1 (count modless)))
      (is (= :info (-> modless first :severity)))
      (is (= "demo" (-> modless first :detail :module-name))))))

(deftest module-with-exports-passes
  (testing "a module with at least one :exported child is not flagged"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (value "Surface" "Exported.")
                 (exports Surface)))
          findings (coverage/check db)]
      (is (empty? (filter #(= :inspect.coverage/module-without-exports (:check %))
                          findings))))))

;; ---------------------------------------------------------------------------
;; Check 5 — rules without trigger
;; ---------------------------------------------------------------------------

(deftest rule-without-trigger-detected
  (testing "a rule with no triggering function surfaces as :warning"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (rule "Lonely"
                   "Nothing fires this rule."
                   (when (Lonely (x :String))))))
          findings (coverage/check db)
          orphans  (filter #(= :inspect.coverage/rule-without-trigger (:check %))
                           findings)]
      (is (= 1 (count orphans)))
      (is (= :warning (-> orphans first :severity)))
      (is (= :canvas/rule (-> orphans first :detail :role))))))

(deftest rule-with-trigger-passes
  (testing "a rule triggered by a function is not flagged"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (rule "X"
                   "A real rule."
                   (when (X (a :String))))
                 (function "fire_x"
                   "Triggers X."
                   (takes [a :String])
                   (gives :Unit)
                   (triggers X))))
          findings (coverage/check db)]
      (is (empty? (filter #(= :inspect.coverage/rule-without-trigger (:check %))
                          findings))))))

;; ---------------------------------------------------------------------------
;; Check 6 — events without handler
;; ---------------------------------------------------------------------------

(deftest event-without-handler-detected
  (testing "an event with no handler surfaces as :warning"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (event "Untouched" "Nothing handles me.")))
          findings (coverage/check db)
          orphans  (filter #(= :inspect.coverage/event-without-handler (:check %))
                           findings)]
      (is (= 1 (count orphans)))
      (is (= :warning (-> orphans first :severity)))
      (is (= :canvas/event (-> orphans first :detail :role))))))

(deftest event-with-handler-passes
  (testing "an event with a handler on it is not flagged"
    (let [db (h/with-canvas
               (h/within-module "demo"
                 (event "Touched" "An event.")
                 (handler "react"
                   "Handles Touched."
                   (on :demo/Touched))))
          findings (coverage/check db)
          orphans  (filter #(= :inspect.coverage/event-without-handler (:check %))
                           findings)]
      (is (empty? (filter #(re-find #"Touched" (-> % :offenders first :stable-id))
                          orphans))
          "event with a handler must not be flagged"))))

;; ---------------------------------------------------------------------------
;; Cross-cutting — severity ladder + finding shape
;; ---------------------------------------------------------------------------

(deftest all-findings-carry-severity-in-ladder
  (testing "every finding has :severity in #{:error :warning :info}"
    (let [base (h/with-canvas
                 (h/within-module "demo"
                   (rule "R"
                     "Untriggered rule."
                     (when (R (a :String))))
                   (event "E" "Unhandled event.")
                   (value "Internal" "Orphan type.")))
          orphan-uuid (random-uuid)
          db   (d/db-with base [{:entity/id orphan-uuid
                                 :entity/type :Type
                                 :entity/name "Floating"
                                 :entity/tag []}])
          findings (coverage/check db)
          severities (into #{} (map :severity) findings)]
      (is (every? #{:error :warning :info} severities)
          "every finding's :severity must come from the ladder")
      (is (every? (fn [f] (and (keyword? (:check f))
                               (string? (:message f))
                               (vector? (:offenders f))
                               (map? (:detail f))))
                  findings)
          "every finding must have :check :message :offenders :detail"))))
