(ns canvas.language.op
  "The fukan-on-fukan model's COMPUTATION layer, built on the data layer
   (`canvas.language.shape`): a `Stage` consumes input Shapes, produces an output
   Shape, performs Effects, and calls downstream Stages.

   Vocab-only canvas spec (no `build-canvas`)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defn ^:export read-effect
  "Expand an effect literal — a keyword like `:io` — into Effect clauses, so
   effects author as `(performs :io :require)`."
  [kw]
  [(list 'name (name kw))])

(defstructure ^:value Effect
  "A named side effect a Stage performs (e.g. :io, :require, :stderr, :throws).
   Value-identified — `:io` is one node shared across every Stage that performs it."
  (slot :name (one :String))
  (reader read-effect))

(defstructure Stage
  "A computation stage: consumes input shapes, produces exactly one output shape,
   performs effects, and may call downstream stages."
  (includes Connected)
  (slot :in       (many Shape))
  (slot :out      (one  Shape))
  (slot :performs (many Effect))
  (slot :calls    (many Stage)))
