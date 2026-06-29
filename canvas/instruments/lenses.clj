(ns canvas.instruments.lenses
  "fukan's own LENSES — the focuses it points at its own model, authored against the
   core `Lens` grammar. A lens names a slice (`:focus`) and carries its runnable datalog
   `:select`. These are TOOL-DEFINITIONS authored against the core `Lens` grammar — not
   fukan's design. A user project authors its own lenses the same way, in its own canvas."
  (:require [fukan.canvas.core.lens :refer [Lens]]
            [fukan.canvas.core.coverage :refer [ReaderConvention]]
            [canvas.vocab.grouping :refer [Grouping]]))

;; fukan's reader convention: its Lens acts are realized by probe-prefixed reader functions
;; (the projection/probes leaves). The core Coverage law reads this to check reader→lens coverage.
(ReaderConvention {:prefix "probe-"})

;; fukan's focuses over its own model. A lens SELECTS a slice — it does NOT gate; checking is the
;; law/correspondence substrate's job (reading and checking are different acts, kept apart). So
;; there is no gating/non-gating partition here — every entry is just a focus.
(Lens survey      {:focus  "the whole model's structure"
                   :select ["every node" '[[?n :structure/of _]]]})
(Lens patterns    {:focus  "recurring structures across the model"
                   :select ["every reified relation" '[[?n :rel/kind _]]]})
(Lens consistency {:focus  "where operation names collide across modules"
                   :select ["every operation" '[(Operation ?n)]]})
(Lens callers     {:focus  "the call-graph callers — nodes with outgoing edges"
                   :select ["nodes with an outgoing edge" '[[?r :rel/from ?n]]]})
(Lens purity      {:focus  "operations that directly perform a consequential effect"
                   :select ["operations performing a consequential effect (io/state/require)"
                            '[(Operation ?n) (performs ?n ?e) [?e :val/name ?en] [(not= ?en "throws")]]]})
(Lens drift       {:focus  "spec ↔ code divergence"
                   :select ["authored operations with no extracted twin"
                            ;; "no extracted twin" is exactly the `op-twin` defrelation negated — the
                            ;; same join the Realization law uses: a not-join over the rule.
                            '[(authored ?n) (not-join [?n] (op-twin ?n ?o))]]})

(Grouping lens
  {:child [survey patterns consistency callers purity drift]})
