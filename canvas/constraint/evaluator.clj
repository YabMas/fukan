(ns canvas.constraint.evaluator
  "Canvas port of constraint/evaluator.allium + evaluator.boundary.

   Phase 2 migration: escape hatches replaced by vocab lifts.

   Coverage:
     - value Stratum        → construction/value (opaque type; Phase 2 lift exists)
     - value Binding        → construction/value (opaque type)
     - fn evaluate_rules    → construction/function with cross-module type refs
     - fn query             → construction/function with cross-module type refs
     - 7 invariants         → vocab.behavioral/invariant each

   Notes:
     - Cross-module type refs use namespaced keywords: :ast/ConstraintRule,
       :derivations/EDB, :ast/ConstraintAtom. The function lift emits
       :references Relations automatically for these.
     - exports: Stratum Binding — using construction/exports macro."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "constraint.evaluator"

      ;; Opaque value types from evaluator.allium.
      ;; Phase 2 construction/value handles structureless opaque types.
      (value "Stratum"
        "A group of ConstraintRules whose head predicate can be evaluated
         together. Internal shape is withheld; evaluated in dependency order.")

      (value "Binding"
        "A substitution map from logic-variable Term to bound value.
         The evaluator threads sets of Bindings through a rule body.")

      ;; Invariants from evaluator.allium
      (invariant "StratifiedFixedPoint"
        "evaluate_rules partitions input rules into Strata such that negation /
         aggregation references resolve against already-settled predicates.
         Strata are evaluated in order; each stratum iterates to a naive fixed
         point until no new tuples are derived."
        (holds-that "stratified-fixed-point-semantics"))

      (invariant "StratificationSafeNegation"
        "A negated atom in a rule body references a predicate from a strictly
         earlier stratum (or the EDB). A rule set with a negation cycle is
         unstratifiable."
        (holds-that "no-negation-cycle-within-stratum"))

      (invariant "NaiveFixedPoint"
        "Within each stratum the evaluator runs full re-evaluation per
         iteration — no semi-naive optimisation. The fixed point is reached
         when one full pass adds no new tuples."
        (holds-that "naive-fixed-point-within-stratum"))

      (invariant "UnificationSemantics"
        "Atom evaluation unifies positional argument terms against EDB tuples.
         A variable matches anything but must agree across re-occurrences;
         a constant matches by equality. Tuple-arity mismatch rejects the row."
        (holds-that "positional-unification-semantics"))

      (invariant "AggregationSemantics"
        "An aggregation atom evaluates the inner body once per outer binding,
         collects input-var values, and applies the aggregation op (:count,
         :sum, :min, :max). :min/:max on empty input binds nil."
        (holds-that "aggregation-semantics-as-specified"))

      (invariant "ComparisonOperators"
        "Supported ops: :=, :!=, :<, :<=, :>, :>=, :matches-regex,
         :not-matches-regex. The evaluator substitutes terms under the current
         binding and keeps bindings for which the op returns truthy."
        (holds-that "comparison-operator-set"))

      (invariant "Determinism"
        "evaluate_rules is a pure function of (rules, edb). The same input
         pair always derives the same EDB. No external state participates."
        (holds-that "pure-function-of-rules-and-edb"))

      ;; Public functions from evaluator.boundary.
      ;; Cross-module types use namespaced keywords; :references Relations
      ;; are emitted automatically by the function lift.
      ;;
      ;; evaluate_rules(rules: List<ast.ConstraintRule>,
      ;;                edb: derivations.EDB) -> derivations.EDB
      (function "evaluate_rules"
        "Stratify the rules, evaluate each stratum to a naive fixed point,
         and return the derived EDB extending the seed EDB."
        (takes [rules :ast/ConstraintRule
                edb   :derivations/EDB])
        (gives :derivations/EDB))

      ;; query(rules: List<ast.ConstraintRule>, edb: derivations.EDB,
      ;;       query_atom: ast.ConstraintAtom) -> Set<Binding>
      (function "query"
        "Run evaluate_rules then unify the query atom against the resulting
         predicate tuples. Returns variable bindings under which the atom holds."
        (takes [rules      :ast/ConstraintRule
                edb        :derivations/EDB
                query_atom :ast/ConstraintAtom])
        (gives :Binding))

      ;; Exports closure from evaluator.boundary
      (exports Stratum Binding))))
