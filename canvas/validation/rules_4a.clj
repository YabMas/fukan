(ns canvas.validation.rules-4a
  "Canvas port of validation/rules_4a.allium + rules_4a.boundary.

   Scope: Phase 4a — composition rules. Five structural rules a merged
   Model must satisfy regarding subsystem composition.

   Coverage:
     - fn check  → vocab.validation/checker (Model) -> [Violation]
     - 5 rules   → vocab.behavioral/rule each
     - 6 invariants → vocab.behavioral/invariant each"
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]
            [fukan.canvas.vocab.validation :refer [checker]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "validation.rules-4a"

      (rule "AtMostOneCompositeParent"
        "Check that every module-Container belongs to at most one composite
         parent. Emits :4a/multiple-composite-parents (error) for each module
         appearing in two or more composites' :children sets."
        (when AtMostOneCompositeParent (model :model/Model)))

      (rule "TopLevelModulesAreWarning"
        "Check that every module-Container that no composite contains is
         flagged as top-level. Emits :4a/top-level-module (warning) per
         such module. Top-level is permitted but advisory."
        (when TopLevelModulesAreWarning (model :model/Model)))

      (rule "NoSubsystemCompositionCycles"
        "Check that the composition graph induced by composites' :children
         sets is acyclic. Emits :4a/subsystem-cycle (error) per cycle,
         carrying the cyclic path in the Violation's :location."
        (when NoSubsystemCompositionCycles (model :model/Model)))

      (rule "ContainsPathsResolve"
        "Check that every entry in a composite's :children set names a known
         module-Container or a known composite. Unknown entries emit
         :4a/unresolved-contains (error)."
        (when ContainsPathsResolve (model :model/Model)))

      (rule "SubsystemNamesAreUnique"
        "Check that composites' :label fields are unique across the Model.
         Duplicates emit :4a/duplicate-subsystem-name (error)."
        (when SubsystemNamesAreUnique (model :model/Model)))

      (invariant "AtMostOneCompositeParent"
        "A module-Container belongs to at most one composite parent. A
         Violation of kind :4a/multiple-composite-parents (error) is
         emitted for every module appearing in two or more composites'
         :children sets."
        (holds-that "module-has-at-most-one-composite-parent"))

      (invariant "TopLevelModulesAreWarning"
        "A module-Container that no composite contains is top-level. A
         Violation of kind :4a/top-level-module (warning) is emitted per
         such module. Top-level is permitted but advisory."
        (holds-that "top-level-module-emits-warning"))

      (invariant "NoSubsystemCompositionCycles"
        "The composition graph induced by composites' :children sets is
         acyclic. A Violation of kind :4a/subsystem-cycle (error) is
         emitted per cycle, carrying the cyclic path in its :location."
        (holds-that "subsystem-composition-graph-is-acyclic"))

      (invariant "ContainsPathsResolve"
        "Every entry in a composite's :children set names a known
         module-Container or a known composite. Unknown entries emit
         :4a/unresolved-contains (error)."
        (holds-that "contains-entries-resolve-to-known-containers"))

      (invariant "SubsystemNamesAreUnique"
        "Composites' :label fields partition the composite population —
         every composite has a unique name. Duplicates emit
         :4a/duplicate-subsystem-name (error)."
        (holds-that "subsystem-labels-are-unique"))

      (invariant "CheckIsPure"
        "The 4a check function is a pure derivation from the input
         Model — it reads only :primitives and :tag-apps, performs no
         I/O, and returns a value-equal Violation sequence on
         value-equal inputs."
        (holds-that "check-is-a-pure-function-of-model"))

      ;; Public entry point from rules_4a.boundary.
      ;; checker bakes in (Model) -> [Violation].
      (checker "check"
        "Run all five 4a composition rules against the Model and return
         the aggregated Violation sequence. Returns the empty sequence
         when the Model satisfies all five composition invariants."))))
