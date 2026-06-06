(ns canvas.essence.lens
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
  "A focus over the model — what it brings into view / weighs as salient.

   `:focus` is prose (the human description of the slice). It may carry a companion
   SELECTION QUERY as its payload: a single datalog `:where` expression (authored as
   quoted data) binding `?n` as the focused node — the executable form of the focus.
   `evaluate-lens` runs it (with the vocab-derived rules, so it reads at domain
   altitude — `(Stage ?n) (in-module ?n \"…\")`) to yield the focus sub-graph.
   Transitive scope is just recursion in that one query; there is no separate closure
   knob. Prose-only lenses (no query) remain valid — they simply aren't evaluable."
  (slot :doc   (optional :String))
  (slot :focus (one :String) :payload :query))   ; prose + an optional datalog selection query
