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
   / `probe-drift` call the correspondence stages. Modelled faithfully — each fn an Operation.
   The two modules share the `Db` and `ProbeName` Kinds."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.target :as target]))

(def Db         (Kind))
(def ProbeName  (Kind))

;; probes — the implemented leaves + the live runner
(def Finding    (Kind))
(def FindingMap (Kind))

(def probe-patterns    (Operation (in [target-db Db]) (out Finding)))   ; pure (datascript): recurring structures
(def probe-survey      (Operation (in [target-db Db]) (out Finding)))   ; counts by structure kind
(def probe-consistency (Operation (in [target-db Db]) (out Finding)))   ; Operation-name ambiguity across modules
(def probe-tar-pit     (Operation (in [target-db Db]) (out Finding)))   ; top nodes by relation degree
(def probe-integrity
  (Operation (in [target-db Db]) (out Finding)                            ; the integrity inspect: composes check
    (calls kernel/check)))
(def probe-coverage
  (Operation (in [target-db Db]) (out Finding)                             ; code→spec gaps
    (calls target/uncovered-operations)))
(def probe-drift
  (Operation (in [target-db Db]) (out Finding)                                ; spec→code gaps
    (calls target/drifted-operations)))
(def run
  (Operation (in [target-db Db]) (in [probe-name ProbeName]) (out Finding) (performs :throws)
    (calls probe-patterns probe-integrity)))                                            ; dispatch to a registered leaf
(def run-all
  (Operation (in [target-db Db]) (out FindingMap)                                 ; run every implemented leaf
    (calls probe-patterns probe-integrity)))

(def probes
  (Subsystem
    (exposes run run-all)                          ; the probe dispatch surface
    (child Db ProbeName Finding FindingMap
           probe-patterns probe-survey probe-consistency probe-tar-pit
           probe-integrity probe-coverage probe-drift)))

;; probe-code — project a probe's implementation spec from the model
(def CapabilityName (Kind))
(def ContractForm   (Kind))
(def Instruction    (Kind))
(def ProbeArtifact  (Kind))

(def probe-capability
  (Operation (in [db Db]) (in [probe-name ProbeName]) (out CapabilityName) (performs :throws)))
(def observations-contract (Operation (out ContractForm)))
(def instruction (Operation (in [db Db]) (in [probe-name ProbeName]) (out Instruction)))
(def project-probe
  (Operation (in [db Db]) (in [probe-name ProbeName]) (out ProbeArtifact) (performs :throws)
    (calls probe-capability observations-contract instruction)))

(def probe-code
  (Subsystem
    (exposes project-probe)                        ; the probe-spec projector
    (child Db ProbeName CapabilityName ContractForm Instruction ProbeArtifact
           probe-capability observations-contract instruction)))
