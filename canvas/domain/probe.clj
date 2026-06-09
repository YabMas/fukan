(ns canvas.domain.probe
  "Self-spec: fukan's PROBE acts — reading the model through a lens to yield a finding.
   Forward-looking (the tier is parked under `.paused/`). Each probe reads THROUGH a
   lens (the `lens` view's Lens instances — interlocking) and yields a finding.
   INSPECT ⊂ PROBE: integrity/coverage/drift are probes whose findings GATE action
   (Signals); survey/patterns/… yield non-gating Views. The overview's `Faculty
   \"Probe\"` is `realized-by` this module."
  (:require [canvas.vocabulary.probe :refer [Probe Finding]]
            [lib.grouping :refer [Module]]
            [canvas.domain.lens :as lens]))

;; non-gating findings — perspectives to reason with (Views). Each STATES its `holds`
;; invariant in prose; the executable check lives in canvas.realization.acts.
(def Survey   (Finding (gating false) (doc "A structural overview of the whole model.")))
(def Patterns (Finding (gating false)
                (doc "Recurring structural patterns across the model.")
                (holds "a model with no recurring structures yields no reported patterns")))
(def Consistency (Finding (gating false) (doc "Where contracts and structure align — or drift.")))
(def TarPit      (Finding (gating false) (doc "Complexity hotspots — tangles worth attention.")))
;; gating findings — trust verdicts (the inspect case → Signals)
(def IntegrityReport
  (Finding (gating true)
    (doc "Whether the model's structure holds together.")
    (holds "a model with no law violations yields no reported violations")))
(def CoverageReport (Finding (gating true) (doc "How much of the target's code is spec-covered.")))
(def DriftReport    (Finding (gating true) (doc "Where specifications and code have diverged.")))

;; probes — each reads through a lens → a finding. (Which kernel capability a probe
;; invokes when run — e.g. integrity composes `check` — is a ProbeComposition in the
;; realization view, not stated here.)
(def survey      (Probe (through lens/survey)      (yields Survey)))
(def patterns    (Probe (through lens/patterns)    (yields Patterns)))
(def consistency (Probe (through lens/consistency) (yields Consistency)))
(def tar-pit     (Probe (through lens/tar-pit)     (yields TarPit)))
;; inspects — probes whose finding gates
(def integrity   (Probe (through lens/integrity)   (yields IntegrityReport)))
(def coverage    (Probe (through lens/coverage)    (yields CoverageReport)))
(def drift       (Probe (through lens/drift)       (yields DriftReport)))

(def probe
  (Module
    (child Survey Patterns Consistency TarPit IntegrityReport CoverageReport DriftReport
           survey patterns consistency tar-pit integrity coverage drift)))
