(ns canvas.descent.source
  "DESCENT STEP — the `Source` in-fold, descended with TEETH (the first generative-descent slice).
   `canvas.subject/Source` is a pure portrait: it declares a `:polarity [:enum \"design-down\"
   \"code-up\"]` in-fold but asserts no law, so nothing forces its realization to cover both
   flavours. This spec adds the missing pressure: a realization edge (`SourceRealizer` names the
   `Module` that realizes one polarity) plus a structural-witness LAW the descent must satisfy.

   The same law reads three ways: DOWN = verify (it runs in `check`), UP = carve and GAP = prompt
   (those readings live in `fukan.descent`). It joins to the reflected `Source` by its tag — the
   manifest's existing mechanism — so NO kernel structure-reference slot is lifted. This is the
   model↔realization correspondence for the in-fold, so it lives in its own seam (like
   `fukan.target.correspondence`), NOT on the pure `Source` portrait (which must not name
   realizers). The editorial `canvas.manifest/z-source` entry is left as-is; this is its toothed
   companion."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.code :refer [Module Operation]]
            [lib.grouping :refer [Grouping]]
            ;; the :witnesses / :polarity [:enum …] scalars check through the malli type dialect
            [lib.type.malli]
            ;; slice-1 realizer Modules (referred) + slice-2 producer Operations (aliased)
            [canvas.architecture.canvas-source :as cs :refer [canvas-source]]  ; canvas-source = the Module (slice-1 :by); cs/build = an Operation it owns (slice-2 :via)
            [canvas.architecture.target :refer [target-clojure]]
            [canvas.architecture.pipeline :as pipeline]
            [canvas.architecture.extraction :as extraction]))

(defstructure SourceRealizer
  "A toothed realization edge for the `canvas.subject/Source` in-fold: the `Module` in `:by`
   realizes the `:witnesses` polarity flavour. The structural-witness law asserts the descent
   obligation — every polarity the `Source` portrait declares must have a realizer — joining to
   the reflected `Source` via its tag (the manifest's mechanism), so no core slot is lifted."
  {:witnesses [:enum "design-down" "code-up"]    ; the in-fold flavour this realizer covers
   :by        Module}                            ; the code module that realizes it
  ;; STRUCTURAL WITNESS (descent obligation, strength b): every polarity of the reflected `Source`
  ;; in-fold is witnessed by a `SourceRealizer`. `:scope :global` — the offenders are the
  ;; unwitnessed polarity choice nodes, not `SourceRealizer`s. The leading `Source`-tag clause is
  ;; the guard: vacuous on any db where the subject is not reflected. Negation routes through the
  ;; `(witnessed …)` rule so the zero-realizer case dodges datascript's empty-relation not-join gotcha.
  (law "every polarity of the Source in-fold is witnessed by a realizer"
    :scope :global
    :offenders '[?choice]
    :rules '[[(witnessed ?polarity)
              [?w :structure/of :canvas.descent.source/SourceRealizer]
              [?w :val/witnesses ?polarity]]]
    :where '[[?src :val/tag ":canvas.subject/Source"]
             [?pr :rel/from ?src] [?pr :rel/label "polarity"] [?pr :rel/to ?enum]
             [?cr :rel/from ?enum] [?cr :rel/kind :choice] [?cr :rel/to ?choice]
             [?choice :val/value ?polarity]
             (not (witnessed ?polarity))]))

;; the two realizers of the in-fold — design authored DOWN, code extracted UP
(SourceRealizer w-design {:witnesses "design-down" :by canvas-source})
(SourceRealizer w-code   {:witnesses "code-up"     :by target-clojure})

;; ── slice 2: the :into Model convergence, with VERIFIED COMPOSITION ──────────────────────────────
(defstructure ConvergenceEdge
  "Witnesses that the `Source :into Model` convergence UNIFIES one polarity: the `:realizer` (the
   convergence operation, e.g. build-model) must actually `:delegates` to the `:via` producer that
   yields this `:polarity` side. One edge per polarity. The law requires every Source polarity to be
   converged AND the realizer to truly delegate to its producer — strength (b) structural witness,
   checked against real modelled wiring, not a second declaration. Where slice 1's `SourceRealizer`
   asserts each polarity is *realized*, this asserts the merge *unifies* both."
  {:realizer Operation                          ; the convergence op (build-model)
   :polarity [:enum "design-down" "code-up"]    ; the polarity side this edge covers
   :via      Operation}                          ; the producer the realizer must actually delegate to
  ;; VERIFIED COMPOSITION + TOTALITY (descent obligation, strength b): for every polarity of the
  ;; reflected Source in-fold there must be a ConvergenceEdge whose :realizer actually :delegates to
  ;; its :via producer. `:scope :global` (offenders are unconverged polarity choice nodes). Same
  ;; Source-tag guard as the witness law (vacuous when the subject is not reflected); negation routes
  ;; through the `(converged …)` rule so the zero-edge / empty-relation case is safe.
  (law "the Source convergence realizer delegates to a producer for every polarity"
    :scope :global
    :offenders '[?choice]
    :rules '[[(converged ?polarity)
              [?ce :structure/of :canvas.descent.source/ConvergenceEdge]
              [?ce :val/polarity ?polarity]
              [?rr :rel/from ?ce] [?rr :rel/kind :realizer]  [?rr :rel/to ?r]
              [?vr :rel/from ?ce] [?vr :rel/kind :via]       [?vr :rel/to ?prod]
              [?dr :rel/from ?r]  [?dr :rel/kind :delegates] [?dr :rel/to ?prod]]]
    :where '[[?src :val/tag ":canvas.subject/Source"]
             [?pr :rel/from ?src] [?pr :rel/label "polarity"] [?pr :rel/to ?enum]
             [?cr :rel/from ?enum] [?cr :rel/kind :choice] [?cr :rel/to ?choice]
             [?choice :val/value ?polarity]
             (not (converged ?polarity))]))

;; build-model converges both polarities: design-down via the ingest producer, code-up via the
;; extraction PLUG-POINT (run-extractor) — extractor-agnostic, NOT target-clojure (the registered
;; extractor) directly; the convergence is decoupled from any specific extractor.
(ConvergenceEdge ce-design {:realizer pipeline/build-model :polarity "design-down" :via cs/build})
(ConvergenceEdge ce-code   {:realizer pipeline/build-model :polarity "code-up"     :via extraction/run-extractor})

(Grouping source-descent
  {:child [w-design w-code ce-design ce-code]})
