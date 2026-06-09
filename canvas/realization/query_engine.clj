(ns canvas.realization.query-engine
  "Self-spec: fukan's RULE-DERIVATION machinery — `core.rules`
   (`fukan.canvas.core.rules`): derive datalog rules from the live vocabulary (a kind
   rule per structure, a relation rule per relation slot, plus fixed substrate rules),
   so a law/lens names domain predicates, not raw triples.

   It is the upstream half of the kernel's query machinery: the kernel's `vocab-rules`
   bridge (modelled in `canvas.realization.kernel`) calls `derive-rules`, and the lens ENGINE
   (`canvas.realization.lens-engine`, module `core.lens`) evaluates queries against those
   rules. `core.rules` references nothing else, so the dependency chain is acyclic
   (lens-engine → kernel → query-engine).

   Modelled faithfully like canvas-source/target — each fn an Operation with shaped I/O +
   call edges."
  (:require [lib.code :refer [Kind Operation Module]]))

(Module core-rules
  "Derive datalog rules from the live vocabulary so laws/lenses read at domain altitude."
  (Kind Keyword) (Kind Symbol) (Kind StructureDef) (Kind Pred) (Kind Rule)   ; owned data-shapes
  (Operation ^:private rule-sym "A tag → its rule head symbol."
    (signature [:=> [:catn [:kw Keyword]] Symbol]))                           ; internal
  (Operation derive-rules "Derive the datalog rules from the live structure defs."
    (signature [:=> [:catn [:structures [:vector StructureDef]] [:scalar? Pred]] [:vector Rule]])
    (calls rule-sym)))                                                        ; the exposed capability
