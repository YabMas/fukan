(ns canvas.validation.rules-4c
  "Canvas port of validation/rules_4c.allium + rules_4c.boundary.

   Scope: Phase 4c — binding rules. Five structural rules over the
   bindings that wire Operations on Boundaries to Rules and Operations
   on Intents.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 6 invariants → vocab.behavioral/invariant each

   Notes:
     - rule BindingOperationResolves, BindingTriggerRuleResolves,
       AttachReturnsRequiresTriggers, ReturnDerivationMatchesOperation,
       SignatureMatchVerifiable, CheckIsPure: no rule lift (deferred).
       Left as TODO comments below."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4c"

      ;; TODO: rule BindingOperationResolves — no rule lift (deferred).
      ;; Structural intent: every fn binding references an Operation whose
      ;; id resolves in the Model. Violation kind :4c/unresolved-operation (error).

      ;; TODO: rule BindingTriggerRuleResolves — no rule lift (deferred).
      ;; Structural intent: every fn body triggers: clause references a Rule
      ;; whose id resolves in the Model. Violation kind
      ;; :4c/unresolved-trigger-rule (error).

      ;; TODO: rule AttachReturnsRequiresTriggers — no rule lift (deferred).
      ;; Structural intent: an attach-form fn with returns: must also have
      ;; triggers:. Violation kind :4c/attach-returns-without-triggers (warning).

      ;; TODO: rule ReturnDerivationMatchesOperation — no rule lift (deferred).
      ;; Structural intent: return-type presence on Operation matches
      ;; returns_expression presence on Boundary::Binding tag. Violation kind
      ;; :4c/return-derivation-mismatch (error).

      ;; TODO: rule SignatureMatchVerifiable — no rule lift (deferred).
      ;; Structural intent: Operation→Rule bindings where the Rule has no
      ;; other triggers and the Operation has parameters emit
      ;; :4c/signature-match-uncertain (warning).

      (invariant "BindingOperationResolves"
        "Every fn binding of the form `fn Contract.op` or
         `fn alias/Contract.op` references an Operation primitive whose
         id resolves in the Model. Unresolved references emit
         :4c/unresolved-operation (error)."
        (holds-that "binding-operation-reference-resolves-in-model"))

      (invariant "BindingTriggerRuleResolves"
        "Every fn body triggers: clause references a Rule primitive
         whose id resolves in the Model. Unresolved references emit
         :4c/unresolved-trigger-rule (error)."
        (holds-that "binding-trigger-rule-reference-resolves-in-model"))

      (invariant "AttachReturnsRequiresTriggers"
        "An attach-form fn that declares returns: must also declare
         triggers: — without a triggers clause there is no edge to
         carry the returns expression's tag. Emits
         :4c/attach-returns-without-triggers (warning)."
        (holds-that "attach-returns-requires-triggers-clause"))

      (invariant "ReturnDerivationMatchesOperation"
        "For every :relation/triggers edge from an Operation, the
         Operation has a :return-type iff the Boundary::Binding tag
         carries a returns_expression payload. Mismatch emits
         :4c/return-derivation-mismatch (error)."
        (holds-that "return-type-presence-matches-returns-expression"))

      (invariant "SignatureMatchVerifiable"
        "An Operation→Rule binding whose target Rule has no other
         :relation/triggers edges cannot be verified for parameter-
         signature equality at the current fidelity. Such bindings
         emit :4c/signature-match-uncertain (warning) when the
         Operation has at least one parameter."
        (holds-that "single-trigger-binding-emits-signature-uncertain"))

      (invariant "CheckIsPure"
        "The 4c check function is a pure derivation from the input
         Model — it reads only :primitives, :edges, :tag-apps, and
         :phase4-state."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4c.boundary.
      (checker "check"
        "Run all five 4c binding rules against the Model and return
         the aggregated Violation sequence."))))
