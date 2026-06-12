(ns canvas.architecture.kernel
  "Self-spec: fukan's defstructure KERNEL (`fukan.canvas.core.structure`) — a boundary sketch.
   The defstructure layer (Structure = slots + laws) is NOT hand-modelled: grammar reflection
   (`lib.grammar/with-grammar`) derives it from the live registry, where it can never drift. What
   remains hand-modelled is the SUBSTRATE the registry sits on — `Node` and the reified `Relation`
   — which is not registry data, so reflection can't reach it. It rides here as the module's own
   children (the reflexive self-description, in `canvas.architecture.kernel`): `Node`, and `Relation`
   whose scalar fields use `MetaSlot`'s `:scalar` leaf type, so no scalar needs a Concept of its
   own. The kernel also exposes one capability, `check` (laws → violations): the canonical
   integrity inspect, modelled because code is a projection of the model 1-on-1.

   The reflexive META-GRAMMAR (`Concept`/`MetaSlot` — `defstructure` described in `defstructure`)
   is folded in here, its sole consumer: a `Concept` is composed of `MetaSlot`s, a `MetaSlot` is a
   named relation with a cardinality to a target Concept or a leaf scalar — exactly a defstructure
   slot. (Co-located, not split into a `vocabulary/meta` — a bespoke grammar used by one module.)"
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.query-engine :as query-engine]
            ;; MetaSlot's refined [:enum …] slots check through the malli type dialect
            [lib.type.malli]))

;; ── the meta-grammar: defstructure described in defstructure (its only consumer is below) ─────
(defstructure ^:value MetaSlot
  "A slot of a Concept — value-identified by its (name, cardinality, target). Mirrors a real slot's
   target: a structure-REF (`:of` another Concept) OR a leaf SCALAR type (`:scalar`). A field
   declares one or the other — exactly as the core distinguishes a symbol (tag ref) from a keyword
   (scalar type), so no scalar needs a Concept node of its own."
  {:name        :String
   :cardinality [:enum "one" "optional" "many" "some" "set"]
   :of          [:? Concept]                                    ; a ref target (another Concept), or
   :scalar      [:? [:enum "Keyword" "String" "Int" "Bool"]]})  ; a leaf scalar type

(defstructure Concept
  "A concept (type) in a modelled data model — a defstructure structure, a reified relation, a
   scalar type, …. Composed of MetaSlots."
  {:slot [:* MetaSlot]})

;; ── the substrate: the kernel's own data-shapes (reflection can't reach below the registry) ──
(Concept Node
  "An instance: identified by name + uuid, or by content when value-typed.")
(Concept Relation
  "A reified slot value — a kinded edge between Nodes, carrying optional label/order."
  {:slot [(MetaSlot {:name "from"  :cardinality "one"      :of     Node})
          (MetaSlot {:name "to"    :cardinality "one"      :of     Node})
          (MetaSlot {:name "kind"  :cardinality "one"      :scalar "Keyword"})
          (MetaSlot {:name "label" :cardinality "optional" :scalar "String"})
          (MetaSlot {:name "order" :cardinality "optional" :scalar "Int"})]})

;; ── owned data-shapes + the exposed capability ──────────────────────────────────────────────
(Kind StructureDb
  "The unified structure db — the data realization of the domain `Model`
   (canvas.subject): a datascript db of structure instances + their reified
   relations. Owned here; every subsystem adopts this one Kind.")
(Kind Violation)
(Kind Rule)

(Operation vocab-rules
  "The datalog rules derived from the live vocabulary, injected into laws/lenses."
  {:signature [:=> [:cat] [:vector Rule]]
   :delegates [query-engine/derive-rules]})
(Operation check
  "Run every structure's laws over the model db; yield the violations."
  {:signature [:=> [:catn [:db StructureDb]] [:vector Violation]]
   :guidance  "Inject vocab-rules into each law's :where so laws read at domain altitude; route negation through rules to dodge datascript's empty-relation not-join gotcha."})

(Module core-structure
  "The defstructure kernel — laws → violations over the structure graph."
  {:exposes [check vocab-rules]                  ; the kernel capabilities others compose
   :owns    [StructureDb Violation]              ; data-shapes that cross the boundary (others adopt by name)
   :child   [Rule Node Relation]})  ; internal grain: the rules-output type + the reflexive substrate
