(ns canvas.model.pipeline
  "Self-spec: fukan's model-build pipeline (`fukan.model.pipeline`), modelled with
   the fukan-on-fukan grammar (`canvas.vocab.{shape,op}`).

   Post lean-kernel prune this subsystem is a single public entry point:
   `build-model` is a thin delegate that hands off entirely to the ingestion
   machinery in `canvas-source`. The merge/ingest stages it used to model now live
   in `canvas-source` (their real home), so this spec no longer redeclares them — it
   instead LINKS across to the realizing stage with `(calls (across …))`. That
   cross-module call is the seam between the entry point and the machinery: one edge,
   not a duplicated description. Authored separately from the vocabulary, mirroring
   the demos' vocab/model split."
  (:require [fukan.canvas.core.structure :as s]
            ;; the fukan-on-fukan grammar; Shape is authored inline (data), not referred
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "model.pipeline"
      (Kind "SrcRoot")
      (Kind "StructureDb")

      ;; build-model : SrcRoot -> StructureDb ; the pipeline entry point — a thin
      ;; delegate that hands off to canvas-source/build (cross-module link, the seam)
      (Stage "build-model"
        (in [source SrcRoot])
        (out StructureDb)
        (calls (across "canvas-source" "build"))))))
