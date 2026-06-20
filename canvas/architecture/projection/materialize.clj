(ns canvas.architecture.projection.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the inverse of the
   target layer's extraction. It composes per-primitive `render` instructions (a multimethod — the
   open extension point, modelled for coverage but its inline-method fan-out is not) over a Lens's
   focus, projecting the model into an implementation specification. `materialize-view` is the
   public entry. `core.lens` lives in `canvas.architecture.kernel.lens`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.substrate :as substrate]
            [canvas.architecture.kernel.lens :as lens-engine]))

(Module materialize
  "Project the model down into an implementation spec, through a Lens focus + a Projection."
  (Kind Lens) (Kind Instruction) (Kind Projection)
  (Kind ProjectionName :string) (Kind ModuleName :string)
  (Kind Clause) (Kind Eid :int)
  (Operation materialize-view "Render a lens focus under Blueprint (the default projection)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:lens Lens]] Instruction]})
  (Operation materialize-over "Render a refined focus (node-set) under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:focus [:vector Eid]]] Instruction]})
  (Operation materialize-focus "Render the nodes an ad-hoc :where clause selects, under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:clauses [:vector Clause]]] Instruction]
     :delegates [lens-engine/focus-nodes]})
  (Operation materialize-module "Render a module's Operations under a projection."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:module ModuleName]] Instruction]})
  (Operation materialize-projection "Render a modelled Projection through its own lens (model-driven)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:proj Projection]] Instruction]
     :delegates [lens-engine/evaluate-lens]})
  (Operation render "Render a single node under a projection (composes the per-primitive render-base multimethod)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:eid Eid]] Instruction]})
  (Operation materialize-finding "Compose a finding's observation foci into a projection — the probe→projection seam."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:projection ProjectionName] [:finding :any]] Instruction]})
  (Operation ^:private render-base
    "The per-(projection, kind) render dispatch point. Its defmethods have inline bodies (no named
     handler ops), so it carries no :dispatches-to fan-out — modelled for coverage."
    {}))
