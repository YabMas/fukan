(ns canvas.validation.phase4
  "Canvas port of validation/phase4.allium + phase4.boundary.

   Phase 2 migration: escape hatches replaced by vocab lifts.

   Coverage:
     - value Phase4Result    → construction/record with cross-module field refs
                               :model/Model and (list-of :agent/Violation)
     - rule RunPhase4        → vocab.behavioral/rule
     - fn run                → construction/function (triggers: RunPhase4)
     - fn gate_g2            → construction/function
     - fn rules_4a..rules_4g → vocab.validation/checker each (7 checkers)
     - 6 invariants          → vocab.behavioral/invariant each

   Notes:
     - checker shape is baked in as (Model) -> [Violation] via vocab.validation;
       the :references Relations to :model/Model and :agent/Violation are emitted
       automatically by the checker lift."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function record]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.phase4"

      ;; Value type from phase4.allium.
      ;; Phase4Result.model is :model/Model and .violations is List<agent.Violation>.
      ;; Both are now expressible with namespaced keyword refs and shape grammar.
      (record "Phase4Result"
        "Non-throwing return envelope from the Phase 4 runner. Carries the
         input Model unchanged plus the aggregated Violation sequence."
        (field model      :model/Model)
        (field violations (list-of :agent/Violation)))

      (rule "RunPhase4"
        "Phase 4 entry point — fired by the build pipeline after Phases 1-3.
         Walks sub-phases 4a through 4g in fixed order, aggregates Violations,
         and applies Gate G2. Returns Phase4Result on success (warnings only);
         raises on any :error-severity Violation."
        (when RunPhase4 (model :model/Model)))

      ;; Invariants from phase4.allium
      (invariant "SubPhaseOrdering"
        "Sub-phases execute in fixed order 4a → 4b → ... → 4g. The order is
         observable in the aggregated Violation sequence."
        (holds-that "sub-phase-fixed-order"))

      (invariant "AggregateBeforeGate"
        "Every sub-phase runs and contributes its Violations before Gate G2
         is consulted. A halt-worthy Violation in 4a does not short-circuit
         execution of 4b through 4g."
        (holds-that "aggregate-before-gate-g2"))

      (invariant "GateG2HaltsOnError"
        "If the aggregated Violation sequence contains at least one :error-severity
         Violation, the runner raises rather than returning a Phase4Result."
        (holds-that "gate-g2-halts-on-error-severity"))

      (invariant "GateG2IgnoresWarnings"
        "Warnings never halt the build. A Phase 4 run that produces only warnings
         returns a Phase4Result; the pipeline continues."
        (holds-that "warnings-do-not-halt"))

      (invariant "MissingSubPhaseIsSilent"
        "A sub-phase whose rules_4X module is absent at runtime contributes
         the empty Violation sequence — its absence is not itself a defect."
        (holds-that "missing-subphase-is-empty-not-error"))

      (invariant "PurelyDerivedFromModel"
        "The aggregated Violation sequence is a pure function of the input Model
         and registered sub-phase rule modules. No global mutable state or I/O."
        (holds-that "purely-derived-from-model"))

      ;; Public functions from phase4.boundary.
      ;; run has triggers: RunPhase4 — no anchor syntax; noted in docstring.
      (function "run"
        "Run the seven sub-phases in fixed order. Returns Phase4Result on
         success (warnings only). Raises Gate-G2 halt on any :error-severity
         Violation. (Triggers: RunPhase4; returns: post.result)"
        (takes [model :model/Model])
        (gives :Phase4Result))

      (function "gate_g2"
        "Apply Gate G2 to an already-aggregated Violation sequence. Returns
         Phase4Result when there are no :error-severity Violations; raises
         otherwise."
        (takes [model      :model/Model
                violations (list-of :agent/Violation)])
        (gives :Phase4Result))

      ;; Sub-phase entry points 4a–4g.
      ;; vocab.validation/checker bakes in the signature (Model) -> [Violation]
      ;; and emits :references Relations automatically.
      (checker "rules_4a"
        "Run sub-phase 4a against the model.")

      (checker "rules_4b"
        "Run sub-phase 4b against the model.")

      (checker "rules_4c"
        "Run sub-phase 4c against the model.")

      (checker "rules_4d"
        "Run sub-phase 4d against the model.")

      (checker "rules_4e"
        "Run sub-phase 4e against the model.")

      (checker "rules_4f"
        "Run sub-phase 4f against the model.")

      (checker "rules_4g"
        "Run sub-phase 4g against the model."))))
