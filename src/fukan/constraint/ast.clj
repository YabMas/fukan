(ns fukan.constraint.ast
  "Constraint AST — plain Clojure maps for Datalog rule definition.

   Rule = {:head <atom> :body [<atom-or-neg-or-comp-or-agg>...]}
   Atom = {:kind :atom :predicate <kw> :args [<term>...]}
   Neg  = {:kind :negation :inner <atom>}
   Cmp  = {:kind :comparison :op <kw> :left <term> :right <term>}
   Agg  = {:kind :aggregation :op <kw> :var <var> :body [<atom>...] :result <var>}

   Terms are either variables (keywords starting with ?) or constants
   (anything else)."
  (:refer-clojure :exclude [var?])
  (:require [clojure.string :as str]
            [clojure.set :as set]))

(defn var?
  "True iff x is a logic variable (keyword starting with ?)."
  [x]
  (and (keyword? x) (str/starts-with? (name x) "?")))

(defn constant?
  "True iff x is a constant term (anything that's not a variable)."
  [x]
  (not (var? x)))

(defn make-atom
  "Construct a positive atom: {:kind :atom :predicate kw :args [...]}"
  [predicate args]
  {:kind :atom :predicate predicate :args (vec args)})

(defn make-negation
  "Construct a negation: {:kind :negation :inner <atom>}"
  [inner-atom]
  {:kind :negation :inner inner-atom})

(defn make-comparison
  "Construct a comparison: {:kind :comparison :op kw :left term :right term}"
  [op left right]
  {:kind :comparison :op op :left left :right right})

(defn make-aggregation
  "Construct an aggregation: {:kind :aggregation :op kw :var var :body [...] :result var}"
  [op input-var body result-var]
  {:kind :aggregation :op op :var input-var :body (vec body) :result result-var})

(defn make-rule
  "Construct a rule: {:head <atom> :body [...]}"
  [head body]
  {:head head :body (vec body)})

(defn vars-in-term
  "Collect all variables appearing in a term (0 or 1)."
  [t]
  (if (var? t) #{t} #{}))

(defn vars-in-atom
  "Collect all variables appearing in an atom (any kind)."
  [atom]
  (case (:kind atom)
    :atom        (reduce set/union #{} (map vars-in-term (:args atom)))
    :negation    (vars-in-atom (:inner atom))
    :comparison  (set/union (vars-in-term (:left atom)) (vars-in-term (:right atom)))
    :aggregation (conj (reduce set/union #{} (map vars-in-atom (:body atom)))
                       (:result atom))))

(defn vars-in-body
  "Collect all variables appearing in a rule body (sequence of atoms)."
  [body]
  (reduce set/union #{} (map vars-in-atom body)))
