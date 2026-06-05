(ns canvas.model.lens-engine
  "Self-spec: fukan's LENS ENGINE — `core.lens` (`fukan.canvas.core.lens`): run a
   selection query, WITH the vocab-derived rules, to a focus node-set, and refine a
   focus by a further query (lens-within-lens).

   This was split out of the rule-derivation machinery (`canvas.model.query-engine`,
   module `core.rules`) so the cross-module references stay acyclic: the engine calls
   the kernel's `vocab-rules` bridge (`core.structure/vocab-rules`), which in turn
   calls `core.rules/derive-rules` — a chain lens-engine → kernel → query-engine, not a
   cycle. (The Lens *vocab* — the primitive that CARRIES a query — is the forward-
   looking view in `canvas.model.lens`; this is the machinery that RUNS it.)

   Modelled faithfully — each fn a Stage with its shaped I/O + call edges."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]
            [canvas.model.kernel :as kernel]))

(def Db     (Kind))
(def Clause (Kind))
(def Eid    (Kind))

;; the shared engine: run :where clauses (binding ?n) WITH the vocab-derived rules
(def focus-nodes
  (Stage (in [db Db]) (in [clauses [Clause]]) (out [Eid])           ; pure (datascript + rules)
    (calls kernel/vocab-rules)))
;; read a stored lens's :val/query, then delegate to focus-nodes
(def evaluate-lens
  (Stage (in [db Db]) (in [lens-eid Eid]) (out [Eid]) (performs :throws)
    (calls focus-nodes)))
;; refined focus (lens-within-lens): narrow a focus to members also matching clauses —
;; the composable step acts chain over
(def refine
  (Stage (in [db Db]) (in [focus [Eid]]) (in [clauses [Clause]]) (out [Eid])
    (calls focus-nodes)))

(def core-lens
  (Module "core.lens" (child Db Clause Eid focus-nodes evaluate-lens refine)))
