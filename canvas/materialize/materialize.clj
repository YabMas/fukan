(ns canvas.materialize.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the inverse of the
   target layer's extraction. It composes per-primitive `render` instructions (a multimethod — the
   open extension point, not modelled as an Operation) over a Lens's focus, projecting the model
   into an implementation specification. `materialize-view` is the public entry. `core.lens` lives
   in `canvas.materialize.lens-engine`."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.lens-engine :as lens-engine]))

(Subsystem materialize
  "Project the model down into an implementation spec, through a Lens focus + a Projection."
  (Kind Lens) (Kind Instruction) (Kind Projection) (Kind ProjectionName) (Kind ModuleName)
  (Kind Clause) (Kind Eid)
  (Operation materialize-view "Render a lens focus under Blueprint (the default projection)."
    [db kernel/StructureDb] [lens Lens] -> Instruction)
  (Operation materialize-over "Render a refined focus (node-set) under a projection."
    [db kernel/StructureDb] [projection ProjectionName] [focus [:vector Eid]] -> Instruction)
  (Operation materialize-focus "Render the nodes an ad-hoc :where clause selects, under a projection."
    [db kernel/StructureDb] [projection ProjectionName] [clauses [:vector Clause]] -> Instruction
    (calls materialize-over lens-engine/focus-nodes))
  (Operation materialize-module "Render a module's Operations under a projection."
    [db kernel/StructureDb] [projection ProjectionName] [module ModuleName] -> Instruction
    (calls materialize-focus))
  (Operation materialize-projection "Render a modelled Projection through its own lens (model-driven)."
    [db kernel/StructureDb] [proj Projection] -> Instruction
    (calls lens-engine/evaluate-lens)))
