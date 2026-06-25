(ns fukan.model.pipeline
  "Build pipeline (lean-kernel, design decision (ii)).

   The structure substrate IS the model. `build-model` ingests the defstructure canvas specs
   (canvas/**/*.clj) — the DESIGN surface — into one native Cozo structure substrate, and, when
   given a source tree, merges the code structures extracted from it onto the SAME graph.
   Specification and implementation then live together on one assertable graph (fukan's thesis),
   so a `check` surfaces model↔code drift via the correspondence laws (which live in their own
   concern, the code-vocab correspondence, and run whenever they are loaded).

   The pipeline names no specific extractor: it runs whatever the project registered at the
   `fukan.model.extraction` plug-point (fukan-on-itself registers its Clojure extractor over
   `src/` in `fukan.infra.model`)."
  (:require [clojure.java.io :as io]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            [fukan.model.extraction :as extraction]
            [fukan.cozo.build :as cozo-build]))

(defn build-model
  "Build the model — the unified native Cozo structure substrate. Requires the canvas namespaces
   (interning their instance-vars), gathers the extraction FACTS through the plug-point (when
   `code-root` names an existing source tree AND a fact extractor is registered), and assembles
   canvas + code into one Cozo db via `model->cozo` — the :calls graph grounded and the grammar
   reflected. Pass nil (or build with no extractor registered) for the design model alone."
  [code-root]
  (let [nss   (canvas-source/require-canvas-namespaces!)
        facts (if (and code-root (.exists (io/file code-root)))
                (extraction/extract-facts code-root)
                {:roots [] :var-usages []})]
    (cozo-build/model->cozo nss facts)))
