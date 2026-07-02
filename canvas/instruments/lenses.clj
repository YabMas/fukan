(ns canvas.instruments.lenses
  "fukan's own LENSES — the focuses it points at its own model, authored against the core `Lens`
   grammar. A lens is a description (its docstring) + a runnable datalog `:select` binding `?n`.
   These are TOOL-DEFINITIONS authored against the core `Lens` grammar — not fukan's design. A
   user project authors its own lenses the same way, in its own canvas."
  (:require [fukan.canvas.core.lens :refer [Lens]]
            [canvas.vocab.grouping :refer [Grouping]]))

;; fukan's focuses over its own model. A lens is UNOPINIONATED — it names the structural SLICE it
;; highlights, not an interpretation of what it surfaces (that belongs to whatever composes over it:
;; a Projection, a reading, a future tool-set). So each name describes the slice, never an intent.
;; A lens SELECTS — it does NOT gate; checking is the law/correspondence substrate's job (reading and
;; checking are different acts). There is no gating/non-gating partition here — every entry is a focus.
(Lens everything "every node — the whole model, unnarrowed (the maximal focus; whole-model projections render through it)"
  {:select '[[?n :structure/of _]]})
(Lens relations "every reified relation"
  {:select '[[?n :rel/kind _]]})
(Lens operations "every operation"
  {:select '[(Operation ?n)]})
(Lens unrealized-operations "authored operations with no extracted twin"
  ;; "no extracted twin" is exactly the `op-twin` defrelation negated — the same join the
  ;; Realization law uses: a not-join over the rule.
  {:select '[(authored ?n) (not-join [?n] (op-twin ?n ?o))]})
(Lens modules "every module"
  {:select '[(Module ?n)]})

(Grouping lens
  {:child [everything relations operations modules unrealized-operations]})
