(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the code `Module` vocabulary — the FACT vocabulary (`Ns`), the extraction that
   builds it, and the design↔Clojure CORRESPONDENCE (`Module ↦ Ns`, the twin ROOT).

   `Ns` is the codomain: the Clojure realization of a Module — an extracted namespace owning its
   functions. A namespace maps to a Module 1-on-1, so `Ns` reads no clj-kondo detail beyond its name and
   members. The essential `(correspond [Module ?m Ns ?ns] …)` pairs a canvas Module with its `Ns` twin —
   a canvas short-name is a separator-agnostic dotted suffix of the code namespace
   (`infra-model` ← `fukan.infra.model`) — into the ambient `corresponds`, the ROOT every `Operation ↦
   Fn` pairing nests within. The generic `Module` structure lives in `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.extraction.clojure.operation :refer [Fn]]
            [fukan.common.vocab.code.module :as module :refer [Module]]))

;; ── the FACT vocabulary: a Clojure namespace ─────────────────────────────────
(defstructure Ns
  "The Clojure realization of a Module — an EXTRACTED namespace, owning its functions via `:child`
   (a `contains` species). The fact-side root the design Module twins with by name."
  {:child [:* Fn]})

;; ── the correspondence: the ENTIRE Module ↔ Ns bridge (the twin ROOT) ────────
;; Matching is a flat identity query — a canvas short-name is a separator-agnostic dotted suffix of the
;; code namespace. Realization is the single-slot map `{:child :child}`: every design member is realized
;; by a same-role fact member. This pairing is the ROOT the Operation↦Fn correspondence nests within
;; (its match joins into the ambient `corresponds`). Coverage is a READING of the join, not a law.
(s/correspond [Module ?m Ns ?ns]
  [(named ?m ?mn) (named ?ns ?nn)
   [(name-match :qualified-suffix ?mn ?nn)]]
  {:child :child})

(defn extract-module
  "Build an extracted `Ns` InstanceValue named `mname` owning the functions named by `op-ids` (their
   natural-key ids) via `:child` — `substrate/Ref`s the assembler resolves to the `Fn` roots.
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ids]
  (sub/->InstanceValue ::Ns (str mname) nil nil
                       [{:rk :child :card :many :targets (mapv sub/->Ref op-ids)}] false))
