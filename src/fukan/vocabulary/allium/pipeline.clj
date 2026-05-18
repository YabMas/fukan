(ns fukan.vocabulary.allium.pipeline
  "Pipeline orchestrator: source root walk → per-file analyzer → cross-file
   merge → validated Model. Plan 2b's top-level entry point.")

(defn load-source
  "Walk source-root, parse every .allium file, and return a validated Model.
   Stub: not yet implemented — progressively filled by Tasks 1–14."
  [_source-root]
  (throw (UnsupportedOperationException. "load-source not yet implemented (Plan 2b Task 14)")))
