(ns canvas.vocab.op
  "The fukan-on-fukan model's COMPUTATION layer, built on the data layer
   (`canvas.vocab.shape`): a `Stage` consumes input Shapes, produces an output
   Shape, and calls downstream Stages. (Effects are added in a later cycle.)

   Vocab-only canvas spec (no `build-canvas`)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Stage
  "A computation stage: consumes input shapes, produces exactly one output shape,
   and may call downstream stages."
  (slot :in    (many Shape) :label-as :param)
  (slot :out   (one  Shape))
  (slot :calls (many Stage)))
