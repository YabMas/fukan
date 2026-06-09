(ns canvas.domain.faculties
  "The STRUCTURAL perspective: fukan's core concepts and how they compose — the
   top-level frame the subsystem models (kernel, pipeline, canvas_source, …) realize.
   One of the authored views `canvas.vocabulary.view` maps between (its sibling is the
   flow perspective, `canvas.vocabulary.phase`).

   The Model is the hub: a unified structure db of a Target codebase, built from
   Structures authored on the Canvas surface; a cross-cutting Lens (focus) and the
   two acts that compose with it — Probe (read → finding) and Projection (re-present)
   — read it and each yield something a human/LLM reasons with.

   (Which subsystem modules realize each faculty is the correspondence concern, authored
   off this perspective in `canvas.correspondence` — the domain and its
   perspectives never point down at their realizers.)"
  (:require [canvas.vocabulary.faculty :refer [Faculty]]
            [lib.grouping :refer [Module]]))

;; faculties referenced before they are defined (the flow has forward edges)
(declare Structure Probe Projection Finding Blueprint Instruction)

;; the hub — BUILT ON the Structure primitive (foundation, not dataflow)
(def Model
  (Faculty (doc "The unified structure db — fukan's heart; everything orbits it.")
    (builds-on Structure)))

;; the foundation + the external inputs the Model rests on / is supplied by
(def Structure
  (Faculty (doc "The lean-core primitive: a composition of slots + laws — the Model is built on it.")))
(def Canvas
  (Faculty (doc "The reasoning surface; specs author structures into the model.")
    (supplies Model)))                                              ; external input (authored in)
(def Target
  (Faculty (doc "The analyzed codebase — implementation + specifications.")
    (supplies Model)))                                              ; external input (extracted via the decoupled plug-point)

;; the cross-cutting FOCUS and the two ACTS that compose with it
(def Lens
  (Faculty (doc "A focus over the model — which slice/aspect to attend to; composes with either act.")
    (reads Model) (feeds Probe Projection)))
(def Probe
  (Faculty (doc "Reads the model through a lens → a finding (inspect = a gating finding).")
    (reads Model) (feeds Finding)))
(def Projection
  (Faculty (doc "Re-presents the model in a target form — blueprint, instructions, ….")
    (reads Model) (feeds Blueprint Instruction)))                   ; instruct ⊂ projection (a target)

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
