(ns canvas.model.expression
  "Canvas port of model/expression.allium + expression.boundary.

   Coverage:
     - 3 invariants from expression.allium → vocab.behavioral/invariant each:
         LabelIsAddressabilityOnly, AggregateKindIsClosed,
         EnvironmentIsCallsiteTyped
     - fn make_var                          → construction/function
     - fn make_ref                          → construction/function
     - fn make_lit                          → construction/function
     - fn make_apply                        → construction/function
     - fn make_let                          → construction/function
     - fn make_if                           → construction/function
     - fn make_forall                       → construction/function
     - fn make_exists                       → construction/function
     - fn make_aggregate                    → construction/function
     - fn make_match_arm                    → construction/function
     - fn make_match                        → construction/function
     - fn expression_identity               → construction/function
     - fn make_environment_onestate         → construction/function
     - fn make_environment_twostate         → construction/function
     - fn make_environment_model_introspection → construction/function

   Notes:
     - Expression / ExpressionForm value types and Environment sum live in
       canvas.model.spec.
     - Core operators: arithmetic (+,-,*,/), comparison (=,!=,<,<=,>,>=),
       logical (and,or,not), set membership (in,contains), presence
       (is-present,is-absent). Methodology operators register additively
       in Plan 4.
     - Constraint-language semantics are deferred (Plan 4)."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.expression"

      ;; Invariants from expression.allium

      (invariant "LabelIsAddressabilityOnly"
        "expression_identity strips :label recursively from the form tree
         before comparison. Two Expressions differing only in :label have
         equal identity (parallels Clause's ClausesAreFirstClass treatment of
         label per K15a)."
        (holds-that "expression-identity-strips-label-before-comparison"))

      (invariant "AggregateKindIsClosed"
        "make_aggregate raises when kind is not one of count | sum | min | max.
         The substrate commits to this closed set of aggregation shapes;
         methodology aggregates do not extend it."
        (holds-that "make-aggregate-raises-on-unknown-kind"))

      (invariant "EnvironmentIsCallsiteTyped"
        "Per spec.EnvironmentIsCallsiteTyped: Environment is part of the
         callsite type, not of the Expression. The Environment constructors
         produce typed binding contexts (OneState, TwoState,
         ModelIntrospection) consumed by the type-checker; Expression values
         themselves carry no environment slot."
        (holds-that "environment-is-callsite-type-not-expression-slot"))

      ;; ExpressionForm constructors from expression.boundary

      (function "make_var"
        "VarForm: reference to an Environment binding. Optional first argument
         carries a label."
        (takes [name (optional :String)])
        (gives :Any))

      (function "make_ref"
        "RefForm: reference to a kernel target (per Type.Ref shape). Optional
         first argument carries a label."
        (takes [ref_target :Any])
        (gives :Any))

      (function "make_lit"
        "LitForm: typed literal. Optional first argument carries a label."
        (takes [type_value :Any
                value      :Any])
        (gives :Any))

      (function "make_apply"
        "ApplyForm: operator / function application. Core operators in
         core_operators; methodology operators register additively."
        (takes [op   :String
                args (list-of :Any)])
        (gives :Any))

      (function "make_let"
        "LetForm: local binding within an Expression. Optional first argument
         carries a label."
        (takes [name   :String
                source :Any
                body   :Any])
        (gives :Any))

      (function "make_if"
        "IfForm. Optional first argument carries a label."
        (takes [cond :Any
                then :Any
                else :Any])
        (gives :Any))

      (function "make_forall"
        "ForallForm: universal quantification over source, binding var in body."
        (takes [var_name :String
                source   :Any
                body     :Any])
        (gives :Any))

      (function "make_exists"
        "ExistsForm: existential quantification."
        (takes [var_name :String
                source   :Any
                body     :Any])
        (gives :Any))

      (function "make_aggregate"
        "AggregateForm: aggregation over source, projecting via projection.
         kind ∈ count | sum | min | max."
        (takes [kind       :String
                source     :Any
                projection :Any])
        (gives :Any))

      (function "make_match_arm"
        "MatchArm value: { pattern: TypePattern, body: Expression }."
        (takes [pattern :Any
                body    :Any])
        (gives :Any))

      (function "make_match"
        "MatchForm: pattern-match on a scrutinee."
        (takes [scrutinee :Any
                arms      (list-of :Any)])
        (gives :Any))

      ;; Structural identity

      (function "expression_identity"
        "Structural identity over :form, recursively stripped of :label. Two
         Expressions with equal identity are the same Expression value
         (parallels Clause's label per K15a — label is addressability only,
         never identity)."
        (takes [expression :Any])
        (gives :Any))

      ;; Environment constructors

      (function "make_environment_onestate"
        "OneState Environment for Container / Behaviour / Boundary / Operation
         Intent assertions."
        (takes [bindings (map-of :String :Any)])
        (gives :Any))

      (function "make_environment_twostate"
        "TwoState Environment for Rule.intent.assertions and Rule.body."
        (takes [pre    (map-of :String :Any)
                post   (map-of :String :Any)
                params (map-of :String :Any)])
        (gives :Any))

      (function "make_environment_model_introspection"
        "ModelIntrospection Environment for constraint-language predicate
         bodies."
        (takes [bindings (map-of :String :Any)])
        (gives :Any)))))
