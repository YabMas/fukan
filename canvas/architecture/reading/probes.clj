(ns canvas.architecture.reading.probes
  "Self-spec: fukan's PROBE IMPLEMENTATION — a boundary sketch of the realized probe surface.
   `probes` (`fukan.canvas.projection.probes`) exposes the live run/run-all dispatch over the
   implemented probe leaves; `probe-code` (`fukan.canvas.projection.probe-code`) projects a
   probe's SPEC from the model. Realizes the `probe` act perspective. The individual probe leaves
   are internals (extraction's job); what the surface DELEGATES to — the kernel's `check` and the
   target correspondence queries — is sketched on the exposed dispatch. Both read the kernel's
   shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.reading.correspondence :as corr]
            [canvas.architecture.reading.finding :as finding]
            [canvas.subject :as subj]))

(Module probes
  "The live run/run-all dispatch surface over the implemented probe leaves."
  {:realizes subj/Lens}                          ; faculty role: reads the graph (findings)
  (Kind ProbeName [:enum "survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift" "type-drift"])
  (Kind Finding
    [:map [:lens :string]
          [:gating :boolean]
          [:observations [:vector [:map [:focus [:set :int]] [:as :keyword] [:note :string]]]]])
  (Kind FindingMap [:map-of ProbeName Finding])
  (Operation run "Dispatch a named probe over a target db → a finding."
    {:signature [:=> [:catn [:target-db substrate/StructureDb] [:probe-name ProbeName]] Finding]
     :performs  [:throws]
     :delegates [kernel/check corr/uncovered-operations corr/drifted-operations finding/finding finding/observation]})
  (Operation run-all "Run every implemented probe leaf → a map of findings."
    {:signature [:=> [:catn [:target-db substrate/StructureDb]] FindingMap]
     :delegates [kernel/check corr/uncovered-operations corr/drifted-operations finding/finding finding/observation]})
  ;; ── the probe leaves: internal handlers the dispatch point routes to (each a private defn-) ──
  (Operation ^:private probe-survey      "Structural overview (a View).")
  (Operation ^:private probe-patterns    "Pattern reading (a View).")
  (Operation ^:private probe-consistency "Operation-name ambiguity (a View).")
  (Operation ^:private probe-tar-pit     "Complexity hotspots (a View).")
  (Operation ^:private probe-integrity   "The integrity inspect (a gating Signal) — runs the kernel's check.")
  (Operation ^:private probe-coverage    "Spec↔code coverage (a gating Signal).")
  (Operation ^:private probe-drift       "Spec↔code drift (a gating Signal).")
  (Operation ^:private probe-type-drift  "Spec↔code TYPE drift (a gating Signal).")
  (Operation ^:private run-probe
    "The dispatch point: run/run-all route here, and it dispatches to the registered probe leaves
     (explicit indirection — the decoupling seam between the surface and the implementations)."
    {:performs      [:throws]
     :dispatches-to [probe-survey probe-patterns probe-consistency probe-tar-pit
                     probe-integrity probe-coverage probe-drift probe-type-drift]}))

(Module probe-code
  "Project a probe's implementation spec from the model. (ProbeName is owned by `probes`.)"
  (Kind ProbeArtifact)
  (Operation project-probe "Project a probe's runnable spec (fn-form or Instruction) from the model."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:probe-name ProbeName]] ProbeArtifact]
     :performs  [:throws]}))
