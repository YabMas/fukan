(ns canvas.vocab.probe
  "The fukan-on-fukan model's PROBE layer — one of the two acts on the model. A `Probe`
   READS the model through a `Lens` and yields a `Finding`: it observes / extracts,
   leaving the model unchanged (its dual, a `Projection`, RE-PRESENTS the model as a
   target artifact).

   INSPECT ⊂ PROBE: an inspect is just a probe whose finding GATES action (a trust /
   health verdict — a Signal). A non-gating finding is a View, a perspective to reason
   with. So inspect is not a separate structure — it is a probe whose `Finding` has
   `:gating true`, queryable as data. (The kernel already ships one such probe:
   `structure/check`, laws → violations, is the canonical integrity inspect.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Finding
  "What a probe yields — an observation ABOUT the model. A gating finding is a trust
   Signal that gates action (the inspect case); a non-gating finding is a View, a
   perspective a human/LLM reasons with."
  (slot :doc    (optional :String))
  (slot :gating (one :Bool))       ; gating → a trust Signal (inspect); else a View
  ;; a finding is meaningful only if some probe yields it
  (law "every finding is yielded by some probe"
    :offenders '[?f]
    :where '[(not [?r :rel/kind :yields] [?r :rel/to ?f])]))

(defstructure Probe
  "Reads the model through a Lens → a Finding. The model is unchanged (probe observes;
   it does not re-present)."
  (slot :doc     (optional :String))
  (slot :through (one Lens))       ; the focus it reads through
  (slot :yields  (one Finding)))   ; the observation it produces
