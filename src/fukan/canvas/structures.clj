(ns fukan.canvas.structures
  "The base structure vocabulary, written in `defstructure` — the lean-kernel
   successor to construction.clj + vocab/*. Each kind is a structure: slots
   (relations-with-laws) + free laws, registered as data.

   Re-expressing the old construction lifts surfaced one load-bearing modelling
   choice: with refinement dropped (design decision #4), value and record do NOT
   become two structures needing a common supertype. They collapse into a single
   `Type` with an optional `:field` slot — atomic when fieldless, a record when it
   has fields — so a slot target like `(gives (one Type))` accepts either. A slot
   target must name one structure that subsumes its variants.

   See doc/specs/2026-05-31-defstructure-design.md."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Type
  "A named type. Atomic when it has no fields; a record type when it does.
   (Subsumes the old `value` and `record` lifts — see ns docstring.)"
  (slot :field (many Type) :label-as :field-name))

(defstructure Effect
  "A named effect a Function may perform (e.g. :io, :db).")

(defstructure Event
  "A named event a Function may emit.")

(defstructure Function
  "A synchronous call: takes typed inputs, gives exactly one typed output, may
   perform effects and emit events."
  (slot :takes    (many Type)   :label-as :param)
  (slot :gives    (one  Type))
  (slot :performs (many Effect))
  (slot :emits    (many Event)))
