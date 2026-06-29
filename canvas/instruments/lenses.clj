(ns canvas.instruments.lenses
  "fukan's own LENSES — the focuses it points at its own model, authored against the core `Lens`
   grammar. A lens is a description (its docstring) + a runnable datalog `:select` binding `?n`.
   These are TOOL-DEFINITIONS authored against the core `Lens` grammar — not fukan's design. A
   user project authors its own lenses the same way, in its own canvas."
  (:require [fukan.canvas.core.lens :refer [Lens]]
            [canvas.vocab.grouping :refer [Grouping]]))

;; fukan's focuses over its own model. A lens SELECTS a slice — it does NOT gate; checking is the
;; law/correspondence substrate's job (reading and checking are different acts, kept apart). So
;; there is no gating/non-gating partition here — every entry is just a focus.
(Lens survey "the whole model's structure"
  {:select '[[?n :structure/of _]]})
(Lens patterns "recurring structures across the model"
  {:select '[[?n :rel/kind _]]})
(Lens consistency "where operation names collide across modules"
  {:select '[(Operation ?n)]})
(Lens callers "the call-graph callers — nodes with outgoing edges"
  {:select '[[?r :rel/from ?n]]})
(Lens purity "operations that directly perform a consequential effect (io/state/require)"
  {:select '[(Operation ?n) (performs ?n ?e) [?e :val/name ?en] [(not= ?en "throws")]]})
(Lens drift "spec ↔ code divergence — authored operations with no extracted twin"
  ;; "no extracted twin" is exactly the `op-twin` defrelation negated — the same join the
  ;; Realization law uses: a not-join over the rule.
  {:select '[(authored ?n) (not-join [?n] (op-twin ?n ?o))]})

(Grouping lens
  {:child [survey patterns consistency callers purity drift]})
