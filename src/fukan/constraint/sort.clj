(ns fukan.constraint.sort
  "Sort guards — unary predicates for typed predicate arguments per MODEL.md
   §6.3. Used by constraint authors to filter argument-value sorts."
  (:require [clojure.string :as str]))

(defn is-string? [x] (string? x))
(defn is-number? [x] (number? x))
(defn is-keyword? [x] (keyword? x))

(defn is-primitive-id?
  "True iff x looks like a fukan kernel primitive id (contains '::').
   Validates structure, not membership."
  [x]
  (and (string? x) (str/includes? x "::")))
