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
      ;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new
      ;; target — it renders Blueprint's specs through the drift lens (the unrealized
      ;; Stages) and frames them with a drift-closing context. The same composing shape
      ;; contextualizes Blueprint as a new feature, a refactor, etc. — just a different
      ;; context over the same base.
      (Projection "DriftClose"
        (doc "Blueprint, framed as drift to close — the unrealized Stages as instructions to implement.")
        (contextualizes Blueprint)
        (through (across "lens" "drift"))
        (context "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:"))
      ;; future targets — a BASE is its own Projection with :maps; a CONTEXTUALIZATION
      ;; reuses a base via :contextualizes + :context:
      ;;   (Projection "Docs"    (through (across "lens" "survey"))  (maps (Mapping (from "a module") (to "a doc page")) …))
      ;;   (Projection "Feature" (contextualizes Blueprint) (through …) (context "Add this new feature:"))
      )))
