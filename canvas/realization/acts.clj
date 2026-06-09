(ns canvas.realization.acts
  "Executable realizations of the use-side domain acts — the MECHANISM that RUNS a modelled
   concept, kept OFF the domain (which states only focus/invariant/purpose + its laws). Each
   node `:realizes` a domain concept and carries the executable form the engines/projector
   read:
     LensSelection    — the datalog selection that computes a `Lens`'s focus (read by
                        `core.lens/evaluate-lens`);
     FindingCheck     — the runtime predicate that enforces a `Finding`'s `:holds` invariant
                        (read by the probe-code projector);
     ProbeComposition — the kernel capability a `Probe` invokes when run (read by the projector).

   The domain `Lens`/`Finding`/`Probe` STATE the focus/invariant/purpose; these PIN how they
   run. Like `correspondence`, this is a seam — it knows the domain concepts and the realizing
   code."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.vocabulary.lens :refer [Lens]]
            [canvas.vocabulary.probe :refer [Probe Finding]]
            [lib.code :refer [Operation]]
            [lib.grouping :refer [Module]]
            [canvas.domain.lens :as lens]
            [canvas.domain.probe :as probe]
            [canvas.realization.kernel :as kernel]))

(defstructure LensSelection
  "The datalog `:where` selection (binding `?n` as the focused node) that resolves a `Lens`'s
   prose focus to a genuine sub-graph. Evaluated with the vocab-derived rules, so it reads at
   domain altitude. A prose-only lens simply has no LensSelection (not evaluable)."
  (slot :realizes (one Lens))
  (slot :selects  (one :String) :payload :query))   ; :selects = recap; :query = the datalog form

(defstructure FindingCheck
  "The runtime predicate that enforces a `Finding`'s `:holds` invariant — a `(fn [result
   target-db] → ok?)` checked against a probe RESULT + the target model. (The invariant itself
   stays stated on the domain `Finding`; this pins how it's verified.)"
  (slot :realizes (one Finding))
  (slot :enforces (one :String) :payload :pred))    ; :enforces = recap; :pred = the predicate fn

(defstructure ProbeComposition
  "The modelled kernel capability a `Probe` invokes when run (e.g. the integrity probe composes
   the kernel's `check`). The same `:calls` relation an op `Operation` uses — a probe IS an operation."
  (slot :realizes (one Probe))
  (slot :calls    (many Operation)))

;; ── lens selections ──────────────────────────────────────────────────────────
(def s-survey      (LensSelection (realizes lens/survey)
                     (selects "every node" '[[?n :structure/of _]])))
(def s-patterns    (LensSelection (realizes lens/patterns)
                     (selects "every relation source" '[[?r :rel/from ?n]])))
(def s-consistency (LensSelection (realizes lens/consistency)
                     (selects "the contract-bearing authored operations"
                       '[(Operation ?n) (not [?n :val/extracted true])])))
(def s-tar-pit     (LensSelection (realizes lens/tar-pit)
                     (selects "the call-graph callers" '[(calls ?n ?callee)])))
(def s-integrity   (LensSelection (realizes lens/integrity)
                     (selects "the whole model" '[[?n :structure/of _]])))
(def s-coverage    (LensSelection (realizes lens/coverage)
                     (selects "the extracted code operations"
                       '[(Operation ?n) [?n :val/extracted true]])))
(def s-drift       (LensSelection (realizes lens/drift)
                     (selects "authored operations with no extracted twin"
                       '[(Operation ?n) (not [?n :val/extracted true]) (named ?n ?nm) (in-module ?n ?cm)
                         (not (Operation ?o) [?o :val/extracted true] (named ?o ?nm) (in-module ?o ?km)
                              [(fukan.target.correspondence/module-corresponds? ?cm ?km)])])))

;; ── finding checks (the executable holds) ────────────────────────────────────
(def c-patterns
  (FindingCheck (realizes probe/Patterns)
    (enforces "no recurring structure anywhere ⇒ no patterns reported"
      (fn [result target-db]
        (or (empty? (:observations result))
            (seq (datascript.core/q
                   '[:find ?ft ?k ?tt
                     :where [?r1 :rel/from ?f1] [?r1 :rel/kind ?k] [?r1 :rel/to ?t1]
                            [?f1 :structure/of ?ft] [?t1 :structure/of ?tt]
                            [?r2 :rel/from ?f2] [?r2 :rel/kind ?k] [?r2 :rel/to ?t2]
                            [?f2 :structure/of ?ft] [?t2 :structure/of ?tt]
                            [(not= ?r1 ?r2)]]
                   target-db)))))))
(def c-integrity
  (FindingCheck (realizes probe/IntegrityReport)
    (enforces "no law violations ⇒ no violations reported"
      (fn [result target-db]
        (or (empty? (:observations result))
            (seq (fukan.canvas.core.structure/check target-db)))))))

;; ── probe compositions (the kernel capabilities a probe invokes) ─────────────
(def k-integrity (ProbeComposition (realizes probe/integrity) (calls kernel/check)))

(def realization
  (Module (child s-survey s-patterns s-consistency s-tar-pit s-integrity s-coverage s-drift
                 c-patterns c-integrity k-integrity)))
