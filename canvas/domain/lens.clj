(ns canvas.domain.lens
  "Self-spec: fukan's LENSES — the focuses over the model. A lens names WHAT to attend to
   (prose `:focus`) and carries its own `:select` — the datalog selection that resolves the
   focus to a genuine sub-graph (model-native datalog, evaluated by `core.lens/evaluate-lens`;
   it is the focus stated runnably, not a separate realization). A lens is cross-cutting: the
   same focus feeds different acts — a probe reads through it (patterns → a view, integrity →
   a trust signal), a projection renders through it (Blueprint, DriftClose). The probes that
   consume these lenses live in the `probe` view."
  (:require [canvas.vocabulary.act :refer [Lens]]
            [lib.grouping :refer [Grouping]]))

;; focuses fed to reasoning probes (non-gating findings)
(Lens survey      {:focus  "the whole model's structure"
                   :select ["every node" '[[?n :structure/of _]]]})
(Lens patterns    {:focus  "recurring structures across the model"
                   :select ["every relation source" '[[?r :rel/from ?n]]]})
(Lens consistency {:focus  "where contracts and structure align — or drift"
                   :select ["the contract-bearing authored operations"
                            '[(Operation ?n) (not [?n :val/extracted true])]]})
(Lens tar-pit     {:focus  "complexity hotspots — tangles worth attention"
                   :select ["the call-graph callers" '[(calls ?n ?callee)]]})
;; focuses fed to inspect probes (gating findings — trust verdicts)
(Lens integrity   {:focus  "the model's structural integrity — laws, partitions"
                   :select ["the whole model" '[[?n :structure/of _]]]})
(Lens coverage    {:focus  "spec ↔ code coverage"
                   :select ["the extracted code operations"
                            '[(Operation ?n) [?n :val/extracted true]]]})
(Lens drift       {:focus  "spec ↔ code divergence"
                   :select ["authored operations with no extracted twin"
                            '[(Operation ?n) (not [?n :val/extracted true]) (named ?n ?nm) (in-module ?n ?cm)
                              (not (Operation ?o) [?o :val/extracted true] (named ?o ?nm) (in-module ?o ?km)
                                   [(fukan.target.correspondence/module-corresponds? ?cm ?km)])]]})

(Grouping lens
  {:child [survey patterns consistency tar-pit integrity coverage drift]})
