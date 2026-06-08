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
   The two modules share the `ProbeName` Kind (and read the kernel's `StructureDb`)."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.target :as target]))

(def ProbeName  (Kind))

;; probes — the implemented leaves + the live runner
(def Finding    (Kind))
(def FindingMap (Kind))

(def probe-patterns    (Operation [target-db kernel/StructureDb] -> Finding))   ; pure (datascript): recurring structures
(def probe-survey      (Operation [target-db kernel/StructureDb] -> Finding))   ; counts by structure kind
(def probe-consistency (Operation [target-db kernel/StructureDb] -> Finding))   ; Operation-name ambiguity across modules
(def probe-tar-pit     (Operation [target-db kernel/StructureDb] -> Finding))   ; top nodes by relation degree
(def probe-integrity
  (Operation [target-db kernel/StructureDb] -> Finding                            ; the integrity inspect: composes check
    (calls kernel/check)))
(def probe-coverage
  (Operation [target-db kernel/StructureDb] -> Finding                             ; code→spec gaps
    (calls target/uncovered-operations)))
(def probe-drift
  (Operation [target-db kernel/StructureDb] -> Finding                                ; spec→code gaps
    (calls target/drifted-operations)))
(def run
  (Operation [target-db kernel/StructureDb] [probe-name ProbeName] -> Finding (performs :throws)
    (calls probe-patterns probe-integrity)))                                            ; dispatch to a registered leaf
(def run-all
  (Operation [target-db kernel/StructureDb] -> FindingMap                                 ; run every implemented leaf
    (calls probe-patterns probe-integrity)))

(def probes
  (Subsystem
    (exposes run run-all)                          ; the probe dispatch surface
    (owns ProbeName Finding FindingMap)
    (child probe-patterns probe-survey probe-consistency probe-tar-pit
           probe-integrity probe-coverage probe-drift)))

;; probe-code — project a probe's implementation spec from the model
(def CapabilityName (Kind))
(def ContractForm   (Kind))
(def Instruction    (Kind))
(def ProbeArtifact  (Kind))

(def probe-capability
  (Operation [db kernel/StructureDb] [probe-name ProbeName] -> CapabilityName (performs :throws)))
(def observations-contract (Operation -> ContractForm))
(def instruction (Operation [db kernel/StructureDb] [probe-name ProbeName] -> Instruction))
(def project-probe
  (Operation [db kernel/StructureDb] [probe-name ProbeName] -> ProbeArtifact (performs :throws)
    (calls probe-capability observations-contract instruction)))

(def probe-code
  (Subsystem
    (exposes project-probe)                        ; the probe-spec projector
    (owns CapabilityName ContractForm Instruction ProbeArtifact)   ; ProbeName is owned by `probes`
    (child probe-capability observations-contract instruction)))
