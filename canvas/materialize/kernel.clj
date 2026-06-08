(ns canvas.materialize.kernel
  "Self-spec: fukan's defstructure KERNEL (`fukan.canvas.core.structure`), modelled
   reflexively with the schema vocab (`canvas.language.meta`). The deepest self-
   reference in the corpus — the model describes the very primitive that defines
   it: the substrate (Node, reified Relation), the defstructure layer (Slot, Law),
   and at its heart `Structure` = a composition of Slots + Laws.

   First cut: it captures the composition + the slot's (cardinality, target). It
   deliberately omits value-identity / ordered / the reader / the scalar-type
   registry — to be added only if the model is felt to want them.

   The kernel also PROVIDES one operation — `check` (laws → violations), the
   canonical integrity inspect. It is modelled here as a capability (an op `Operation`)
   because code is a projection of the model 1-on-1: the agent's integrity probe
   composes `check`, so `check` must exist in the model, not just in code.

   (Concept instance vars are `c-`-prefixed so they don't shadow the `Concept`
   constructor — fukan's own kernel modelled in fukan's own meta-vocab.)"
  (:require [canvas.language.meta :refer [Concept MetaSlot]]
            [canvas.materialize.vocab :refer [Kind Operation Subsystem]]
            [canvas.materialize.query-engine :as query-engine]))

;; leaf concepts — scalar types, the substrate's atoms, and (reflexively) the
;; meta-type Concept itself
(def c-Keyword     (Concept "Keyword"))
(def c-String      (Concept "String"))
(def c-Bool        (Concept "Bool"))
(def c-Int         (Concept "Int"))
(def c-Concept     (Concept "Concept" (doc "A type/structure — the meta-type this model is built from.")))
(def c-Node        (Concept "Node" (doc "An instance: identified by name + uuid, or by content when value-typed.")))
(def c-Cardinality (Concept "Cardinality" (doc "one | some | many | optional | ordered.")))

;; the substrate seam: a reified slot relation
(def c-Relation
  (Concept "Relation"
    (doc "A reified slot value — a kinded edge between Nodes, carrying optional label/order.")
    (slot (MetaSlot (name "from")  (cardinality "one")      (of c-Node)))
    (slot (MetaSlot (name "to")    (cardinality "one")      (of c-Node)))
    (slot (MetaSlot (name "kind")  (cardinality "one")      (of c-Keyword)))
    (slot (MetaSlot (name "label") (cardinality "optional") (of c-String)))
    (slot (MetaSlot (name "order") (cardinality "optional") (of c-Int)))))

;; the defstructure layer
(def c-Slot
  (Concept "Slot"
    (doc "A relation-with-a-law: a named relation, a cardinality, and a target concept.")
    (slot (MetaSlot (name "rel")         (cardinality "one") (of c-Keyword)))
    (slot (MetaSlot (name "cardinality") (cardinality "one") (of c-Cardinality)))
    (slot (MetaSlot (name "target")      (cardinality "one") (of c-Concept)))))
(def c-Law
  (Concept "Law"
    (doc "A datalog constraint that must hold of a structure's instances.")
    (slot (MetaSlot (name "desc")      (cardinality "one")      (of c-String)))
    (slot (MetaSlot (name "recursive") (cardinality "optional") (of c-Bool)))))

;; the heart: a structure IS a composition of Slots + Laws
(def c-Structure
  (Concept "Structure"
    (doc "The kernel's heart: a composition of Slots and Laws — the model IS this db.")
    (slot (MetaSlot (name "slot")  (cardinality "many")     (of c-Slot)))
    (slot (MetaSlot (name "law")   (cardinality "many")     (of c-Law)))
    (slot (MetaSlot (name "value") (cardinality "optional") (of c-Bool)))))

;; the kernel's one OPERATION (modelled with the op vocab): check runs every
;; structure's laws over the db and yields the violations that hold. This is
;; the canonical integrity inspect — the capability the agent's integrity probe
;; composes, so it lives in the model 1-on-1 with the code.
(def StructureDb
  (Kind (doc "The unified structure db — the data realization of the domain `Model` faculty
              (canvas.perspectives.structure.overview): a datascript db of structure instances +
              their reified relations. Owned here; every subsystem adopts this one Kind.")))
(def Violation   (Kind))
(def Rule        (Kind))

;; the rules-cut bridge: derive the datalog rules from the live vocabulary
;; (delegating to core.rules) — the rules check + the lens engine inject so
;; laws/lenses read at domain altitude.
(def vocab-rules
  (Operation
    (doc "The datalog rules derived from the live vocabulary, injected into laws/lenses.")
    (out [Rule])
    (calls query-engine/derive-rules)))
(def check
  (Operation
    (doc "Run every structure's laws over the model db; yield the violations.")
    (in [db StructureDb])
    (out [Violation])
    (calls vocab-rules)))

(def core-structure
  (Subsystem "core.structure"
    (exposes check vocab-rules)                    ; the kernel capabilities others compose
    (owns StructureDb Violation Rule)              ; the data-shapes core.structure decides (others adopt StructureDb)
    (child c-Keyword c-String c-Bool c-Int c-Concept c-Node c-Cardinality
           c-Relation c-Slot c-Law c-Structure)))
