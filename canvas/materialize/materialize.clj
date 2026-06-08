(ns canvas.materialize.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the
   inverse of the target layer's extraction. It composes per-primitive `render`
   instructions (a multimethod — not modelled as an Operation, it's the open extension
   point) over a Lens's focus, projecting the model into an implementation
   specification. `materialize-view` is the public entry; it pairs with the
   correspondence concern (which reports which Operations lack code).

   Modelled faithfully like canvas-source — the public fn as an Operation with shaped I/O.
   `core.lens` lives in `canvas.materialize.lens-engine`."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.lens-engine :as lens-engine]))

(def Lens            (Kind))
(def Instruction     (Kind))
(def Projection      (Kind))
(def ProjectionName  (Kind))
(def ModuleName      (Kind))
(def Clause          (Kind))
(def Eid             (Kind))

;; render-base is a defmulti dispatching on [base-projection, kind] — the open
;; extension point, not modelled as an Operation. The entries compose its renders over a
;; focus, parameterized by the PROJECTION (a base, or a contextualization framing one):
(def materialize-view
  (Operation [db kernel/StructureDb] [lens Lens] -> Instruction))  ; lens focus, Blueprint default
;; the focus-consuming entry — a refined focus (core.lens/refine) renders straight in
(def materialize-over
  (Operation [db kernel/StructureDb] [projection ProjectionName] [focus [Eid]]
    -> Instruction))
(def materialize-focus
  (Operation [db kernel/StructureDb] [projection ProjectionName] [clauses [Clause]]
    -> Instruction                                                                   ; ad-hoc focus
    (calls materialize-over lens-engine/focus-nodes)))
(def materialize-module
  (Operation [db kernel/StructureDb] [projection ProjectionName] [module ModuleName]
    -> Instruction
    (calls materialize-focus)))
(def materialize-projection
  (Operation [db kernel/StructureDb] [proj Projection] -> Instruction  ; model-driven
    (calls lens-engine/evaluate-lens)))

(def materialize
  (Subsystem
    (exposes materialize-view materialize-over materialize-focus materialize-module
             materialize-projection)               ; the materialize API
    (owns Lens Instruction Projection ProjectionName ModuleName Clause Eid)))
