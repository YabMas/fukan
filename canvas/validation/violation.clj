(ns canvas.validation.violation
  "Canvas port of validation/violation.allium + violation.boundary.

   Scope: Violation construction — the single fabrication point for
   Phase 4 / Phase 5 Violation values, plus severity-based filtering
   helpers used by Gate G2.

   Coverage:
     - fn make_violation → construction/function with Map<String,Value> -> Violation
     - fn error          → construction/function predicate
     - fn warning        → construction/function predicate
     - fn errors         → construction/function filter
     - fn warnings       → construction/function filter
     - 4 rules           → vocab.behavioral/rule each
     - 4 invariants      → vocab.behavioral/invariant each

   Notes:
     - The Violation value type itself lives in agent/api.allium; this module
       references it via :agent/Violation."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.violation"

      (rule "ViolationShape"
        "Define the required shape of a Violation: every Violation carries
         :severity (:error or :warning), :phase (:phase4 or :phase5),
         :sub_phase, :kind (open keyword), :location (attribution map),
         and :message (human-readable string)."
        (when ViolationShape (violation :agent/Violation)))

      (rule "MakeViolationIsTotal"
        "Define that make_violation never throws for any combination of the
         six fields. Missing :location is filled with the empty map. No input
         causes the factory to raise."
        (when MakeViolationIsTotal (fields (map-of :String :Value))))

      (rule "SeverityPartition"
        "Define that error? and warning? partition every well-formed
         Violation: exactly one is true per Violation. The errors and
         warnings selectors are their pointwise filters — the disjoint union
         of their outputs equals the input sequence."
        (when SeverityPartition (violations (list-of :agent/Violation))))

      (rule "LocationDefaultsToEmpty"
        "Define that a Violation constructed without an explicit :location
         carries the empty map. Callers may safely assoc into :location
         without guarding for nil."
        (when LocationDefaultsToEmpty (fields (map-of :String :Value))))

      (invariant "ViolationShape"
        "Every Violation carries six attributes: :severity, :phase,
         :sub_phase, :kind, :location, :message. :severity is one of
         :error / :warning. :phase is one of :phase4 / :phase5.
         :sub_phase is one of :4a :4b :4c :4d :4e :4f :4g :5. :kind
         is an open keyword identifying the violation type. :location
         is a free-form attribution map. :message is a human-readable
         string."
        (holds-that "violation-carries-six-required-attributes"))

      (invariant "MakeViolationIsTotal"
        "The make_violation factory accepts any combination of the six
         fields and returns a fully-populated Violation map. No input
         causes the factory to throw; missing :location is filled with
         the empty map."
        (holds-that "make-violation-is-total-and-never-throws"))

      (invariant "SeverityPartition"
        "The error?/warning? predicates partition every well-formed
         Violation: exactly one is true per Violation. The errors and
         warnings selectors are their pointwise filters — the disjoint
         union of their outputs equals the input."
        (holds-that "error-and-warning-predicates-partition-violations"))

      (invariant "LocationDefaultsToEmpty"
        "A Violation constructed without an explicit :location carries
         the empty map. Callers may safely assoc into :location without
         guarding for nil."
        (holds-that "absent-location-defaults-to-empty-map"))

      ;; Public functions from violation.boundary.
      ;; Violation type lives in agent/api.allium — referenced as :agent/Violation.

      (function "make_violation"
        "Construct a Violation from a fields map. Recognised keys:
         :severity, :phase, :sub_phase, :kind, :location, :message.
         :location defaults to the empty map when absent."
        (takes [fields (map-of :String :Value)])
        (gives :agent/Violation))

      (function "error"
        "True iff v's :severity is :error."
        (takes [v :agent/Violation])
        (gives :Bool))

      (function "warning"
        "True iff v's :severity is :warning."
        (takes [v :agent/Violation])
        (gives :Bool))

      (function "errors"
        "The subsequence of violations whose :severity is :error."
        (takes [violations (list-of :agent/Violation)])
        (gives (list-of :agent/Violation)))

      (function "warnings"
        "The subsequence of violations whose :severity is :warning."
        (takes [violations (list-of :agent/Violation)])
        (gives (list-of :agent/Violation)))

      (exports make_violation error warning errors warnings))))
