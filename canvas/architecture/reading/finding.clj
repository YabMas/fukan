(ns canvas.architecture.reading.finding
  "Self-spec: fukan's FINDING output type — a boundary sketch of `fukan.canvas.projection.finding`,
   the read side's output value. A Finding is a list of Observations (a focus node-set + an `:as`
   tag + a note); the focus is the composition currency a Lens emits and a Projection consumes.

   A LEAF module: it constructs and renders Finding values and delegates to nothing (no cross-module
   dependencies). It lives in the `reading` subsystem alongside `probes`, which delegates here to build
   its findings — keeping that coupling intra-subsystem. It OWNS the `Observation`/`Finding` data-shapes
   (probes adopts `Finding` by name — the dependency stays probes→finding) and types its public surface,
   which lets `probes` declare the `:delegates [finding/finding …]` the Fidelity law requires."
  (:require [canvas.vocab.code.kind :refer [Kind]]
            [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]))

(Module ^{:name "finding"} finding-faculty
  "The Finding data type — a probe's output: a list of Observations, plus the trivial text projection."
  (Kind Observation [:map [:focus [:set :int]] [:as :keyword] [:note :string]])  ; one observed sub-graph
  (Kind Finding     [:map [:lens :string] [:observations [:vector Observation]]]) ; a lens name + its observations
  (Operation observation  "Construct one observation: a focus node-set + an `:as` tag + a note."
    {:signature [:=> [:catn [:focus [:set :int]] [:as :keyword] [:note :string]] Observation]})
  (Operation finding       "Construct a Finding: a lens name and its observations."
    {:signature [:=> [:catn [:lens :string] [:observations [:sequential Observation]]] Finding]})
  (Operation finding->text "The trivial text projection of a Finding — its observation notes, in order."
    {:signature [:=> [:catn [:finding Finding]] [:vector :string]]}))
