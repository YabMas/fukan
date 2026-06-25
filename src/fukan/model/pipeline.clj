(ns fukan.model.pipeline
  "Build pipeline (lean-kernel, design decision (ii)).

   The structure substrate IS the model. `build-model` ingests the defstructure
   canvas specs (canvas/**/*.clj) — the DESIGN surface — into one structure db, and,
   when given a source tree, merges the code structures extracted from it onto the
   SAME graph. Specification and implementation then live together on one assertable
   graph (fukan's thesis), so a `check` can surface model↔code drift via the
   correspondence laws (which live in their own concern, the code-vocab correspondence,
   and run whenever they are loaded).

   The pipeline names no specific extractor: it runs whatever the project has
   registered at the `fukan.model.extraction` plug-point (fukan-on-itself registers
   its Clojure extractor over `src/` in `fukan.infra.model`)."
  (:require [clojure.java.io :as io]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.model.extraction :as extraction]
            [fukan.cozo.build :as cozo-build]
            [canvas.vocab.grammar :as grammar]))

(defn build-model
  "Build the model — the unified structure substrate db. Always ingests the canvas/
   design specs; when `code-root` names an existing source tree AND a project
   extractor is registered, the code structures it yields are merged onto the same
   graph and cross-references re-resolved. The model's GRAMMAR is then reflected
   onto the same graph (`canvas.vocab.grammar/with-grammar`), so the registry has no
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
     ;; seed reflection with EVERY discovered canvas namespace, so a zero-instance
     ;; pure-grammar stratum (if any) still reflects
     ;; (with-grammar coerces the ns symbols to strings)
     (canvas-source/canvas-namespaces))))

(defn ^:test-support build-cozo-model
  "Build the model as a native Cozo substrate (no datascript) — the cozo twin of `build-model`,
   exercised while the datascript build is cut over. Requires the canvas namespaces, gathers the
   extraction facts through the plug-point (when `code-root` exists), and assembles them into one
   Cozo db via `model->cozo`. TRANSITIONAL: this becomes `build-model` once the datascript path
   is dropped (Stage C of the cut-over)."
  [code-root]
  (let [nss   (canvas-source/require-canvas-namespaces!)
        facts (if (and code-root (.exists (io/file code-root)))
                (extraction/extract-facts code-root)
                {:roots [] :var-usages []})]
    (cozo-build/model->cozo nss facts)))
