(ns canvas.instruments.findings
  "fukan's own FINDINGS — each reads `:through` one of its lenses
   (`canvas.instruments.lenses`). A gating Finding is a trust Signal (the inspect case); a
   non-gating one is a View, a perspective to reason with. TOOL-DEFINITIONS, authored against
   the `lib.lens` grammar."
  (:require [lib.lens :refer [Finding]]
            [lib.grouping :refer [Grouping]]
            [canvas.instruments.lenses :refer [survey patterns consistency tar-pit
                                               integrity coverage drift]]))

;; non-gating readings — perspectives to reason with (Views); each reads through its lens
(Finding Survey "A structural overview of the whole model."
  {:through survey
   :gating  false})
(Finding Patterns "Recurring structural patterns across the model."
  {:through patterns
   :gating  false
   :holds   "a model with no recurring structures yields no reported patterns"})
(Finding Consistency "Where contracts and structure align — or drift."
  {:through consistency
   :gating  false})
(Finding TarPit "Complexity hotspots — tangles worth attention."
  {:through tar-pit
   :gating  false})
;; gating readings — trust verdicts (the inspect case → Signals)
(Finding IntegrityReport "Whether the model's structure holds together."
  {:through integrity
   :gating  true
   :holds   "a model with no law violations yields no reported violations"})
(Finding CoverageReport "How much of the target's code is spec-covered."
  {:through coverage
   :gating  true})
(Finding DriftReport "Where specifications and code have diverged."
  {:through drift
   :gating  true})

(Grouping probe
  {:child [Survey Patterns Consistency TarPit IntegrityReport CoverageReport DriftReport]})
