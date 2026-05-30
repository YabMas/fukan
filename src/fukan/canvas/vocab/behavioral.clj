(ns fukan.canvas.vocab.behavioral
  "Methodology vocabulary for systems that reason about named behavioral
   commitments. Opt-in: require this namespace only if your project models
   invariants and reactive rules explicitly.

   Ships two lifts: `invariant` (timeless commitments) and `rule` (reactive
   declarations fired by triggers). Both produce no-shape Affordances with
   distinct roles."
  (:require [fukan.canvas.core.defconstructor :refer [defconstructor]]
            [fukan.canvas.vocab.construct :as construct]))

(defconstructor invariant
  "A named behavioral commitment of the enclosing module. Allium's `invariant`
   declaration ports here. Allium-style `guarantee` declarations also port here
   — the semantic distinction (consumer-facing vs internal) is carried by the
   declaration's name (e.g. `<Thing>Guarantee` vs `<Thing>Invariant`), not by a
   separate lift."

  (form holds-that "What must remain true." :shape :prose)

  (produces [name doc forms]
    (construct/build :canvas/invariant name (first (:holds-that forms)) {} :doc doc)))

(defconstructor rule
  "A reactive behavioral declaration: fires when its trigger pattern matches.
   Allium's `rule X { when: X(params) }` declaration ports here. The `when`
   form carries the trigger signature — typically the rule's own name followed
   by parameter pairs, captured as edn data in the formal-expression.

   Phase 2 deferred this lift; Phase 3 Sprint 2 surfaced 49 deferred instances
   across the codebase, well past the rule of three. Shipped 2026-05-26."

  (form when "Trigger pattern: typically (TriggerName (param :Type) ...)." :required true)

  (produces [name doc forms]
    (construct/build :canvas/rule name {:when (vec (:when forms))} {} :doc doc)))
