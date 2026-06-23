(ns canvas.architecture.reading.finding
  "Self-spec: fukan's FINDING output type — a boundary sketch of `fukan.canvas.projection.finding`,
   the read side's output value. A Finding is a list of Observations (a focus node-set + an `:as`
   tag + a note); the focus is the composition currency a Lens emits and a Projection consumes.

   A LEAF module: it constructs and renders Finding values and delegates to nothing (no cross-module
   dependencies). It lives in the `reading` subsystem alongside `probes`, which delegates here to
   build its findings — keeping that coupling intra-subsystem. Modelling it (minimal: just its public
   surface, no signatures) is what lets `probes` declare the `:delegates [finding/finding …]` that the
   Fidelity law requires once both ends of the call are modelled."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]))

(Module ^{:name "finding"} finding-faculty
  "The Finding data type — a probe's output: a list of Observations, plus the trivial text projection."
  (Operation observation  "Construct one observation: a focus node-set + an `:as` tag + a note.")
  (Operation finding       "Construct a Finding: a lens name and its observations.")
  (Operation finding->text "The trivial text projection of a Finding — its observation notes, in order."))
