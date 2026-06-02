(ns canvas.model.overview
  "Self-spec: fukan's CORE CONCEPTS and how they flow — the top-level view, the
   frame the subsystem models (kernel, pipeline, canvas_source, …) realize.

   The Model is the hub: a unified structure db of a Target codebase, built from
   Structures authored on the Canvas surface; the thinking faculties (Lens,
   Inspect, Project, Instruct, Agent) read it and each yield something a human/LLM
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

      ;; the thinking faculties — each reads the Model and yields a perspective/output
      (Faculty "Lens"    (doc "A thinking-mode / view over the model.")        (reads Model) (feeds View)
        (realized-by (across "lens")))                                          ; the lens subsystem view
      (Faculty "Inspect" (doc "Trust signals — integrity, coverage, drift.")   (reads Model) (feeds Signal)
        (realized-by (across "inspect")))                                       ; the inspect subsystem view
      (Faculty "Project" (doc "Realizes the model back into code.")            (reads Model) (feeds Code))
      (Faculty "Instruct"(doc "Turns the model into instructions for an LLM.")  (reads Model) (feeds Instruction))
      (Faculty "Agent"   (doc "Queries the model; saved, shiftable views.")     (reads Model) (feeds View))

      ;; the outputs reasoned-with
      (Faculty "View"        (doc "A perspective on the model a human/LLM reasons with."))
      (Faculty "Signal"      (doc "A trust signal about the model's health."))
      (Faculty "Code"        (doc "Implementation realized from the model."))
      (Faculty "Instruction" (doc "Guidance an LLM acts on to build or close drift.")))))
