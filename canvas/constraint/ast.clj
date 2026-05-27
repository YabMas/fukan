(ns canvas.constraint.ast
  "Canvas port of constraint/ast.allium + ast.boundary.

   Coverage:
     - value Term            → construction/value (opaque; variable/constant distinction is runtime)
     - value ConstraintAtom  → construction/value (opaque; fields kind/predicate/args)
     - value Negation        → construction/value (opaque; fields kind/inner)
     - value Comparison      → construction/value (opaque; fields kind/op/left/right)
     - value Aggregation     → construction/value (opaque; fields kind/op/var/body/result)
     - value ConstraintRule  → construction/value (opaque; fields head/body)
     - 2 invariants          → vocab.behavioral/invariant each
     - 9 functions           → construction/function each
     - exports               → construction/exports macro

   Notes:
     - All value types are opaque (no field lifts) — AST is plain data per PlainData invariant.
     - Bool-returning predicate functions (is_var, is_constant) use plain function with (gives :Bool).
     - Cross-module type refs use namespaced keywords: :ast/ConstraintAtom, :ast/ConstraintRule."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function value exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "constraint.ast"

      ;; Opaque value types from ast.allium.
      ;; All are plain-data shapes — no field declarations in the canvas.

      (value "Term"
        "A constraint-language term. Either a logic variable (a keyword whose name
         starts with '?') or a constant (any other value matched against EDB tuples
         by equality). The variable/constant distinction lives in a runtime predicate,
         not in the data shape.")

      (value "ConstraintAtom"
        "A positive Datalog atom: one predicate applied to a positional argument list.
         Each argument is a Term — variables introduce bindings, constants narrow tuples
         by equality. The :kind discriminator tags this as the positive case among
         ConstraintAtom / Negation / Comparison / Aggregation body elements.")

      (value "Negation"
        "Negation-as-failure over an inner ConstraintAtom. The rule body succeeds for a
         binding when no EDB tuple unifies with the inner atom under that binding.
         Stratification places the inner predicate in a strictly earlier stratum.")

      (value "Comparison"
        "A built-in binary comparison atom. The evaluator applies op to the substituted
         left and right terms and retains bindings for which the comparison holds.
         Supported ops: := :!= :< :<= :> :>= :matches-regex :not-matches-regex.")

      (value "Aggregation"
        "An aggregation atom: evaluate the inner body for each current binding, collect
         values of the input variable across the resulting inner bindings, apply the
         aggregation op, and bind the resulting scalar to the result variable.
         Supported ops: :count :sum :min :max.")

      (value "ConstraintRule"
        "A Datalog rule: head atom plus a body of atoms (positive, negative, comparison,
         or aggregation). The rule asserts that the head tuple holds for every variable
         binding that satisfies every body atom. Multiple rules with the same head
         predicate union their derived tuples.")

      ;; Invariants from ast.allium.
      (invariant "PlainData"
        "The AST is plain inert data. Constructors only assemble maps; they perform no
         validation, no normalisation, and no inter-node linking. Two AST values that
         print equal evaluate identically."
        (holds-that "plain-inert-data"))

      (invariant "VariableConvention"
        "A term is a logic variable iff it is a keyword whose name starts with the '?'
         character. Every other value is a constant. This convention is enforced
         uniformly across constructors, variable-collection helpers, unification, and
         substitution."
        (holds-that "variable-iff-keyword-starts-with-question"))

      ;; Public functions from ast.boundary.
      ;; Bool-returning functions use plain function with (gives :Bool).

      (function "is_var"
        "True iff x is a logic variable (keyword whose name starts with '?')."
        (takes [x :Any])
        (gives :Bool))

      (function "is_constant"
        "True iff x is a constant term (anything that is not a variable)."
        (takes [x :Any])
        (gives :Bool))

      (function "make_atom"
        "Construct a positive ConstraintAtom from a predicate and an argument list."
        (takes [predicate :Keyword
                args      (list-of :ast/Term)])
        (gives :ast/ConstraintAtom))

      (function "make_negation"
        "Construct a Negation wrapping the inner positive atom."
        (takes [inner :ast/ConstraintAtom])
        (gives :ast/Negation))

      (function "make_comparison"
        "Construct a Comparison binding the supplied built-in op to its left and right terms."
        (takes [op    :Keyword
                left  :ast/Term
                right :ast/Term])
        (gives :ast/Comparison))

      (function "make_aggregation"
        "Construct an Aggregation: op + input var + inner body + result var. The evaluator
         iterates the body per outer binding, folds the input-var values with op, and
         binds the scalar to result_var."
        (takes [op         :Keyword
                input_var  :ast/Term
                body       (list-of :ast/ConstraintAtom)
                result_var :ast/Term])
        (gives :ast/Aggregation))

      (function "make_rule"
        "Construct a ConstraintRule from a head atom and a body of body elements
         (any of ConstraintAtom, Negation, Comparison, Aggregation)."
        (takes [head :ast/ConstraintAtom
                body (list-of :Any)])
        (gives :ast/ConstraintRule))

      (function "vars_in_term"
        "Collect the variables appearing in a term — at most one element."
        (takes [t :ast/Term])
        (gives (set-of :ast/Term)))

      (function "vars_in_atom"
        "Collect the variables appearing in any body element
         (ConstraintAtom, Negation, Comparison, Aggregation)."
        (takes [atom :Any])
        (gives (set-of :ast/Term)))

      (function "vars_in_body"
        "Collect the variables appearing across an entire rule body."
        (takes [body (list-of :Any)])
        (gives (set-of :ast/Term)))

      ;; Exports closure from ast.boundary.
      (exports Term ConstraintAtom Negation Comparison Aggregation ConstraintRule))))
