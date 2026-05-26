(ns canvas.constraint.derivations
  "Canvas port of constraint/derivations.allium + derivations.boundary.

   Coverage:
     - value EDB              → construction/value (opaque; predicate→tuples map)
     - 3 invariants           → vocab.behavioral/invariant each
     - fn model_to_edb        → construction/function with cross-module type ref :model/Model
     - exports                → construction/exports macro

   Notes:
     - EDB is opaque — internal shape (map from predicate keyword to set of tuples) is
       withheld per design. The KernelUniversal invariant names the predicate catalogue.
     - Cross-module ref :model/Model auto-emits :references Relation."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "constraint.derivations"

      ;; Opaque value type from derivations.allium.
      (value "EDB"
        "A map from predicate keyword to a set of tuples. The Datalog evaluator looks up
         tuples by predicate and unifies positional arguments against tuple elements. The
         constraint subsystem's EDB shape and the agent subsystem's EDB shape are
         independent projections of the same Model — the predicate catalogues are not
         interchangeable.")

      ;; Invariants from derivations.allium.
      (invariant "KernelUniversal"
        "The projection emits exactly these predicates, with the indicated tuple shapes:
           :primitive       [id]
           :primitive-kind  [id kind]
           :has-tag         [id tag-string]
           :tag-payload     [id tag-string payload-map]
           :in-module       [id module-id]
           :edge            [from-id relation-kind to-id]
           :has-field       [id field-name]
           :has-label       [id label]
         The set is kernel-universal: any Model produces these and only these predicates
         at this layer. Additional predicates layer on top via derivations_extra or via
         per-project registrations."
        (holds-that "kernel-universal-predicate-set"))

      (invariant "ModuleDerivation"
        ":in-module is computed from primitives whose id has a module-id prefix followed
         by the '::' separator. A primitive is a module iff it carries the Allium::Module
         tag. The derivation walks every primitive id and pairs it with every
         prefix-matching module id."
        (holds-that "in-module-from-id-prefix"))

      (invariant "PureProjection"
        "model_to_edb is a pure function of the Model. It never reads state outside the
         supplied Model value, never mutates the Model, and is deterministic — the same
         Model yields tuple sets that compare equal across invocations."
        (holds-that "pure-function-of-model"))

      ;; Public function from derivations.boundary.
      (function "model_to_edb"
        "Project a Model into the kernel-universal EDB: a map keyed by predicate keyword
         to a set of tuples. The output covers exactly the predicate set listed in the
         KernelUniversal invariant."
        (takes [model :model/Model])
        (gives :derivations/EDB))

      ;; Exports closure from derivations.boundary.
      (exports EDB))))
