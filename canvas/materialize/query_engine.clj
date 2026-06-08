(ns canvas.materialize.query-engine
  "Self-spec: fukan's RULE-DERIVATION machinery — `core.rules`
   (`fukan.canvas.core.rules`): derive datalog rules from the live vocabulary (a kind
   rule per structure, a relation rule per relation slot, plus fixed substrate rules),
   so a law/lens names domain predicates, not raw triples.

   It is the upstream half of the kernel's query machinery: the kernel's `vocab-rules`
   bridge (modelled in `canvas.materialize.kernel`) calls `derive-rules`, and the lens ENGINE
   (`canvas.materialize.lens-engine`, module `core.lens`) evaluates queries against those
   rules. `core.rules` references nothing else, so the dependency chain is acyclic
   (lens-engine → kernel → query-engine).

   Modelled faithfully like canvas-source/target — each fn an Operation with shaped I/O +
   call edges."
  (:require [canvas.materialize.vocab :refer [Kind Operation Subsystem]]))

(Subsystem core-rules
  "Derive datalog rules from the live vocabulary so laws/lenses read at domain altitude."
  (Kind Keyword) (Kind Symbol) (Kind StructureDef) (Kind Pred) (Kind Rule)   ; owned data-shapes
  (Operation ^:private rule-sym "A tag → its rule head symbol."
    [kw Keyword] -> Symbol)                                                   ; internal
  (Operation derive-rules "Derive the datalog rules from the live structure defs."
    [structures [StructureDef]] [scalar? Pred] -> [Rule]
    (calls rule-sym)))                                                        ; the exposed capability
