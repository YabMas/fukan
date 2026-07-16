(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the generic code `Module` vocabulary.

   Assembles an extracted Module fact from a namespace name and the natural-key ids of its extracted
   Operations. Unlike the Operation/Effect groundings this reads no clj-kondo detail — a namespace maps
   to a Module 1-on-1, so this is the generic extracted-root wrapper — but it is the Module half of the
   extraction seam, sibling to `extract-operation`, kept out of the vocab file (which carries only
   `Module`'s structure + correspondence). The generic `Module` structure lives in
   `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.common.vocab.code.module :as module]))

(defn extract-module
  "Build an extracted Module InstanceValue named `mname` owning the Operations named by `op-ids` (their
   natural-key ids) via `:child` — `substrate/Ref`s the assembler resolves to the Operation roots.
   Provenance (`:val/extracted`) is stamped by the BUILD at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ids]
  (sub/->InstanceValue ::module/Module (str mname) nil nil
                       [{:rk :child :card :many :targets (mapv sub/->Ref op-ids)}] false))
