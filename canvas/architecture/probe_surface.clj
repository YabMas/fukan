(ns canvas.architecture.probe-surface
  "Self-spec: fukan's PROBE IMPLEMENTATION — a boundary sketch of the realized probe surface.
   `probes` (`fukan.canvas.projection.probes`) exposes the live run/run-all dispatch over the
   implemented probe leaves; `probe-code` (`fukan.canvas.projection.probe-code`) projects a
   probe's SPEC from the model. Realizes the `probe` act perspective. The individual probe leaves
   are internals (extraction's job); what the surface DELEGATES to — the kernel's `check` and the
   target correspondence queries — is sketched on the exposed dispatch. Both read the kernel's
   shared `StructureDb`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.target :as target]))

(Module probes
  "The live run/run-all dispatch surface over the implemented probe leaves."
  (Kind ProbeName [:enum "survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift" "type-drift"])
  (Kind Finding
    [:map [:lens :string]
          [:gating :boolean]
          [:observations [:vector [:map [:focus [:set :int]] [:as :keyword] [:note :string]]]]])
  (Kind FindingMap)
  (Operation run "Dispatch a named probe over a target db → a finding."
    {:signature [:=> [:catn [:target-db kernel/StructureDb] [:probe-name ProbeName]] Finding]
     :performs  [:throws]
     :delegates [kernel/check target/uncovered-operations target/drifted-operations]})
  (Operation run-all "Run every implemented probe leaf → a map of findings."
    {:signature [:=> [:catn [:target-db kernel/StructureDb]] FindingMap]
     :delegates [kernel/check target/uncovered-operations target/drifted-operations]}))

(Module probe-code
  "Project a probe's implementation spec from the model. (ProbeName is owned by `probes`.)"
  (Kind ProbeArtifact)
  (Operation project-probe "Project a probe's runnable spec (fn-form or Instruction) from the model."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:probe-name ProbeName]] ProbeArtifact]
     :performs  [:throws]}))
