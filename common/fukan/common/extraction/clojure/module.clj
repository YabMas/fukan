(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the code `Module` vocabulary — the FACT vocabulary (`Ns`), the extraction that
   builds it, and the design↔Clojure CORRESPONDENCE (`Module ↦ Ns`, the twin ROOT).

   `Ns` is the codomain: the Clojure realization of a Module — an extracted namespace owning its
   functions. A namespace maps to a Module 1-on-1, so `Ns` reads no clj-kondo detail beyond its name and
   members. The ordinary derived relation `module-twin` pairs a canvas Module with its `Ns` twin — a
   canvas short-name is a separator-agnostic dotted suffix of the code namespace
   (`infra-model` ← `fukan.infra.model`) — and every `Operation ↦ Fn` twin nests WITHIN a twinned
   Module/Ns pair. The generic `Module` structure lives in `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s :refer [defrelation defstructure]]
            [fukan.common.extraction.clojure.operation :refer [Fn]]
            [fukan.common.vocab.code.module :as module :refer [Module]]))

;; ── the FACT vocabulary: a Clojure namespace ─────────────────────────────────
(defstructure Ns
  "The Clojure realization of a Module — an EXTRACTED namespace, owning its functions via `:child`
   (a `contains` species). The fact-side root the design Module twins with by name."
  {:child [:* Fn]})

;; ── the correspondence: Module ↔ Ns, the root bridge between design and Clojure facts ──
;; Matching is ordinary Datalog. `correspond` only states which relation is the carrier and what
;; coverage it must have; it adds no second matching language.
(defrelation :module-twin
  "A design Module and extracted Ns whose names agree by qualified suffix."
  [?m ?ns]
  [(is ?m Module) (design ?m)
   (is ?ns Ns) (fact ?ns)
   (named ?m ?mn) (named ?ns ?nn)
   [(name-match :qualified-suffix ?mn ?nn)]])

(s/correspond-legacy Module Ns
  {:carrier :module-twin :coverage :both})

(defn extract-module
  "Build an extracted `Ns` InstanceValue named `mname` owning the functions named by `op-ids` (their
   natural-key ids) via `:child` — `substrate/Ref`s the assembler resolves to the `Fn` roots.
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ids]
  (sub/->InstanceValue ::Ns (str mname) nil nil
                       [{:rk :child :card :many :targets (mapv sub/->Ref op-ids)}] false))
