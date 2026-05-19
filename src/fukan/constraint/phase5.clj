(ns fukan.constraint.phase5
  "Phase 5 constraint evaluation runner. Reads :predicates from the
   model, evaluates each against the kernel-universal derivations + any
   project-shipped constraints, and produces Violations.

   Per DESIGN.md: Phase 5 is non-gating. Violations attach to the Model
   under :violations alongside Phase 4 violations.")

(defn run
  "Run Phase 5 against the model. Returns the model with Phase 5
   violations appended to :violations.

   Stub (Task 5 implements): no constraints evaluated, no violations
   added — model passes through unchanged."
  [model]
  model)
