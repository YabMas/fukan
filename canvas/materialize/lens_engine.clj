(ns canvas.materialize.lens-engine
  "Self-spec: fukan's LENS ENGINE — `core.lens` (`fukan.canvas.core.lens`): run a
   selection query, WITH the vocab-derived rules, to a focus node-set, and refine a
   focus by a further query (lens-within-lens).

   This was split out of the rule-derivation machinery (`canvas.materialize.query-engine`,
   module `core.rules`) so the cross-module references stay acyclic: the engine calls
   the kernel's `vocab-rules` bridge (`core.structure/vocab-rules`), which in turn
   calls `core.rules/derive-rules` — a chain lens-engine → kernel → query-engine, not a
   cycle. (The Lens *vocab* — the primitive that CARRIES a query — is the forward-
   looking view in `canvas.domain.lens-model`; this is the machinery that RUNS it.)

   Modelled faithfully — each fn an Operation with its shaped I/O + call edges."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.kernel :as kernel]))

(def Clause (Kind))
(def Eid    (Kind))

;; the shared engine: run :where clauses (binding ?n) WITH the vocab-derived rules
(def focus-nodes
  (Operation [db kernel/StructureDb] [clauses [Clause]] -> [Eid]           ; pure (datascript + rules)
    (calls kernel/vocab-rules)))
;; read a stored lens's :val/query, then delegate to focus-nodes
(def evaluate-lens
  (Operation [db kernel/StructureDb] [lens-eid Eid] -> [Eid] (performs :throws)
    (calls focus-nodes)))
;; refined focus (lens-within-lens): narrow a focus to members also matching clauses —
;; the composable step acts chain over
(def refine
  (Operation [db kernel/StructureDb] [focus [Eid]] [clauses [Clause]] -> [Eid]
    (calls focus-nodes)))

(def core-lens
  (Subsystem
     (exposes focus-nodes evaluate-lens refine)     ; the lens-evaluation API
     (owns Clause Eid)))
