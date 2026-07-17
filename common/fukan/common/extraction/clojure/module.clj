(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the generic code `Module` vocabulary.

   Assembles an extracted Module fact from a namespace name and the natural-key ids of its extracted
   Operations. Unlike the Operation/Effect groundings this reads no clj-kondo detail — a namespace maps
   to a Module 1-on-1, so this is the generic extracted-root wrapper — but it is the Module half of the
   extraction seam, sibling to `extract-operation`, kept out of the vocab file (which carries only
   `Module`'s structure + correspondence). The generic `Module` structure lives in
   `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s]
            [fukan.common.vocab.code.module :as module :refer [Module]]))

;; ── the design↔Clojure correspondence: the bridged twin ROOT ─────────────────
;; `(bridge :qualified-suffix)` pairs a canvas Module with its extracted code twin by the kernel's
;; generic name-match strategy — a canvas short-name is a separator-agnostic dotted suffix of the code
;; namespace (`infra-model` ← `fukan.infra.model`) — and every Operation twin nests WITHIN a twinned
;; Module pair. The STRATEGY is the map from design to Clojure's module construct, so it belongs to
;; this plugin rather than the language-neutral vocabulary (where it sat until 2026-07-17).

(s/correspond Module (bridge :qualified-suffix))

(defn extract-module
  "Build an extracted Module InstanceValue named `mname` owning the Operations named by `op-ids` (their
   natural-key ids) via `:child` — `substrate/Ref`s the assembler resolves to the Operation roots.
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ids]
  (sub/->InstanceValue ::module/Module (str mname) nil nil
                       [{:rk :child :card :many :targets (mapv sub/->Ref op-ids)}] false))
