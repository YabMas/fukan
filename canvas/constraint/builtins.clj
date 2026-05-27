(ns canvas.constraint.builtins
  "Canvas port of constraint/builtins.allium + builtins.boundary.

   Coverage:
     - 1 invariant  → vocab.behavioral/invariant (BuiltinCatalogue)
     - 4 functions  → construction/function each (Bool-returning; plain function with (gives :Bool))

   Notes:
     - No predicate-catalog lift. The catalogue is named via the BuiltinCatalogue invariant;
       individual functions are plain function declarations.
     - Bool-returning functions are NOT a special case — function with (gives :Bool).
     - No exports: in builtins.boundary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "constraint.builtins"

      ;; Invariant from builtins.allium.
      (invariant "BuiltinCatalogue"
        "The non-comparison built-ins this module exposes:
           in?         set membership over a Set<Any>
           contains?   substring test over two Strings
           is_present? non-nil predicate
           is_absent?  nil predicate
         Adding a new built-in is a coordinated change with the evaluator's
         body-element dispatch and with the constraint DSL documentation in MODEL.md §6.5."
        (holds-that "builtin-catalogue-is-closed-set"))

      ;; Public functions from builtins.boundary.
      ;; All are Bool-returning — plain function with (gives :Bool).

      (function "in"
        "Set membership. True iff x is a member of s."
        (takes [x :Any
                s (set-of :Any)])
        (gives :Bool))

      (function "contains"
        "Substring containment. True iff haystack contains needle as a substring.
         False for non-string inputs."
        (takes [haystack :String
                needle   :String])
        (gives :Bool))

      (function "is_present"
        "True iff x is non-nil."
        (takes [x :Any])
        (gives :Bool))

      (function "is_absent"
        "True iff x is nil."
        (takes [x :Any])
        (gives :Bool)))))
