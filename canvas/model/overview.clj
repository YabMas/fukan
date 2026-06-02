(ns canvas.model.overview
  "Self-spec: fukan's CORE CONCEPTS and how they flow — the top-level view, the
   frame the subsystem models (kernel, pipeline, canvas_source, …) realize.

   The Model is the hub: a unified structure db of a Target codebase, built from
   Structures authored on the Canvas surface; the thinking faculties (Lens,
   Inspect, Projection, Instruct, Agent) read it and each yield something a human/LLM
   reasons with."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.arch :refer [Faculty]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "fukan"
      ;; the hub — realized by the subsystems that build and hold it
      (Faculty "Model" (doc "The unified structure db — fukan's heart; everything orbits it.")
        (realized-by (across "model.pipeline") (across "canvas-source") (across "infra.model")))

      ;; what the Model is built from / fed by — with cross-view links to the
      ;; subsystem models that realize them (interlocking views)
      (Faculty "Structure" (doc "The lean-core primitive: a composition of slots + laws.")
        (feeds Model)
        (realized-by (across "core.structure")))          ; the kernel meta-model
      (Faculty "Canvas" (doc "The reasoning surface; specs author structures into the model.")
        (feeds Model))
      (Faculty "Target" (doc "The analyzed codebase — implementation + specifications.")
        (feeds Model))

      ;; the cross-cutting FOCUS and the two ACTS that compose with it
      (Faculty "Lens" (doc "A focus over the model — which slice/aspect to attend to; composes with either act.")
        (reads Model) (feeds Probe Projection)
        (realized-by (across "lens")))                                          ; the lens (focus) view
      (Faculty "Probe" (doc "Reads the model through a lens → a finding (inspect = a gating finding).")
        (reads Model) (feeds Finding)
        (realized-by (across "probe")))                                         ; the probe (read) view
      (Faculty "Projection" (doc "Re-presents the model in a target form — blueprint, instructions, ….")
        (reads Model) (feeds Blueprint Instruction)                             ; instruct ⊂ projection (a target)
        (realized-by (across "projection")))                                    ; the projection (render) view
      (Faculty "Agent"   (doc "Orchestrates lenses ∘ acts to serve an LLM/human — the next milestone.") (reads Model) (feeds Finding))

      ;; the outputs reasoned-with
      (Faculty "Finding"     (doc "A probe's output — a View to reason with, or a gating Signal (inspect)."))
      (Faculty "Blueprint"   (doc "Implementation code projected from the model — one projection target."))
      (Faculty "Instruction" (doc "Guidance an LLM acts on to build or close drift.")))))
