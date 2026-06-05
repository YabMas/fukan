(ns canvas.model.probe
  "Self-spec: fukan's PROBE acts — reading the model through a lens to yield a finding.
   Forward-looking (the tier is parked under `.paused/`). Each probe reads THROUGH a
   lens (the `lens` view's Lens instances — interlocking) and yields a finding.
   INSPECT ⊂ PROBE: integrity/coverage/drift are probes whose findings GATE action
   (Signals); survey/patterns/… yield non-gating Views. The overview's `Faculty
   \"Probe\"` is `realized-by` this module."
  (:require [canvas.vocab.probe :refer [Probe Finding]]
            [canvas.vocab.arch :refer [Module]]
            [canvas.model.lens :as lens]
            [canvas.model.kernel :as kernel]))

;; non-gating findings — perspectives to reason with (Views)
(def Survey   (Finding (gating false) (doc "A structural overview of the whole model.")))
(def Patterns
  (Finding (gating false)
    (doc "Recurring structural patterns across the model.")
    (holds "a model with no recurring structures yields no reported patterns"
           (fn [result target-db]
             (or (empty? (:observations result))
                 (seq (datascript.core/q
                        '[:find ?ft ?k ?tt
                          :where [?r1 :rel/from ?f1] [?r1 :rel/kind ?k] [?r1 :rel/to ?t1]
                                 [?f1 :structure/of ?ft] [?t1 :structure/of ?tt]
                                 [?r2 :rel/from ?f2] [?r2 :rel/kind ?k] [?r2 :rel/to ?t2]
                                 [?f2 :structure/of ?ft] [?t2 :structure/of ?tt]
                                 [(not= ?r1 ?r2)]]
                        target-db)))))))
(def Consistency (Finding (gating false) (doc "Where contracts and structure align — or drift.")))
(def TarPit      (Finding (gating false) (doc "Complexity hotspots — tangles worth attention.")))
;; gating findings — trust verdicts (the inspect case → Signals)
(def IntegrityReport
  (Finding (gating true)
    (doc "Whether the model's structure holds together.")
    (holds "a model with no law violations yields no reported violations"
           (fn [result target-db]
             (or (empty? (:observations result))
                 (seq (fukan.canvas.core.structure/check target-db)))))))
(def CoverageReport (Finding (gating true) (doc "How much of the target's code is spec-covered.")))
(def DriftReport    (Finding (gating true) (doc "Where specifications and code have diverged.")))

;; probes — each reads through a lens → a finding
(def survey      (Probe (through lens/survey)      (yields Survey)))
(def patterns    (Probe (through lens/patterns)    (yields Patterns)))
(def consistency (Probe (through lens/consistency) (yields Consistency)))
(def tar-pit     (Probe (through lens/tar-pit)     (yields TarPit)))
;; inspects — probes whose finding gates. integrity COMPOSES the kernel's modelled
;; `check` capability — the same :calls relation a Stage uses (1-on-1 with the
;; projected code: probe-integrity calls check).
(def integrity   (Probe (through lens/integrity)   (yields IntegrityReport)
                   (calls kernel/check)))
(def coverage    (Probe (through lens/coverage)    (yields CoverageReport)))
(def drift       (Probe (through lens/drift)       (yields DriftReport)))

(def probe
  (Module
    (child Survey Patterns Consistency TarPit IntegrityReport CoverageReport DriftReport
           survey patterns consistency tar-pit integrity coverage drift)))
