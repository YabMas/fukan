(ns canvas.materialize.probe-surface
  "Self-spec: fukan's PROBE IMPLEMENTATION — the realized probe leaves and their projector.
   `probes` (`fukan.canvas.projection.probes`) — the implemented probe leaves (each a model-db →
   finding reader) + the live run/run-all surface. `probe-code` (`fukan.canvas.projection.probe-code`)
   — projects a probe's SPEC from the model. Realizes the `probe` act perspective. The two read the
   kernel's shared `StructureDb`."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.target :as target]))

(Subsystem probes
  "The implemented probe leaves + the live run/run-all dispatch surface."
  (Kind ProbeName) (Kind Finding) (Kind FindingMap)
  (Operation ^:private probe-patterns "Recurring structures (a View)."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]))
  (Operation ^:private probe-survey "Counts by structure kind (a View)."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]))
  (Operation ^:private probe-consistency "Operation-name ambiguity across modules (a View)."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]))
  (Operation ^:private probe-tar-pit "Complexity hotspots — top nodes by degree (a View)."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]))
  (Operation ^:private probe-integrity "The integrity inspect — composes the kernel's check."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]) (calls kernel/check))
  (Operation ^:private probe-coverage "Code→spec gaps (coverage Signal)."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]) (calls target/uncovered-operations))
  (Operation ^:private probe-drift "Spec→code gaps (drift Signal)."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] Finding]) (calls target/drifted-operations))
  (Operation run "Dispatch a named probe over a target db → a finding."
    (signature [:=> [:catn [:target-db kernel/StructureDb] [:probe-name ProbeName]] Finding]) (performs :throws)
    (calls probe-patterns probe-integrity))
  (Operation run-all "Run every implemented probe leaf → a map of findings."
    (signature [:=> [:catn [:target-db kernel/StructureDb]] FindingMap])
    (calls probe-patterns probe-integrity)))

(Subsystem probe-code
  "Project a probe's implementation spec from the model. (ProbeName is owned by `probes`.)"
  (Kind CapabilityName) (Kind ContractForm) (Kind Instruction) (Kind ProbeArtifact)
  (Operation ^:private probe-capability "The kernel capability a composing probe :calls."
    (signature [:=> [:catn [:db kernel/StructureDb] [:probe-name ProbeName]] CapabilityName]) (performs :throws))
  (Operation ^:private observations-contract "The uniform finding contract form."
    (signature [:=> [:cat] ContractForm]))
  (Operation ^:private instruction "A projected Instruction for a fresh probe's leaf."
    (signature [:=> [:catn [:db kernel/StructureDb] [:probe-name ProbeName]] Instruction]))
  (Operation project-probe "Project a probe's runnable spec (fn-form or Instruction) from the model."
    (signature [:=> [:catn [:db kernel/StructureDb] [:probe-name ProbeName]] ProbeArtifact]) (performs :throws)
    (calls probe-capability observations-contract instruction)))
