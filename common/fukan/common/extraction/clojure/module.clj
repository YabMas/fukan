(ns fukan.common.extraction.clojure.module
  "Clojure grounding for the generic code `Module` vocabulary.

   Assembles an extracted Module fact from a namespace name and its extracted Operation facts.
   Unlike the Operation/Effect groundings this reads no clj-kondo detail — a namespace maps to a
   Module 1-on-1, so this is the generic extracted-root wrapper — but it is the Module half of the
   extraction seam, sibling to `extract-operation`, kept out of the vocab file (which carries only
   `Module`'s structure + correspondence). The generic `Module` structure lives in
   `fukan.common.vocab.code.module`."
  (:require [fukan.canvas.core.substrate :as sub]
            [fukan.common.vocab.code.module :as module]))

(defn extract-module
  "Build an extracted Module InstanceValue named `mname` owning the given extracted Operation
   InstanceValues (`op-ivs`) via `:child`. Provenance (`:val/extracted`) is stamped by the BUILD
   at the merge (`substrate/stamp-stratum`), not here."
  [mname op-ivs]
  (sub/->InstanceValue ::module/Module (str mname) nil nil
                       [{:rk :child :card :many :targets (vec op-ivs)}] false))
