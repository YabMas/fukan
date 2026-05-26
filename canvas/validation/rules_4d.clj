(ns canvas.validation.rules-4d
  "Canvas port of validation/rules_4d.allium + rules_4d.boundary.

   Scope: Phase 4d — module-visibility rules. Structural rules over the
   Boundary::ModuleApi tag application that flips an Allium-open module
   closed.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 4 invariants → vocab.behavioral/invariant each

   Notes:
     - rule AtMostOneModuleApiTag, ExportsResolve, ExportsDisallowKinds,
       CheckIsPure: no rule lift (deferred). Left as TODO comments below."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4d"

      ;; TODO: rule AtMostOneModuleApiTag — no rule lift (deferred).
      ;; Structural intent: a module-Container carries at most one
      ;; Boundary::ModuleApi tag. Violation kind :4d/multiple-module-api-tags (error).

      ;; TODO: rule ExportsResolve — no rule lift (deferred).
      ;; Structural intent: every entry in a closed module's :exported list
      ;; resolves to a primitive owned by that module. Violation kind
      ;; :4d/exports-unresolved (error).

      ;; TODO: rule ExportsDisallowKinds — no rule lift (deferred).
      ;; Structural intent: Rules, Invariants, and Contracts are not
      ;; individually exportable. Violation kind :4d/exports-disallowed-kind (error).

      (invariant "AtMostOneModuleApiTag"
        "A module-Container carries at most one Boundary::ModuleApi tag
         application. Multiple applications emit
         :4d/multiple-module-api-tags (error), carrying the module id
         and the tag-application count."
        (holds-that "module-has-at-most-one-module-api-tag"))

      (invariant "ExportsResolve"
        "Every entry in a closed module's :exported list resolves to a
         primitive owned by that module. Unresolved entries emit
         :4d/exports-unresolved (error)."
        (holds-that "exports-entries-resolve-to-owned-primitives"))

      (invariant "ExportsDisallowKinds"
        "Rules, Invariants, and Contracts are not individually exportable.
         An :exported entry resolving to a primitive of any of these kinds
         emits :4d/exports-disallowed-kind (error)."
        (holds-that "exports-disallow-rule-invariant-contract-kinds"))

      (invariant "CheckIsPure"
        "The 4d check function is a pure derivation from the input
         Model — it reads only :primitives and :tag-apps."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4d.boundary.
      (checker "check"
        "Run all 4d module-visibility rules against the Model and return
         the aggregated Violation sequence."))))
