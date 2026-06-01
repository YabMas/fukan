(ns canvas.pipeline.model
  "Self-spec: fukan's model-build pipeline (`fukan.model.pipeline` +
   `canvas.projection.canvas-source`), modelled with `canvas.pipeline.vocab`.

   The point of this spec is to SEE value-identity in a real fukan model: the
   `StructureDb` type-shape is the input-or-output of nearly every stage, so it
   recurs across four signature positions — yet, being value-identified, it
   collapses to ONE Shape node. `merge-dbs`'s input `(list StructureDb)` wraps that
   very same shared leaf. Authored separately from the vocabulary, mirroring the
   demos' vocab/model split."
  (:require [fukan.canvas.core.structure :as s]
            [fukan.canvas.structures :refer [Type]]
            ;; the vocab is required so its structures register (:Shape resolves at
            ;; build); Shape is authored inline as data, so it is not referred.
            [canvas.pipeline.vocab :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "model.pipeline"
      (Type "SrcRoot")
      (Type "StructureDb")

      ;; merge-dbs : (list StructureDb) -> StructureDb
      (Stage "merge-dbs"
        (in [dbs (Shape (kind "list") (of (Shape (kind "type") (type StructureDb))))])
        (out (Shape (kind "type") (type StructureDb))))

      ;; ingest (canvas-source/build) : () -> StructureDb ; merges the per-spec dbs
      (Stage "ingest"
        (out (Shape (kind "type") (type StructureDb)))
        (calls merge-dbs))

      ;; build-model : SrcRoot -> StructureDb ; the pipeline entry point
      (Stage "build-model"
        (in [source (Shape (kind "type") (type SrcRoot))])
        (out (Shape (kind "type") (type StructureDb)))
        (calls ingest)))))
