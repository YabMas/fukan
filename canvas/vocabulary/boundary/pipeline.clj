(ns canvas.vocabulary.boundary.pipeline
  "Canvas port of vocabulary/boundary/pipeline.allium.

   Scope: Phase 3 of the build pipeline — .boundary source-root walk
   and per-file analysis on top of the Allium-loaded Model.

   Coverage:
     - 5 invariants: DeterministicFileOrder, PathCanonicalisation,
       DefaultsRegisteredBeforeAnalysis, ComposesOntoAllium,
       PipelinePurity

   TODO: rule LoadSource — no rule lift (deferred).
     Structural intent:
       when: LoadSource(model: model.Model, source_root: String)
     Steps: seed input Model with Boundary tag catalogue; discover
     .boundary files (sorted); per-file derive coordinate, parse AST,
     extract + canonicalise use-alias map, run analyzer.
     Returns enriched Model. No Violations produced."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.boundary.pipeline"

      ;; TODO: rule LoadSource — no rule lift (deferred to Sprint 2).
      ;; Structural intent:
      ;;   when: LoadSource(model: model.Model, source_root: String)
      ;; Steps: seed input Model with Boundary TagDefinition catalogue;
      ;; discover every .boundary file under source_root in sorted order;
      ;; per-file: derive coordinate from relative path (minus .boundary),
      ;; parse AST, extract + canonicalise use-alias map, run per-file analyzer.
      ;; Returns enriched Model. No Violations produced.

      (invariant "DeterministicFileOrder"
        "Files are loaded in sorted absolute-path order. Same source-root
         contents always produce the same load sequence."
        (holds-that "deterministic-file-order-sorted"))

      (invariant "PathCanonicalisation"
        "Each file's raw use-alias paths are canonicalised relative to
         the host file's coordinate before reaching the analyzer. The
         canonical form is the root-relative path with the .boundary or
         .allium extension stripped."
        (holds-that "use-alias-paths-canonicalised-before-analyzer"))

      (invariant "DefaultsRegisteredBeforeAnalysis"
        "The Boundary TagDefinition catalogue is seeded onto the input
         Model before any .boundary file is analyzed. An unregistered
         tag would fail payload-schema validation at application time."
        (holds-that "boundary-defaults-registered-before-analysis"))

      (invariant "ComposesOntoAllium"
        "The Boundary pipeline takes the Allium-loaded Model as input
         and adds to it. It does not produce a fresh Model and does not
         remove or rewrite Allium-produced primitives. Cross-extension
         references — attach-form fns binding to Allium-declared
         Operations, fn triggers: edges targeting Allium-declared
         Rules — resolve against the Allium primitives already in
         :primitives."
        (holds-that "boundary-pipeline-composes-onto-allium-model"))

      (invariant "PipelinePurity"
        "The pipeline is a pure function of (input Model, source tree).
         No global state, no caches, no I/O outside the file walk and
         parse calls."
        (holds-that "boundary-pipeline-pure-of-model-and-source-tree")))))
