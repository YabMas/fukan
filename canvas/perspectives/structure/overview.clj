(ns canvas.perspectives.structure.overview
  "The STRUCTURAL perspective: fukan's core concepts and how they compose — the
   top-level frame the subsystem models (kernel, pipeline, canvas_source, …) realize.
   One of the authored views `canvas.domain.view` maps between (its sibling is the
   flow perspective, `canvas.perspectives.flow.collab`).

   The Model is the hub: a unified structure db of a Target codebase, built from
   Structures authored on the Canvas surface; a cross-cutting Lens (focus) and the
   two acts that compose with it — Probe (read → finding) and Projection (re-present)
   — read it and each yield something a human/LLM reasons with.

   Each Faculty's `realized-by` references the actual subsystem Module vars across the
   model (interlocking views)."
  (:require [canvas.domain.faculty :refer [Faculty]]
            [canvas.language.grouping :refer [Module]]
            [canvas.materialize.pipeline :as pipeline]
            [canvas.materialize.canvas-source :as canvas-source]
            [canvas.materialize.infra :as infra]
            [canvas.materialize.kernel :as kernel]
            [canvas.materialize.query-engine :as query-engine]
            [canvas.materialize.target :as target]
            [canvas.domain.lens-model :as lens]
            [canvas.materialize.lens-engine :as lens-engine]
            [canvas.domain.probe-acts :as probe]
            [canvas.materialize.probe-surface :as probe-surface]
            [canvas.domain.projection-model :as projection]))

;; faculties referenced before they are defined (the flow has forward edges)
(declare Structure Probe Projection Finding Blueprint Instruction)

;; the hub — realized by the subsystems that build and hold it; BUILT ON the Structure
;; primitive (foundation, not dataflow)
(def Model
  (Faculty (doc "The unified structure db — fukan's heart; everything orbits it.")
    (builds-on Structure)
    (realized-by pipeline/model-pipeline canvas-source/canvas-source infra/infra-model)))

;; the foundation + the external inputs the Model rests on / is supplied by — with
;; cross-view links to the subsystem models that realize them (interlocking views)
(def Structure
  (Faculty (doc "The lean-core primitive: a composition of slots + laws — the Model is built on it.")
    (realized-by kernel/core-structure query-engine/core-rules)))   ; kernel meta-model + query-rule derivation
(def Canvas
  (Faculty (doc "The reasoning surface; specs author structures into the model.")
    (supplies Model)))                                              ; external input (authored in)
(def Target
  (Faculty (doc "The analyzed codebase — implementation + specifications.")
    (supplies Model)                                                ; external input (extracted via the decoupled plug-point)
    (realized-by target/target-clojure target/target-correspondence)))  ; extraction + correspondence

;; the cross-cutting FOCUS and the two ACTS that compose with it
(def Lens
  (Faculty (doc "A focus over the model — which slice/aspect to attend to; composes with either act.")
    (reads Model) (feeds Probe Projection)
    (realized-by lens/lens lens-engine/core-lens)))                     ; the lens (focus) view + its engine
(def Probe
  (Faculty (doc "Reads the model through a lens → a finding (inspect = a gating finding).")
    (reads Model) (feeds Finding)
    (realized-by probe/probe probe-surface/probes)))                    ; the probe (read) view + its leaves
(def Projection
  (Faculty (doc "Re-presents the model in a target form — blueprint, instructions, ….")
    (reads Model) (feeds Blueprint Instruction)                         ; instruct ⊂ projection (a target)
    (realized-by projection/projection probe-surface/probe-code)))      ; the projection view + the probe-spec projector

;; the outputs reasoned-with
(def Finding     (Faculty (doc "A probe's output — a View to reason with, or a gating Signal (inspect).")))
(def Blueprint   (Faculty (doc "Implementation code projected from the model — one projection target.")))
(def Instruction (Faculty (doc "Guidance an LLM acts on to build or close drift.")))

;; the "fukan" module groups the faculties — collab's phases reference its members
;; (e.g. (across "fukan" "Lens")) as ordinary var refs into this namespace.
(def fukan
  (Module
    (child Model Structure Canvas Target Lens Probe Projection
           Finding Blueprint Instruction)))
