(ns canvas.validation.rules-4f
  "Canvas port of validation/rules_4f.allium + rules_4f.boundary.

   Scope: Phase 4f — export-closure rule. A closed module's :exported list
   must be self-coherent: every type referenced from an exported item's
   signature must itself be externally reachable.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 4 rules   → vocab.behavioral/rule each
     - 5 invariants → vocab.behavioral/invariant each"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4f"

      (rule "ExportedSignaturesAreClosed"
        "Check that for every entry in a closed module's :exported list,
         every Composite-named type referenced from the entry's fields
         (Entity) or parameters and return type (Operation) is itself
         externally visible. Missing references emit
         :4f/closure-violation (error)."
        (when ExportedSignaturesAreClosed (model :model/Model)))

      (rule "VisibilityFromOpenModules"
        "Define the visibility set used to evaluate closure: every primitive
         owned by an open module (a module-Container with no
         Boundary::ModuleApi tag) is unconditionally visible."
        (when VisibilityFromOpenModules (model :model/Model)))

      (rule "ExternalEntityCountsAsVisible"
        "Define that a Container tagged Allium::ExternalEntity is externally
         visible regardless of its owning module's closure state. An exported
         signature referencing such a Container does not emit a closure
         violation."
        (when ExternalEntityCountsAsVisible (model :model/Model)))

      (rule "ClosureScopeIsMVP"
        "Define current closure scope: walks cover Entity fields and
         Operation signatures (parameters plus return type). Surface,
         Variant, and Actor closures are deferred until the corpus surfaces
         them as load-bearing."
        (when ClosureScopeIsMVP (model :model/Model)))

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
