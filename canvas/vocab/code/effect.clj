(ns canvas.vocab.code.effect
  "Code vocab — `Effect`: a named side effect an Operation performs, plus the generic
   operation predicate for direct effects.
   The effect-drift readers live in `canvas.principles.declared-effects` (the adopted-principle
   home); the law is generated from Operation's `:performs {:covered-from …}` slot option.
   Clojure-specific effect classification lives in
   `canvas.vocab.code.extractors.clojure.effect`; partiality readings live with
   parse-don't-validate."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :require, :stderr, :throws).
   Value-identified — `:io` is one node shared across every Operation that performs it.
   A single-scalar `^:value` atom: the kernel derives its literal reader, so effects
   author as `:performs [:io :require]` and `:val/name` holds the keyword verbatim."
  {:name :keyword})

(s/defrelation :effectful
  "An Operation that DIRECTLY performs an Effect. Consumers that care about a subset of effects
   should filter by the effect's name in their own layer. The leaf PROPERTY that the composition
   operator transports along a transitive relation, e.g. (via :delegates Operation effectful)."
  '[?o]
  '[(Operation ?o) (performs ?o ?e)])
