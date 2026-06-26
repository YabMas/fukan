(ns canvas.architecture.kernel.coverage
  "Self-spec: fukan's COVERAGE concern — `core.coverage` (`fukan.canvas.core.coverage`): the act↔code
   matching apparatus built on the Lens act. An extracted reader realizing a Lens (by a declared
   ReaderConvention) must be covered by a declared Lens of the same focus. Reads the kernel's shared
   StructureDb; its only deps are kernel-internal."
  (:require [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.cozo.query :as query]
            [canvas.architecture.kernel.substrate :as substrate]))

(Module core-coverage
  "Lens coverage — the read-surface dual of Encapsulation for fukan's Lens act, parameterized by a
   declared ReaderConvention (the prefix by which a Lens is realized as a reader)."
  (Operation uncovered-readers "Extracted readers with no covering Lens — the Coverage worklist."
    {:signature [:=> [:catn [:db substrate/StructureDb]] :any]
     :performs  [:throws]                          ; reaches the query layer's :throws via check
     :delegates [kernel/check kernel/structure-by-tag query/entity]})
  (Operation reader-realizes? "Reader rn realizes Lens ln under a prefix (rn = prefix+ln) — the coverage name match."
    {:signature [:=> [:catn [:rn :string] [:prefix :string] [:ln :string]] :boolean]}))
