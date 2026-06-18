(ns canvas.architecture.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the inverse of the
   target layer's extraction. It composes per-primitive `render` instructions (a multimethod — the
   open extension point, not modelled as an Operation) over a Lens's focus, projecting the model
   into an implementation specification. `materialize-view` is the public entry. `core.lens` lives
   in `canvas.architecture.lens-engine`."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel :as kernel]
            [canvas.architecture.lens-engine :as lens-engine]
            [canvas.subject :as subj]))

(Module materialize
  "Project the model down into an implementation spec, through a Lens focus + a Projection."
  {:realizes subj/Projection}                    ; faculty role: projects the graph to a target
  (Kind Lens) (Kind Instruction) (Kind Projection)
  (Kind ProjectionName :string) (Kind ModuleName :string)
  (Kind Clause) (Kind Eid :int)
  (Operation materialize-view "Render a lens focus under Blueprint (the default projection)."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:lens Lens]] Instruction]})
  (Operation materialize-over "Render a refined focus (node-set) under a projection."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:projection ProjectionName] [:focus [:vector Eid]]] Instruction]})
  (Operation materialize-focus "Render the nodes an ad-hoc :where clause selects, under a projection."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:projection ProjectionName] [:clauses [:vector Clause]]] Instruction]
     :delegates [lens-engine/focus-nodes]})
  (Operation materialize-module "Render a module's Operations under a projection."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:projection ProjectionName] [:module ModuleName]] Instruction]})
  (Operation materialize-projection "Render a modelled Projection through its own lens (model-driven)."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:proj Projection]] Instruction]
     :delegates [lens-engine/evaluate-lens]}))
