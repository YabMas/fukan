(ns canvas.domain.probe
  "Self-spec: fukan's PROBE acts — reading the model through a lens to yield a finding.
   Forward-looking (the tier is parked under `.paused/`). Each probe reads THROUGH a
   lens (the `lens` view's Lens instances — interlocking) and yields a finding.
   INSPECT ⊂ PROBE: integrity/coverage/drift are probes whose findings GATE action
   (Signals); survey/patterns/… yield non-gating Views. Realized by the probe
   surface (`canvas.realization.probe-surface`)."
  (:require [canvas.vocabulary.act :refer [Probe Finding]]
            [lib.grouping :refer [Grouping]]
            [canvas.domain.lens :as lens]))

;; non-gating findings — perspectives to reason with (Views). Each STATES its `holds`
;; invariant in prose; the executable check lives in canvas.realization.acts.
(Finding Survey "A structural overview of the whole model."
  {:gating false})
(Finding Patterns "Recurring structural patterns across the model."
  {:gating false
   :holds  "a model with no recurring structures yields no reported patterns"})
(Finding Consistency "Where contracts and structure align — or drift."
  {:gating false})
(Finding TarPit "Complexity hotspots — tangles worth attention."
  {:gating false})
;; gating findings — trust verdicts (the inspect case → Signals)
(Finding IntegrityReport "Whether the model's structure holds together."
  {:gating true
   :holds  "a model with no law violations yields no reported violations"})
(Finding CoverageReport "How much of the target's code is spec-covered."
  {:gating true})
(Finding DriftReport "Where specifications and code have diverged."
  {:gating true})

;; probes — each reads through a lens → a finding. (Which kernel capability a probe
;; invokes when run — e.g. integrity composes `check` — is a ProbeComposition in the
;; realization view, not stated here.)
(Probe survey      {:through lens/survey      :yields Survey})
(Probe patterns    {:through lens/patterns    :yields Patterns})
(Probe consistency {:through lens/consistency :yields Consistency})
(Probe tar-pit     {:through lens/tar-pit     :yields TarPit})
;; inspects — probes whose finding gates
(Probe integrity   {:through lens/integrity   :yields IntegrityReport})
(Probe coverage    {:through lens/coverage    :yields CoverageReport})
(Probe drift       {:through lens/drift       :yields DriftReport})

(Grouping probe
  {:child [Survey Patterns Consistency TarPit IntegrityReport CoverageReport DriftReport
           survey patterns consistency tar-pit integrity coverage drift]})
