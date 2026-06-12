(ns canvas.architecture.lens-engine
  "Self-spec: fukan's LENS ENGINE — `core.lens` (`fukan.canvas.core.lens`): run a selection query,
   WITH the vocab-derived rules, to a focus node-set, and refine a focus by a further query
   (lens-within-lens). Split out of the rule-derivation machinery so the chain stays acyclic:
   lens-engine → kernel → query-engine. (The Lens *vocab* — the primitive that carries a query —
   is the forward-looking view in `canvas.acts`; this is the machinery that RUNS it.)"
  (:require [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.kernel :as kernel]))

(Module core-lens
  "The lens engine — evaluate a focus query (with vocab rules) and refine it."
  (Kind Clause)
  (Kind Eid)
  (Operation focus-nodes "Run :where clauses (binding ?n) with the vocab rules → focus node-set."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:clauses [:vector Clause]]] [:vector Eid]]
     :delegates [kernel/vocab-rules]})
  (Operation evaluate-lens "Read a stored lens's query, then resolve it to a focus node-set."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:lens-eid Eid]] [:vector Eid]]
     :performs  [:throws]})
  (Operation refine "Narrow a focus to members also matching further clauses (lens-within-lens)."
    {:signature [:=> [:catn [:db kernel/StructureDb] [:focus [:vector Eid]] [:clauses [:vector Clause]]] [:vector Eid]]}))
