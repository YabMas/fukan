(ns fukan.common.vocab.code.effect
  "Code vocab — `Effect`: a named side effect an Operation performs, plus the generic
   operation predicate for direct effects and the effect-correspondence reader `undeclared-effects`
   (a thin worklist over the generated `:performs {:covered-from …}` demand, rehomed here when the
   principles layer was cut). Clojure-specific effect classification lives in
   `fukan.common.extraction.clojure.effect`."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.cozo.law :as law]))

(defstructure ^:value Effect
  "A named side effect an Operation performs (e.g. :io, :require, :state, :throws).
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

(defn undeclared-effects
  "The EFFECT-CORRESPONDENCE offenders — modelled ops whose extracted twin TRANSITIVELY reaches an
   effect the op does not declare in its `:performs`, as a set of op names (the under-declaration
   direction). Empty ⇔ design declares every effect the code reaches, to call-graph depth. Reads the
   generated `:corresponds/Operation.performs-covered` demand."
  [db]
  (law/violation-names db :corresponds/Operation.performs-covered))
