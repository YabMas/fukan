(ns canvas.instruments.projections
  "fukan's own PROJECTIONS — re-presentations of the model rendered through a lens
   (`canvas.instruments.lenses`) into a target artifact. TOOL-DEFINITIONS, authored against
   the core `Lens`/`Projection` grammar."
  (:require [fukan.canvas.core.lens :refer [Projection Mapping]]
            [canvas.vocab.grouping :refer [Grouping]]
            [canvas.instruments.lenses :refer [survey drift patterns consistency callers]]))

(Projection Blueprint
  "The model projected to implementation code — the first projection target."
  {:through survey   ; renders through the whole-model focus (reused from the survey reading)
   :maps    [(Mapping {:from "an atomic value"    :to "a def"})
             (Mapping {:from "a record structure" :to "a Malli schema"})
             (Mapping {:from "a function"         :to "a defn"})
             (Mapping {:from "a law"              :to "a predicate"})]})

;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new target — it
;; renders Blueprint's specs through the drift lens (the unrealized Operations) and frames them with
;; a drift-closing context. The same composing shape contextualizes Blueprint as a new feature, a
;; refactor, etc. — just a different context over the same base.
(Projection DriftClose
  "Blueprint, framed as drift to close — the unrealized Operations as instructions to implement."
  {:contextualizes Blueprint
   :through        drift
   :context        "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:"})

;; the READINGS — projections whose target artifact is a Finding (a list of observations) rather
;; than implementation text. Each renders its :through lens's focus through materialize/render-finding.
;; A reading observes patterns ACROSS the focus (counts, recurring triplets, ambiguities, hotspots),
;; so it aggregates the whole focus rather than mapping each node — but it is the same Projection act.
(Projection Survey
  "The model surveyed — one observation per structure kind, counted (a reading)."
  {:through survey
   :maps    [(Mapping {:from "a structure kind" :to "a counted observation"})]})

(Projection Patterns
  "Recurring structures — structural triplets borne by more than one relation (a reading)."
  {:through patterns
   :maps    [(Mapping {:from "a reified relation" :to "a pattern observation"})]})

(Projection Consistency
  "Operation-name ambiguity — names borne by more than one module (a reading)."
  {:through consistency
   :maps    [(Mapping {:from "an operation" :to "an ambiguity observation"})]})

(Projection Callers
  "Coupling hotspots — the focus's highest-degree nodes (a reading)."
  {:through callers
   :maps    [(Mapping {:from "a caller node" :to "a hotspot observation"})]})

(Grouping projection
  {:child [Blueprint DriftClose Survey Patterns Consistency Callers]})
