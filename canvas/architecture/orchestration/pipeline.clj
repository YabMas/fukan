(ns canvas.architecture.orchestration.pipeline
  "Self-spec: fukan's model-build pipeline (`fukan.model.pipeline`).

   `build-model` is the single public entry point. It requires the canvas design specs
   (canvas-source/require-canvas-namespaces!) and, given a code-root, folds the extracted code
   facts (extraction/extract-facts) onto the same graph, assembling both natively into one Cozo
   substrate (cozo-build/model->cozo) — design + implementation unified (fukan's thesis). It names
   no specific extractor — it runs whatever the project registered. Collaborators are cross-module
   var-refs (the seams)."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.cozo.build :as cozo-build]
            [canvas.architecture.cozo.db :as cozo-db]
            [canvas.architecture.ingestion.source :as canvas-source]
            [canvas.architecture.ingestion.extraction :as extraction]))

(Module model-pipeline
  "The model-build pipeline — the single build entry point."
  (Operation build-model
    "Ingest the canvas design specs + fold the extracted code into one native Cozo substrate:
     require the canvas namespaces, gather the extraction facts through the plug-point, and
     assemble canvas + code via model->cozo."
    {:signature [:=> [:catn [:source extraction/Path]] cozo-db/CozoDb]
     :performs  [:io :stderr :require :throws :state]
     :delegates [canvas-source/require-canvas-namespaces! extraction/extract-facts cozo-build/model->cozo]}))
