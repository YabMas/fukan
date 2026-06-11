(ns canvas.vocabulary.act
  "Grammar for the cross-cutting FOCUS and the two complementary ACTS on the model.

   A `Lens` is a focus — which slice/aspect to attend to (the WHAT), not what to do with
   it — so it composes with either act. The two acts are complementary, analysis vs
   synthesis:
     - a `Probe` READS the model through a lens → a `Finding` (it observes; the model is
       unchanged). INSPECT ⊂ PROBE: an inspect is a probe whose finding GATES action.
     - a `Projection` RE-PRESENTS the model through a lens as a target artifact (it
       synthesizes; it does not judge).
   The same lens can feed both (a drift lens feeds the drift inspect AND a drift-close
   projection).

   The domain instances live in `canvas.domain.{lens,probe,projection}`; the executable
   mechanism that runs these acts lives in `canvas.realization.acts`.

   (Consolidated from the former `vocabulary.lens` / `vocabulary.probe` /
   `vocabulary.projection` shards — they mirrored `domain/` 1:1 for no structural gain.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

;; ── The focus ────────────────────────────────────────────────────────────────

(defstructure Lens
  "A focus over the model — what it brings into view / weighs as salient. `:focus` is the
   prose description of the slice; that's all the domain states. The executable form of the
   focus (the datalog selection that resolves it to a sub-graph) is realization mechanism —
   a `LensSelection` in `canvas.realization.acts`, read by `core.lens/evaluate-lens`.
   A lens with no LensSelection is prose-only (not evaluable)."
  {:focus :String})   ; the prose description of the slice

;; ── The observation act: Probe → Finding (inspect ⊂ probe) ───────────────────

(defstructure Finding
  "What a probe yields — an observation ABOUT the model. A gating finding is a trust
   Signal that gates action (the inspect case); a non-gating finding is a View, a
   perspective a human/LLM reasons with.

   A finding may STATE a CONTRACT — a `:holds` invariant (the human's correctness spec for
   the probe's output). The executable check of that invariant (a `(fn [result target-db] →
   ok?)`) is realization mechanism — a `FindingCheck` in `canvas.realization.acts`,
   surfaced by the projector as a runtime gate. The complement of this observation act is a
   `Projection` (synthesis)."
  {:gating :Bool          ; gating → a trust Signal (inspect); else a View
   :holds  [:? :String]}  ; the stated invariant (its executable check lives in the realization view)
  ;; a finding is meaningful only if some probe yields it
  (law "every finding is yielded by some probe"
    :offenders '[?f]
    :where '[(not [?r :rel/kind :yields] [?r :rel/to ?f])]))

(defstructure Probe
  "Reads the model through a Lens → a Finding. The model is unchanged (probe observes;
   it does not re-present).

   The domain probe states only what it reads (`:through` a Lens) and produces (`:yields` a
   Finding). Which kernel capability it invokes when run (e.g. the integrity probe composes
   the kernel's `check`) is realization mechanism — a `ProbeComposition` in
   `canvas.realization.acts`."
  {:through Lens       ; the focus it reads through
   :yields  Finding})  ; the observation it produces

(defstructure Signal
  "A gating Finding — an inspect's trust verdict. Realized: derived, not instantiated."
  (realized-as '[(Finding ?e) [?e :val/gating true]]))

(defstructure View
  "A non-gating Finding — a perspective to reason with. Realized."
  (realized-as '[(Finding ?e) [?e :val/gating false]]))

(defstructure Inspect
  "A Probe whose Finding gates action (Signal). Realized — 'inspect ⊂ probe' as a derived
   concept, not a separate structure (matches the long-standing prose)."
  (realized-as '[(Probe ?e) [?r :rel/from ?e] [?r :rel/kind :yields] [?r :rel/to ?f] (Signal ?f)]))

;; ── The synthesis act: Projection (re-present) ───────────────────────────────

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
   Either flavour renders THROUGH a `Lens` (the WHAT). The same lens can feed a probe and
   a projection (the drift lens feeds the drift inspect AND DriftClose)."
  {:through        Lens              ; the focus it renders through (the WHAT)
   :maps           [:* Mapping]      ; a BASE's source→artifact mappings (the HOW)
   :contextualizes [:? Projection]   ; a CONTEXTUALIZATION's base projection
   :context        [:? :String]}     ; the framing prose wrapped around the base render
  ;; a projection is one flavour or the other — it declares mappings (base) or frames
  ;; another (contextualization); neither would render nothing.
  (law "a projection is a base (declares mappings) or a contextualization (frames another)"
    :offenders '[?p]
    :where '[(not-join [?p] [?m :rel/from ?p] [?m :rel/kind :maps])
             (not-join [?p] [?c :rel/from ?p] [?c :rel/kind :contextualizes])]))
