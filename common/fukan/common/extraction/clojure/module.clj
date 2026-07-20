(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the code `Module` vocabulary — the FACT theory (`Ns`), the extraction that
   builds it, and the design↔Clojure CORRESPONDENCE (`Module ↦ Ns`, the twin ROOT).

   `Ns` is the codomain: the Clojure realization of a Module — an extracted namespace owning its
   functions. A namespace maps to a Module 1-on-1, so `Ns` reads no clj-kondo detail beyond its name and
   members. `(bridge :qualified-suffix)` pairs a canvas Module with its `Ns` twin by the kernel's generic
   name-match strategy — a canvas short-name is a separator-agnostic dotted suffix of the code namespace
   (`infra-model` ← `fukan.infra.model`) — and every `Operation ↦ Fn` twin nests WITHIN a twinned
   Module/Ns pair. The generic `Module` structure lives in `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.extraction.clojure.operation :refer [Fn]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :as module :refer [Module]]))

;; ── the FACT theory: a Clojure namespace ─────────────────────────────────────
(defstructure Ns
  "The Clojure realization of a Module — an EXTRACTED namespace, owning its functions via `:child`
   (a `contains` species). The fact-side root the design Module twins with by name."
  {:child [:* Fn]})

;; ── the design→Clojure signature MORPHISM — one block ─────────────────────────
;; Every entry is `design-symbol :incl fact-expression`. Declared here (the last plugin file) because it
;; sees both fact structures — `Fn` (required) and `Ns` (above). It rides Operation/Module from OUTSIDE
;; (their `defstructure`s stay pure identity); every law is generated at :corresponds/*.
;;
;;   · OBJECT MAP (the sort maps): `Module :eq Ns` (a bijection, root, paired by name-match) and
;;     `Operation :eq [Fn :public]` (a bijection onto Fn's PUBLIC sub-sort — private fns are interior,
;;     neither sort images nor coupling endpoints). `:eq` ⇒ total (every design node twinned) + surjective
;;     (every codomain node has a preimage). Nested sorts pair by name within twinned containers.
;;   · RELATION MAPS (nested under Operation): `:delegates ⊑` the public call graph — the roll-up
;;     `calls·(private·calls)*` (a delegation routed through another PUBLIC op is two delegations, not
;;     one); its reverse (fidelity) is an ARCHITECTURAL concern Subsystem `:may-depend` already enforces,
;;     so `:sub` only. `:performs ⊒` `calls*·performs` — every effect the twin reaches must be declared.
;;   · the IDENTITY component (`in↦in`, `out↦out` over the shared `Schema` sort) is DERIVED — a morphism
;;     states only its non-identity maps; the shared-sort slots agree for free (types content-dedup).
(s/correspond
  (Module    :eq Ns  (bridge :qualified-suffix))
  (Operation :eq [Fn :public]
    (:delegates :sub [:cat :calls [:* [:cat [:test :private] :calls]]])
    (:performs  :sup [:cat [:* :calls] :performs])))

(defn extract-module
  "Build an extracted `Ns` InstanceValue named `mname` owning the functions named by `op-ids` (their
   natural-key ids) via `:child` — `substrate/Ref`s the assembler resolves to the `Fn` roots.
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ids]
  (sub/->InstanceValue ::Ns (str mname) nil nil
                       [{:rk :child :card :many :targets (mapv sub/->Ref op-ids)}] false))
