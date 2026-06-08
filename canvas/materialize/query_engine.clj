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

(def Keyword      (Kind))
(def Symbol       (Kind))
(def StructureDef (Kind))
(def Pred         (Kind))
(def Rule         (Kind))

(def rule-sym
  (Operation (in [kw Keyword]) (out Symbol)))                            ; pure: a tag → its rule head symbol
(def derive-rules
  (Operation (in [structures [StructureDef]]) (in [scalar? Pred])    ; pure
    (out [Rule])
    (calls rule-sym)))

(def core-rules
  (Subsystem
    (exposes derive-rules)                         ; the rule-derivation capability
    (owns Keyword Symbol StructureDef Pred Rule)
    (child rule-sym)))
