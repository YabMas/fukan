(ns canvas.model.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the
   inverse of the target layer's extraction. It composes per-primitive `render`
   instructions (a multimethod — not modelled as a Stage, it's the open extension
   point) over a Lens's focus, projecting the model into an implementation
   specification. `materialize-view` is the public entry; it pairs with the
   correspondence concern (which reports which Stages lack code).

   Modelled faithfully like canvas-source — the public fn as a Stage with shaped I/O."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "materialize"
      (Kind "StructureDb") (Kind "Lens") (Kind "Instruction")
      (Kind "Projection") (Kind "ProjectionName") (Kind "ModuleName") (Kind "Clause")
      ;; render is a defmulti dispatching on [projection, kind] — the open extension
      ;; point, not modelled as a Stage (a defmulti isn't extracted as an Operation).
      ;; The four public entries compose its renders over a focus, parameterized by
      ;; the PROJECTION (which target form):
      (Stage "materialize-view" (in [db StructureDb]) (in [lens Lens]) (out Instruction))  ; lens focus, Blueprint default
      (Stage "materialize-focus" (in [db StructureDb]) (in [projection ProjectionName]) (in [clauses [Clause]])
        (out Instruction)                                                                  ; ad-hoc focus
        (calls (across "core.lens" "focus-nodes")))
      (Stage "materialize-module" (in [db StructureDb]) (in [projection ProjectionName]) (in [module ModuleName])
        (out Instruction)
        (calls materialize-focus))
      (Stage "materialize-projection" (in [db StructureDb]) (in [proj Projection]) (out Instruction)  ; model-driven
        (calls (across "core.lens" "evaluate-lens"))))))
