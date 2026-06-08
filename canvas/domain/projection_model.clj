(ns canvas.domain.projection-model
  "Self-spec: fukan's PROJECTION subsystem — re-presenting the model in target forms.
   Forward-looking (the parked projection tier). The implementation Blueprint is the
   first projection target; more (docs, diagrams, other realizations) are projections
   we add as we go. Each projection is built from the source→artifact mappings that
   turn model elements into that target's content. The overview's `Faculty
   \"Projection\"` is `realized-by` this module."
  (:require [canvas.domain.projection :refer [Projection Mapping]]
            [canvas.language.grouping :refer [Module]]
            [canvas.domain.lens-model :as lens]))

(def Blueprint
  (Projection
    (doc "The model projected to implementation code — the first projection target.")
    (through lens/survey)   ; renders through the whole-model focus (reused from the survey probe)
    (maps (Mapping (from "an atomic value")    (to "a def")))
    (maps (Mapping (from "a record structure") (to "a Malli schema")))
    (maps (Mapping (from "a function")         (to "a defn")))
    (maps (Mapping (from "a law")              (to "a predicate")))))

;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new
;; target — it renders Blueprint's specs through the drift lens (the unrealized
;; Stages) and frames them with a drift-closing context. The same composing shape
;; contextualizes Blueprint as a new feature, a refactor, etc. — just a different
;; context over the same base.
(def DriftClose
  (Projection
    (doc "Blueprint, framed as drift to close — the unrealized Stages as instructions to implement.")
    (contextualizes Blueprint)
    (through lens/drift)
    (context "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:")))

(def projection
  (Module (child Blueprint DriftClose)))
