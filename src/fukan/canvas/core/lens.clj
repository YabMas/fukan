(ns fukan.canvas.core.lens
  "Lens evaluation — a focus resolved to a genuine sub-graph.

   A lens carries ONE selection query (its `:val/query`: datalog `:where` clauses
   binding `?n` as the focused node). `evaluate-lens` runs it with the vocab-derived
   rules — so it reads at domain altitude — and returns the focus node-set; the
   induced relations among those nodes are the rest of the sub-graph. Transitive
   scope (closure) is just recursion within that single query, not a separate knob.

   This is the generic mechanic; the `Lens` primitive that carries the query is the
   self-model's own vocab. No cycle: it depends on the kernel for `vocab-rules`, the
   kernel does not depend back."
  (:require [datascript.core :as d]
            [fukan.canvas.core.structure :as s]))

(defn evaluate-lens
  "Run lens `lens-eid`'s selection query (its `:val/query` — datalog `:where` clauses
   binding `?n`) with the vocab-derived rules, returning the focus node-set (a set of
   eids). Throws if the lens carries no query (a prose-only lens isn't evaluable)."
  [db lens-eid]
  (let [clauses (:val/query (d/entity db lens-eid))]
    (when-not clauses
      (throw (ex-info "lens has no selection query — not evaluable"
                      {:lens lens-eid})))
    (set (d/q (vec (concat '[:find [?n ...] :in $ %] [:where] clauses))
              db (s/vocab-rules)))))
