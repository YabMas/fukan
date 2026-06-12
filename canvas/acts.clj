(ns canvas.acts
  "fukan's ACTS stratum — the FOCUS (Lens) and the two ways the model is USED (reading, synthesis),
   grammar and instances co-located (one artifact per stratum; see `canvas.subject` for the
   rationale — a bespoke grammar locked to its instances doesn't earn the vocabulary/domain split).

   The two uses are NOT twins:
     - a LENS is a focus — which slice/aspect to attend to. Evaluating a lens reads the model into a
       `Finding`; the lens IS the read (no `Probe` structure wraps it).
     - a `Projection` RE-PRESENTS the model through a lens as a target artifact — built ON the lens,
       doing work it does not (mapping, contextualization).
   The same lens can feed both (the drift lens feeds the drift Finding AND DriftClose).

   The executable mechanism that RUNS these lives in `canvas.architecture.acts` (the realization
   seam — that split IS driftable, it crosses to code)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.grouping :refer [Grouping]]))

;; ── THE FOCUS: a Lens names a slice and carries its runnable selection ─────────────────────────

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

;; focuses fed to reasoning readings (non-gating findings)
(Lens survey      {:focus  "the whole model's structure"
                   :select ["every node" '[[?n :structure/of _]]]})
(Lens patterns    {:focus  "recurring structures across the model"
                   :select ["every relation source" '[[?r :rel/from ?n]]]})
(Lens consistency {:focus  "where contracts and structure align — or drift"
                   :select ["the contract-bearing authored operations"
                            '[(Operation ?n) (not [?n :val/extracted true])]]})
(Lens tar-pit     {:focus  "complexity hotspots — tangles worth attention"
                   :select ["the call-graph callers" '[(calls ?n ?callee)]]})
;; focuses fed to inspect readings (gating findings — trust verdicts)
(Lens integrity   {:focus  "the model's structural integrity — laws, partitions"
                   :select ["the whole model" '[[?n :structure/of _]]]})
(Lens coverage    {:focus  "spec ↔ code coverage"
                   :select ["the extracted code operations"
                            '[(Operation ?n) [?n :val/extracted true]]]})
(Lens drift       {:focus  "spec ↔ code divergence"
                   :select ["authored operations with no extracted twin"
                            '[(Operation ?n) (not [?n :val/extracted true]) (named ?n ?nm) (in-module ?n ?cm)
                              (not (Operation ?o) [?o :val/extracted true] (named ?o ?nm) (in-module ?o ?km)
                                   [(fukan.target.correspondence/module-corresponds? ?cm ?km)])]]})

(Grouping lens
  {:child [survey patterns consistency tar-pit integrity coverage drift]})

;; ── THE READING: a Finding reads through a Lens (inspect ⊂ reading) ────────────────────────────

(defstructure Finding
  "What reading the model through a Lens yields — an observation ABOUT the model. A Finding reads
   `:through` a Lens (the focus); evaluating that lens IS the act of reading, so there is no separate
   `Probe`. A gating Finding is a trust Signal that gates action (the inspect case); a non-gating
   Finding is a View, a perspective a human/LLM reasons with.

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

;; non-gating readings — perspectives to reason with (Views); each reads through its lens above
(Finding Survey "A structural overview of the whole model."
  {:through survey
   :gating  false})
(Finding Patterns "Recurring structural patterns across the model."
  {:through patterns
   :gating  false
   :holds   "a model with no recurring structures yields no reported patterns"})
(Finding Consistency "Where contracts and structure align — or drift."
  {:through consistency
   :gating  false})
(Finding TarPit "Complexity hotspots — tangles worth attention."
  {:through tar-pit
   :gating  false})
;; gating readings — trust verdicts (the inspect case → Signals)
(Finding IntegrityReport "Whether the model's structure holds together."
  {:through integrity
   :gating  true
   :holds   "a model with no law violations yields no reported violations"})
(Finding CoverageReport "How much of the target's code is spec-covered."
  {:through coverage
   :gating  true})
(Finding DriftReport "Where specifications and code have diverged."
  {:through drift
   :gating  true})

(Grouping probe
  {:child [Survey Patterns Consistency TarPit IntegrityReport CoverageReport DriftReport]})

;; ── THE SYNTHESIS: a Projection re-presents the model through a Lens ───────────────────────────

(defstructure ^:value Mapping
  "One source-kind → target-artifact rule within a projection — value-identified by
   its (from, to)."
  {:from :String     ; the source structure kind
   :to   :String})   ; the target artifact it becomes

(defstructure Projection
  "A projected representation of the model — a target we render it into. Two flavours, composing:
     a BASE projection renders source kinds directly — it `:maps` each focused kind to a target
     artifact (Blueprint → implementation specs; Docs → documentation).
     a CONTEXTUALIZATION renders THROUGH a base it `:contextualizes`, wrapping that base's output in
     a framing `:context` (DriftClose = Blueprint framed as drift to close; the same composes
     Blueprint with a 'new feature' or 'refactor' context). It adds no mappings of its own — it
     reuses the base's, told differently.
   Either flavour renders THROUGH a `Lens` (the WHAT). The same lens can feed a Finding and a
   projection (the drift lens feeds the drift inspect AND DriftClose)."
  {:through        Lens              ; the focus it renders through (the WHAT)
   :maps           [:* Mapping]      ; a BASE's source→artifact mappings (the HOW)
   :contextualizes [:? Projection]   ; a CONTEXTUALIZATION's base projection
   :context        [:? :String]}     ; the framing prose wrapped around the base render
  ;; a projection is one flavour or the other — it declares mappings (base) or frames
  ;; another (contextualization); neither would render nothing.
  (law "a projection is a base (declares mappings) or a contextualization (frames another)"
    (has-any :maps :contextualizes)))

(Projection Blueprint
  "The model projected to implementation code — the first projection target."
  {:through survey   ; renders through the whole-model focus (reused from the survey reading)
   :maps    [(Mapping {:from "an atomic value"    :to "a def"})
             (Mapping {:from "a record structure" :to "a Malli schema"})
             (Mapping {:from "a function"         :to "a defn"})
             (Mapping {:from "a law"              :to "a predicate"})]})

;; instruct ⊂ projection: DriftClose is a CONTEXTUALIZATION of Blueprint, not a new target — it
;; renders Blueprint's specs through the drift lens (the unrealized Operations) and frames them with
;; a drift-closing context. The same composing shape contextualizes Blueprint as a new feature, a
;; refactor, etc. — just a different context over the same base.
(Projection DriftClose
  "Blueprint, framed as drift to close — the unrealized Operations as instructions to implement."
  {:contextualizes Blueprint
   :through        drift
   :context        "The following capabilities are modelled but have no realizing function (drift). Implement each so the model and code correspond:"})

(Grouping projection
  {:child [Blueprint DriftClose]})
