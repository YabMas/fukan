(ns fukan.vocabulary.boundary.pipeline
  "Source walk + parse + analyze for .boundary files.
   Mirrors fukan.vocabulary.allium.pipeline's shape.
   Built up across Task 8.")

(defn load-source
  "Walk source-root, parse every .boundary file, analyze each against
   the (already Allium-loaded) model. Returns the enriched model.

   Stub: not yet implemented (Plan 3b Task 8)."
  [model _source-root]
  model)
