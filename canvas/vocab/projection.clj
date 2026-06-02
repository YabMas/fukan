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
   implementation Blueprint is one; more (docs, diagrams, instructions, …) as we add
   them. Like a probe, a projection composes with a `Lens` — it renders THROUGH a focus
   (the WHAT) and its mappings say HOW each focused source kind becomes a target
   artifact. The same lens can feed a probe and a projection (e.g. the drift lens feeds
   the drift inspect AND the drift-close projection)."
  (slot :doc     (optional :String))
  (slot :through (one Lens))     ; the focus it renders through (the WHAT)
  (slot :maps    (some Mapping)))   ; ≥1 source→artifact mapping (the HOW)
