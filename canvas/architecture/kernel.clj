(ns canvas.architecture.kernel
  "Self-spec: fukan's defstructure KERNEL (`fukan.canvas.core.structure`) — a boundary sketch.
   The defstructure layer (Structure = slots + laws) is NOT hand-modelled: grammar reflection
   (`lib.grammar/with-grammar`) derives it from the live registry, where it can never drift. What
   remains hand-modelled is the SUBSTRATE the registry sits on — `Node` and the reified `Relation`
   — which is not registry data, so reflection can't reach it. Both are ordinary Kinds carrying
   their shape via the same malli dialect as every other type; `Relation`'s `:shape` is the
   map of its five fields. The kernel also exposes one capability, `check` (laws → violations):
   the canonical integrity inspect, modelled because code is a projection of the model 1-on-1."
  (:require [fukan.canvas.core.structure :refer [defstructure]]
            [lib.code :refer [Kind Operation Module]]
            [canvas.architecture.query-engine :as query-engine]
            ;; [:enum …] / :keyword literals in Kind bodies check through the malli type dialect
            [lib.type.malli]))

;; ── the substrate: the kernel's own data-shapes, as ordinary Kinds ───────────────────────────
(Kind Node "the substrate atom — identified by name+uuid, or by content when value-typed.")
(Kind Relation
  [:map [:from Node] [:to Node]
        [:kind :keyword]
        [:label {:optional true} :string]
        [:order {:optional true} :int]])

;; ── owned data-shapes + the exposed capability ──────────────────────────────────────────────
(Kind StructureDb
  "The unified structure db — the data realization of the domain `Model`
   (canvas.subject): a datascript db of structure instances + their reified
   relations. Owned here; every subsystem adopts this one Kind.")
(Kind Violation [:map [:structure :keyword] [:law :string] [:offenders [:vector [:vector :any]]]])
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
