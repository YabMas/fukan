(ns canvas.vocabulary.act
  "Grammar for the FOCUS (Lens) and the two ways the model is USED — reading and synthesis.

   The two uses are NOT twins (an earlier grammar forced them under one `Act`/`Probe` symmetry):
     - a LENS is a focus — which slice/aspect to attend to. Evaluating a lens reads the model into
       a `Finding`. The lens IS the read; there is no `Probe` structure wrapping it — a Finding
       reads `:through` a Lens directly.
     - a `Projection` RE-PRESENTS the model through a lens as a target artifact (it synthesizes; it
       does not judge). It is built ON the lens, doing work the lens does not (mapping,
       contextualization).
   The same lens can feed both (a drift lens feeds a drift Finding AND a drift-close projection).

   The domain instances live in `canvas.domain.{lens,probe,projection}`; the executable mechanism
   that runs these lives in `canvas.architecture.acts`.

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

;; ── The focus ────────────────────────────────────────────────────────────────

(defstructure Lens
  "A focus over the model — what it brings into view / weighs as salient. `:focus` is the prose
   description of the slice; `:select` is the focus's own executable form — the datalog selection
   (binding `?n`, evaluated by `core.lens/evaluate-lens`) that resolves the prose to a genuine
   sub-graph. The selection lives HERE, not in a realization shim: it is model-native datalog — it
   references no code, only the graph's own vocabulary, exactly like a law's `:where` or a
   `realized-as` derivation. It is the focus stated runnably, not a second thing that could drift
   from it. A lens with no `:select` is prose-only (not evaluable)."
  {:focus  :String                          ; the prose description of the slice
   :select [:? {:payload :query} :String]}) ; recap + the datalog selection (the :query payload)

;; ── The reading: a Finding reads through a Lens (inspect ⊂ reading) ───────────

(defstructure Finding
  "What reading the model through a Lens yields — an observation ABOUT the model. A Finding reads
   `:through` a Lens (the focus); evaluating that lens IS the act of reading, so there is no
   separate `Probe`. A gating Finding is a trust Signal that gates action (the inspect case); a
   non-gating Finding is a View, a perspective a human/LLM reasons with.

   A finding may STATE a CONTRACT — a `:holds` invariant (the human's correctness spec for the
   reading's output). The executable check of that invariant (a `(fn [result target-db] → ok?)`) is
   realization mechanism — a `FindingCheck` in `canvas.architecture.acts`, surfaced by the projector
   as a runtime gate. The complement of this reading is a `Projection` (synthesis)."
  {:through Lens          ; the focus it reads through (the model is unchanged)
   :gating  :Bool         ; gating → a trust Signal (inspect); else a View
   :holds   [:? :String]}) ; the stated invariant (its executable check lives in the realization view)

(defstructure Signal
  "A gating Finding — an inspect's trust verdict (a reading whose result gates action). Realized:
   derived, not instantiated."
  (realized-as '[(Finding ?e) [?e :val/gating true]]))

(defstructure View
  "A non-gating Finding — a perspective to reason with. Realized."
  (realized-as '[(Finding ?e) [?e :val/gating false]]))

;; ── The synthesis: Projection (re-present) ───────────────────────────────────

(defstructure ^:value Mapping
  "One source-kind → target-artifact rule within a projection — value-identified by
   its (from, to)."
  {:from :String     ; the source structure kind
   :to   :String})   ; the target artifact it becomes

(defstructure Projection
  "A projected representation of the model — a target we render it into. Two flavours,
   composing:
     a BASE projection renders source kinds directly — it `:maps` each focused kind to
     a target artifact (Blueprint → implementation specs; Docs → documentation).
     a CONTEXTUALIZATION renders THROUGH a base it `:contextualizes`, wrapping that base's
     output in a framing `:context` (DriftClose = Blueprint framed as drift to close; the
     same composes Blueprint with a 'new feature' or 'refactor' context). It adds no
     mappings of its own — it reuses the base's, told differently.
   Either flavour renders THROUGH a `Lens` (the WHAT). The same lens can feed a Finding and
   a projection (the drift lens feeds the drift inspect AND DriftClose)."
  {:through        Lens              ; the focus it renders through (the WHAT)
   :maps           [:* Mapping]      ; a BASE's source→artifact mappings (the HOW)
   :contextualizes [:? Projection]   ; a CONTEXTUALIZATION's base projection
   :context        [:? :String]}     ; the framing prose wrapped around the base render
  ;; a projection is one flavour or the other — it declares mappings (base) or frames
  ;; another (contextualization); neither would render nothing.
  (law "a projection is a base (declares mappings) or a contextualization (frames another)"
    (has-any :maps :contextualizes)))
