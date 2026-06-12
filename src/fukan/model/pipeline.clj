(ns fukan.model.pipeline
  "Build pipeline (lean-kernel, design decision (ii)).

   The structure substrate IS the model. `build-model` ingests the defstructure
   canvas specs (canvas/**/*.clj) — the DESIGN surface — into one structure db, and,
   when given a source tree, merges the code structures extracted from it onto the
   SAME graph. Specification and implementation then live together on one assertable
   graph (fukan's thesis), so a `check` can surface model↔code drift via the
   correspondence laws (which live in their own concern, `fukan.target.correspondence`,
   and run whenever they are loaded).

   The pipeline names no specific extractor: it runs whatever the project has
   registered at the `fukan.model.extraction` plug-point (fukan-on-itself registers
   its Clojure extractor over `src/` in `fukan.infra.model`)."
  (:require [clojure.java.io :as io]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.model.extraction :as extraction]
            [lib.grammar :as grammar]))

(defn build-model
  "Build the model — the unified structure substrate db. Always ingests the canvas/
   design specs; when `code-root` names an existing source tree AND a project
   extractor is registered, the code structures it yields are merged onto the same
   graph and cross-references re-resolved. The model's GRAMMAR is then reflected
   onto the same graph (`lib.grammar/with-grammar`), so the registry has no
   off-graph remainder. Pass nil (or build with no extractor registered) for the
   design model alone."
  [code-root]
  (let [design  (canvas-source/build)
        code-db (when (and code-root (.exists (io/file code-root)))
                  (extraction/run-extractor code-root))]
    (grammar/with-grammar
     (if code-db
       (canvas-source/union-dbs [design code-db])
       design)
     ;; seed reflection with EVERY discovered canvas namespace, so a pure-grammar
     ;; stratum (canvas.subject — portraits, no instances) still reflects
     ;; (with-grammar coerces the ns symbols to strings)
     (canvas-source/canvas-namespaces))))
