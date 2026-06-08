(ns canvas.domain.probe
  "The fukan-on-fukan model's PROBE layer — one of the two acts on the model. A `Probe`
   READS the model through a `Lens` and yields a `Finding`: it observes / extracts,
   leaving the model unchanged (its complement, a `Projection`, RE-PRESENTS the model as a
   target artifact). Probe and Projection are complementary acts — analysis vs synthesis.

   INSPECT ⊂ PROBE: an inspect is just a probe whose finding GATES action (a trust /
   health verdict — a Signal). A non-gating finding is a View, a perspective to reason
   with. So inspect is not a separate structure — it is a probe whose `Finding` has
   `:gating true`, queryable as data. (The kernel already ships one such probe:
   `structure/check`, laws → violations, is the canonical integrity inspect.)

   Vocab-only canvas spec (no build-canvas)."
  (:require [fukan.canvas.core.structure :refer [defstructure]]))

(defstructure Finding
  "What a probe yields — an observation ABOUT the model. A gating finding is a trust
   Signal that gates action (the inspect case); a non-gating finding is a View, a
   perspective a human/LLM reasons with.

   A finding may STATE a CONTRACT — a `:holds` invariant (the human's correctness spec for
   the probe's output). The executable check of that invariant (a `(fn [result target-db] →
   ok?)`) is realization mechanism — a `FindingCheck` in `canvas.materialize.realization`,
   surfaced by the projector as a runtime gate. The complement of this observation act is a
   `Projection` (synthesis)."
  (slot :doc    (optional :String))
  (slot :gating (one :Bool))       ; gating → a trust Signal (inspect); else a View
  (slot :holds  (optional :String)) ; the stated invariant (its executable check lives in the realization view)
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
   `canvas.materialize.realization`."
  (slot :doc     (optional :String))
  (slot :through (one Lens))       ; the focus it reads through
  (slot :yields  (one Finding)))   ; the observation it produces

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
