(ns canvas.model.agent
  "Self-spec: fukan's AGENT surface — the orchestrator that COMPOSES ITS OWN TOOLS from
   the primitive focuses and acts fukan provides. Forward-looking AND the NEXT MILESTONE:
   the agent is the main driver for testing fukan's vision. Each Tool combines primitives
   into a capability NO single faculty act is — bundling several probes, or chaining a
   probe into a projection — referencing the lenses across the lens view. This is the
   point of separating lens from act: the agent builds its toolset from the model. The
   overview's `Faculty \"Agent\"` is `realized-by` this module."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.agent :refer [Tool]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "agent"
      ;; BUNDLES — several probes combined into one tool (no single inspect does this)
      (Tool "health-audit"
        (answers "how healthy is the model — integrity, coverage, AND drift, together?")
        (probes (across "lens" "integrity") (across "lens" "coverage") (across "lens" "drift")))
      (Tool "tangle-diagnosis"
        (answers "where is complexity, and which recurring patterns drive it?")
        (probes (across "lens" "tar-pit") (across "lens" "patterns")))
      ;; CHAINS — observe through a focus, then render through one
      (Tool "audit-and-close"
        (answers "where has code drifted, and what should I do to close it?")
        (probes   (across "lens" "drift"))
        (projects (across "lens" "drift")))
      (Tool "contract-scaffold"
        (answers "generate implementation that honours the declared contracts")
        (probes   (across "lens" "consistency"))
        (projects (across "lens" "survey"))))))
