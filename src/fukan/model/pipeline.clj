(ns fukan.model.pipeline
  "Build pipeline (lean-kernel, design decision (ii)).

   The structure substrate IS the model: ingest the defstructure-based canvas
   specs (canvas/**/*.clj) and merge them into one structure db. There is no
   model-map projection and no Phase-6 Clojure analyzer here — the analyzer is
   paused (parked under .paused/), to be rebuilt on the structure substrate in a
   later cycle, alongside the viewer and agent surfaces."
  (:require [fukan.canvas.projection.canvas-source :as canvas-source]))

(defn build-model
  "Build the model: the merged structure substrate db over all canvas specs."
  [_source-root]
  (canvas-source/build))
