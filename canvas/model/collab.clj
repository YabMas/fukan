(ns canvas.model.collab
  "Self-spec: fukan's COLLABORATION LOOP — the human⊗AI OODA cycle that gives the
   faculties their purpose. The companion overview to `model/overview`: where the
   overview is the static map of faculties, this is them in motion. Each phase flows
   into the next and (most) exercise a faculty (the overview's Faculty vars), so the
   loop interlocks with the whole faculty map. The human⊗AI drives the loop; the
   faculties are what each phase puts to work."
  (:require [canvas.vocab.collab :refer [Phase]]
            [canvas.vocab.arch :refer [Module]]
            [canvas.essence.overview :as overview]))

;; the loop is a cycle — every phase's :next is defined after it (except Reinspect's)
(declare Focus Observe Reason Apply Reinspect)

(def Intend
  (Phase
    (doc "A human/LLM forms a question or a goal for the system.")
    (next Focus)))
(def Focus
  (Phase
    (doc "Attend to the relevant slice of the model — pick a lens.")
    (via overview/Lens)
    (next Observe)))
(def Observe
  (Phase
    (doc "Probe the model through that focus → a finding to reason with.")
    (via overview/Probe)
    (next Reason)))
(def Reason
  (Phase
    (doc "The human/LLM reasons with the finding and decides a change.")
    (next Apply)))
(def Apply
  (Phase
    (doc "Make the change — project a blueprint, or the LLM edits code from it.")
    (via overview/Projection)
    (next Reinspect)))
(def Reinspect
  (Phase
    (doc "The edit drifts code from the model; re-inspecting (the drift probe) surfaces it, reopening the loop.")
    (via overview/Probe)
    (next Intend)))

(def collab
  (Module (child Intend Focus Observe Reason Apply Reinspect)))
