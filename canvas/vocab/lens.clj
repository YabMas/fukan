(ns canvas.vocab.lens
  "The fukan-on-fukan model's THINKING-MODE layer — the grammar for the lens
   substrate. A `Lens` is a pluggable thinking-mode that weighs one aspect of the
   model and yields a `View`; new modes plug in simply by adding a Lens (the
   registry is just the set of them). (A vocab layer alongside shape/op/meta/arch.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure View
  "A weighted perspective on the model that a human/LLM reasons with."
  (slot :doc (optional :String))
  ;; a view is meaningful only if something produces it
  (law "every view is yielded by some lens"
    :offenders '[?v]
    :where '[(not [?r :rel/kind :yields] [?r :rel/to ?v])]))

(defstructure Lens
  "A pluggable thinking-mode: it weighs one aspect of the model and yields a View.
   Adding a Lens is how a new thinking-mode joins the substrate."
  (slot :doc    (optional :String))
  (slot :weighs (one :String))     ; the aspect of the model it focuses on
  (slot :yields (one View)))
