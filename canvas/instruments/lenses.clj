(ns canvas.instruments.lenses
  "fukan's own LENSES — the focuses it points at its own model, authored against the
   core `Lens` grammar. A lens names a slice (`:focus`) and carries its runnable datalog
   `:select`. These are TOOL-DEFINITIONS authored against the core `Lens` grammar — not
   fukan's design. A user project authors its own lenses the same way, in its own canvas."
  (:require [fukan.canvas.core.lens :refer [Lens]]
            [canvas.vocab.grouping :refer [Grouping]]))

;; fukan's focuses over its own model. A lens SELECTS a slice — it does NOT gate; checking is the
;; law/correspondence substrate's job (reading and checking are different acts, kept apart). So
;; there is no gating/non-gating partition here — every entry is just a focus.
(Lens survey      {:focus  "the whole model's structure"
                   :select ["every node" '[[?n :structure/of _]]]})
(Lens patterns    {:focus  "recurring structures across the model"
                   :select ["every relation source" '[[?r :rel/from ?n]]]})
(Lens consistency {:focus  "where contracts and structure align — or drift"
                   :select ["the contract-bearing authored operations"
                            '[(Operation ?n) (not [?n :val/extracted true])]]})
(Lens tar-pit     {:focus  "complexity hotspots — tangles worth attention"
                   :select ["the call-graph callers" '[(calls ?n ?callee)]]})
(Lens purity      {:focus  "operations that directly perform a consequential effect"
                   :select ["operations performing a consequential effect (io/state/require)"
                            '[(Operation ?n) (performs ?n ?e) [?e :val/name ?en] [(not= ?en "throws")]]]})
(Lens integrity   {:focus  "the model's structural integrity — laws, partitions"
                   :select ["the whole model" '[[?n :structure/of _]]]})
(Lens coverage    {:focus  "spec ↔ code coverage"
                   :select ["the extracted code operations"
                            '[(Operation ?n) [?n :val/extracted true]]]})
(Lens drift       {:focus  "spec ↔ code divergence"
                   :select ["authored operations with no extracted twin"
                            ;; "no extracted twin" is exactly the `op-twin` defrelation negated — the
                            ;; same join the Realization law uses: a not-join over the rule.
                            '[(Operation ?n) (not [?n :val/extracted true]) (not-join [?n] (op-twin ?n ?o))]]})
(Lens type-drift  {:focus  "spec ↔ code TYPE divergence — where a modelled signature and its realizing function disagree"
                   :select ["authored operations whose realizing twin carries a type annotation"
                            '[(Operation ?n) (not [?n :val/extracted true]) (named ?n ?nm) (in-module ?n ?cm)
                              (Operation ?o) [?o :val/extracted true] (named ?o ?nm) (in-module ?o ?km)
                              [(canvas.vocab.code.module/module-corresponds? ?cm ?km)]
                              [?o :val/sig _]]]})

(Grouping lens
  {:child [survey patterns consistency tar-pit purity integrity coverage drift type-drift]})
