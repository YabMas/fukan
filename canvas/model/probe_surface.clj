(ns canvas.model.probe-surface
  "Self-spec: fukan's PROBE IMPLEMENTATION — the realized probe leaves and their
   projector. Two namespaces, two modules:

     probes      (fukan.canvas.projection.probes)      — the implemented probe leaves
                                                          (each a model-db → finding reader)
                                                          plus the live run/run-all surface.
     probe-code  (fukan.canvas.projection.probe-code)  — projects a probe's SPEC from the
                                                          model: a composing probe (it :calls
                                                          a modelled capability) → a mechanical
                                                          fn-form + contract; a fresh probe →
                                                          an Instruction for an implementing LLM.

   This is the realization side of the forward-looking `probe` vocab view
   (canvas.model.probe). `probe-integrity` composes the kernel's `check`; `probe-coverage`
   / `probe-drift` call the correspondence stages. Modelled faithfully — each fn a Stage.
   The two modules share the `Db` and `ProbeName` Kinds."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]
            [canvas.model.kernel :as kernel]
            [canvas.model.target :as target]))

(def Db         (Kind "Db"))
(def ProbeName  (Kind "ProbeName"))

;; probes — the implemented leaves + the live runner
(def Finding    (Kind "Finding"))
(def FindingMap (Kind "FindingMap"))

(def probe-patterns    (Stage "probe-patterns"    (in [target-db Db]) (out Finding)))   ; pure (datascript): recurring structures
(def probe-survey      (Stage "probe-survey"      (in [target-db Db]) (out Finding)))   ; counts by structure kind
(def probe-consistency (Stage "probe-consistency" (in [target-db Db]) (out Finding)))   ; Stage-name ambiguity across modules
(def probe-tar-pit     (Stage "probe-tar-pit"     (in [target-db Db]) (out Finding)))   ; top nodes by relation degree
(def probe-integrity
  (Stage "probe-integrity" (in [target-db Db]) (out Finding)                            ; the integrity inspect: composes check
    (calls kernel/check)))
(def probe-coverage
  (Stage "probe-coverage" (in [target-db Db]) (out Finding)                             ; code→spec gaps
    (calls target/unrealized-operations)))
(def probe-drift
  (Stage "probe-drift" (in [target-db Db]) (out Finding)                                ; spec→code gaps
    (calls target/unrealized-stages)))
(def run
  (Stage "run" (in [target-db Db]) (in [probe-name ProbeName]) (out Finding) (performs :throws)
    (calls probe-patterns probe-integrity)))                                            ; dispatch to a registered leaf
(def run-all
  (Stage "run-all" (in [target-db Db]) (out FindingMap)                                 ; run every implemented leaf
    (calls probe-patterns probe-integrity)))

(def probes
  (Module "probes"
    (child Db ProbeName Finding FindingMap
           probe-patterns probe-survey probe-consistency probe-tar-pit
           probe-integrity probe-coverage probe-drift run run-all)))

;; probe-code — project a probe's implementation spec from the model
(def CapabilityName (Kind "CapabilityName"))
(def ContractForm   (Kind "ContractForm"))
(def Instruction    (Kind "Instruction"))
(def ProbeArtifact  (Kind "ProbeArtifact"))

(def probe-capability
  (Stage "probe-capability" (in [db Db]) (in [probe-name ProbeName]) (out CapabilityName) (performs :throws)))
(def observations-contract (Stage "observations-contract" (out ContractForm)))
(def instruction (Stage "instruction" (in [db Db]) (in [probe-name ProbeName]) (out Instruction)))
(def project-probe
  (Stage "project-probe" (in [db Db]) (in [probe-name ProbeName]) (out ProbeArtifact) (performs :throws)
    (calls probe-capability observations-contract instruction)))

(def probe-code
  (Module "probe-code"
    (child Db ProbeName CapabilityName ContractForm Instruction ProbeArtifact
           probe-capability observations-contract instruction project-probe)))
