(ns canvas.materialize.pipeline
  "Self-spec: fukan's model-build pipeline (`fukan.model.pipeline`), modelled with
   the fukan-on-fukan grammar (`canvas.language.{shape,op}`).

   `build-model` is the single public entry point. It ingests the canvas design
   specs (canvas-source/build, which discovers + assembles them) and, given a
   code-root, folds the extracted code structures onto the same graph with
   canvas-source's `union-dbs` — design + implementation unified on one graph
   (fukan's thesis). It names no specific extractor: it runs whatever the project
   registered, via the extraction plug-point's `run-extractor`. The ingest/union
   machinery lives in canvas-source and the runner in `extraction`, so this spec
   doesn't redeclare them — it LINKS across to each realizing stage with
   `(calls …)`. Those cross-module calls are the seams."
  (:require [canvas.language.shape :refer [Kind]]
            [canvas.language.op :refer [Stage]]
            [canvas.language.grouping :refer [Module]]
            [canvas.materialize.canvas-source :as canvas-source]
            [canvas.materialize.extraction :as extraction]))

(def SrcRoot     (Kind))
(def StructureDb (Kind))

;; build-model : SrcRoot -> StructureDb ; ingests the design (canvas-source/build)
;; and, given a code-root, folds the registered project extractor's output
;; (extraction/run-extractor) onto the same graph via union-dbs. All collaborators
;; are cross-module links (the seams).
(def build-model
  (Stage
    (in [source SrcRoot])
    (out StructureDb)
    (calls canvas-source/build
           extraction/run-extractor
           canvas-source/union-dbs)))

(def model-pipeline
  (Module "model.pipeline" (child SrcRoot StructureDb build-model)))
