(ns canvas.model.probe
  "Self-spec: fukan's PROBE acts — reading the model through a lens to yield a finding.
   Forward-looking (the tier is parked under `.paused/`). Each probe reads THROUGH a
   lens (referenced across the `lens` view — interlocking) and yields a finding.
   INSPECT ⊂ PROBE: integrity/coverage/drift are probes whose findings GATE action
   (Signals); survey/patterns/… yield non-gating Views. The overview's `Faculty
   \"Probe\"` is `realized-by` this module."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.probe :refer [Probe Finding]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "probe"
      ;; non-gating findings — perspectives to reason with (Views)
      (Finding "Survey"      (gating false) (doc "A structural overview of the whole model."))
      (Finding "Patterns"    (gating false) (doc "Recurring structural patterns across the model."))
      (Finding "Consistency" (gating false) (doc "Where contracts and structure align — or drift."))
      (Finding "TarPit"      (gating false) (doc "Complexity hotspots — tangles worth attention."))
      ;; gating findings — trust verdicts (the inspect case → Signals)
      (Finding "IntegrityReport" (gating true) (doc "Whether the model's structure holds together."))
      (Finding "CoverageReport"  (gating true) (doc "How much of the target's code is spec-covered."))
      (Finding "DriftReport"     (gating true) (doc "Where specifications and code have diverged."))

      ;; probes — each reads through a lens (cross-module) → a finding
      (Probe "survey"      (through (across "lens" "survey"))      (yields Survey))
      (Probe "patterns"    (through (across "lens" "patterns"))    (yields Patterns))
      (Probe "consistency" (through (across "lens" "consistency")) (yields Consistency))
      (Probe "tar-pit"     (through (across "lens" "tar-pit"))     (yields TarPit))
      ;; inspects — probes whose finding gates
      ;; integrity COMPOSES the kernel's modelled `check` capability — the same :calls
      ;; relation a Stage uses (1-on-1 with the projected code: probe-integrity calls check)
      (Probe "integrity"   (through (across "lens" "integrity"))   (yields IntegrityReport)
        (calls (across "core.structure" "check")))
      (Probe "coverage"    (through (across "lens" "coverage"))    (yields CoverageReport))
      (Probe "drift"       (through (across "lens" "drift"))       (yields DriftReport)))))
