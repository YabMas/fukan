(ns fukan.canvas.vocab.behavioral
  "Methodology vocabulary for systems that reason about named behavioral
   commitments. Opt-in: require this namespace only if your project models
   invariants explicitly. Ships one lift: `invariant`."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.core.helpers :as h]))

(defconstructor invariant
  "A named behavioral commitment of the enclosing module. Allium's `invariant`
   declaration ports here. Allium-style `guarantee` declarations also port here
   — the semantic distinction (consumer-facing vs internal) is carried by the
   declaration's name (e.g. `<Thing>Guarantee` vs `<Thing>Invariant`), not by a
   separate lift."

  (form holds-that "What must remain true." :shape :prose)

  (produces [name doc forms]
    (h/declare-affordance name
      :role :canvas/invariant
      :formal-expression (first (:holds-that forms))
      :doc doc)))
