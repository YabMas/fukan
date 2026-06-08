(ns canvas.materialize.lens-engine
  "Self-spec: fukan's LENS ENGINE — `core.lens` (`fukan.canvas.core.lens`): run a selection query,
   WITH the vocab-derived rules, to a focus node-set, and refine a focus by a further query
   (lens-within-lens). Split out of the rule-derivation machinery so the chain stays acyclic:
   lens-engine → kernel → query-engine. (The Lens *vocab* — the primitive that carries a query —
   is the forward-looking view in `canvas.domain.lens-model`; this is the machinery that RUNS it.)"
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]))

(Subsystem core-lens
  "The lens engine — evaluate a focus query (with vocab rules) and refine it."
  (Kind Clause)
  (Kind Eid)
  (Operation focus-nodes "Run :where clauses (binding ?n) with the vocab rules → focus node-set."
    [db kernel/StructureDb] [clauses [:vector Clause]] -> [:vector Eid]
    (calls kernel/vocab-rules))
  (Operation evaluate-lens "Read a stored lens's query, then delegate to focus-nodes."
    [db kernel/StructureDb] [lens-eid Eid] -> [:vector Eid] (performs :throws)
    (calls focus-nodes))
  (Operation refine "Narrow a focus to members also matching further clauses (lens-within-lens)."
    [db kernel/StructureDb] [focus [:vector Eid]] [clauses [:vector Clause]] -> [:vector Eid]
    (calls focus-nodes)))
