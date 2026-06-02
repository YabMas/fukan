(ns canvas.model.collab
  "Self-spec: fukan's COLLABORATION LOOP — the human⊗AI OODA cycle that gives the
   faculties their purpose. The companion overview to `model/overview`: where the
   overview is the static map of faculties, this is them in motion. Each phase flows
   into the next and (most) exercise a faculty — referenced across the overview view, so
   the loop interlocks with the whole faculty map. The Agent orchestrates the loop as a
   whole; here it appears at re-inspection, where it surfaces drift and drives closure."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.collab :refer [Phase]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "collab"
      (Phase "Intend"
        (doc "A human/LLM forms a question or a goal for the system.")
        (next Focus))
      (Phase "Focus"
        (doc "Attend to the relevant slice of the model — pick a lens.")
        (via (across "fukan" "Lens"))
        (next Observe))
      (Phase "Observe"
        (doc "Probe the model through that focus → a finding to reason with.")
        (via (across "fukan" "Probe"))
        (next Reason))
      (Phase "Reason"
        (doc "The human/LLM reasons with the finding and decides a change.")
        (next Apply))
      (Phase "Apply"
        (doc "Make the change — project a blueprint, or the LLM edits code from it.")
        (via (across "fukan" "Projection"))
        (next Reinspect))
      (Phase "Reinspect"
        (doc "The edit drifts code from the model; the agent surfaces it and drives closure, reopening the loop.")
        (via (across "fukan" "Agent"))
        (next Intend)))))
