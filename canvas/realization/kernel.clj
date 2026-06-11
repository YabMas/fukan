(ns canvas.realization.kernel
  "Self-spec: fukan's defstructure KERNEL (`fukan.canvas.core.structure`) — the part
   BELOW the registry. The defstructure layer itself (Structure = a composition of
   slots + laws, cardinalities, type targets) is NOT hand-modelled here: grammar
   reflection (`lib.grammar/with-grammar`, run by every build) derives it from the
   live registry, where it can never drift. What remains hand-modelled is the
   SUBSTRATE the registry sits on — Node and the reified Relation with its
   from/to/kind/label/order — which is not registry data, so reflection cannot
   reach it (modelled with the schema vocab, `canvas.vocabulary.meta`).

   The kernel also PROVIDES one operation — `check` (laws → violations), the
   canonical integrity inspect. It is modelled here as a capability (an op `Operation`)
   because code is a projection of the model 1-on-1: the agent's integrity probe
   composes `check`, so `check` must exist in the model, not just in code.

   (Concept instance vars are `c-`-prefixed — `String` would shadow the class
   referral — with `^{:name …}` restoring the concept names; fukan's own kernel
   modelled in fukan's own meta-vocab.)"
  (:require [canvas.vocabulary.meta :refer [Concept MetaSlot]]
            [lib.code :refer [Kind Operation Module]]
            [canvas.realization.query-engine :as query-engine]))

;; leaf concepts — the scalar types the substrate's attributes carry
(Concept ^{:name "Keyword"} c-Keyword)
(Concept ^{:name "String"}  c-String)
(Concept ^{:name "Int"}     c-Int)
(Concept ^{:name "Node"}    c-Node
  "An instance: identified by name + uuid, or by content when value-typed.")

;; the substrate seam: a reified slot relation (below the registry — reflection
;; cannot derive this; everything above it, lib.grammar does)
(Concept ^{:name "Relation"} c-Relation
  "A reified slot value — a kinded edge between Nodes, carrying optional label/order."
  {:slot [(MetaSlot {:name "from"  :cardinality "one"      :of c-Node})
          (MetaSlot {:name "to"    :cardinality "one"      :of c-Node})
          (MetaSlot {:name "kind"  :cardinality "one"      :of c-Keyword})
          (MetaSlot {:name "label" :cardinality "optional" :of c-String})
          (MetaSlot {:name "order" :cardinality "optional" :of c-Int})]})

;; the kernel's one OPERATION (modelled with the op vocab): check runs every
;; structure's laws over the db and yields the violations that hold. This is
;; the canonical integrity inspect — the capability the agent's integrity probe
;; composes, so it lives in the model 1-on-1 with the code.
(Kind StructureDb
  "The unified structure db — the data realization of the domain `Model` faculty
   (canvas.domain.faculties): a datascript db of structure instances +
   their reified relations. Owned here; every subsystem adopts this one Kind.")
(Kind Violation)
(Kind Rule)

;; the rules-cut bridge: derive the datalog rules from the live vocabulary
;; (delegating to core.rules) — the rules check + the lens engine inject so
;; laws/lenses read at domain altitude.
(Operation vocab-rules
  "The datalog rules derived from the live vocabulary, injected into laws/lenses."
  {:signature [:=> [:cat] [:vector Rule]]
   :calls     [query-engine/derive-rules]})
(Operation check
  "Run every structure's laws over the model db; yield the violations."
  {:signature [:=> [:catn [:db StructureDb]] [:vector Violation]]
   :calls     [vocab-rules]})

(Module core-structure
  {:exposes [check vocab-rules]                  ; the kernel capabilities others compose
   :owns    [StructureDb Violation Rule]         ; the data-shapes core.structure decides (others adopt StructureDb)
   :child   [c-Keyword c-String c-Int c-Node c-Relation]})
