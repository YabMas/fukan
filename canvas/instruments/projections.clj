(ns canvas.instruments.projections
  "fukan's own PROJECTIONS — re-presentations of the model rendered through a lens
   (`canvas.instruments.lenses`) into a target artifact. TOOL-DEFINITIONS, authored against
   the core `Lens`/`Projection` grammar."
  (:require [fukan.canvas.core.lens :refer [Projection Mapping]]
            [canvas.vocab.grouping :refer [Grouping]]
            [canvas.instruments.lenses :refer [everything unrealized-operations relations operations modules]]))

(Projection Blueprint
  "The model projected to implementation code — the first projection target."
  {:through everything   ; renders the whole model — the maximal focus
   :maps    [(Mapping {:from "an atomic value"    :to "a def"})
             (Mapping {:from "a record structure" :to "a Malli schema"})
             (Mapping {:from "a function"         :to "a defn"})
             (Mapping {:from "a law"              :to "a predicate"})]})

;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new target — it
;; renders Blueprint's specs through the unrealized-operations lens and frames them with a
;; drift-closing context. The same composing shape contextualizes Blueprint as a new feature, a
;; refactor, etc. — just a different context over the same base.
(Projection DriftClose
  "Blueprint, framed as drift to close — the unrealized Operations as instructions to implement."
  {:contextualizes Blueprint
   :through        unrealized-operations
   :context        "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:"})

;; the READINGS — projections whose target artifact is a Finding (a list of observations) rather
;; than implementation text. Each renders its :through lens's focus through materialize/render-finding.
;; A reading observes patterns ACROSS the focus (recurring triplets, ambiguities), so it aggregates
;; the whole focus rather than mapping each node — but it is the same Projection act. The reading
;; supplies the INTERPRETATION; its :through lens supplies the (unopinionated) structural slice.
(Projection Patterns
  "Recurring structures — structural triplets borne by more than one relation (a reading)."
  {:through relations
   :maps    [(Mapping {:from "a reified relation" :to "a pattern observation"})]})

(Projection Consistency
  "Operation-name ambiguity — names borne by more than one module (a reading)."
  {:through operations
   :maps    [(Mapping {:from "an operation" :to "an ambiguity observation"})]})

(Projection Depth
  "Module depth — interface size against implementation size, shallowest first (a reading)."
  {:through modules
   :maps    [(Mapping {:from "a module" :to "a depth observation"})]})

(Grouping projection
  {:child [Blueprint DriftClose Patterns Consistency Depth]})
