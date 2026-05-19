(ns fukan.validation.phase4
  "Phase 4 structural validation runner. Runs sub-phases 4a-4g sequentially,
   aggregating violations per sub-phase. Gate G2 halts on errors > 0.

   Per DESIGN.md §'Phase ordering and error semantics': sub-phases run in
   fixed order (4a → 4b → 4c → 4d → 4e → 4f → 4g). Each aggregates all
   violations before the next begins. Errors trip G2; warnings never do.

   Returns {:model <model> :violations [Violation ...]} on success.
   Throws ex-info {:type :gate-g2-halt :violations [...]} on error."
  (:require [fukan.validation.violation :as v]))

(defn- run-sub-phases
  "Tasks 1-10 register sub-phase runners here. For now, no rules: returns []."
  [_model]
  [])

(defn gate-g2
  "Halt iff there are any errors among the violations. Tasks-1+ wire this
   into the pipeline."
  [model violations]
  (let [errs (v/errors violations)]
    (if (seq errs)
      (throw (ex-info "Phase 4 structural validation failed (Gate G2)"
                      {:type :gate-g2-halt
                       :violations violations
                       :error-count (count errs)
                       :warning-count (count (v/warnings violations))}))
      {:model model :violations violations})))

(defn run
  "Run Phase 4 validation on the model. Returns {:model :violations} on
   pass (warnings only) or throws on Gate G2 halt (any errors)."
  [model]
  (let [violations (run-sub-phases model)]
    (gate-g2 model violations)))
