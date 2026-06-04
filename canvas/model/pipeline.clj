(ns canvas.model.pipeline
  "Self-spec: fukan's model-build pipeline (`fukan.model.pipeline`), modelled with
   the fukan-on-fukan grammar (`canvas.vocab.{shape,op}`).

   `build-model` is the single public entry point. It ingests the canvas design
   specs (canvas-source/build) and, given a code-root, merges the extracted code
   structures onto the same graph via canvas-source's merge-dbs + resolve-cross-refs
   — design + implementation unified on one graph (fukan's thesis). It names no
   specific extractor: it runs whatever the project registered, via the extraction
   plug-point's `run-extractor`. The merge/ingest machinery lives in canvas-source
   (its real home) and the runner in `extraction`, so this spec doesn't redeclare
   them — it LINKS across to each realizing stage with `(calls (across …))`. Those
   cross-module calls are the seams. Authored separately from the vocabulary,
   mirroring the demos' vocab/model split."
  (:require [fukan.canvas.core.structure :as s]
            ;; the fukan-on-fukan grammar; Shape is authored inline (data), not referred
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "model.pipeline"
      (Kind "SrcRoot")
      (Kind "StructureDb")

      ;; build-model : SrcRoot -> StructureDb ; ingests the design (canvas-source/build)
      ;; and, given a code-root, merges the registered project extractor's output
      ;; (extraction/run-extractor) onto the same graph via merge-dbs +
      ;; resolve-cross-refs. All collaborators are cross-module links (the seams).
      (Stage "build-model"
        (in [source SrcRoot])
        (out StructureDb)
        (calls (across "canvas-source" "build")
               (across "extraction" "run-extractor")
               (across "canvas-source" "merge-dbs")
               (across "canvas-source" "resolve-cross-refs"))))))
