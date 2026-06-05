(ns canvas.model.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the
   inverse of the target layer's extraction. It composes per-primitive `render`
   instructions (a multimethod — not modelled as a Stage, it's the open extension
   point) over a Lens's focus, projecting the model into an implementation
   specification. `materialize-view` is the public entry; it pairs with the
   correspondence concern (which reports which Stages lack code).

   Modelled faithfully like canvas-source — the public fn as a Stage with shaped I/O.
   `core.lens` lives in `canvas.model.lens-engine`."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]
            [canvas.model.lens-engine :as lens-engine]))

(def StructureDb     (Kind "StructureDb"))
(def Lens            (Kind "Lens"))
(def Instruction     (Kind "Instruction"))
(def Projection      (Kind "Projection"))
(def ProjectionName  (Kind "ProjectionName"))
(def ModuleName      (Kind "ModuleName"))
(def Clause          (Kind "Clause"))
(def Eid             (Kind "Eid"))

;; render-base is a defmulti dispatching on [base-projection, kind] — the open
;; extension point, not modelled as a Stage. The entries compose its renders over a
;; focus, parameterized by the PROJECTION (a base, or a contextualization framing one):
(def materialize-view
  (Stage "materialize-view" (in [db StructureDb]) (in [lens Lens]) (out Instruction)))  ; lens focus, Blueprint default
;; the focus-consuming entry — a refined focus (core.lens/refine) renders straight in
(def materialize-over
  (Stage "materialize-over" (in [db StructureDb]) (in [projection ProjectionName]) (in [focus [Eid]])
    (out Instruction)))
(def materialize-focus
  (Stage "materialize-focus" (in [db StructureDb]) (in [projection ProjectionName]) (in [clauses [Clause]])
    (out Instruction)                                                                   ; ad-hoc focus
    (calls materialize-over lens-engine/focus-nodes)))
(def materialize-module
  (Stage "materialize-module" (in [db StructureDb]) (in [projection ProjectionName]) (in [module ModuleName])
    (out Instruction)
    (calls materialize-focus)))
(def materialize-projection
  (Stage "materialize-projection" (in [db StructureDb]) (in [proj Projection]) (out Instruction)  ; model-driven
    (calls lens-engine/evaluate-lens)))

(def materialize
  (Module "materialize"
    (child StructureDb Lens Instruction Projection ProjectionName ModuleName Clause Eid
           materialize-view materialize-over materialize-focus materialize-module
           materialize-projection)))
