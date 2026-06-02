(ns canvas.vocab.agent
  "The fukan-on-fukan model's COMPOSER layer — the Agent surface. The Agent is the
   orchestrator: it answers a question by COMPOSING a Lens (focus) with an ACT — a
   probe (read the model → a finding) or a projection (render it → an artifact). A
   `Composition` is one composed, runnable unit (a saved view / capability); the agent
   surface is the set of them.

   This is the spine the agent operates on — it tests that the `lens ∘ act` algebra
   composes up into the query surface, reusing the very lenses the probe and projection
   views already use.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Composition
  "A composed, runnable unit the agent offers: a Lens focus put to an act, answering a
   question. `:act` is \"probe\" (read → a finding) or \"project\" (render → an artifact)."
  (slot :doc     (optional :String))
  (slot :answers (one :String))    ; the question this saved view answers
  (slot :through (one Lens))       ; the focus it composes (same :through relation as probe/projection)
  (slot :act     (one :String))    ; "probe" | "project" — the act it puts the lens to
  (law "a composition's act is probe or project"
    :offenders '[?c]
    :where '[[?c :val/act ?a]
             [(clojure.core/contains? #{"probe" "project"} ?a) ?ok]
             [(clojure.core/false? ?ok)]]))
