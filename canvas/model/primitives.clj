(ns canvas.model.primitives
  "Canvas port of model/primitives.allium + primitives.boundary.

   Coverage:
     - 3 invariants from primitives.allium → vocab.behavioral/invariant each:
         ConstructorsProduceSubstrate, KindIsAttached, IdentityIsDeterministic
     - Sub-substrate value record constructors:
         make_field, field_identity, make_parameter, parameter_identity,
         make_definition, definition_identity, make_rule_body
     - Kernel primitive constructors:
         make_container, make_actor, make_behaviour, make_rule,
         make_boundary, make_operation, make_intent, make_clause, make_event

   Notes:
     - The substrate value types themselves (Field, Parameter, Definition,
       RuleBody, and the nine kernel primitives) live in canvas.model.spec.
     - Validation of referenced ids is an analyzer / pipeline concern.
     - Each constructor attaches a :kind discriminator from PrimitiveKind
       (spec.PrimitiveKind)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.primitives"

      ;; Invariants from primitives.allium

      (invariant "ConstructorsProduceSubstrate"
        "Each constructor produces a value matching its corresponding substrate
         spec in model/spec.allium: make_container -> Container,
         make_actor -> Actor, make_behaviour -> Behaviour, make_rule -> Rule,
         make_boundary -> Boundary, make_operation -> Operation,
         make_intent -> Intent, make_clause -> Clause, make_event -> Event,
         make_field -> Field, make_parameter -> Parameter,
         make_definition -> Definition, make_rule_body -> RuleBody."
        (holds-that "constructors-produce-matching-substrate-type"))

      (invariant "KindIsAttached"
        "Every primitive constructor attaches a :kind discriminator drawn from
         the PrimitiveKind enum (spec.PrimitiveKind). The kind is the dispatch
         key for the closed Primitive sum."
        (holds-that "constructors-attach-kind-discriminator"))

      (invariant "IdentityIsDeterministic"
        "The identity helpers (field_identity, parameter_identity,
         definition_identity) are pure functions of their inputs and return the
         (host_id, name) tuple committed by §3.2. Calling the helper twice with
         equal inputs returns equal tuples."
        (holds-that "identity-helpers-are-pure-of-inputs"))

      ;; Sub-substrate value record constructors from primitives.boundary

      (function "make_field"
        "Field substrate: typed data member owned by a Container."
        (takes [name       :String
                type_value :Any
                optional   :Boolean])
        (gives :Any))

      (function "field_identity"
        "Returns (container_id, field.name) per §3.2."
        (takes [container_id :String
                field        :Any])
        (gives :Any))

      (function "make_parameter"
        "Parameter substrate: ordered typed parameter on Operation or Event."
        (takes [name       :String
                type_value :Any
                optional   :Boolean
                ordinal    :Integer])
        (gives :Any))

      (function "parameter_identity"
        "Returns (parent_id, parameter.name) per §3.2."
        (takes [parent_id :String
                parameter :Any])
        (gives :Any))

      (function "make_definition"
        "Definition substrate: typed local binding inside Rule.body."
        (takes [name       :String
                expression :Any])
        (gives :Any))

      (function "definition_identity"
        "Returns (rule_id, definition.name) per §3.2."
        (takes [rule_id    :String
                definition :Any])
        (gives :Any))

      (function "make_rule_body"
        "RuleBody bundles definitions + effects. No independent identity."
        (takes [definitions (list-of :Any)
                effects     (list-of :Any)])
        (gives :Any))

      ;; Kernel primitive constructors

      (function "make_container"
        "Container with optional faces (intent, children, fields, events,
         behaviour, boundary). Required: id, label."
        (takes [spec :Any])
        (gives :Any))

      (function "make_actor"
        "Actor primitive. Required: id, label. Optional: description."
        (takes [spec :Any])
        (gives :Any))

      (function "make_behaviour"
        "Behaviour aggregate. Required: id, label, rules. Optional: intent."
        (takes [spec :Any])
        (gives :Any))

      (function "make_rule"
        "Rule. Required: id, label. Optional: description, intent, body."
        (takes [spec :Any])
        (gives :Any))

      (function "make_boundary"
        "Boundary aggregate. Required: id, label, operations. Optional: intent."
        (takes [spec :Any])
        (gives :Any))

      (function "make_operation"
        "Operation. Required: id, label, parameters. Optional: description,
         return_type, intent."
        (takes [spec :Any])
        (gives :Any))

      (function "make_intent"
        "Intent aggregate. Required: id, clauses, assertions. Optional: label."
        (takes [spec :Any])
        (gives :Any))

      (function "make_clause"
        "Clause. Required: id, body. Optional: label."
        (takes [spec :Any])
        (gives :Any))

      (function "make_event"
        "Event. Required: id, label, parameters. Optional: description."
        (takes [spec :Any])
        (gives :Any)))))
