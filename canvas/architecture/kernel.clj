(ns canvas.architecture.kernel
  "Self-spec: fukan's defstructure KERNEL (`fukan.canvas.core.structure`) — a boundary sketch.
   The defstructure layer (Structure = slots + laws) is NOT hand-modelled: grammar reflection
   (`lib.grammar/with-grammar`) derives it from the live registry, where it can never drift. What
   remains hand-modelled is the SUBSTRATE the registry sits on — `Node` and the reified `Relation`
   — which is not registry data, so reflection can't reach it. It rides here as the module's own
   children (the reflexive self-description, in `canvas.vocabulary.meta`). The kernel also exposes
   one capability, `check` (laws → violations): the canonical integrity inspect, modelled because
   code is a projection of the model 1-on-1.

   (One concept needs a var workaround: `String` shadows `java.lang.String`, so its var is
   `c-String` with `^{:name}` restoring the concept name. The rest carry no shadow — plain names.)"
  (:require [canvas.vocabulary.meta :refer [Concept MetaSlot]]
            [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.query-engine :as query-engine]))

;; ── the substrate: the kernel's own data-shapes (reflection can't reach below the registry) ──
(Concept Keyword)
(Concept ^{:name "String"} c-String)   ; only String shadows java.lang.String → keep the var workaround
(Concept Int)
(Concept Node
  "An instance: identified by name + uuid, or by content when value-typed.")
(Concept Relation
  "A reified slot value — a kinded edge between Nodes, carrying optional label/order."
  {:slot [(MetaSlot {:name "from"  :cardinality "one"      :of Node})
          (MetaSlot {:name "to"    :cardinality "one"      :of Node})
          (MetaSlot {:name "kind"  :cardinality "one"      :of Keyword})
          (MetaSlot {:name "label" :cardinality "optional" :of c-String})
          (MetaSlot {:name "order" :cardinality "optional" :of Int})]})

;; ── owned data-shapes + the exposed capability ──────────────────────────────────────────────
(Kind StructureDb
  "The unified structure db — the data realization of the domain `Model`
   (canvas.domain.subject): a datascript db of structure instances + their reified
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
   :child   [Rule Keyword c-String Int Node Relation]})  ; internal grain: the rules-output type + the reflexive substrate
