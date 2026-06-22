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
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [fukan.canvas.core.structure :as s]))

(defn focus-nodes
  "Run datalog `:where` `clauses` (binding `?n` as the focused node) with the
   vocab-derived rules, returning the focus node-set (a set of eids). The shared
   evaluation engine behind both a stored lens and any ad-hoc focus."
  [db clauses]
  (set (d/q (vec (concat '[:find [?n ...] :in $ %] [:where] clauses))
            db (s/vocab-rules))))

(defn evaluate-lens
  "Run lens `lens-eid`'s own selection query — the `:val/query` payload it carries (its
   `:select` slot) — with the vocab-derived rules, returning the focus node-set (a set of
   eids). The selection is the focus stated runnably (model-native datalog), so it lives ON
   the lens; no `:realizes` indirection. TOTAL: a prose-only lens (no `:select`) is not
   evaluable, so it yields `nil` — a Maybe (`nil` = not evaluable, distinct from `#{}` =
   evaluated to no nodes), never a throw. This is a trusted-core reader over the Model, so it
   stays total (parse-don't-validate); deciding a prose-only lens is unevaluable is the
   caller's concern, not an exception in the core."
  [db lens-eid]
  (when-let [clauses (:val/query (d/entity db lens-eid))]
    (focus-nodes db clauses)))

(defn refine
  "Narrow a `focus` (a node-set) to its members that ALSO match `clauses` (binding `?n`,
   evaluated with the vocab-derived rules) — lens-within-lens. The composable step: a
   focus refined by a further query, so acts CHAIN by passing a refined focus forward
   (e.g. focus-nodes → refine → materialize-over / a scoped probe)."
  [db focus clauses]
  (set/intersection (set focus) (focus-nodes db clauses)))
