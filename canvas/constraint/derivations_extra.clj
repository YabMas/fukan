(ns canvas.constraint.derivations-extra
  "Canvas port of constraint/derivations_extra.allium + derivations_extra.boundary.

   Coverage:
     - 3 invariants           → vocab.behavioral/invariant each
     - fn depends_on_rules    → construction/function with cross-module type ref :ast/ConstraintRule

   Notes:
     - No exports: in derivations_extra.boundary.
     - depends_on_rules() takes no arguments — nullary function.
     - Cross-module ref :ast/ConstraintRule auto-emits :references Relation."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "constraint.derivations-extra"

      ;; Invariants from derivations_extra.allium.
      (invariant "DependsOnSemantics"
        ":depends-on is the transitive closure of :edge over its from/to endpoints,
         ignoring the relation kind. Two rules generate the relation:
           depends-on(?x, ?y) :- edge(?x, ?_rel, ?y).
           depends-on(?x, ?z) :- edge(?x, ?_rel, ?y), depends-on(?y, ?z).
         A primitive transitively depends on itself iff it participates in a dependency cycle."
        (holds-that "depends-on-transitive-closure-of-edge"))

      (invariant "AppendsToBaseEDB"
        "depends_on_rules returns ConstraintRules to be evaluated alongside the
         kernel-universal EDB; it never seeds tuples directly. Composition rule: the
         caller threads the returned rules into the evaluator together with any
         registration-level rules."
        (holds-that "rules-append-to-base-edb"))

      (invariant "PureDerivationLayer"
        "The derivation-rule factories take no Model and no EDB. They return the same
         ConstraintRules across invocations and never mutate state."
        (holds-that "pure-stateless-rule-factories"))

      ;; Public function from derivations_extra.boundary.
      (function "depends_on_rules"
        "Return the two ConstraintRules defining :depends-on as the transitive closure
         of :edge over its endpoints."
        (takes [])
        (gives (list-of :ast/ConstraintRule))))))
