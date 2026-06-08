(ns canvas.perspectives.flow.collab
  "The FLOW perspective (collaboration-loop) — fukan's purpose in motion, the dynamic
   companion to the structural faculty map. It shows the faculties IN MOTION: the human⊗AI
   cycle by which the model is used — intend → focus → observe → reason → apply →
   re-inspect → (drift) → intend. A `Phase` is one step; `:via` is the faculty it
   exercises (some phases are pure human/LLM judgement, with no faculty); `:next` is the
   phase it flows into. The phases form a CLOSED CYCLE — the loop never terminates.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Phase
  "One step of the human⊗AI collaboration loop. It exercises a faculty (`:via`, optional
   — some phases are pure human/LLM judgement) and flows into the next phase (`:next`);
   the phases form a cycle."
  (slot :doc  (optional :String))
  (slot :via  (optional Faculty))   ; the faculty this phase puts to work
  (slot :next (one Phase))          ; the phase it flows into — cyclic
  ;; the loop closes — no phase is a dead end / unreachable entry: every phase is some
  ;; phase's :next (so following :next from anywhere cycles through them all)
  (law "the loop closes — every phase is reached"
    :offenders '[?p]
    :where '[(not [?r :rel/kind :next] [?r :rel/to ?p])]))
