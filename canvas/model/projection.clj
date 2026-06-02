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
        (maps (Mapping (from "an atomic value")    (to "a def")))
        (maps (Mapping (from "a record structure") (to "a Malli schema")))
        (maps (Mapping (from "a function")         (to "a defn")))
        (maps (Mapping (from "a law")              (to "a predicate"))))
      ;; future targets — each its own Projection of the same shape:
      ;;   (Projection "Docs"    (maps (Mapping (from "a module") (to "a doc page")) …))
      ;;   (Projection "Diagram" (maps (Mapping (from "a relation") (to "an edge")) …))
      )))
