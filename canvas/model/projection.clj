(ns canvas.model.projection
  "Self-spec: fukan's PROJECTION subsystem — re-presenting the model in target forms.
   Forward-looking (the parked projection tier). The implementation Blueprint is the
   first projection target; more (docs, diagrams, other realizations) are projections
   we add as we go. Each projection is built from the source→artifact mappings that
   turn model elements into that target's content. The overview's `Faculty
   \"Projection\"` is `realized-by (across \"projection\")`."
  (:require [fukan.canvas.core.structure :as s]
            ;; Mapping is a value structure authored inline (as quoted data) → required
            ;; for registration but not referred
            [canvas.vocab.projection :refer [Projection]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "projection"
      (Projection "Blueprint"
        (doc "The model projected to implementation code — the first projection target.")
        (through (across "lens" "survey"))   ; renders through the whole-model focus (reused from the survey probe)
        (maps (Mapping (from "an atomic value")    (to "a def")))
        (maps (Mapping (from "a record structure") (to "a Malli schema")))
        (maps (Mapping (from "a function")         (to "a defn")))
        (maps (Mapping (from "a law")              (to "a predicate"))))
      ;; instruct ⊂ projection: rendering the model into instructions an LLM acts on is
      ;; just another projection target. DriftClose renders THROUGH the drift lens — the
      ;; same focus the drift inspect-probe reads — turning drift into close-instructions.
      (Projection "DriftClose"
        (doc "Drift rendered into instructions an LLM acts on to close it.")
        (through (across "lens" "drift"))
        (maps (Mapping (from "a drift item") (to "a close-instruction"))))
      ;; future targets — each its own Projection of the same shape:
      ;;   (Projection "Docs"    (through (across "lens" "survey")) (maps (Mapping (from "a module") (to "a doc page")) …))
      ;;   (Projection "Diagram" (through (across "lens" "patterns")) (maps (Mapping (from "a relation") (to "an edge")) …))
      )))
