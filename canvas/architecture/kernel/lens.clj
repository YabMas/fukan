(ns canvas.architecture.kernel.lens
  "Self-spec: fukan's LENS ENGINE — `core.lens` (`fukan.canvas.core.lens`): run a selection query,
   WITH the vocab-derived rules, to a focus node-set, and refine a focus by a further query
   (lens-within-lens). Split out of the rule-derivation machinery so the chain stays acyclic:
   lens-engine → kernel → query-engine. This module now ALSO owns the act grammar — the
   `Lens`/`Projection`/`Mapping`/`Check` structures — fukan-native apparatus, not domain vocab,
   alongside the machinery that runs it (incl. `run-checks`, the use-side dual of `check`)."
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.architecture.kernel.substrate :as substrate]))

(Module core-lens
  "The lens engine — evaluate a focus query (with vocab rules) and refine it."
  (Kind Clause)
  (Kind Eid :int)
  (Operation focus-nodes "Run :where clauses (binding ?n) with the vocab rules → focus node-set."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:clauses [:vector Clause]]] [:vector Eid]]
     :delegates [kernel/vocab-rules]})
  (Operation evaluate-lens "Read a stored lens's query, then resolve it to a focus node-set (TOTAL: a prose-only lens yields nil, never throws)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:lens-eid Eid]] [:vector Eid]]})
  (Operation refine "Narrow a focus to members also matching further clauses (lens-within-lens)."
    {:signature [:=> [:catn [:db substrate/StructureDb] [:focus [:vector Eid]] [:clauses [:vector Clause]]] [:vector Eid]]})
  (Operation run-checks "Evaluate every Check — a non-empty gated lens focus is a violation (the use-side dual of structure/check)."
    {:signature [:=> [:catn [:db substrate/StructureDb]] :any]}))
