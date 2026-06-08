(ns canvas.materialize.probe-surface
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

   This is the realization side of the `probe` act perspective
   (canvas.domain.probe-acts). `probe-integrity` composes the kernel's `check`; `probe-coverage`
   / `probe-drift` call the correspondence stages. Modelled faithfully — each fn a Stage.
   The two modules share the `Db` and `ProbeName` Kinds."
  (:require [canvas.language.shape :refer [Kind]]
            [canvas.language.op :refer [Stage]]
            [canvas.language.grouping :refer [Module]]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.target :as target]))

(def Db         (Kind))
(def ProbeName  (Kind))

;; probes — the implemented leaves + the live runner
(def Finding    (Kind))
(def FindingMap (Kind))

(def probe-patterns    (Stage (in [target-db Db]) (out Finding)))   ; pure (datascript): recurring structures
(def probe-survey      (Stage (in [target-db Db]) (out Finding)))   ; counts by structure kind
(def probe-consistency (Stage (in [target-db Db]) (out Finding)))   ; Stage-name ambiguity across modules
(def probe-tar-pit     (Stage (in [target-db Db]) (out Finding)))   ; top nodes by relation degree
(def probe-integrity
  (Stage (in [target-db Db]) (out Finding)                            ; the integrity inspect: composes check
    (calls kernel/check)))
(def probe-coverage
  (Stage (in [target-db Db]) (out Finding)                             ; code→spec gaps
    (calls target/unrealized-operations)))
(def probe-drift
  (Stage (in [target-db Db]) (out Finding)                                ; spec→code gaps
    (calls target/unrealized-stages)))
(def run
  (Stage (in [target-db Db]) (in [probe-name ProbeName]) (out Finding) (performs :throws)
    (calls probe-patterns probe-integrity)))                                            ; dispatch to a registered leaf
(def run-all
  (Stage (in [target-db Db]) (out FindingMap)                                 ; run every implemented leaf
    (calls probe-patterns probe-integrity)))

(def probes
  (Module
    (child Db ProbeName Finding FindingMap
           probe-patterns probe-survey probe-consistency probe-tar-pit
           probe-integrity probe-coverage probe-drift run run-all)))

;; probe-code — project a probe's implementation spec from the model
(def CapabilityName (Kind))
(def ContractForm   (Kind))
(def Instruction    (Kind))
(def ProbeArtifact  (Kind))

(def probe-capability
  (Stage (in [db Db]) (in [probe-name ProbeName]) (out CapabilityName) (performs :throws)))
(def observations-contract (Stage (out ContractForm)))
(def instruction (Stage (in [db Db]) (in [probe-name ProbeName]) (out Instruction)))
(def project-probe
  (Stage (in [db Db]) (in [probe-name ProbeName]) (out ProbeArtifact) (performs :throws)
    (calls probe-capability observations-contract instruction)))

(def probe-code
  (Module
    (child Db ProbeName CapabilityName ContractForm Instruction ProbeArtifact
           probe-capability observations-contract instruction project-probe)))
