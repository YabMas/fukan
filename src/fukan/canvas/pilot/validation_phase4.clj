(ns fukan.canvas.pilot.validation-phase4
  "Canvas port of validation/phase4.allium + phase4.boundary.

   Slice chosen: phase4 overview (runner orchestration).
   Why: best structural variety — one value type with cross-module typed fields,
   one rule, six invariants, and 9 boundary functions including one with a
   triggers:/returns: clause and seven structurally-identical sub-phase
   entry points. Individual rules files (4a-4g) would repeat the same gaps.

   Coverage:
     - value Phase4Result     → bare h/declare-affordance (cross-module field types
                                 not expressible in record lift). Gap 12.
     - fn run                 → (function ...) — triggers:/returns: dropped. Gap 13.
     - fn gate_g2             → (function ...)
     - fn rules_4a..rules_4g  → 7x (function ...) — verbose repetition. Gap 14.

   Gaps (see doc/plans/2026-05-25-pilot-port-findings.md):
     - Phase4Result.model: model.Model — cross-module typed field. Gap 12.
     - Phase4Result.violations: List<agent.Violation> — cross-module + List. Gaps 5, 8.
     - fn run has triggers: RunPhase4 and returns: post.result — no function
       lift support for rule anchors. Gap 13.
     - rule RunPhase4 — no rule lift. Gap 15.
     - 6 invariants — no invariant lift. Gap 6/11.
     - 7 structurally-similar sub-phase functions — no iteration lift. Gap 14."
  (:require [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :refer [function]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.phase4"
      ;; Phase4Result — both fields have cross-module types and one is
      ;; List<Violation>. Record lift cannot express this. Escaped to
      ;; bare substrate marker. Gap 12.
      (h/declare-affordance "Phase4Result"
        :role :fukan.canvas.monolith/value-type)

      ;; fn run(model: model.Model) -> Phase4Result
      ;; The boundary spec has triggers: RunPhase4 and returns: post.result —
      ;; the function lift has no trigger-anchor or returns-label syntax. Gap 13.
      (function "run"
        "Run the seven sub-phases in fixed order. Returns Phase4Result on
         success; raises Gate-G2 halt when any error-severity Violation present."
        (takes [model :Model])
        (gives :Phase4Result))

      ;; fn gate_g2(model, violations) -> Phase4Result
      (function "gate_g2"
        "Apply Gate G2 to an already-aggregated Violation sequence."
        (takes [model      :Model
                violations :ViolationList])
        (gives :Phase4Result))

      ;; Sub-phase entry points 4a–4g: structurally identical pattern.
      ;; Repeated 7 times — verbose but expressible. Gap 14.
      (function "rules_4a"
        "Run sub-phase 4a against the model."
        (takes [model :Model])
        (gives :ViolationList))

      (function "rules_4b"
        "Run sub-phase 4b against the model."
        (takes [model :Model])
        (gives :ViolationList))

      (function "rules_4c"
        "Run sub-phase 4c against the model."
        (takes [model :Model])
        (gives :ViolationList))

      (function "rules_4d"
        "Run sub-phase 4d against the model."
        (takes [model :Model])
        (gives :ViolationList))

      (function "rules_4e"
        "Run sub-phase 4e against the model."
        (takes [model :Model])
        (gives :ViolationList))

      (function "rules_4f"
        "Run sub-phase 4f against the model."
        (takes [model :Model])
        (gives :ViolationList))

      (function "rules_4g"
        "Run sub-phase 4g against the model."
        (takes [model :Model])
        (gives :ViolationList)))))
