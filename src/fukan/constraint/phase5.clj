(ns fukan.constraint.phase5
  "Phase 5 constraint evaluation runner. Reads :predicates from the
   model and evaluates each against the kernel-universal EDB + the
   registration's body. Each head tuple becomes one Violation.

   Per DESIGN.md: Phase 5 is non-gating. Violations attach to the Model
   under :violations alongside Phase 4 violations."
  (:require [fukan.validation.violation :as v]
            [fukan.constraint.evaluator :as e]
            [fukan.constraint.derivations :as d]
            [fukan.constraint.derivations-extra :as dx]))

(defn- evaluate-registration
  "Evaluate one PredicateRegistration against the EDB. Returns a vector
   of Violations (one per head-tuple)."
  [edb registration]
  (let [predicate (:predicate registration)
        head      (:head predicate)
        body      (:body predicate)
        rule      {:head head :body body}
        derived   (e/evaluate-rules (cons rule (dx/depends-on-rules)) edb)
        head-pred (:predicate head)
        head-args (:args head)
        results   (get derived head-pred #{})]
    (vec (for [tup results]
           (v/make-violation
             {:severity (:severity registration)
              :phase :phase5
              :sub-phase :5
              :kind (keyword (:namespace registration) (:name registration))
              :location (zipmap head-args tup)
              :message (or (:message-template registration)
                           (str "constraint " (:namespace registration) "/" (:name registration)
                                " fired on " (vec tup)))})))))

(defn run
  "Run Phase 5: evaluate every PredicateRegistration in :predicates
   against the kernel-universal EDB, accumulate violations, append
   them to :violations (preserving any Phase 4 violations already
   present)."
  [model]
  (let [edb (d/model->edb model)
        new-vs (vec (mapcat #(evaluate-registration edb %)
                            (:predicates model)))]
    (update model :violations (fnil into []) new-vs)))
