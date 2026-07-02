(ns canvas.instruments.projections
  "fukan's own PROJECTIONS — re-presentations of the model rendered from a focus into a target
   artifact. TOOL-DEFINITIONS, authored against the core `Lens`/`Projection` grammar. Each
   projection carries its own INLINE `:select` (a focus is named as a `Lens` only when genuinely
   shared — the `defrelation` of selections; fukan currently shares none); no focus at all =
   the whole model."
  (:require [fukan.canvas.core.lens :refer [Projection Mapping]]
            [canvas.vocab.grouping :refer [Grouping]]))

(Projection Blueprint
  "The model projected to implementation code — the first projection target."
  ;; deliberately NO focus: absence of narrowing IS the maximal focus (the whole model)
  {:maps    [(Mapping {:from "an atomic value"    :to "a def"})
             (Mapping {:from "a record structure" :to "a Malli schema"})
             (Mapping {:from "a function"         :to "a defn"})
             (Mapping {:from "a law"              :to "a predicate"})]})

;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new target — it
;; renders Blueprint's specs over its own focus (the unrealized Operations) and frames them with a
;; drift-closing context. The same composing shape contextualizes Blueprint as a new feature, a
;; refactor, etc. — just a different context over the same base.
(Projection DriftClose
  "Blueprint, framed as drift to close — the unrealized Operations as instructions to implement."
  {:contextualizes Blueprint
   ;; "no extracted twin" is exactly the `op-twin` defrelation negated — the same join the
   ;; Realization law uses: a not-join over the rule.
   :select         '[(authored ?n) (not-join [?n] (op-twin ?n ?o))]
   :context        "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:"})

;; the READINGS — projections whose target artifact is a Finding (a list of observations) rather
;; than implementation text. Each renders its resolved focus through materialize/render-finding.
;; A reading observes patterns ACROSS the focus, so it aggregates the whole focus rather than
;; mapping each node — but it is the same Projection act. The reading supplies the INTERPRETATION;
;; its inline `:select` supplies the (unopinionated) structural slice.
(Projection Patterns
  "Recurring structures — structural triplets borne by more than one relation (a reading)."
  {:select '[[?n :rel/kind _]]
   :maps   [(Mapping {:from "a reified relation" :to "a pattern observation"})]})

(Projection Consistency
  "Operation-name ambiguity — names borne by more than one module (a reading)."
  {:select '[(Operation ?n)]
   :maps   [(Mapping {:from "an operation" :to "an ambiguity observation"})]})

(Projection Depth
  "Module depth — interface size against implementation size, shallowest first (a reading)."
  {:select '[(Module ?n)]
   :maps   [(Mapping {:from "a module" :to "a depth observation"})]})

(Projection Boundary
  "The trust story per boundary — declared parsers and their failure channels, undeclared
   producers, validator-shaped ops (a reading)."
  {:select '[(TrustBoundary ?n)]
   :maps   [(Mapping {:from "a trust boundary" :to "a boundary observation"})]})

(Grouping projection
  {:child [Blueprint DriftClose Patterns Consistency Depth Boundary]})
