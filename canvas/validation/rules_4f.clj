(ns canvas.validation.rules-4f
  "Canvas port of validation/rules_4f.allium + rules_4f.boundary.

   Scope: Phase 4f — export-closure rule. A closed module's :exported list
   must be self-coherent: every type referenced from an exported item's
   signature must itself be externally reachable.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 5 invariants → vocab.behavioral/invariant each

   Notes:
     - rule ExportedSignaturesAreClosed, VisibilityFromOpenModules,
       ExternalEntityCountsAsVisible, ClosureScopeIsMVP, CheckIsPure:
       no rule lift (deferred). Left as TODO comments below."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4f"

      ;; TODO: rule ExportedSignaturesAreClosed — no rule lift (deferred).
      ;; Structural intent: every Composite-named type referenced from an
      ;; exported item's fields or signatures is itself externally visible.
      ;; Violation kind :4f/closure-violation (error).

      ;; TODO: rule VisibilityFromOpenModules — no rule lift (deferred).
      ;; Structural intent: primitives owned by open modules are unconditionally
      ;; visible when evaluating closure.

      ;; TODO: rule ExternalEntityCountsAsVisible — no rule lift (deferred).
      ;; Structural intent: Allium::ExternalEntity tagged Containers are
      ;; visible regardless of owning module closure state.

      ;; TODO: rule ClosureScopeIsMVP — no rule lift (deferred).
      ;; Structural intent: current closure walks cover Entity fields and
      ;; Operation signatures; Surface/Variant/Actor closures are deferred.

      (invariant "ExportedSignaturesAreClosed"
        "For every entry in a closed module's :exported list, every
         Composite-named type referenced from the entry's fields (Entity)
         or parameters and return type (Operation) is itself externally
         visible. Missing references emit :4f/closure-violation (error)."
        (holds-that "exported-signatures-only-reference-visible-types"))

      (invariant "VisibilityFromOpenModules"
        "The visibility set used to evaluate closure includes every
         primitive owned by an open module (a module-Container with
         no Boundary::ModuleApi tag). Open modules expose all
         top-level declarations by default."
        (holds-that "open-module-primitives-are-unconditionally-visible"))

      (invariant "ExternalEntityCountsAsVisible"
        "A Container tagged Allium::ExternalEntity is externally
         visible regardless of its owning module's closure state. An
         exported signature referencing such a Container does not emit
         a closure violation."
        (holds-that "external-entity-counts-as-visible-in-closure"))

      (invariant "ClosureScopeIsMVP"
        "Current closure walks cover Entity fields and Operation
         signatures (parameters plus return type). Surface, Variant,
         and Actor closures will be added as the corpus surfaces them
         as load-bearing."
        (holds-that "closure-scope-covers-entity-and-operation-only"))

      (invariant "CheckIsPure"
        "The 4f check function is a pure derivation from the input
         Model — it reads only :primitives and :tag-apps."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4f.boundary.
      (checker "check"
        "Run the 4f export-closure rule across every closed module in
         the Model and return the aggregated Violation sequence."))))
