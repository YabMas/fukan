(ns canvas.model.agent
  "Self-spec: fukan's AGENT surface — the orchestrator that composes lens ∘ act to
   answer an LLM/human's questions. Forward-looking AND the NEXT MILESTONE: the agent is
   the main driver for testing fukan's vision. Each saved Composition picks a focus (a
   lens, referenced across the lens view) and an act — proving the agent surface is
   BUILT from the lens ∘ act algebra, reusing the same lenses the probe and projection
   views use (the drift lens, e.g., is composed here as both a probe and a projection).
   The overview's `Faculty \"Agent\"` is `realized-by` this module."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.agent :refer [Composition]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "agent"
      ;; probe compositions — read the model → a finding
      (Composition "contracts"
        (answers "what contracts does the model declare, and do they align?")
        (through (across "lens" "consistency")) (act "probe"))
      (Composition "drift-report"
        (answers "where have specifications and code diverged?")
        (through (across "lens" "drift")) (act "probe"))
      (Composition "hotspots"
        (answers "where are the complexity tangles worth attention?")
        (through (across "lens" "tar-pit")) (act "probe"))
      ;; projection compositions — render the model → an artifact
      (Composition "scaffold"
        (answers "what implementation code realizes the model?")
        (through (across "lens" "survey")) (act "project"))
      (Composition "close-drift"
        (answers "what should an LLM do to close the drift?")
        (through (across "lens" "drift")) (act "project")))))
