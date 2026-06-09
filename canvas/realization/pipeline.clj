(ns canvas.realization.pipeline
  "Self-spec: fukan's model-build pipeline (`fukan.model.pipeline`).

   `build-model` is the single public entry point. It ingests the canvas design specs
   (canvas-source/build) and, given a code-root, folds the extracted code structures onto the
   same graph (extraction/run-extractor + union-dbs) — design + implementation unified on one
   StructureDb (fukan's thesis). It names no specific extractor — it runs whatever the project
   registered. Collaborators are cross-module var-refs (the seams)."
  (:require [lib.code :refer [Operation Module]]
            [canvas.realization.kernel :as kernel]
            [canvas.realization.canvas-source :as canvas-source]
            [canvas.realization.extraction :as extraction]))

(Module model-pipeline
  "The model-build pipeline — the single build entry point."
  (Operation build-model "Ingest the design specs + fold extracted code onto one StructureDb."
    (signature [:=> [:catn [:source extraction/Path]] kernel/StructureDb])
    (calls canvas-source/build extraction/run-extractor canvas-source/union-dbs)))
