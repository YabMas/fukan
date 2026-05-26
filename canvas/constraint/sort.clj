(ns canvas.constraint.sort
  "Canvas port of constraint/sort.allium + sort.boundary.

   Coverage:
     - 1 invariant   → vocab.behavioral/invariant (SortGuardCatalogue)
     - 4 functions   → construction/function each (Bool-returning; plain function with (gives :Bool))

   Notes:
     - No predicate-catalog lift. The catalogue is named via the SortGuardCatalogue invariant;
       individual functions are plain function declarations.
     - Bool-returning sort guards are NOT a special case — function with (gives :Bool).
     - No exports: in sort.boundary."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [function]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "constraint.sort"

      ;; Invariant from sort.allium.
      (invariant "SortGuardCatalogue"
        "The sort guards this module exposes:
           is_string?        true iff value is a String
           is_number?        true iff value is a Number
           is_keyword?       true iff value is a Keyword
           is_primitive_id?  true iff value is a String containing the '::' separator —
                             structural shape check, not membership in the Model
         Sort guards run inside a rule body as host-callable predicates routed through
         the same dispatch as the built-in non-comparison predicates in builtins.allium."
        (holds-that "sort-guard-catalogue-is-closed-set"))

      ;; Public functions from sort.boundary.
      ;; All are Bool-returning — plain function with (gives :Bool).

      (function "is_string"
        "True iff x is a String."
        (takes [x :Any])
        (gives :Bool))

      (function "is_number"
        "True iff x is a Number."
        (takes [x :Any])
        (gives :Bool))

      (function "is_keyword"
        "True iff x is a Keyword."
        (takes [x :Any])
        (gives :Bool))

      (function "is_primitive_id"
        "True iff x is a String containing the '::' separator. Structural shape only —
         does not verify Model membership."
        (takes [x :Any])
        (gives :Bool)))))
