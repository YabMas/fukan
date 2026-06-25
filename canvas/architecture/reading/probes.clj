(ns canvas.architecture.reading.probes
  "Self-spec: fukan's PROBE IMPLEMENTATION — a boundary sketch of the realized probe surface.
   `probes` (`fukan.canvas.projection.probes`) exposes the live run/run-all dispatch over the
   implemented probe leaves. Realizes the `Lens` read perspective. The individual probe leaves
   are internals (extraction's job); what the surface DELEGATES to — the kernel's `check` and the
   target correspondence queries — is sketched on the exposed dispatch. Reads the kernel's
   shared `StructureDb`."
  (:require [canvas.vocab.code.kind :refer [Kind]] [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.reading.finding :as finding]))

(Module probes
  "The live run/run-all dispatch surface over the implemented probe leaves."
  (Kind ProbeName [:enum "survey" "patterns" "consistency" "tar-pit" "integrity" "coverage" "drift" "type-drift"])
  (Kind Finding
    [:map [:lens :string]
          [:observations [:vector [:map [:focus [:set :int]] [:as :keyword] [:note :string]]]]])
  (Kind FindingMap [:map-of ProbeName Finding])
  (Operation run "Dispatch a named probe over a target db → a finding."
    {:signature [:=> [:catn [:target-db substrate/StructureDb] [:probe-name ProbeName]] Finding]
     :performs  [:throws]
     :delegates [kernel/check finding/finding finding/observation query/q]})
  (Operation run-all "Run every implemented probe leaf → a map of findings."
    {:signature [:=> [:catn [:target-db substrate/StructureDb]] FindingMap]
     :performs  [:throws]                          ; via the probe leaves / the query compiler
     :delegates [kernel/check finding/finding finding/observation]})
  ;; ── the probe leaves: internal handlers the dispatch point routes to (each a private defn-) ──
  ;; the readings that read the graph through the query layer carry its :throws surface
  (Operation ^:private probe-survey      "Structural overview (a reading)."      {:performs [:throws]})
  (Operation ^:private probe-patterns    "Pattern reading (a reading)."          {:performs [:throws]})
  (Operation ^:private probe-consistency "Operation-name ambiguity (a reading)." {:performs [:throws]})
  (Operation ^:private probe-tar-pit     "Complexity hotspots (a reading)."      {:performs [:throws]})
  (Operation ^:private probe-integrity   "The integrity reading — runs the kernel's check.")
  (Operation ^:private probe-coverage    "Spec↔code coverage (a reading)."       {:performs [:throws]})
  (Operation ^:private probe-drift       "Spec↔code drift (a reading)."          {:performs [:throws]})
  (Operation ^:private probe-type-drift  "Spec↔code TYPE drift (a reading)."     {:performs [:throws]})
  (Operation ^:private run-probe
    "The dispatch point: run/run-all route here, and it dispatches to the registered probe leaves
     (explicit indirection — the decoupling seam between the surface and the implementations)."
    {:performs      [:throws]
     :dispatches-to [probe-survey probe-patterns probe-consistency probe-tar-pit
                     probe-integrity probe-coverage probe-drift probe-type-drift]}))
