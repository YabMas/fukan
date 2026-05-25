(ns fukan.canvas.pilot.constraint-evaluator
  "Canvas port of constraint/evaluator.allium + evaluator.boundary.

   Slice chosen: evaluator (the stratified bottom-up Datalog engine).
   Why: richest behavioral module — 7 named invariants, 2 opaque value
   types, 2 cross-module-typed functions. Maximum stress on lift library.

   Coverage:
     - value Stratum        → bare h/declare-state (Gap 4: record requires >= 1 field)
     - value Binding        → bare h/declare-state (same gap)
     - fn evaluate_rules    → (function ...) with approximated type refs
     - fn query             → (function ...) with approximated type refs

   Gaps (see doc/plans/2026-05-25-pilot-port-findings.md):
     - Stratum, Binding are opaque value types — record lift (:required true on field)
       forces at least one field. No value/opaque-record lift exists. Gap 4.
     - evaluate_rules and query take cross-module-typed params (ast.ConstraintRule,
       derivations.EDB) — function lift takes only keyword type refs. Gap 5.
     - Seven invariants have no lift equivalent. Gap 6.
     - ComparisonOperators / AggregationSemantics carry enumerated op sets
       with no canvas expression. Gap 7.
     - exports: Stratum Binding — no closure mechanism. Gap 2."
  (:require [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :refer [function]]))

;; NOTE: Stratum and Binding are intentionally structureless opaque types
;; in the spec — their internal shape is withheld. The record lift requires
;; at least one (field ...) form (Gap 4), so we fall back to bare
;; h/declare-affordance to put named markers in the module. This is
;; substrate-level escape; the gap is documented.

(defn build-canvas []
  (h/with-canvas
    (h/within-module "constraint.evaluator"
      ;; Opaque value types — escaped to bare substrate because record
      ;; lift requires >= 1 field. Gap 4.
      (h/declare-affordance "Stratum"
        :role :fukan.canvas.monolith/value-type)
      (h/declare-affordance "Binding"
        :role :fukan.canvas.monolith/value-type)

      ;; Public functions from evaluator.boundary.
      ;;
      ;; Parameter types are cross-module qualified (ast.ConstraintRule,
      ;; derivations.EDB) — approximated as opaque local keywords. Gap 5.
      ;;
      ;; evaluate_rules(rules: List<ast.ConstraintRule>, edb: derivations.EDB)
      ;; -> derivations.EDB
      (function "evaluate_rules"
        "Stratify the rules, evaluate each stratum to a naive fixed point,
         and return the derived EDB extending the seed EDB."
        (takes [rules :ConstraintRule-list
                edb   :EDB])
        (gives :EDB))

      ;; query(rules: List<ast.ConstraintRule>, edb: derivations.EDB,
      ;;       query_atom: ast.ConstraintAtom) -> Set<Binding>
      (function "query"
        "Run evaluate_rules then unify the query atom against the resulting
         predicate tuples. Returns variable bindings under which the atom holds."
        (takes [rules      :ConstraintRule-list
                edb        :EDB
                query_atom :ConstraintAtom])
        (gives :BindingSet)))))
