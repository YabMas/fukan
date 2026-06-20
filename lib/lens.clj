(ns lib.lens
  "The reusable USE-SIDE grammar — the act of FOCUSING (Lens) and SYNTHESIS (Projection).
   Opt-in stdlib vocab: any project `:require`s it to author its own instruments. Ships NO
   instances — fukan's own instruments live under `canvas/instruments/`.

     - a LENS is a focus — which slice/aspect to attend to. Evaluating a lens IS the read: it
       resolves the model to a sub-graph (no wrapper structure). A view is just a lens read.
       A GATE/CHECK is NOT a use-side act here: gating is the law/correspondence substrate
       (`structure/check` and `target.correspondence`), which already asserts and gates on
       violations — reading (Lens) and checking (Law) are different acts, kept apart.
     - a `Projection` RE-PRESENTS the model through a lens as a target artifact — built ON the
       lens, doing work it does not (mapping, contextualization).
   The same lens can feed both a read and a projection (the drift lens feeds drift readings AND a
   DriftClose projection)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            ;; :string scalar slots check through the malli type dialect
            [lib.type.malli]))

;; ── THE FOCUS: a Lens names a slice and carries its runnable selection ─────────────────────────

(defstructure Lens
  "A focus over the model — what it brings into view / weighs as salient. `:focus` is the prose
   description of the slice; `:select` is the focus's own executable form — the datalog selection
   (binding `?n`, evaluated by `core.lens/evaluate-lens`) that resolves the prose to a genuine
   sub-graph. The selection lives HERE, not in a realization shim: it is model-native datalog — it
   references no code, only the graph's own vocabulary, exactly like a law's `:where` or a
   `realized-as` derivation. It is the focus stated runnably, not a second thing that could drift
   from it. A lens with no `:select` is prose-only (not evaluable)."
  {:focus  :string                          ; the prose description of the slice
   :select [:? {:payload :query} :string]}) ; recap + the datalog selection (the :query payload)

;; ── THE SYNTHESIS: a Projection re-presents the model through a Lens ───────────────────────────

(defstructure ^:value Mapping
  "One source-kind → target-artifact rule within a projection — value-identified by
   its (from, to)."
  {:from :string     ; the source structure kind
   :to   :string})   ; the target artifact it becomes

(defstructure Projection
  "A projected representation of the model — a target we render it into. Two flavours, composing:
     a BASE projection renders source kinds directly — it `:maps` each focused kind to a target
     artifact (Blueprint → implementation specs; Docs → documentation).
     a CONTEXTUALIZATION renders THROUGH a base it `:contextualizes`, wrapping that base's output in
     a framing `:context` (DriftClose = Blueprint framed as drift to close; the same composes
     Blueprint with a 'new feature' or 'refactor' context). It adds no mappings of its own — it
     reuses the base's, told differently.
   Either flavour renders THROUGH a `Lens` (the WHAT). The same lens can feed a read and a
   projection (the drift lens feeds drift readings AND DriftClose)."
  {:through        Lens              ; the focus it renders through (the WHAT)
   :maps           [:* Mapping]      ; a BASE's source→artifact mappings (the HOW)
   :contextualizes [:? Projection]   ; a CONTEXTUALIZATION's base projection
   :context        [:? :string]}     ; the framing prose wrapped around the base render
  ;; a projection is one flavour or the other — it declares mappings (base) or frames
  ;; another (contextualization); neither would render nothing.
  (law "a projection is a base (declares mappings) or a contextualization (frames another)"
    (has-any :maps :contextualizes)))
