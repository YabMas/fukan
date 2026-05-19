(ns fukan.constraint.builtins
  "Datalog built-in predicates per MODEL.md §6.5. Used inside constraint
   rule bodies. Comparison ops (=, <, etc.) are handled directly by the
   evaluator via :comparison atoms; this namespace covers the
   non-comparison built-ins."
  (:refer-clojure :exclude [contains?])
  (:require [clojure.string :as str]))

(defn in?
  "Set membership."
  [x s]
  (clojure.core/contains? s x))

(defn contains?
  "String substring containment."
  [haystack needle]
  (and (string? haystack) (string? needle)
       (str/includes? haystack needle)))

(defn is-present?
  "True iff x is non-nil."
  [x]
  (some? x))

(defn is-absent?
  "True iff x is nil."
  [x]
  (nil? x))
