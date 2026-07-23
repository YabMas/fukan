(ns canvas.architecture.kernel.rules
  "Self-spec: fukan's FIXED SUBSTRATE RULES — `core.rules` (`fukan.canvas.core.rules`): the
   vocab-agnostic datalog the model always carries (`named` / `fact` / `design`), which the kernel's
   declaration registry (`structure/terms-of`) composes into the vocabulary-derived rules.

   The rule DERIVATION itself — a kind rule per structure, a relation rule per slot, inclusion /
   defrelation / transitive closures / the essential correspond's `corresponds`/`realized-*`
   pairing rules — lives in the kernel's declaration handlers (`canvas.architecture.kernel.structure`),
   NOT here. This module holds only the fixed substrate a pure data def; it exposes no operations and
   references nothing else, so the chain stays acyclic (lens-engine → kernel → query-engine)."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module core-rules
  "The fixed, vocab-agnostic substrate rules (named / fact / design) the model always carries; the
   kernel's declaration registry (structure/terms-of) composes them into the vocab-derived rules.")
