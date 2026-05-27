(ns canvas.validation.rules-4e
  "Canvas port of validation/rules_4e.allium + rules_4e.boundary.

   Scope: Phase 4e — subsystem-visibility rules. Two structural rules
   over the Boundary::Exports tag application on a composite Container.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 2 rules   → vocab.behavioral/rule each
     - 3 invariants → vocab.behavioral/invariant each"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4e"

      (rule "SubsystemExportsAliasResolves"
        "Check that every alias.Item entry in a composite's :exported list
         names a directly-contained child. Aliases that do not name a direct
         child emit :4e/subsystem-exports-unresolved (error)."
        (when SubsystemExportsAliasResolves (model :model/Model)))

      (rule "SubsystemExportsRespectClosedModule"
        "Check that a composite cannot re-export alias.Item from a closed
         child module unless Item is in that child's own :exported list.
         Violations emit :4e/subsystem-exports-private (error)."
        (when SubsystemExportsRespectClosedModule (model :model/Model)))

      (invariant "SubsystemExportsAliasResolves"
        "Every entry of the form `alias.Item` (or bare `alias`) in a
         composite's :exported list names a directly-contained child.
         Aliases that do not name a direct child emit
         :4e/subsystem-exports-unresolved (error)."
        (holds-that "subsystem-exports-alias-names-direct-child"))

      (invariant "SubsystemExportsRespectClosedModule"
        "When a composite re-exports `alias.Item` from a closed child
         module, the child module's own :exported list must contain Item.
         A subsystem cannot widen a closed module's surface. Violations
         emit :4e/subsystem-exports-private (error)."
        (holds-that "subsystem-cannot-widen-closed-child-module-surface"))

      (invariant "CheckIsPure"
        "The 4e check function is a pure derivation from the input
         Model — it reads only :primitives and :tag-apps."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4e.boundary.
      (checker "check"
        "Run all 4e subsystem-visibility rules against the Model and
         return the aggregated Violation sequence."))))
