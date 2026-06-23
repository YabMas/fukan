(ns canvas.architecture.kernel.rules
  "Self-spec: fukan's RULE-DERIVATION machinery — `core.rules` (`fukan.canvas.core.rules`):
   derive datalog rules from the live vocabulary (a kind rule per structure, a relation rule per
   relation slot, plus fixed substrate rules), so a law/lens names domain predicates, not raw
   triples. A boundary sketch — one exposed capability, `derive-rules`.

   It is the upstream half of the kernel's query machinery: the kernel's `vocab-rules` bridge
   delegates to `derive-rules`, and the lens ENGINE (`canvas.architecture.kernel.lens`) evaluates
   queries against those rules. `core.rules` references nothing else, so the chain is acyclic
   (lens-engine → kernel → query-engine)."
  (:require [canvas.vocab.code.kind :refer [Kind]] [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]))

(Module core-rules
  "Derive datalog rules from the live vocabulary so laws/lenses read at domain altitude."
  (Kind StructureDef) (Kind Pred) (Kind Rule)                                ; owned data-shapes
  (Operation derive-rules "Derive the datalog rules from the live structure defs."
    {:signature [:=> [:catn [:structures [:vector StructureDef]] [:scalar? Pred]] [:vector Rule]]}))
