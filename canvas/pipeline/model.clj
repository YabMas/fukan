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
            ;; the fukan-on-fukan grammar; Shape is authored inline (data), not referred
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "model.pipeline"
      (Kind "SrcRoot")
      (Kind "StructureDb")

      ;; merge-dbs : (list StructureDb) -> StructureDb
      (Stage "merge-dbs"
        (in [dbs [StructureDb]])
        (out StructureDb))

      ;; ingest (canvas-source/build) : () -> StructureDb ; merges the per-spec dbs
      (Stage "ingest"
        (out StructureDb)
        (calls merge-dbs))

      ;; build-model : SrcRoot -> StructureDb ; the pipeline entry point
      (Stage "build-model"
        (in [source SrcRoot])
        (out StructureDb)
        (calls ingest)))))
