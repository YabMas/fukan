(ns canvas.model.kernel
  "Self-spec: fukan's defstructure KERNEL (`fukan.canvas.core.structure`), modelled
   reflexively with the schema vocab (`canvas.vocab.meta`). The deepest self-
   reference in the corpus — the model describes the very primitive that defines
   it: the substrate (Node, reified Relation), the defstructure layer (Slot, Law),
   and at its heart `Structure` = a composition of Slots + Laws.

   First cut: it captures the composition + the slot's (cardinality, target). It
   deliberately omits value-identity / ordered / the reader / the scalar-type
   registry — to be added only if the model is felt to want them.

   The kernel also PROVIDES one operation — `check` (laws → violations), the
   canonical integrity inspect. It is modelled here as a capability (an op `Stage`)
   because code is a projection of the model 1-on-1: the agent's integrity probe
   composes `check`, so `check` must exist in the model, not just in code."
  (:require [fukan.canvas.core.structure :as s]
            ;; MetaSlot is authored inline (a value), so required (registers it) but not referred
            [canvas.vocab.meta :refer [Concept]]
            ;; the kernel's `check` operation is modelled with the data + computation
            ;; vocabs (Kind for its I/O leaves, Stage for the operation itself)
            [canvas.vocab.shape :refer [Kind]]
            [canvas.vocab.op :refer [Stage]]))

(defn ^:export build-canvas []
  (s/with-structures
    (s/within-module "core.structure"
      ;; leaf concepts — scalar types, the substrate's atoms, and (reflexively) the
      ;; meta-type Concept itself
      (Concept "Keyword")
      (Concept "String")
      (Concept "Bool")
      (Concept "Int")
      (Concept "Concept" (doc "A type/structure — the meta-type this model is built from."))
      (Concept "Node" (doc "An instance: identified by name + uuid, or by content when value-typed."))
      (Concept "Cardinality" (doc "one | some | many | optional | ordered."))

      ;; the substrate seam: a reified slot relation
      (Concept "Relation"
        (doc "A reified slot value — a kinded edge between Nodes, carrying optional label/order.")
        (slot (MetaSlot (name "from")  (cardinality "one")      (of Node)))
        (slot (MetaSlot (name "to")    (cardinality "one")      (of Node)))
        (slot (MetaSlot (name "kind")  (cardinality "one")      (of Keyword)))
        (slot (MetaSlot (name "label") (cardinality "optional") (of String)))
        (slot (MetaSlot (name "order") (cardinality "optional") (of Int))))

      ;; the defstructure layer
      (Concept "Slot"
        (doc "A relation-with-a-law: a named relation, a cardinality, and a target concept.")
        (slot (MetaSlot (name "rel")         (cardinality "one") (of Keyword)))
        (slot (MetaSlot (name "cardinality") (cardinality "one") (of Cardinality)))
        (slot (MetaSlot (name "target")      (cardinality "one") (of Concept))))
      (Concept "Law"
        (doc "A datalog constraint that must hold of a structure's instances.")
        (slot (MetaSlot (name "desc")      (cardinality "one")      (of String)))
        (slot (MetaSlot (name "recursive") (cardinality "optional") (of Bool))))

      ;; the heart: a structure IS a composition of Slots + Laws
      (Concept "Structure"
        (doc "The kernel's heart: a composition of Slots and Laws — the model IS this db.")
        (slot (MetaSlot (name "slot")  (cardinality "many")     (of Slot)))
        (slot (MetaSlot (name "law")   (cardinality "many")     (of Law)))
        (slot (MetaSlot (name "value") (cardinality "optional") (of Bool))))

      ;; the kernel's one OPERATION (modelled with the op vocab): check runs every
      ;; structure's laws over the db and yields the violations that hold. This is
      ;; the canonical integrity inspect — the capability the agent's integrity probe
      ;; composes, so it lives in the model 1-on-1 with the code.
      (Kind "StructureDb")
      (Kind "Violation")
      (Kind "Rule")
      ;; the rules-cut bridge: derive the datalog rules from the live vocabulary
      ;; (delegating to core.rules) — the rules check + the lens engine inject so
      ;; laws/lenses read at domain altitude.
      (Stage "vocab-rules"
        (doc "The datalog rules derived from the live vocabulary, injected into laws/lenses.")
        (out [Rule])
        (calls (across "core.rules" "derive-rules")))
      (Stage "check"
        (doc "Run every structure's laws over the model db; yield the violations.")
        (in [db StructureDb])
        (out [Violation])
        (calls vocab-rules)))))
