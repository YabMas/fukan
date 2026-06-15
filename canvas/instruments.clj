(ns canvas.instruments
  "fukan's OWN instruments — the concrete lenses, findings, and projections fukan points at
   itself, authored against the reusable `lib.lens` grammar (fukan as its own first user). These
   are configured CONTENT, not fukan's abstract design: the design portrait lives in
   `canvas.subject`; the authoring grammar lives in `lib.lens`. A user project authors its own
   instruments the same way, in its own canvas.

   The executable mechanism that RUNS these lives in `canvas.architecture.acts` (the realization
   seam — that split IS driftable, it crosses to code)."
  (:require [lib.lens :refer [Lens Finding Projection Mapping]]
            [lib.grouping :refer [Grouping]]))

;; ── THE FOCUS: a Lens names a slice and carries its runnable selection ─────────────────────────

;; focuses fed to reasoning readings (non-gating findings)
(Lens survey      {:focus  "the whole model's structure"
                   :select ["every node" '[[?n :structure/of _]]]})
(Lens patterns    {:focus  "recurring structures across the model"
                   :select ["every relation source" '[[?r :rel/from ?n]]]})
(Lens consistency {:focus  "where contracts and structure align — or drift"
                   :select ["the contract-bearing authored operations"
                            '[(Operation ?n) (not [?n :val/extracted true])]]})
(Lens tar-pit     {:focus  "complexity hotspots — tangles worth attention"
                   :select ["the call-graph callers" '[(calls ?n ?callee)]]})
;; focuses fed to inspect readings (gating findings — trust verdicts)
(Lens integrity   {:focus  "the model's structural integrity — laws, partitions"
                   :select ["the whole model" '[[?n :structure/of _]]]})
(Lens coverage    {:focus  "spec ↔ code coverage"
                   :select ["the extracted code operations"
                            '[(Operation ?n) [?n :val/extracted true]]]})
(Lens drift       {:focus  "spec ↔ code divergence"
                   :select ["authored operations with no extracted twin"
                            '[(Operation ?n) (not [?n :val/extracted true]) (named ?n ?nm) (in-module ?n ?cm)
                              (not (Operation ?o) [?o :val/extracted true] (named ?o ?nm) (in-module ?o ?km)
                                   [(fukan.target.correspondence/module-corresponds? ?cm ?km)])]]})

(Grouping lens
  {:child [survey patterns consistency tar-pit integrity coverage drift]})

;; ── THE READING: a Finding reads through a Lens (inspect ⊂ reading) ────────────────────────────

;; non-gating readings — perspectives to reason with (Views); each reads through its lens above
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

;; ── THE SYNTHESIS: a Projection re-presents the model through a Lens ───────────────────────────

(Projection Blueprint
  "The model projected to implementation code — the first projection target."
  {:through survey   ; renders through the whole-model focus (reused from the survey reading)
   :maps    [(Mapping {:from "an atomic value"    :to "a def"})
             (Mapping {:from "a record structure" :to "a Malli schema"})
             (Mapping {:from "a function"         :to "a defn"})
             (Mapping {:from "a law"              :to "a predicate"})]})

;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new target — it
;; renders Blueprint's specs through the drift lens (the unrealized Operations) and frames them with
;; a drift-closing context. The same composing shape contextualizes Blueprint as a new feature, a
;; refactor, etc. — just a different context over the same base.
(Projection DriftClose
  "Blueprint, framed as drift to close — the unrealized Operations as instructions to implement."
  {:contextualizes Blueprint
   :through        drift
   :context        "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:"})

(Grouping projection
  {:child [Blueprint DriftClose]})
