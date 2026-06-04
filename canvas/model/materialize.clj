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
      ;; materialize-view : (StructureDb, Lens) → Instruction ; render the lens's focus
      ;; sub-graph by composing each focused primitive's `render` instruction
      (Stage "materialize-view" (in [db StructureDb]) (in [lens Lens]) (out Instruction)))))
