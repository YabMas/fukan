(ns canvas.vocabulary.allium.effect-canonicalise
  "Canvas port of vocabulary/allium/effect_canonicalise.allium.

   Scope: Match Bool Expressions in an Allium rule's ensures: block against
   the four §3.8.4 patterns and produce the corresponding kernel Effect.

   Coverage:
     - 5 invariants: PatternEvaluationOrder, SilentNonMatch,
       SourceBackreference, PureMatching, CompletenessForCanonicalShapes"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "vocabulary.allium.effect-canonicalise"

      (invariant "PatternEvaluationOrder"
        "Patterns are tried in fixed order:
           1. not exists post.X         → :effect/destroy
           2. emitted(E, args...)        → :effect/emit
           3. post.X = T.created(...)    → :effect/create
           4. post.X.f = rhs             → :effect/write
         The first match wins. Order matters because pattern 3 (create) is
         a more specific subcase of pattern 4 (write); the broader pattern
         runs last."
        (holds-that "pattern-evaluation-order-fixed"))

      (invariant "SilentNonMatch"
        "An Expression that matches no pattern produces null rather than
         raising. Not every assertion in an ensures: block is a state
         change — some are pure consistency assertions. The analyzer drops
         nulls and emits no kernel edge for them."
        (holds-that "non-match-returns-null-not-raise"))

      (invariant "SourceBackreference"
        "Every produced Effect carries the source_expr_id of the
         originating Expression. Projection uses this to backreference
         the assertion that produced each kernel edge (writes, creates,
         destroys, emits)."
        (holds-that "effect-carries-source-expr-id"))

      (invariant "PureMatching"
        "Canonicalisation is a pure function of (expr, source_expr_id).
         Same expression, same id, same Effect or same null. No state,
         no I/O."
        (holds-that "canonicalisation-pure-of-expr-and-id"))

      (invariant "CompletenessForCanonicalShapes"
        "Every Bool Expression of one of the four canonical shapes
         produces a matching Effect. The matcher is the authoritative
         bridge from Allium ensures: clauses to kernel side-effect edges
         (:relation/writes, :relation/creates, :relation/destroys,
         :relation/emits); a canonical-shape expression that produced no
         Effect would be a defect."
        (holds-that "canonical-shape-always-produces-effect")))))
