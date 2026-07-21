(ns canvas.architecture.kernel.substrate
  "Self-spec: the kernel SUBSTRATE (`fukan.canvas.core.substrate`) — a boundary sketch. The node layer
   beneath the `defstructure` grammar: what a node IS (a named entity or a `^:value` InstanceValue) and
   how it is IDENTIFIED, plus the empty db they live in. A LEAF — it depends on nothing; the grammar,
   the assembler, the checker, and value-construction all sit ON it.

   It owns the substrate data-shapes (`Node`, the reified `Relation`, the in-flight `InstanceValue`, and
   the unified `StructureDb` every subsystem adopts) and exposes the node-construction + identity
   primitives. Extracted DOWNWARD from `core-structure`: the substrate the registry sits on, long a
   model-only `Node`/`Relation` portrait, finally has a code home."
  (:require [fukan.common.vocab.code.kind :refer [Kind]] [fukan.common.vocab.code.operation :refer [Operation]] [fukan.common.vocab.code.module :refer [Module]]
            ;; [:enum …] / :keyword literals in Kind bodies check through the malli type dialect
            [fukan.common.typing.malli]))

;; ── the substrate data-shapes ────────────────────────────────────────────────
(Kind Node "the substrate atom — identified by name+uuid, or by content when value-typed.")
(Kind Relation
  [:map [:from Node] [:to Node]
        [:kind :keyword]
        [:label {:optional true} :string]
        [:order {:optional true} :int]])
(Kind InstanceValue
  "the in-flight record an authored instance evaluates to before the assembler stamps it into the db.")
(Kind Ref
  "a reference to an already-emitted node by its natural-key entity-id — the assembler resolves it to
   that id and emits no node (the node arrives via its own root); the generated analog of a var reference.")
(Kind StructureDb
  "The unified structure db — the data realization of the model: a Cozo
   db of structure instances + their reified relations. Owned here; every subsystem adopts this one Kind.")
(Kind Eid
  "An entity id — the identity a query yields for a node. Owned here (a substrate primitive);
   every module that navigates the db adopts this one Kind."
  :int)

;; ── node construction + identity ──────────────────────────────────────────────
(Operation value-content-key
  "A deterministic, purely structural identity for a ^:value InstanceValue (the content-dedup key)."
  {:signature [:=> [:catn [:iv :any]] :any]})
(Operation var-id
  "A var's stable entity-id — its fully-qualified name (the identity an authored instance carries)."
  {:signature [:=> [:catn [:v :any]] :string]})
(Operation var-simple-name
  "A var's simple (unqualified) name — the default entity name when no ^{:name …} override is given."
  {:signature [:=> [:catn [:v :any]] :string]})
(Operation instance-value?
  "Whether a value is an InstanceValue — the predicate the assembler scans interned vars with."
  {:signature [:=> [:catn [:x :any]] :boolean]})
(Operation ref?
  "Whether a value is a Ref — the reference-by-id an extracted feeder wires cross-references with, resolved by the assembler."
  {:signature [:=> [:catn [:x :any]] :boolean]})
(Operation stamp-stratum
  "Stamp an InstanceValue tree as fact-stratum — provenance on it and every nested non-value instance; ^:value nodes are stratum-free."
  {:signature [:=> [:catn [:iv :any]] :any]})

(Module core-substrate
  "The node substrate the grammar sits on — node identity + value-node construction + the empty db.
   A leaf: depends on nothing; everything above adopts its `StructureDb` and builds on its primitives."
  {:child [value-content-key var-id var-simple-name instance-value? ref? stamp-stratum
           Node Relation InstanceValue Ref StructureDb Eid]})
