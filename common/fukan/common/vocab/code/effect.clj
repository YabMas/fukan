(ns fukan.common.vocab.code.effect
  "Code vocab — `Effect`: a named side effect an Operation performs, plus the generic
   operation predicate for direct effects. PURE DESIGN, language-neutral.

   The effect-correspondence check is the GENERATED relation map `(:performs :sup [:cat [:* :calls] :performs])`
   demand (:corresponds/Operation.performs-covered) — declared, with the rest of the design↔Clojure
   map, in `fukan.common.extraction.clojure.operation`, since `:calls` is a Clojure construct. A
   caller names that law key through `law/violation-names` directly. Clojure-specific effect
   classification lives in `fukan.common.extraction.clojure.effect`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]))

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :state, :require, :reflect, :throws).
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
