(ns canvas.architecture.kernel.structure
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
            [canvas.architecture.kernel.rules :as query-engine]
            [canvas.subject :as subj]
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
(Operation create
  "Construct an empty StructureDb — the substrate constructor builders start from."
  {:signature [:=> [:cat] StructureDb]})
(Operation structure-by-tag
  "Look up a registered structure definition (slots + laws) by its tag."
  {:signature [:=> [:catn [:tag :keyword]] :any]})
(Operation value-literal->iv
  "Build a ^:value InstanceValue for a value-structure tag from a data literal — the one
   value-construction path; type dialects build their value subgraphs through it."
  {:signature [:=> [:catn [:tag :keyword] [:literal :any]] :any]})
(Operation value-content-key
  "A deterministic, purely structural identity for a ^:value InstanceValue (the content-dedup key)."
  {:signature [:=> [:catn [:iv :any]] :any]})

;; ── structure introspection / identity API (assembler + reflection-facing) ───────────────────
;; Low-level surface the assembler (var→identity, instance detection), the instance print-dual
;; (var short-name) and grammar reflection (the registry roll-call) depend on across the boundary —
;; public by necessity, so named here rather than hidden.
(Operation var-id
  "A var's stable entity-id — its fully-qualified name (the identity an authored instance carries)."
  {:signature [:=> [:catn [:v :any]] :string]})
(Operation var-simple-name
  "A var's simple (unqualified) name — the default entity name when no ^{:name …} override is given."
  {:signature [:=> [:catn [:v :any]] :string]})
(Operation instance-value?
  "Whether a value is an InstanceValue — the predicate the assembler scans interned vars with."
  {:signature [:=> [:catn [:x :any]] :boolean]})
(Operation scalar-slot?
  "Whether a slot stores a leaf VALUE (vs. a relation to a node) — drives value-vs-ref handling."
  {:signature [:=> [:catn [:slot :any]] :boolean]})
(Operation all-structures
  "The live registry roll-call — every registered structure definition (slots + laws). The seam
   grammar reflection reads to project the registry onto the model."
  {:signature [:=> [:cat] [:vector :any]]})

(Module core-structure
  "The defstructure kernel — laws → violations over the structure graph."
  ;; NB: the dialect-facing half of :exposes (create / structure-by-tag / value-literal->iv /
  ;; value-content-key) has grown one widening at a time to serve consumers — esp. the malli
  ;; dialect reaching the value machinery. See the kernel<->dialect-boundary note: this signals
  ;; the kernel<->typing-plugin seam wants a deliberate SPI, not piecemeal exposure (parked).
  {:exposes [check vocab-rules create structure-by-tag value-literal->iv value-content-key   ; the kernel capabilities others compose
              var-id var-simple-name instance-value? scalar-slot? all-structures]            ; introspection/identity API (assembler + reflection-facing)
   :owns    [StructureDb Violation]              ; data-shapes that cross the boundary (others adopt by name)
   :child   [Rule Node Relation]                 ; internal grain: the rules-output type + the reflexive substrate
   :realizes subj/Model})                        ; faculty role: this module realizes the Model hub
