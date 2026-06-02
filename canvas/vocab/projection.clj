(ns canvas.vocab.projection
  "The fukan-on-fukan model's PROJECTION layer. Projecting is a core abstract act:
   re-presenting the model in some target form. A `Projection` is one such projected
   representation (the implementation blueprint is one; docs, diagrams, other
   realizations are future projections), built from source→artifact `Mapping`s.

   NB this is conceptually distinct from a PROBE (lens/inspect, which read the model
   and yield a finding): a projection RE-PRESENTS the model, it doesn't judge it.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure ^:value Mapping
  "One source-kind → target-artifact rule within a projection — value-identified by
   its (from, to)."
  (slot :from (one :String))     ; the source structure kind
  (slot :to   (one :String)))    ; the target artifact it becomes

(defstructure Projection
  "A projected representation of the model — a target we render it into. The
   implementation Blueprint is one; more (docs, diagrams, …) as we add them. Built
   from the mappings that turn each source kind into a target artifact."
  (slot :doc  (optional :String))
  (slot :maps (some Mapping)))   ; at least one source→artifact mapping
