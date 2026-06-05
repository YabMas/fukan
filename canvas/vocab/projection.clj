(ns canvas.vocab.projection
  "The fukan-on-fukan model's PROJECTION layer. Projecting is a core abstract act:
   re-presenting the model in some target form. A `Projection` is one such projected
   representation (the implementation blueprint is one; docs, diagrams, other
   realizations are future projections), built from source→artifact `Mapping`s.

   NB this is the COMPLEMENT of a PROBE (analysis vs synthesis): a probe OBSERVES the
   model and yields a finding; a projection RE-PRESENTS the model, it doesn't judge it.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure ^:value Mapping
  "One source-kind → target-artifact rule within a projection — value-identified by
   its (from, to)."
  (slot :from (one :String))     ; the source structure kind
  (slot :to   (one :String)))    ; the target artifact it becomes

(defstructure Projection
  "A projected representation of the model — a target we render it into. Two flavours,
   composing:
     a BASE projection renders source kinds directly — it `:maps` each focused kind to
     a target artifact (Blueprint → implementation specs; Docs → documentation).
     a CONTEXTUALIZATION renders THROUGH a base it `:contextualizes`, wrapping that base's
     output in a framing `:context` (DriftClose = Blueprint framed as drift to close; the
     same composes Blueprint with a 'new feature' or 'refactor' context). It adds no
     mappings of its own — it reuses the base's, told differently.
   Either flavour renders THROUGH a `Lens` (the WHAT). The same lens can feed a probe and
   a projection (the drift lens feeds the drift inspect AND DriftClose)."
  (slot :doc            (optional :String))
  (slot :through        (one Lens))         ; the focus it renders through (the WHAT)
  (slot :maps           (many Mapping))     ; a BASE's source→artifact mappings (the HOW)
  (slot :contextualizes (optional Projection)) ; a CONTEXTUALIZATION's base projection
  (slot :context        (optional :String)) ; the framing prose wrapped around the base render
  ;; a projection is one flavour or the other — it declares mappings (base) or frames
  ;; another (contextualization); neither would render nothing.
  (law "a projection is a base (declares mappings) or a contextualization (frames another)"
    :offenders '[?p]
    :where '[(not-join [?p] [?m :rel/from ?p] [?m :rel/kind :maps])
             (not-join [?p] [?c :rel/from ?p] [?c :rel/kind :contextualizes])]))
