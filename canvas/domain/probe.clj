(ns canvas.domain.probe
  "Self-spec: fukan's FINDINGS — readings of the model through a lens. Forward-looking (the tier is
   parked under `.paused/`). Each Finding reads `:through` a lens (the `lens` view's Lens instances —
   interlocking) and is gating or not: INSPECT ⊂ READING — integrity/coverage/drift are gating
   readings (trust Signals); survey/patterns/… are non-gating Views. There is no separate `Probe`:
   a Finding IS the reading (lens + verdict). Realized by the probe surface
   (`canvas.architecture.probe-surface`)."
  (:require [canvas.vocabulary.act :refer [Finding]]
            [lib.grouping :refer [Grouping]]
            [canvas.domain.lens :as lens]))

;; non-gating readings — perspectives to reason with (Views). Each reads through its lens and may
;; STATE its `holds` invariant in prose; the executable check lives in canvas.architecture.acts.
(Finding Survey "A structural overview of the whole model."
  {:through lens/survey
   :gating  false})
(Finding Patterns "Recurring structural patterns across the model."
  {:through lens/patterns
   :gating  false
   :holds   "a model with no recurring structures yields no reported patterns"})
(Finding Consistency "Where contracts and structure align — or drift."
  {:through lens/consistency
   :gating  false})
(Finding TarPit "Complexity hotspots — tangles worth attention."
  {:through lens/tar-pit
   :gating  false})
;; gating readings — trust verdicts (the inspect case → Signals)
(Finding IntegrityReport "Whether the model's structure holds together."
  {:through lens/integrity
   :gating  true
   :holds   "a model with no law violations yields no reported violations"})
(Finding CoverageReport "How much of the target's code is spec-covered."
  {:through lens/coverage
   :gating  true})
(Finding DriftReport "Where specifications and code have diverged."
  {:through lens/drift
   :gating  true})

(Grouping probe
  {:child [Survey Patterns Consistency TarPit IntegrityReport CoverageReport DriftReport]})
