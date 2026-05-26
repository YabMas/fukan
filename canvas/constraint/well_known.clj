(ns canvas.constraint.well-known
  "Canvas port of constraint/well_known.allium + well_known.boundary.

   Coverage:
     - 7 invariants  → vocab.behavioral/invariant each
     - 5 functions   → construction/function each with cross-module type ref :model/PredicateRegistration

   Notes:
     - No exports: in well_known.boundary.
     - signal_gap and no_circular_refs are nullary (no args); no_dependency and
       naming_convention are parameterised.
     - Cross-module ref :model/PredicateRegistration auto-emits :references Relation."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "constraint.well-known"

      ;; Invariants from well_known.allium.
      (invariant "SignalGapSemantics"
        "signal_gap (warning) fires for every Event endpoint of a :relation/provides edge
         that has no outgoing :relation/triggers edge. A Surface that provides a signal
         must have at least one Rule consumer wired to the Event."
        (holds-that "signal-gap-semantics"))

      (invariant "ExternalMustHaveWrapperSemantics"
        "external_must_have_wrapper (warning) fires for every Container carrying the
         Allium::ExternalEntity tag that does not belong to any module under the
         :in-module derivation. Every external entity must live inside a wrapping module."
        (holds-that "external-must-have-wrapper-semantics"))

      (invariant "NoDependencySemantics"
        "no_dependency (error) is parameterised over (from_tag, to_tag). Fires for every
         pair of primitives where the from primitive carries from_tag, the to primitive
         carries to_tag, and from transitively depends on to via the :depends-on
         derivation. The two tags define a forbidden cross-tag dependency direction."
        (holds-that "no-dependency-semantics"))

      (invariant "NoCircularRefsSemantics"
        "no_circular_refs (error) fires for every primitive that transitively depends on
         itself via the :depends-on derivation — i.e. participates in a dependency cycle."
        (holds-that "no-circular-refs-semantics"))

      (invariant "NamingConventionSemantics"
        "naming_convention (warning) is parameterised over (kind, regex). Fires for every
         primitive of the given kind whose label does not match the supplied regex."
        (holds-that "naming-convention-semantics"))

      (invariant "FactoryPurity"
        "Each factory function returns a fresh PredicateRegistration map. The returned
         value depends only on the function arguments (or is constant for nullary
         factories). Calling a factory twice produces value-equal registrations."
        (holds-that "factory-functions-are-pure"))

      (invariant "IdempotentRegistration"
        "The well-known factories produce registrations with stable (namespace, name)
         tuples — fixed for nullary constraints, shared across calls with the same
         parameters for parameterised ones. The pipeline's defaults-registration step
         treats the (namespace, name) tuple as the registration key, skipping duplicates
         so repeat loads do not multiply violation counts."
        (holds-that "registration-key-is-namespace-name-tuple"))

      ;; Public functions from well_known.boundary.
      ;; All return :model/PredicateRegistration.

      (function "signal_gap"
        "Warning: every Surface→Event :relation/provides edge has at least one
         Event→Rule :relation/triggers consumer. Returns a PredicateRegistration."
        (takes [])
        (gives :model/PredicateRegistration))

      (function "external_must_have_wrapper"
        "Warning: every Container tagged Allium::ExternalEntity belongs to a known module
         under :in-module. Returns a PredicateRegistration."
        (takes [])
        (gives :model/PredicateRegistration))

      (function "no_dependency"
        "Error: Containers tagged from_tag must not have any transitive dependency on
         Containers tagged to_tag. Returns a parameterised PredicateRegistration."
        (takes [from_tag :String
                to_tag   :String])
        (gives :model/PredicateRegistration))

      (function "no_circular_refs"
        "Error: no primitive may transitively depend on itself. Returns a
         PredicateRegistration."
        (takes [])
        (gives :model/PredicateRegistration))

      (function "naming_convention"
        "Warning: every primitive of the given kind has a label matching the supplied
         regex. Returns a parameterised PredicateRegistration."
        (takes [kind  :Keyword
                regex :String])
        (gives :model/PredicateRegistration)))))
