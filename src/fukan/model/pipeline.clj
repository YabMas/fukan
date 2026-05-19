(ns fukan.model.pipeline
  "Multi-extension build pipeline (Phase 1-3 per DESIGN.md).

   Phase 1: per-extension parse (Allium + Boundary parse independently).
   Phase 2: cross-extension reference resolution (Boundary references
            Allium-produced Operations / Rules / Containers).
   Phase 3: merge (kernel content unioned by identity).

   Phase 4 (structural validation) is Plan 3c; Phase 5 (constraints) is
   Plan 4."
  (:require [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]))

(defn load-source
  "Top-level load: runs the Allium pipeline first (Plan 2b), then the
   Boundary pipeline on the resulting Model. Returns the combined Model.

   The Allium pipeline owns its own model construction (empty model →
   register Allium tags → walk + analyze). The Boundary pipeline
   accepts the Allium-produced model and enriches it."
  [source-root]
  (-> (allium/load-source source-root)
      (boundary/load-source source-root)))
