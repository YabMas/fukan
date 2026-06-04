(ns canvas.model.materialize
  "Self-spec: fukan's materialize / LOWER layer (`fukan.model.materialize`) — the
   inverse of the target layer's extraction. It projects a modelled `Stage` into an
   implementation specification (intent + signature) for an implementer to realize;
   it pairs with the correspondence concern (which reports which Stages lack code).

   Modelled faithfully like canvas-source — each public fn a Stage with shaped I/O."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "materialize"
      (Kind "StructureDb") (Kind "StageName") (Kind "Spec") (Kind "Instruction")
      ;; materialize-stage : (StructureDb, StageName) → Spec ; project a Stage's impl spec
      (Stage "materialize-stage" (in [db StructureDb]) (in [stage-name StageName]) (out Spec))
      ;; instruction : Spec → Instruction ; render the spec as prose for an implementer
      (Stage "instruction" (in [spec Spec]) (out Instruction)))))
