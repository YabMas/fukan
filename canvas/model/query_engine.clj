(ns canvas.model.query-engine
  "Self-spec: fukan's RULE-DERIVATION machinery — `core.rules`
   (`fukan.canvas.core.rules`): derive datalog rules from the live vocabulary (a kind
   rule per structure, a relation rule per relation slot, plus fixed substrate rules),
   so a law/lens names domain predicates, not raw triples.

   It is the upstream half of the kernel's query machinery: the kernel's `vocab-rules`
   bridge (modelled in `canvas.model.kernel`) calls `derive-rules`, and the lens ENGINE
   (`canvas.model.lens-engine`, module `core.lens`) evaluates queries against those
   rules. `core.rules` references nothing else, so the dependency chain is acyclic
   (lens-engine → kernel → query-engine).

   Modelled faithfully like canvas-source/target — each fn a Stage with shaped I/O +
   call edges."
  (:require [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]
            [canvas.vocab.arch :refer [Module]]))

(def Keyword      (Kind "Keyword"))
(def Symbol       (Kind "Symbol"))
(def StructureDef (Kind "StructureDef"))
(def Pred         (Kind "Pred"))
(def Rule         (Kind "Rule"))

(def rule-sym
  (Stage "rule-sym" (in [kw Keyword]) (out Symbol)))                            ; pure: a tag → its rule head symbol
(def derive-rules
  (Stage "derive-rules" (in [structures [StructureDef]]) (in [scalar? Pred])    ; pure
    (out [Rule])
    (calls rule-sym)))

(def core-rules
  (Module "core.rules" (child Keyword Symbol StructureDef Pred Rule
                              rule-sym derive-rules)))
