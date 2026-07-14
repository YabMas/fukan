(ns canvas.architecture.projection.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the inverse of the
   target layer's extraction. It composes per-primitive `render` instructions (a multimethod — the
   open extension point, modelled for coverage but its inline-method fan-out is not) over a
   resolved focus (a projection's inline `:select`, a named Lens, or the whole model), projecting
   the model into an implementation specification. `materialize-projection` is the model-driven
   entry (`materialize-view` is a lens-eid convenience). `core.lens` lives in
   `canvas.architecture.kernel.lens`."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.structure :as kstructure]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.kernel.typing :as typing]
            [canvas.architecture.kernel.lens :as lens-engine]
            [canvas.architecture.projection.finding :as finding]))

(Module materialize
  "Project the model down into an implementation spec through a resolved focus + a Projection."
  (Kind Lens) (Kind Instruction) (Kind Projection)
  (Kind ProjectionName :string) (Kind ModuleName :string)
  (Kind FindingMap [:map-of ProjectionName finding/Finding])   ; read-all's return: {projection-name → Finding}
  (Operation materialize-view "Render a lens focus under Blueprint (the default projection)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:lens Lens]] Instruction]
     :performs  [:throws :state]})                 ; reaches the lens engine's query-compiler throw/state
  (Operation materialize-over "Render a refined focus (node-set) under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:focus [:vector substrate/Eid]]] Instruction]
     :performs  [:throws :state]                   ; the renderers read the graph through the query compiler
     :delegates [query/q query/entity]})           ; the module reads node facts via the kernel query layer
  (Operation materialize-focus "Render the nodes an ad-hoc :where clause selects, under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:clauses [:vector query/Clause]]] Instruction]
     :performs  [:throws :state]                   ; via focus-nodes
     :delegates [lens-engine/focus-nodes]})
  (Operation materialize-module "Render a module's Operations under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:module ModuleName]] Instruction]
     :performs  [:throws :state]})                 ; reaches the lens engine's query-compiler throw/state
  (Operation materialize-projection "Render a modelled Projection through its resolved focus (model-driven)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:proj Projection]] Instruction]
     :performs  [:throws :state]                   ; via projection-focus
     :delegates [lens-engine/projection-focus]})
  (Operation render "Render a single node under a projection (composes the per-primitive render-base multimethod)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:eid substrate/Eid]] Instruction]
     :performs  [:throws :state]                    ; the renderers read the graph through the query compiler
     :delegates [typing/render-type kstructure/vocab-rules]})  ; Blueprint schema-emit reaches typing; the renderers' queries inject the kernel rules
  (Operation materialize-finding "Compose a finding's observation foci into a projection — the reading→projection seam."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:finding :any]] Instruction]
     :performs  [:throws :state]})                  ; via materialize-over
  ;; the render dispatch points are POLYMORPHIC OPERATIONS — a defmulti with a uniform signature its
  ;; co-owned defmethods implement (internal dispatch, united ownership; NOT a split-ownership plug-point).
  ;; `render`/`read-projection` call them like any op; their reach is captured over ordinary :calls.
  (Operation render-base
    "The per-(projection, kind) render dispatch point — dispatches on [base kind] to a co-owned
     per-target defmethod (Blueprint, Docs, …), each producing a fragment of that base's target form."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:base ProjectionName] [:eid substrate/Eid]] Instruction]
     :performs  [:throws :state]})
  ;; ── the readings: a Projection whose target artifact is a Finding (the read dual of Blueprint) ──
  (Operation render-finding
    "The per-projection reading-render dispatch point — the read dual of render-base; dispatches on
     projection to a co-owned per-projection defmethod (Patterns, Depth, …), each aggregating its focus
     into a Finding."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:proj ProjectionName] [:focus [:vector substrate/Eid]]] finding/Finding]
     :performs  [:throws :state]})
  (Operation read-projection "Run a reading projection: resolve its focus, render it into a Finding."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:proj-eid substrate/Eid]] finding/Finding]
     :performs  [:throws :state]                   ; via projection-focus, then render-finding
     :delegates [lens-engine/projection-focus finding/finding finding/observation]})  ; the reading render reaches the finding constructors
  (Operation read-all "Run every reading projection present in the db → a map of findings."
    {:signature [:=> [:catn [:db substrate/StructureDb]] FindingMap]
     :performs  [:throws :state]}))                 ; via read-projection / the query compiler
