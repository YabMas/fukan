(ns canvas.model.spec
  "Canvas port of model/spec.allium (no spec.boundary — this is the kernel
   substrate and has no separate boundary file).

   Coverage:
     Type vocabulary (six cases):
       - value ScalarType         → construction/record (1 field: name)
       - value EnumType           → construction/record (1 field: values)
       - value NamedShape         → construction/record (1 field: container)
       - value InlineShape        → construction/record (1 field: fields)
       - value FieldSpec          → construction/record (3 fields)
       - value CollectionType     → construction/record (2 fields)
       - value UnionType          → construction/record (1 field: types)
       - value RefType            → construction/record (2 fields)
       - value RelationalSpec     → construction/record (4 fields)
       - value ArtifactIdentity   → construction/record (3 fields)
       - value SourceLocation     → construction/record (2 fields)
       - value TagRef             → construction/record (2 fields)
       - value PathSegment        → construction/record (2 fields)
       - value MatchArm           → construction/record (2 fields)

     Sub-substrate value records:
       - value Field              → construction/record (3 fields)
       - value Parameter          → construction/record (4 fields)
       - value Definition         → construction/record (2 fields)
       - value RuleBody           → construction/record (2 fields)
       - value Expression         → construction/record (2 fields)
       - value Effect             → construction/record (4 fields)

     Model top-level record:
       - value Model              → construction/record (6 fields)

     Kernel invariants (all → vocab.behavioral/invariant):
       SubstratePrinciple, ChildrenOnParent, SingularAggregatesPerHost,
       ClausesAreFirstClass, EffectExpressionParity, ModeIsCallsiteTyped,
       EnvironmentIsCallsiteTyped, StubResolutionIsUnconditional,
       Container::SingularBehaviour, Container::SingularBoundary,
       Edge::NoSelfEdge, Edge::IdentifyingMetadataPerRelation

     Kernel guarantees (→ vocab.behavioral/invariant):
       TriggersIsOccurrenceCausation, ObservesIsStateCausation,
       ReadsIsDataDependency, WritesIsStateModification,
       CreatesIsIdentityIntroduction, DestroysIsIdentityTermination,
       EmitsIsEventProduction, RealisesIsStructuralRealisation,
       SpecialisesIsStructuralExtension, UsesIsDeclaredDependency,
       ExposesIsViewProtocol, ProvidesIsSignalProtocol,
       ProjectsIsSpecToRealisation,
       PayloadSchemaExtension, TagPresenceImplication, NoFieldOverride,
       NoMultiParent, NoInversePairs, DerivedRelationsAreQueries,
       NamedClosedIncludedFloor

   Notes:
     - Type entity / entity ExpressionForm / entity Environment / etc.
       are sum-type entity declarations in spec.allium; represented as
       records holding the discriminator field (case).
     - Nine kernel primitives (Container, Actor, Behaviour, Rule, Boundary,
       Operation, Intent, Clause, Event) and four endpoint types are recorded.
     - model/spec.allium has no boundary file; all exports are implicit.
     - CollectionSemantics has three cases (sequential, unique, keyed); only
       the discriminator is needed at canvas level.
     - KeyedSemantics sub-variant carries key_type.
     - RelationalSpec and TagApplication payload use map-of shapes."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record value]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "model.spec"

      ;; ── Type Vocabulary ──────────────────────────────────────────────────

      (value "Type"
        "A type expression. Closed over six cases: scalar | enum | composite |
         collection | union | ref. Substrate commits to structural shape only.")

      (record "ScalarType"
        "Atomic leaf. name is methodology-supplied and opaque to substrate."
        (field name :String))

      (record "EnumType"
        "Closed set of literal values. Each case is a literal string."
        (field values (list-of :String)))

      (record "NamedShape"
        "Composite whose shape comes from a value-typed Container's fields."
        (field container :String))

      (record "InlineShape"
        "Composite with anonymous inline fields."
        (field fields (list-of :FieldSpec)))

      (record "FieldSpec"
        "A slot in an InlineShape. Optionality is at slot level, not Type."
        (field name     :String)
        (field type     :Any)
        (field optional :Boolean))

      (record "CollectionType"
        "Zero-or-more of an inner Type with explicit collection semantics.
         semantics distinguishes sequential | unique | keyed."
        (field of        :Any)
        (field semantics :Any))

      (record "UnionType"
        "Sum of distinct Types. Consumers dispatch on case; no implicit
         coercion."
        (field types (list-of :Any)))

      (record "RefType"
        "Reference to a kernel target. where filters admissible targets by
         tag presence."
        (field target :Any)
        (field where  (optional (set-of :TagRef))))

      (value "CollectionSemantics"
        "Closed sum: Sequential | Unique | Keyed(K). Keyed carries key_type.")

      (value "RefTarget"
        "Closed sum: KernelPrimitive(kinds) | Substrate(within_kind,
         slot_kinds).")

      ;; ── Sub-substrate Value Records ──────────────────────────────────────

      (record "Field"
        "A typed data member owned by a Container. Identity:
         (Container.id, Field.name)."
        (field name     :String)
        (field type_ref :Any)
        (field optional :Boolean))

      (record "Parameter"
        "An ordered typed parameter on an Operation or Event. Identity:
         (parent_id, Parameter.name). ordinal carries declared order."
        (field name     :String)
        (field type_ref :Any)
        (field optional :Boolean)
        (field ordinal  :Integer))

      (record "Definition"
        "A typed let binding inside a Rule body. In scope across
         Rule.intent.assertions and Rule.body.effects. Identity:
         (Rule.id, Definition.name)."
        (field name       :String)
        (field expression :Any))

      (record "RuleBody"
        "Bundles definitions and effects under a Rule. No independent
         identity — reduces to host Rule's id."
        (field definitions (list-of :Any))
        (field effects     (list-of :Any)))

      ;; ── Expression Sub-substrate ─────────────────────────────────────────

      (record "Expression"
        "An expression. Identity is structural over form; label is
         addressability-only. Type is determined by form; predicates are
         Expressions whose result type is Boolean."
        (field label (optional :String))
        (field form  :Any))

      (value "ExpressionForm"
        "Closed sum: var | ref | lit | apply | let | if | match | forall |
         exists | aggregate.")

      (record "MatchArm"
        "Pattern-match arm. pattern mirrors the six Type cases."
        (field pattern :Any)
        (field body    :Any))

      (value "Environment"
        "Typed binding context distinguishing callsites: one_state | two_state |
         model_introspection. Environment is part of the callsite type, not of
         the Expression.")

      ;; ── Effect Sub-substrate ─────────────────────────────────────────────

      (record "Effect"
        "A materialised state-change declaration. Lives only inside
         Rule.body.effects. Identity: (rule_id, kind, target). kind ∈ create |
         write | destroy | emit. source resolves back to the originating
         Expression in Rule.intent.assertions."
        (field kind   :String)
        (field target :Any)
        (field value  (optional :Any))
        (field source :String))

      ;; ── Endpoints ────────────────────────────────────────────────────────

      (value "Endpoint"
        "Closed sum: PrimitiveRef | SubstrateAddress | ArtifactRef.")

      (record "PathSegment"
        "Typed segment within a SubstrateAddress path. slot names a substrate
         slot; key is the identifier within the slot (omitted when singular)."
        (field slot :String)
        (field key  (optional :String)))

      (record "ArtifactIdentity"
        "Tuple identity for an Artifact: (case, language, qualified_name).
         Per the projection vocabulary §7.3."
        (field case           :String)
        (field language       :String)
        (field qualified_name :String))

      (record "SourceLocation"
        "Non-identifying — Artifact identity is independent of where the target
         lives on disk."
        (field file :String)
        (field line (optional :Integer)))

      ;; ── Vocabulary Mechanism Records ─────────────────────────────────────

      (record "TagRef"
        "A namespaced tag identifier."
        (field namespace :String)
        (field name      :String))

      (record "RelationalSpec"
        "Spec for relational tags (edge-shaped vocabulary)."
        (field endpoints     (list-of :String))
        (field symmetry      :String)
        (field canonical_side (optional :String))
        (field coherence_query (optional :Any)))

      ;; ── Model Top-Level Record ────────────────────────────────────────────

      (record "Model"
        "Complete substrate. Immutable after construction. Contains all kernel
         primitives, all kernel edges, and the vocabulary registry."
        (field primitives              (map-of :String :Any))
        (field edges                   (list-of :Any))
        (field tag_definitions         (list-of :Any))
        (field tag_applications        (list-of :Any))
        (field predicate_registrations (list-of :Any))
        (field renderer_registrations  (list-of :Any)))

      ;; ── Kernel Invariants ─────────────────────────────────────────────────

      (invariant "SubstratePrinciple"
        "A slot belongs at kernel substrate iff it is essential to understanding
         the primitive in isolation. Cross-primitive interactions live in
         relations. Design/intentional framing lives in vocabulary. Operational
         corollary: aggregated/owned content -> substrate; cross-referenced
         content -> relations."
        (holds-that "substrate-slots-are-essential-to-primitive-in-isolation"))

      (invariant "ChildrenOnParent"
        "Container ownership is stored once on the parent (children); no parent
         slot. Tree integrity is enforced by traversal, not by a mutable
         back-pointer."
        (holds-that "container-ownership-stored-on-parent-not-child"))

      (invariant "SingularAggregatesPerHost"
        "Behaviour, Boundary, Intent, RuleBody are singular aggregates on their
         hosts. Multiplicity lives in their sub-units (Rules, Operations,
         Clauses, Definitions/Effects)."
        (holds-that "behaviour-boundary-intent-rulebody-singular-per-host"))

      (invariant "ClausesAreFirstClass"
        "Per K15a: Clause is a kernel primitive with its own id, despite being
         owned by Intent. Moving a Clause between Intents preserves its id.
         Clause.label is human-facing addressability, not identity."
        (holds-that "clause-has-independent-id-survives-intent-move"))

      (invariant "EffectExpressionParity"
        "Per §3.8.3: every Effect is the canonicalised materialisation of an
         Expression of recognised shape in Rule.intent.assertions, and every
         such qualifying Expression has a corresponding Effect. Bidirectional:
         no qualifying Bool Expression lacks an Effect; no Effect lacks a source
         Expression."
        (holds-that "effect-expression-bidirectional-parity"))

      (invariant "ModeIsCallsiteTyped"
        "Per §3.8.6: variable mode (input-bound / output-solved) lives in the
         constraint-language rule structure that uses an Expression, not in the
         Expression record. Structural identity is mode-free."
        (holds-that "expression-has-no-mode-slot"))

      (invariant "EnvironmentIsCallsiteTyped"
        "Per §3.8.5: Environment is part of the callsite type. An Expression
         typed against OneState cannot reference post.X; type-checking enforces
         this at parse time."
        (holds-that "expression-has-no-environment-slot"))

      (invariant "StubResolutionIsUnconditional"
        "Per §3.6: the merge step identifies an external-entity stub with the
         real Container defined elsewhere if one exists — the Model contains a
         single Container under the real id; all references resolve to it. When
         no real Container exists the stub appears with the ExternalEntity tag.
         Merge is unconditional with respect to target-module visibility."
        (holds-that "stub-merge-is-unconditional-not-visibility-gated"))

      (invariant "ContainerSingularBehaviour"
        "At most one Behaviour per Container; multiplicity lives in Rules."
        (holds-that "container-behaviour-is-at-most-one"))

      (invariant "ContainerSingularBoundary"
        "At most one Boundary per Container; multiplicity lives in Operations."
        (holds-that "container-boundary-is-at-most-one"))

      (invariant "EdgeNoSelfEdge"
        "An edge's from and to endpoints are distinct."
        (holds-that "edge-from-neq-to"))

      (invariant "IdentifyingMetadataPerRelation"
        "Per-relation identifying-metadata slots (closed table): triggers,
         realises, specialises, uses, exposes, provides have none; observes,
         reads have condition; writes, creates, destroys, emits have condition
         and scope; projects has projection_kind."
        (holds-that "identifying-metadata-per-relation-closed-table"))

      ;; ── Kernel Relation Semantics (guarantees) ────────────────────────────

      (invariant "TriggersIsOccurrenceCausation"
        "An Event or Operation occurrence causes the target Rule to fire.
         Endpoints: Event | Operation -> Rule. Multi-subscriber capable."
        (holds-that "triggers-occurrence-causes-rule-fire"))

      (invariant "ObservesIsStateCausation"
        "The Rule has standing interest in a condition on the target.
         Endpoints: Rule -> Container | Field. Per-Rule semantics."
        (holds-that "observes-rule-has-standing-interest-in-condition"))

      (invariant "ReadsIsDataDependency"
        "The Rule looks at the target's state while computing its effects. Not
         a trigger, not an effect — a dependency. Retained because spec-to-code
         drift detection needs reads as a contract surface.
         Endpoints: Rule -> Container | Field."
        (holds-that "reads-is-data-dependency-not-trigger-not-effect"))

      (invariant "WritesIsStateModification"
        "Rule modifies state on existing identity.
         Endpoints: Rule -> Container | Field. Sourced by an Effect record."
        (holds-that "writes-modifies-existing-state"))

      (invariant "CreatesIsIdentityIntroduction"
        "Rule introduces a new identity. Endpoints: Rule -> Container.
         Field-only creation is writes, not creates."
        (holds-that "creates-introduces-new-identity"))

      (invariant "DestroysIsIdentityTermination"
        "Rule terminates an identity. Endpoints: Rule -> Container.
         Field-only destruction is writes, not destroys."
        (holds-that "destroys-terminates-identity"))

      (invariant "EmitsIsEventProduction"
        "Rule produces an Event. Endpoints: Rule -> Event. Sourced by an
         Effect. Distinct from provides (boundary advertisement of an
         event-entry point)."
        (holds-that "emits-rule-produces-event"))

      (invariant "RealisesIsStructuralRealisation"
        "X is the concrete part that completes Y's abstract specification.
         Same-altitude. Endpoints: Container | Operation -> Container |
         Operation."
        (holds-that "realises-is-same-altitude-structural-realisation"))

      (invariant "SpecialisesIsStructuralExtension"
        "X extends Y's structure: inherits Y's fields, may add or narrow.
         Same-altitude. Endpoints: Container -> Container. Edge direction is
         child -> parent."
        (holds-that "specialises-child-extends-parent-structure"))

      (invariant "UsesIsDeclaredDependency"
        "X declares a dependency on Y's interface. Same-altitude. Endpoints:
         Container -> Container. Distinct from the derived depends_on: uses is
         declared at spec altitude; depends_on is observed."
        (holds-that "uses-is-declared-spec-altitude-dependency"))

      (invariant "ExposesIsViewProtocol"
        "X advertises Field F as readable at X's boundary. Endpoints:
         Container -> Field (the Field side is a SubstrateAddress)."
        (holds-that "exposes-advertises-field-as-readable-at-boundary"))

      (invariant "ProvidesIsSignalProtocol"
        "X advertises Event E as an entry point at X's boundary. Endpoints:
         Container -> Event. Distinct from triggers — provides is the
         boundary-level declaration that the Event has an external entry point;
         triggers is what fires when the Event arrives."
        (holds-that "provides-advertises-event-as-boundary-entry-point"))

      (invariant "ProjectsIsSpecToRealisation"
        "A spec primitive projects to a realisation Artifact (code, infra,
         docs, tests). Spec is the source; realisations lose information.
         Endpoints: Container | Operation | Rule | Event | Clause | Expression
         -> Artifact. Carries projection_kind in identity; carries validity as
         a memoised derivation (non-identifying) over (spec_primitive,
         expected_address, code_at_address)."
        (holds-that "projects-spec-primitive-to-realisation-artifact"))

      ;; ── Inheritance Semantics (V9) ────────────────────────────────────────

      (invariant "PayloadSchemaExtension"
        "A child tag's payload-schema extends its parent's: every parent slot
         is present (and may be narrowed); new slots may be added."
        (holds-that "child-tag-payload-schema-extends-parent"))

      (invariant "TagPresenceImplication"
        "A target carrying a child tag is treated, for predicate purposes, as
         carrying every ancestor tag in the parent_tag chain (transitively)."
        (holds-that "child-tag-implies-all-ancestor-tags"))

      (invariant "NoFieldOverride"
        "Plan 1 does not support per-slot override of parent payload values by
         a child tag application."
        (holds-that "no-per-slot-parent-payload-override"))

      (invariant "NoMultiParent"
        "Plan 1 supports a single parent_tag per TagDefinition; multi-parent
         inheritance is deferred."
        (holds-that "tag-definition-has-single-parent-tag"))

      ;; ── Edge Direction ────────────────────────────────────────────────────

      (invariant "NoInversePairs"
        "Relations have direction. The kernel never names two relations as
         inverse pairs (no triggered_by, no realised_by). Inverse queries
         traverse the same edge from the other endpoint."
        (holds-that "no-inverse-relation-pairs-in-kernel"))

      (invariant "DerivedRelationsAreQueries"
        "Derived relations (e.g. chains — Rule emits Event -> Rule triggered by
         Event) are NOT kernel relations. The kernel commits to making them
         expressible and queryable through the constraint language, not to
         storing them as edges."
        (holds-that "derived-relations-are-queries-not-stored-edges"))

      ;; ── Type-System Floor ─────────────────────────────────────────────────

      (invariant "NamedClosedIncludedFloor"
        "Where the substrate must take a position on type-system shape, it
         commits to Allium-aligned defaults: shapes are named, closed, composed
         by inclusion. This is the most restrictive common choice, so
         methodologies can selectively relax it via vocabulary overlay."
        (holds-that "substrate-commits-to-named-closed-included-floor")))))
