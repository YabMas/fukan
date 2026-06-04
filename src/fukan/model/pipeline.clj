(ns fukan.model.pipeline
  "Build pipeline (lean-kernel, design decision (ii)).

   The structure substrate IS the model. `build-model` ingests the defstructure
   canvas specs (canvas/**/*.clj) — the DESIGN surface — into one structure db, and,
   when given a source tree, merges the code structures extracted from it onto the
   SAME graph. Specification and implementation then live together on one assertable
   graph (fukan's thesis), so a `check` can surface model↔code drift via the
   correspondence laws (which live in their own concern, `fukan.target.correspondence`,
   and run whenever they are loaded).

   The extractor wired in here is fukan-on-itself's: a Clojure extractor over fukan's
   own `src/`. A pluggable, per-target extractor registry is deferred."
  (:require [clojure.java.io :as io]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.target.clojure :as target]))

(defn build-model
  "Build the model — the unified structure substrate db. Always ingests the canvas/
   design specs; when `code-root` names an existing source tree, the code structures
   extracted from it (`fukan.target.clojure/extract`) are merged onto the same graph
   and cross-references re-resolved. Pass nil to build the design model alone."
  [code-root]
  (let [design (canvas-source/build)]
    (if (and code-root (.exists (io/file code-root)))
      (canvas-source/resolve-cross-refs
       (canvas-source/merge-dbs [design (target/extract code-root)]))
      design)))
