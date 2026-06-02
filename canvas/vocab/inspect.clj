(ns canvas.vocab.inspect
  "The fukan-on-fukan model's TRUST layer — the grammar for the inspect substrate. A
   `Check` inspects one aspect of the model and raises a `Signal` (a trust/health
   verdict). (A vocab layer alongside shape/op/meta/arch/lens.)

   NB this is structurally PARALLEL to the `lens` layer (a thing reads the model and
   yields an output: Lens→View ≈ Check→Signal) — the recurring 'probe yields output'
   shape worth watching as a candidate for a unified expression.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Signal
  "A trust/health verdict about the model that the inspect tier surfaces."
  (slot :doc (optional :String))
  ;; a signal is meaningful only if some check raises it
  (law "every signal is raised by some check"
    :offenders '[?s]
    :where '[(not [?r :rel/kind :raises] [?r :rel/to ?s])]))

(defstructure Check
  "A trust check: it inspects one aspect of the model and raises a Signal."
  (slot :doc      (optional :String))
  (slot :inspects (one :String))    ; the aspect of the model it verifies
  (slot :raises   (one Signal)))
