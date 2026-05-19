(ns fukan.constraint.ast
  "Constraint AST — plain Clojure maps for Datalog rule definition.

   Task 1 fills in the constructors."
  (:refer-clojure :exclude [var?])
  (:require [clojure.string :as str]))

(defn var?
  "True iff x is a logic variable (keyword starting with ?)."
  [x]
  (and (keyword? x) (str/starts-with? (name x) "?")))

(defn constant?
  "True iff x is a constant term (anything that's not a variable)."
  [x]
  (not (var? x)))
