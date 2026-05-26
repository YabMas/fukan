(ns canvas.model.effect
  "Canvas port of model/effect.allium + effect.boundary.

   Coverage:
     - 3 invariants from effect.allium → vocab.behavioral/invariant each:
         EffectKindIsClosed, EffectIdentityIsTriple,
         CanonicaliseIsMethodologyDelegated
     - fn make_effect      → construction/function
     - fn effect_identity  → construction/function
     - fn canonicalise     → construction/function

   Notes:
     - The Effect value type itself lives in canvas.model.spec.
     - EffectExpressionParity (kernel invariant) lives in canvas.model.spec.
     - Methodology-specific canonicalisation patterns live with the producing
       vocabulary (e.g. vocabulary/allium/effect_canonicalise).
     - EffectKind is a closed set: create | write | destroy | emit."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.effect"

      ;; Invariants from effect.allium

      (invariant "EffectKindIsClosed"
        "make_effect raises when kind is not one of create | write | destroy |
         emit. The substrate commits to this closed EffectKind set; methodology
         effects do not extend it."
        (holds-that "make-effect-raises-on-unknown-kind"))

      (invariant "EffectIdentityIsTriple"
        "effect_identity returns (rule_id, kind, target). Two Effects agreeing
         on this triple are the same Effect, regardless of which originating
         Expression in Rule.intent.assertions they materialised from. This
         stability is what lets §3.8 commit to Effect identity surviving
         Expression rewrites."
        (holds-that "effect-identity-is-rule-id-kind-target-triple"))

      (invariant "CanonicaliseIsMethodologyDelegated"
        "The substrate exposes canonicalise as a recognition seam but ships no
         methodology-specific patterns. The active methodology (e.g. Allium)
         supplies the patterns mapping recognised Expression shapes onto
         Effects."
        (holds-that "canonicalise-delegates-to-active-methodology"))

      ;; Public functions from effect.boundary

      (function "make_effect"
        "Construct an Effect. kind ∈ create | write | destroy | emit.
         value may be nil for Destroy. source_expr_id addresses the
         originating Expression in Rule.intent.assertions."
        (takes [kind           :String
                target         :Any
                value          (optional :Any)
                source_expr_id :String])
        (gives :Any))

      (function "effect_identity"
        "Returns (rule_id, kind, target) per §3.8.7. Stable across
         semantically-equivalent rewrites of the source Expression."
        (takes [rule_id :String
                effect  :Any])
        (gives :Any))

      (function "canonicalise"
        "Map a recognised Expression shape onto its materialised Effect per
         §3.8.4. Delegates to the active methodology's canonicaliser; the
         substrate ships only the recognition seam, not the methodology-specific
         patterns."
        (takes [expression :Any])
        (gives (optional :Any))))))
