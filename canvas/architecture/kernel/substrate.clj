(ns canvas.architecture.kernel.substrate
  "Self-spec: the kernel SUBSTRATE (`fukan.canvas.core.substrate`) — a boundary sketch. The node layer
   beneath the `defstructure` grammar: what a node IS (a named entity or a `^:value` InstanceValue) and
   how it is IDENTIFIED, plus the empty db they live in. A LEAF — it depends on nothing; the grammar,
   the assembler, the checker, and value-construction all sit ON it.

   It owns the substrate data-shapes (`Node`, the reified `Relation`, the in-flight `InstanceValue`, and
   the unified `StructureDb` every subsystem adopts) and exposes the node-construction + identity
   primitives. Extracted DOWNWARD from `core-structure`: the substrate the registry sits on, long a
   model-only `Node`/`Relation` portrait, finally has a code home."
  (:require [canvas.vocab.code.kind :refer [Kind]] [canvas.vocab.code.operation :refer [Operation]] [canvas.vocab.code.module :refer [Module]]
            ;; [:enum …] / :keyword literals in Kind bodies check through the malli type dialect
            [canvas.vocab.type]))

;; ── the substrate data-shapes ────────────────────────────────────────────────
(Kind Node "the substrate atom — identified by name+uuid, or by content when value-typed.")
(Kind Relation
  [:map [:from Node] [:to Node]
        [:kind :keyword]
        [:label {:optional true} :string]
        [:order {:optional true} :int]])
(Kind InstanceValue
  "the in-flight record an authored instance evaluates to before the assembler stamps it into the db.")
(Kind StructureDb
  "The unified structure db — the data realization of the model: a Cozo
   db of structure instances + their reified relations. Owned here; every subsystem adopts this one Kind.")

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

(Module core-substrate
  "The node substrate the grammar sits on — node identity + value-node construction + the empty db.
   A leaf: depends on nothing; everything above adopts its `StructureDb` and builds on its primitives."
  {:exposes [value-content-key var-id var-simple-name instance-value?]
   :owns    [Node Relation InstanceValue StructureDb]})
