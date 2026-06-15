(ns canvas.architecture.acts
  "Executable realizations of the use-side domain concepts that genuinely reference CODE — the
   MECHANISM that RUNS a modelled concept, kept OFF the domain (which states only
   invariant/purpose + its laws). Each node `:realizes` a domain concept and carries the
   code-referencing form the engines/projector read:
     FindingCheck       — the runtime predicate that enforces a `Finding`'s `:holds` invariant
                          (read by the finding-code projector);
     ReadingComposition — the kernel capability a (gating) `Finding` composes when read (read by the
                          projector).

   The split earns its keep here because these reference code that can DRIFT from the concept;
   a `Lens`'s selection does not (it is model-native datalog) — so it lives ON the `Lens`, not
   here. Like `correspondence`, this is a seam — it knows the domain concepts and the realizing
   code."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [canvas.acts :as probe :refer [Finding]]
            [lib.code :refer [Operation]]
            [lib.grouping :refer [Grouping]]
            [canvas.architecture.kernel :as kernel]))

(defstructure FindingCheck
  "The runtime predicate that enforces a `Finding`'s `:holds` invariant — a `(fn [result
   target-db] → ok?)` checked against a probe RESULT + the target model. (The invariant itself
   stays stated on the domain `Finding`; this pins how it's verified.)"
  {:realizes Finding
   :enforces [{:payload :pred} :string]})    ; :enforces = recap; :pred = the predicate fn

(defstructure ReadingComposition
  "The modelled kernel capability a (gating) `Finding` composes when read (e.g. the integrity
   reading composes the kernel's `check`). The same `:calls` relation an op `Operation` uses — a
   reading IS an operation."
  {:realizes Finding
   :calls    [:* Operation]})

;; ── finding checks (the executable holds) ────────────────────────────────────
(FindingCheck c-patterns
  {:realizes probe/Patterns
   :enforces ["no recurring structure anywhere ⇒ no patterns reported"
              (fn [result target-db]
                (or (empty? (:observations result))
                    (seq (datascript.core/q
                           '[:find ?ft ?k ?tt
                             :where [?r1 :rel/from ?f1] [?r1 :rel/kind ?k] [?r1 :rel/to ?t1]
                                    [?f1 :structure/of ?ft] [?t1 :structure/of ?tt]
                                    [?r2 :rel/from ?f2] [?r2 :rel/kind ?k] [?r2 :rel/to ?t2]
                                    [?f2 :structure/of ?ft] [?t2 :structure/of ?tt]
                                    [(not= ?r1 ?r2)]]
                           target-db))))]})
(FindingCheck c-integrity
  {:realizes probe/IntegrityReport
   :enforces ["no law violations ⇒ no violations reported"
              (fn [result target-db]
                (or (empty? (:observations result))
                    (seq (fukan.canvas.core.structure/check target-db))))]})

;; ── reading compositions (the kernel capabilities a gating reading composes) ──
(ReadingComposition k-integrity
  {:realizes probe/IntegrityReport
   :calls    [kernel/check]})

(Grouping realization
  {:child [c-patterns c-integrity k-integrity]})
