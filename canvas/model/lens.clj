(ns canvas.model.lens
  "Self-spec: fukan's LENSES — the focuses over the model. A lens names WHAT to attend
   to AND carries the datalog selection query that resolves that focus to a genuine
   sub-graph (its `:query` payload, binding `?n`; evaluated by the lens engine
   `core.lens/evaluate-lens` with the vocab-derived rules). It is cross-cutting: the
   same focus feeds different acts — a probe reads through it (patterns → a view,
   integrity → a trust signal), a projection renders through it (Blueprint, DriftClose).
   The probes that consume these lenses live in the `probe` view; the overview's
   `Faculty \"Lens\"` is `realized-by` this module + the engine `core.lens`.

   A query is the candidate sub-graph the act attends to — the act (probe/projection)
   does the heavy computation (probe-patterns counts recurrences; correspondence
   computes actual drift). So the queries are honest focuses, not the analyses
   themselves; some overlap (consistency and drift both attend to Stages, differently)."
  (:require [fukan.canvas.core.structure :as s]
            [canvas.vocab.lens :refer [Lens]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "lens"
      ;; focuses fed to reasoning probes (non-gating findings)
      (Lens "survey"      (focus "the whole model's structure"
                                 '[[?n :structure/of _]]))                 ; every node
      (Lens "patterns"    (focus "recurring structures across the model"
                                 '[[?r :rel/from ?n]]))                    ; the relational fabric (relation sources)
      (Lens "consistency" (focus "where contracts and structure align — or drift"
                                 '[(Stage ?n)]))                           ; the contract-bearing units
      (Lens "tar-pit"     (focus "complexity hotspots — tangles worth attention"
                                 '[(calls ?n ?callee)]))                   ; the call graph (callers)
      ;; focuses fed to inspect probes (gating findings — trust verdicts)
      (Lens "integrity"   (focus "the model's structural integrity — laws, partitions"
                                 '[[?n :structure/of _]]))                 ; the whole model (laws span it)
      (Lens "coverage"    (focus "spec ↔ code coverage"
                                 '[(Operation ?n)]))                       ; the code surface
      (Lens "drift"       (focus "spec ↔ code divergence"
                                 ;; the unrealized-Stage pattern — a Stage with no
                                 ;; same-named Operation in a corresponding module
                                 '[(Stage ?n) (named ?n ?nm) (in-module ?n ?cm)
                                   (not (Operation ?o) (named ?o ?nm) (in-module ?o ?km)
                                        [(fukan.target.correspondence/module-corresponds? ?cm ?km)])])))))
