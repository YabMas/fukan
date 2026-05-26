(ns canvas.validation.rules-4g
  "Canvas port of validation/rules_4g.allium + rules_4g.boundary.

   Scope: Phase 4g — cross-module reference-visibility rule. After 4f's
   closure guarantee, every name reachable from outside a module is
   exported; this sub-phase enforces that every cross-module reference
   targets an externally-visible primitive.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 5 invariants → vocab.behavioral/invariant each

   Notes:
     - rule CrossModuleReferencesAreVisible, ContractsAreAlwaysVisible,
       ExternalEntityCountsAsVisible, IntraModuleReferencesIgnored,
       CheckIsPure: no rule lift (deferred). Left as TODO comments below."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4g"

      ;; TODO: rule CrossModuleReferencesAreVisible — no rule lift (deferred).
      ;; Structural intent: every cross-module reference targets an externally
      ;; visible primitive. Violation kind :4g/cross-module-private-reference (error).

      ;; TODO: rule ContractsAreAlwaysVisible — no rule lift (deferred).
      ;; Structural intent: Allium::Contract tagged Containers are always
      ;; externally visible across module walls.

      ;; TODO: rule ExternalEntityCountsAsVisible — no rule lift (deferred).
      ;; Structural intent: Allium::ExternalEntity tagged Containers are
      ;; always externally visible.

      ;; TODO: rule IntraModuleReferencesIgnored — no rule lift (deferred).
      ;; Structural intent: only strictly cross-module references contribute
      ;; to the 4g Violation sequence.

      (invariant "CrossModuleReferencesAreVisible"
        "For every primitive whose :fields or :parameters reference a
         Composite-named type, when the referent lives in a different
         module than the referrer, the referent must be externally
         visible. Hidden cross-module references emit
         :4g/cross-module-private-reference (error)."
        (holds-that "cross-module-references-target-visible-primitives"))

      (invariant "ContractsAreAlwaysVisible"
        "Containers tagged Allium::Contract are externally visible across
         module walls regardless of their owning module's closure state.
         A cross-module reference to a Contract is always permitted."
        (holds-that "allium-contract-is-always-externally-visible"))

      (invariant "ExternalEntityCountsAsVisible"
        "Containers tagged Allium::ExternalEntity are externally visible
         regardless of their owning module's closure state. A cross-module
         reference to an ExternalEntity is always permitted."
        (holds-that "external-entity-counts-as-visible-for-cross-module"))

      (invariant "IntraModuleReferencesIgnored"
        "References whose referrer and target live in the same module are
         not checked. Only strictly cross-module references contribute to
         the 4g Violation sequence."
        (holds-that "intra-module-references-are-not-checked"))

      (invariant "CheckIsPure"
        "The 4g check function is a pure derivation from the input
         Model — it reads only :primitives and :tag-apps."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4g.boundary.
      (checker "check"
        "Walk every primitive's :fields and :parameters for Composite-named
         type references that cross module walls and emit a Violation for
         each that is not externally visible."))))
