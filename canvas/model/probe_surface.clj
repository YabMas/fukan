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
   (canvas.model.probe): there a Probe is a Lens + a Finding; here are the running leaves
   and the projector that specs the not-yet-written ones. `probe-integrity` composes the
   kernel's `check` (the modelled integrity inspect) — the same cross-module call the
   `probe` view's integrity Probe declares. Modelled faithfully — each fn a Stage."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    ;; probes — the implemented leaves + the live runner
    (s/within-module "probes"
      (Kind "Db") (Kind "Finding") (Kind "ProbeName") (Kind "FindingMap")
      (Stage "probe-patterns"  (in [target-db Db]) (out Finding))                  ; pure (datascript): recurring structures
      (Stage "probe-survey"    (in [target-db Db]) (out Finding))                  ; counts by structure kind
      (Stage "probe-consistency" (in [target-db Db]) (out Finding))               ; Stage-name ambiguity across modules
      (Stage "probe-tar-pit"   (in [target-db Db]) (out Finding))                  ; top nodes by relation degree
      (Stage "probe-integrity" (in [target-db Db]) (out Finding)                   ; the integrity inspect: composes check
        (calls (across "core.structure" "check")))
      (Stage "probe-coverage"  (in [target-db Db]) (out Finding)                   ; code→spec gaps
        (calls (across "target.correspondence" "unrealized-operations")))
      (Stage "probe-drift"     (in [target-db Db]) (out Finding)                   ; spec→code gaps
        (calls (across "target.correspondence" "unrealized-stages")))
      (Stage "run"     (in [target-db Db]) (in [probe-name ProbeName]) (out Finding) (performs :throws)
        (calls probe-patterns probe-integrity))                                    ; dispatch to a registered leaf
      (Stage "run-all" (in [target-db Db]) (out FindingMap)                        ; run every implemented leaf
        (calls probe-patterns probe-integrity)))

    ;; probe-code — project a probe's implementation spec from the model
    (s/within-module "probe-code"
      (Kind "Db") (Kind "ProbeName") (Kind "CapabilityName")
      (Kind "ContractForm") (Kind "Instruction") (Kind "ProbeArtifact")
      (Stage "probe-capability"      (in [db Db]) (in [probe-name ProbeName]) (out CapabilityName) (performs :throws))
      (Stage "observations-contract" (out ContractForm))
      (Stage "instruction"           (in [db Db]) (in [probe-name ProbeName]) (out Instruction))
      (Stage "project-probe"         (in [db Db]) (in [probe-name ProbeName]) (out ProbeArtifact) (performs :throws)
        (calls probe-capability observations-contract instruction)))))
