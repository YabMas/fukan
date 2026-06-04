(ns canvas.model.query-engine
  "Self-spec: fukan's KERNEL QUERY MACHINERY — the two namespaces that let laws and
   lenses read at domain altitude over the substrate:

     core.rules  (fukan.canvas.core.rules)  — derive datalog rules from the live
                                               vocabulary (a kind rule per structure,
                                               a relation rule per relation slot, plus
                                               fixed substrate rules), so a law/lens
                                               names domain predicates, not raw triples.
     core.lens   (fukan.canvas.core.lens)   — the lens ENGINE: run a selection query,
                                               WITH those rules, to a focus node-set.

   The two interlock through the kernel's `vocab-rules` bridge (modelled in
   canvas.model.kernel): `derive-rules` feeds it, and the lens engine evaluates a query
   against it. Modelled faithfully like canvas-source/target — each fn is a Stage with
   its shaped I/O + call edges. (The Lens *vocab* — the primitive that CARRIES a query —
   is the forward-looking view in canvas.model.lens; this is the machinery that RUNS it.)"
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    ;; core.rules — the live vocabulary → datalog rules
    (s/within-module "core.rules"
      (Kind "Keyword") (Kind "Symbol") (Kind "StructureDef") (Kind "Pred") (Kind "Rule")
      (Stage "rule-sym" (in [kw Keyword]) (out Symbol))                            ; pure: a tag → its rule head symbol
      (Stage "derive-rules" (in [structures [StructureDef]]) (in [scalar? Pred])   ; pure
        (out [Rule])
        (calls rule-sym)))

    ;; core.lens — the lens engine: a selection query → its focus sub-graph
    (s/within-module "core.lens"
      (Kind "Db") (Kind "Clause") (Kind "Eid")
      ;; the shared engine: run :where clauses (binding ?n) WITH the vocab-derived rules
      (Stage "focus-nodes" (in [db Db]) (in [clauses [Clause]]) (out [Eid])         ; pure (datascript + rules)
        (calls (across "core.structure" "vocab-rules")))
      ;; read a stored lens's :val/query, then delegate to focus-nodes
      (Stage "evaluate-lens" (in [db Db]) (in [lens-eid Eid]) (out [Eid]) (performs :throws)
        (calls focus-nodes))
      ;; refined focus (lens-within-lens): narrow a focus to members also matching clauses —
      ;; the composable step acts chain over
      (Stage "refine" (in [db Db]) (in [focus [Eid]]) (in [clauses [Clause]]) (out [Eid])
        (calls focus-nodes)))))
