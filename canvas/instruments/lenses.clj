(ns canvas.instruments.lenses
  "fukan's own LENSES — the focuses it points at its own model, authored against the
   `lib.lens` grammar. A lens names a slice (`:focus`) and carries its runnable datalog
   `:select`. These are TOOL-DEFINITIONS, not fukan's design: the Lens *concept* portrait
   lives in `canvas.subject`; the authoring grammar in `lib.lens`. A user project authors
   its own lenses the same way, in its own canvas."
  (:require [lib.lens :refer [Lens]]
            [lib.grouping :refer [Grouping]]))

;; focuses fed to reasoning readings (non-gating findings)
(Lens survey      {:focus  "the whole model's structure"
                   :select ["every node" '[[?n :structure/of _]]]})
(Lens patterns    {:focus  "recurring structures across the model"
                   :select ["every relation source" '[[?r :rel/from ?n]]]})
(Lens consistency {:focus  "where contracts and structure align — or drift"
                   :select ["the contract-bearing authored operations"
                            '[(Operation ?n) (not [?n :val/extracted true])]]})
(Lens tar-pit     {:focus  "complexity hotspots — tangles worth attention"
                   :select ["the call-graph callers" '[(calls ?n ?callee)]]})
;; focuses fed to inspect readings (gating findings — trust verdicts)
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
