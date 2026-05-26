(ns canvas.constraint.phase5
  "Canvas port of constraint/phase5.allium + phase5.boundary.

   Coverage:
     - rule RunPhase5 → vocab.behavioral/rule
     - 6 invariants  → vocab.behavioral/invariant each
     - fn run        → construction/function with cross-module type ref :model/Model
                       (triggers: RunPhase5 and returns: post.model noted in docstring)

   Notes:
     - No exports: in phase5.boundary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "constraint.phase5"

      (rule "RunPhase5"
        "Phase 5 of the build pipeline — fired after defaults registration.
         Reads every PredicateRegistration from the Model's predicate registry,
         evaluates each against the kernel-universal EDB (extended with
         depends_on_rules), and appends one Violation per derived head tuple
         to the Model's :violations. Non-gating: violations are outputs, not
         errors."
        (when RunPhase5 (model :model/Model)))

      ;; Invariants from phase5.allium.
      (invariant "NonGating"
        "Phase 5 never raises on constraint violations. Violations are outputs, not
         errors — they accumulate on the Model alongside Phase 4's violations. The
         runner only raises when a registration's predicate fails stratification,
         which is treated as a registration bug rather than a Model condition."
        (holds-that "violations-are-outputs-not-errors"))

      (invariant "PurelyAdditive"
        "The output Model equals the input Model with new entries appended to
         :violations. Primitives, edges, artifacts, tag applications, predicate
         registrations, and existing violations are preserved unchanged."
        (holds-that "purely-additive-to-violations"))

      (invariant "EvaluatesAllRegistrations"
        "Every PredicateRegistration in the input Model's registry is evaluated. No
         registration is filtered, deduplicated, or skipped at the runner layer —
         idempotent registration is the pipeline's responsibility per
         model/pipeline.DefaultsRegistrationIsIdempotent."
        (holds-that "all-registrations-evaluated"))

      (invariant "OneViolationPerHeadTuple"
        "For each registration, the runner emits exactly one Violation per derived head
         tuple. A registration whose head never derives contributes no Violations. A
         registration whose head derives N tuples contributes N Violations."
        (holds-that "one-violation-per-head-tuple"))

      (invariant "ViolationLocationBindsHead"
        "Each emitted Violation's :location is the map from the head atom's argument
         variables to the corresponding tuple values. Head args appearing as constants
         (rare) participate in the map by their literal value as both key and value."
        (holds-that "location-binds-head-args"))

      (invariant "DeterministicAcrossRebuilds"
        "Given the same Model and the same predicate registrations, repeated runs produce
         value-equal violation sequences. The evaluator's determinism plus the runner's
         pure-fold over :predicates carry through to a stable output."
        (holds-that "deterministic-given-same-model-and-registrations"))

      ;; Public function from phase5.boundary.
      ;; triggers: RunPhase5; returns: post.model — no anchor syntax; noted in docstring.
      (function "run"
        "Evaluate every PredicateRegistration in the input Model against the
         kernel-universal EDB extended with the depends-on derivation. Append one
         Violation per head tuple to the Model's :violations. Returns the updated Model.
         (Triggers: RunPhase5; returns: post.model)"
        (takes [model :model/Model])
        (gives :model/Model)))))
