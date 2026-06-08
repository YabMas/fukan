(ns canvas.domain.lens
  "The fukan-on-fukan model's FOCUS layer. A `Lens` is a focus over the model — which
   slice / aspect / framing it brings into view and weighs as salient. It names WHAT to
   attend to, not what to DO with it, so it is cross-cutting: it composes with any act
   (a `Probe` reads through a lens → a finding; a `Projection` renders through one →
   an artifact). The same lens can feed different acts (e.g. a drift lens feeds an
   inspect-probe AND a drift-close projection).

   NB historical note: the parked top-level `projection/` module produced exactly these
   focuses — it was a lens provider, decoupled from the render fns that consumed them.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Lens
  "A focus over the model — what it brings into view / weighs as salient. `:focus` is the
   prose description of the slice; that's all the domain states. The executable form of the
   focus (the datalog selection that resolves it to a sub-graph) is realization mechanism —
   a `LensSelection` in `canvas.materialize.realization`, read by `core.lens/evaluate-lens`.
   A lens with no LensSelection is prose-only (not evaluable)."
  (slot :doc   (optional :String))
  (slot :focus (one :String)))   ; the prose description of the slice
