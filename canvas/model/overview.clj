(ns canvas.model.overview
  "Self-spec: fukan's CORE CONCEPTS and how they flow — the top-level view, the
   frame the subsystem models (kernel, pipeline, canvas_source, …) realize.

   The Model is the hub: a unified structure db of a Target codebase, built from
   Structures authored on the Canvas surface; a cross-cutting Lens (focus) and the
   two acts that compose with it — Probe (read → finding) and Projection (re-present)
   — read it and each yield something a human/LLM reasons with.

   Each Faculty's `realized-by` references the actual subsystem Module vars across the
   model (interlocking views)."
  (:require [canvas.vocab.arch :refer [Faculty Module]]
            [canvas.model.pipeline :as pipeline]
            [canvas.model.canvas-source :as canvas-source]
            [canvas.model.infra :as infra]
            [canvas.model.kernel :as kernel]
            [canvas.model.query-engine :as query-engine]
            [canvas.model.target :as target]
            [canvas.model.lens :as lens]
            [canvas.model.lens-engine :as lens-engine]
            [canvas.model.probe :as probe]
            [canvas.model.probe-surface :as probe-surface]
            [canvas.model.projection :as projection]))

;; faculties referenced before they are defined (the flow has forward edges)
(declare Probe Projection Finding Blueprint Instruction)

;; the hub — realized by the subsystems that build and hold it
(def Model
  (Faculty "Model" (doc "The unified structure db — fukan's heart; everything orbits it.")
    (realized-by pipeline/model-pipeline canvas-source/canvas-source infra/infra-model)))

;; what the Model is built from / fed by — with cross-view links to the
;; subsystem models that realize them (interlocking views)
(def Structure
  (Faculty "Structure" (doc "The lean-core primitive: a composition of slots + laws.")
    (feeds Model)
    (realized-by kernel/core-structure query-engine/core-rules)))   ; kernel meta-model + query-rule derivation
(def Canvas
  (Faculty "Canvas" (doc "The reasoning surface; specs author structures into the model.")
    (feeds Model)))
(def Target
  (Faculty "Target" (doc "The analyzed codebase — implementation + specifications.")
    (feeds Model)
    (realized-by target/target-clojure target/target-correspondence)))  ; extraction + correspondence

;; the cross-cutting FOCUS and the two ACTS that compose with it
(def Lens
  (Faculty "Lens" (doc "A focus over the model — which slice/aspect to attend to; composes with either act.")
    (reads Model) (feeds Probe Projection)
    (realized-by lens/lens lens-engine/core-lens)))                     ; the lens (focus) view + its engine
(def Probe
  (Faculty "Probe" (doc "Reads the model through a lens → a finding (inspect = a gating finding).")
    (reads Model) (feeds Finding)
    (realized-by probe/probe probe-surface/probes)))                    ; the probe (read) view + its leaves
(def Projection
  (Faculty "Projection" (doc "Re-presents the model in a target form — blueprint, instructions, ….")
    (reads Model) (feeds Blueprint Instruction)                         ; instruct ⊂ projection (a target)
    (realized-by projection/projection probe-surface/probe-code)))      ; the projection view + the probe-spec projector

;; the outputs reasoned-with
(def Finding     (Faculty "Finding"     (doc "A probe's output — a View to reason with, or a gating Signal (inspect).")))
(def Blueprint   (Faculty "Blueprint"   (doc "Implementation code projected from the model — one projection target.")))
(def Instruction (Faculty "Instruction" (doc "Guidance an LLM acts on to build or close drift.")))

;; the "fukan" module groups the faculties — collab's phases reference its members
;; (e.g. (across "fukan" "Lens")) as ordinary var refs into this namespace.
(def fukan
  (Module "fukan"
    (child Model Structure Canvas Target Lens Probe Projection
           Finding Blueprint Instruction)))
