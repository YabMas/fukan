(ns canvas.model.pipeline
  "Canvas port of model/pipeline.allium + pipeline.boundary.

   Coverage:
     - 4 guarantees from pipeline.allium → vocab.behavioral/invariant each:
         PhaseOrdering, GateG2Halts, NonGatingPhases,
         DefaultsRegistrationIsIdempotent
     - rule BuildModel → vocab.behavioral/rule
     - fn build_model  → construction/function

   Notes:
     - The pipeline is a thin orchestrator: Phase 1-3 (Allium parser ->
       Boundary parser, merging by identity), Phase 4 (structural validation,
       gate G2), defaults registration, Phase 5 (constraint evaluation),
       Phase 6 (Clojure target analyzer).
     - Defaults registered between Phase 4 and Phase 5:
         fukan/signal_gap (warning)
         fukan/external_must_have_wrapper (warning)"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.pipeline"

      ;; Guarantees from pipeline.allium

      (invariant "PhaseOrdering"
        "The build runs a fixed sequence: Phase 1-3 (Allium parser -> Boundary
         parser, merging by identity); Phase 4 (structural validation,
         sub-phases 4a-4g); Register fukan-shipped well-known constraint
         defaults; Phase 5 (constraint evaluation, kernel-universal +
         project-shipped); Phase 6 (Clojure target analyzer — projection edges
         to Code.* artifacts with per-edge :validity). Defaults are registered
         between Phase 4 and Phase 5 so they participate in the same evaluation
         pass as project-shipped constraints."
        (holds-that "build-phases-run-in-fixed-order-1-2-3-4-defaults-5-6"))

      (invariant "GateG2Halts"
        "Phase 4 is the last gate. If Phase 4 reports errors, the build raises
         and downstream phases (defaults registration, Phase 5, Phase 6) do not
         run. A Model with broken composition or unresolved references would
         produce noisy or misattributed constraint output, so the pipeline halts
         at the last point where attribution is clean. Per doc/DESIGN.md 'Why
         two gates' (G2)."
        (holds-that "phase-4-error-halts-pipeline-raises"))

      (invariant "NonGatingPhases"
        "Phases 5 and 6 are non-gating: their outputs are accumulated onto the
         Model rather than raising. Phase 5 violations append to
         Model.violations alongside Phase 4's. Phase 6 projection edges append
         to Model.edges with per-edge :validity (:valid | :stale | :absent |
         :unknown); :absent edges are the drift surface, not errors."
        (holds-that "phases-5-6-accumulate-not-raise"))

      (invariant "DefaultsRegistrationIsIdempotent"
        "The pipeline appends fukan-shipped well-known constraint registrations
         onto the Model's predicate registry before Phase 5 evaluation. Repeat
         invocations do not double-register: an existing (namespace, name) tuple
         in the predicate registry is skipped. A rebuilt Model containing prior
         defaults retains exactly one registration per default. Currently
         shipped defaults: fukan/signal_gap (warning) and
         fukan/external_must_have_wrapper (warning)."
        (holds-that "defaults-registration-is-idempotent-on-namespace-name"))

      (rule "BuildModel"
        "Top-level build orchestration. Run Phase 1-3 (Allium then Boundary
         loaders, merged by identity), then Phase 4 (structural validation;
         gate G2). On Phase 4 success, append fukan-shipped well-known
         constraint defaults to the predicate registry (idempotent on
         (namespace, name)), then run Phase 5 (constraint evaluation) and
         Phase 6 (Clojure target analyzer). Governed by PhaseOrdering,
         GateG2Halts, NonGatingPhases, and DefaultsRegistrationIsIdempotent."
        (when BuildModel (source_root :String)))

      ;; Public function from pipeline.boundary

      (function "build_model"
        "Top-level build. Orchestrates Allium -> Boundary -> Phase 4 ->
         defaults registration -> Phase 5 -> Phase 6. Returns the unified
         Model. Raises on Phase 4 (Gate G2) errors. Behavioural commitment
         lives in the BuildModel Rule in pipeline.allium."
        (takes [source_root :String])
        (gives :model/Model)))))
