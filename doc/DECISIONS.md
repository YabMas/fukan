# Fukan — Decisions Log

**Status:** Reasoning record for the substrate spec in [MODEL.md](./MODEL.md). Companion to [VISION.md](./VISION.md) and [DESIGN.md](./DESIGN.md).

This document preserves the design-phase decision trace — the forces that produced the substrate commitments, the gates they had to clear, and the framings each decision superseded. MODEL.md encodes *what* the substrate is; this file records *why*, and what alternatives were considered along the way.

**Identifier tags** (cited throughout MODEL.md and DESIGN.md):

- **K** — kernel substrate
- **R** — kernel relations
- **V** — vocabulary mechanism
- **C** — constraint language
- **P** — projection vocabulary

Supersessions resolve in place: the controlling decision absorbs the superseded entry (no shadow records). Re-opening a question means re-engaging the recorded forces, not removing the decision.

---

## Foundational stance

- **K1** The Model is "of the system," not "of the spec language." Spec languages contribute evidence to one shared Model.
- **K2** Two-tier construction: kernel (universal substrate) + vocabulary (methodology-supplied refinement, overlay-only, no new primitives).
- **K3** Kernel ↔ vocabulary dependency is one-way: vocabulary references kernel; kernel knows nothing of vocabulary.
- **K4** Substrate is what's essential to the primitive in isolation; cross-primitive interactions live in relations; design/intentional framing lives in vocabulary.
- **K5** Force-and-gate criterion: a feature belongs at altitude X iff (a) something has forced it and (b) its shape is uniform across the cases that engage it. Applied at substrate, relations, vocabulary, and constraint-language altitudes.

## Kernel primitives

- **K6** Nine primitives: Container, Actor, Behaviour, Boundary, Rule, Operation, Intent, Clause, Event.
- **K7** Container is the universal addressable structural unit; collapses Module / Bounded Context / Aggregate / Entity / Value; distinguished by vocabulary tag + populated substrate slots.
- **K8** Container's faces — `fields`, `behaviour`, `boundary` (each optional); plus `children`, `events`, Container-level `intent`. Different methodologies populate different slots; all are Containers.
- **K9** Actor ≠ Container. Agency-bearing parties (primarily human roles). Third-party APIs are Containers (externally referenced).
- **K10** External is not a kernel category; derived from topology (a Container referenced from outside its owning hierarchy).
- **K11** Behaviour and Boundary are singular aggregates per host; multiplicity lives in their substructure (Rules, Operations).
- **K12** Intent is singular per host (Container, Behaviour, Rule, Boundary, Operation). Carries Clauses. Kinds (invariant, guarantee, guidance, open-question) are vocabulary tags on Clauses, not kernel slots.
- **K13** Boundary substrate is `operations` + `intent`. The Allium View / Signal / Call protocols emerge from substrate + relations + vocabulary tag combinations.
- **K14** Operations are sub-structure of Boundary; addressable via `boundary_id.operation_name`.
- **K15** Rule's substrate: identity + description + intent + body. Trigger lives as a relation (`triggers` or `observes`); preconditions, postconditions, and guarantees are Bool Expressions in `Rule.intent.assertions` (per K30); effects are Effect records in `Rule.body.effects` (per K29, surfacing as kernel edges `writes` / `creates` / `destroys` / `emits` per §4.2). (Original framing — "intentionally minimal substrate, preconditions as Clauses in `Rule.intent`" — superseded by K28–K30.)
- **K15a** Clause is a kernel primitive (per K6), not sub-substrate. Identity = `id`; `label?` is a human-facing name within the parent Intent for tool/projection addressability, *not* part of identity. Reached via `PrimitiveRef`, not `SubstrateAddress`. Sits in `Intent.clauses` as a list element (location, not identity); moving a Clause between Intents preserves its id. Sub-substrate kinds (§4.3 `SubstrateKind`) reduce to `Field | Parameter`. Resolves the §13 TBD on "first-class Clause addressability". (Original rationale also included `projects: Clause → Code.Function` for invariants alongside Rule/Operation projections; superseded by K30 which moved invariants from Clauses to Bool Expressions in `intent.assertions`. The successor mechanism is K31 — Expression also carries `label?` and joins the `projects` from-side, so invariant projection works with the same addressability shape. Clause-as-primitive remains valid for prose content: `@guidance`, descriptions.)
- **K16** Event is owned by the qualifying Container via `Container.events`. Identity `(qualifying Container, local name)`; cross-module references resolve to qualifier's Event. (Absorbs the earlier "root-level primitive + `owns` relation" framing.)
- **K16a** (id-string encoding). K16's composite Event identity `(qualifying Container, local name)` is encoded in the kernel id-string as `<container-id>::events::<local-name>`. Rules and Containers use the flat `<container-id>::<local-name>`. Slot-aware encoding for Events keeps an Allium module's local-name namespace separated by kind, so patterns like `rule X { when: X(...) }` resolve to two distinct primitives (`<module>::X` and `<module>::events::X`) without collision.
- **K17** No `parent` slot on property-primitives. Ownership stored on the owner.
- **K18** No `Reference` type. Slot types name their target directly.
- **K19** `description: Text?` on Container, Actor, Rule, Operation, Event. Aggregates (Behaviour, Boundary) and prose-primitives (Intent, Clause) don't carry description.
- **K20** No `properties: Map<Name, Value>` on kernel primitives.
- **K21** Naming: `Boundary` over `Wall`. `Event` over `Fact`. `Container` unifies what was Container + Concept. `Rule` is the name for Behaviour's sub-unit. `Facet` discarded — design framing in vocabulary.

## Type vocabulary

- **K22** Unified `Type` vocabulary (`Scalar | Enum | Composite | Collection | Union | Ref`) serves both substrate slots and vocabulary payload schemas. Six cases — three type-theoretic combinators (`Composite` product, `Collection` functor, `Union` sum) plus three leaf shapes (`Scalar` atomic, `Enum` closed-literal, `Ref` link). Value-vs-Reference is explicit at the schema level (`Composite(Named(C))` vs `Ref(KernelPrimitive({Container}), where=…)`). `Ref` reaches all kernel primitive kinds, not only Containers. `Ref(Substrate(...))` mirrors the §4.3 address machinery. `Collection` carries a `semantics` discriminator (`Sequential | Unique | Keyed(K)`) covering the three fundamental collection shapes uniformly. Optionality lives on `FieldSpec`, not as a `Type` case. (Absorbs the earlier `TypeRef` sum and the earlier `Primitive | Record | List` names.)
- **K22a** Substrate `Type` vocab is *generic at the target-language level*. Substrate Types commit to structural shape (atomic, product, sum, functor, link, closed-literal) but not to "what kind of integer" or "which target-language collection." Per-target-language translation lives in the project layer (§10.3) as a Type-translation registry consumed by language analyzers (Clojure analyzer in MVP; future TypeScript / Java / … analyzers plug into the same registry shape). Force: keeping Type vocab vague at the target level lets analyzers render idiomatic target code without substrate changes; gate: the registry mechanism is uniform across the six Type cases and across target languages. Default translations ship with each analyzer; methodologies override per `Scalar` name (Allium ships `"Money"`, `"DateTime"`, etc.); projects override per-project for custom domain types. Missing translation = structural error in the analyzer; defaults cover all six cases.

## Altitudes

- **K23** Three spec altitudes — **Behaviour** (Rules, Events, top-level Invariants), **Structure** (Operations, Boundary, contract / module-composition content), **Infra** (deployment commitments). Implementation is projection across all three, not a fourth altitude.
- **K24** Strict one-up altitude-reference rule: each altitude may reference only the altitude immediately above; never downward; never skipping. Same-altitude references unrestricted. Substrate primitives (Container, Field, Actor) are altitude-spanning and may be referenced from any altitude.
- **K25** Altitude is derived from primitive kind + slot population — not stored on primitives or relations. (Resolved this design phase.)
- **K26** Spec-altitude and Artifact-flavour axes are independent. "Infra" as a spec altitude (declarative deployment commitment) ≠ "Infra" as an Artifact flavour (observed deployed reality). Any spec altitude can project to any Artifact flavour.
- **K27** Tag-attachment corollary: vocabulary tags carrying upward cross-altitude references attach to the lower-altitude side; payload schemas reference upward. Higher-attaches-with-downward-payload is disallowed.

## Expression and Effect substrate

- **K28** One `Expression` substrate sort, three callsites. Rule bodies, top-level Intent assertions on any host, and §6 constraint predicates all use the §3.8 Expression sort; Environments (§3.8.5: OneState / TwoState / ModelIntrospection) distinguish them. Bool is a §3.3 Type used as the predicate vehicle (no separate `Predicate` sort); Bindings are the `Let` form (no separate `Binding` sort). Mode for bidirectional evaluation under §6 stratification is **callsite-typed**, not substrate-stored — it lives in the §6 rule structure that uses an Expression (parallel to Environment), not on the Expression record itself; structural identity is mode-free. (Earlier framing — "Mode lives on Expression" — superseded by §3.8.6's callsite-typed rephrasing; the substrate record `{ label?, form }` carries no mode slot.) Force: the three callsites all need the same typed value/predicate calculus over the kernel substrate, and the synthesis confirmed a single sort suffices once Environment carries the callsite distinction. Gate: shape uniform across all engaging callsites. Resolves the §13 TBD on canonicalisation of `condition` / `scope` text — slot content is now typed Expression structure with structural-equality identity.
- **K29** `Effect` as kernel-committed record paired with Expression. Effects are first-class substrate records `(kind, target, value, source)` living in `Rule.body.effects`; their content is the canonicalised materialisation of an Expression pattern in `Rule.intent.assertions` per §3.8.4 (Write / Create / Destroy / Emit). Kernel invariant: every Effect has a source Expression resolving back to `intent.assertions`, and every qualifying Expression in such an assertion list has an Effect. The two sorts are dual readings of the same substrate content. Force: graph kernels need stable edge identity for `writes` / `creates` / `destroys` / `emits` — identity is `(rule_id, kind, target)` from the Effect record, stable across semantically-equivalent rewrites of the originating Expression. Without Effect as substrate, edge identity would drift under expression refactors. Gate: shape uniform across the four effect kinds; canonicalisation rules kernel-fixed.
- **K30** Intent grows `assertions` alongside `clauses`; Rule grows `body`. The Intent primitive carries both prose claims (`clauses: List<Clause>`) and logical claims (`assertions: List<Expression: Bool>`) — every Intent-hosting primitive (Container, Behaviour, Rule, Boundary, Operation) gets both shapes through one mechanism rather than parallel host-level slots. Rule alone additionally grows `body: RuleBody?` carrying `definitions: List<Definition>` (typed `where:` bindings, scoped across `intent.assertions` and `body.effects` — the only cross-slot scope rule in the kernel) and `effects: List<Effect>`. Force: every host needs Bool-Expression invariants/guarantees; parallel host-level slots would duplicate the prose-vs-Bool split. Gate: the prose/Bool split is the same shape across all hosts; Rule-specific concerns (where-definitions and effects) live separately on Rule because no other host has them. Methodology slotting (`Allium::Requires` vs `Allium::Guarantees` etc.) lives at the vocabulary altitude as source-clause tags on the Expressions, not at substrate.
- **K31** Expression carries optional `label?: String` for addressability and projection naming. The Expression substrate is a record `{ label?, form }` where `form` is the sum-typed `ExpressionForm`; `label?` is not part of structural identity (which remains shape + free-variable references). Parallels K15a's `label?` on Clause. The `projects` from-side widens accordingly to include `Expression` (sub-substrate reached via SubstrateAddress per §4.3) so named Expressions — notably `Allium::Invariant`-tagged assertions — produce projection edges without methodology-specific naming mechanisms. Force: §7.6 invariant projection needed a label-resolution mechanism after K30 moved invariants from Clauses to Expressions. Gate: shape uniform with Clause's existing `label?`; future named-Expression cases (named guarantees, named contract postconditions if they earn projection) reuse the same slot. Resolves the §13 "invariant naming for projection" and "`projects` from-side widening" TBDs introduced post-K30.

## Kernel relations

- **R1** Force-and-gate substrate-principle analogue for relation-kind selection. A relation belongs in the kernel iff (a) some primitive's substrate has explicitly pushed cross-referenced content into the relation layer and the kernel needs to express it, AND (b) the relation's structural meaning is uniform across methodologies that touch this content.
- **R2** Thirteen kernel relations: `triggers`, `observes`, `reads`, `writes`, `creates`, `destroys`, `emits`, `realises`, `specialises`, `uses`, `exposes`, `provides`, `projects`. Endpoints, semantics, and identifying metadata per §4.
- **R3** `triggers` and `observes` are separate kernel relations — occurrence-based vs state-based have structurally distinct semantics.
- **R4** `triggers` accepts Event or Operation as source.
- **R5** `emits` is Rule-only. Boundary→Event is methodology framing, lifted as a tag with back-references.
- **R6** `realises` (same-altitude) and `projects` (cross-altitude) split. Two named relations sharing edge-schema and comparator framework.
- **R7** `reads` kept kernel for implementation-integrity grounds (spec-to-code drift detection).
- **R8** `creates` and `destroys` kept kernel — identity-introducing vs identity-terminating effects are structurally distinct from `writes`.
- **R9** `creates` and `destroys` target Container only; not Field. Field-value writes (including null) are `writes`.
- **R10** `contains` is not a relation. Containment is encoded entirely in substrate (`Container.children`).
- **R11** Relation endpoints are a sum of `PrimitiveRef` and `SubstrateAddress`; Field is the v0 trivial case. Generalised path form (`{slot, key?}` segments) committed for future Parameter addressability without kernel-shape change. (Clause is reached via `PrimitiveRef` per K15a, not via this path form.)
- **R12** Cross-Container dependency is derived, not kernel. Methodology-flavoured framings (DDD `manages`, Hex `backed_by`) layer as tags on the derivation.
- **R13** Derived relations (`chains`, `depends_on`, transitive closures) are query-time, not stored — eliminates same-fact-twice drift.
- **R14** Relations carry direction; no inverse-pair vocabulary in kernel. Methodologies that want directional-inverse vocabulary carry it themselves.
- **R15** Edges are values. Identity = `(from, to, kind, identifying_metadata)`. Non-identifying metadata mutates without changing edge identity.
- **R16** Multi-edges allowed iff identifying metadata differs. Methodologies drive granularity by what distinguishing metadata they supply.
- **R17** Kernel commits two identifying-metadata slot names: `condition` and `scope`. Plus `projection_kind` from projection vocab. Per-relation participation per the §4.4 table.
- **R18** `specialises` added to the kernel relation set (R2 nine → ten). Force: Allium variants need a structural way to express child-Container-extends-parent-Container; without it, the relationship is invisible to predicates and to the projection layer. Gate: the shape is uniform across methodologies that engage variant / specialisation / inheritance / sum-type patterns. Distinct from `realises` by semantic intent — `specialises` is extension; `realises` is fulfilment. Container-only endpoints in v0; Operation/Event specialisation deferred until a worked example forces it.
- **R19** `uses` added to the kernel relation set (R2 ten → eleven). Force: Allium `contracts: demands` declares a Surface's dependency on a Contract's interface; without a kernel relation, every declared cross-Container dependency would live as a methodology-specific tag, fragmenting "what depends on what" queries across namespaces. Gate: the shape is uniform across methodologies engaging declared interface dependency — DDD Customer-Supplier (downstream → upstream), Hex Application → Driven Port, C4 dependency arrows, generic interface usage. Methodology-specific shading (CustomerSupplier upstream-side metadata, ACL, Conformist, etc.) layers as edge tags. Distinct from derived `depends_on` — `uses` is declared at spec altitude; `depends_on` is observed from concrete Rule effects + realises edges + type references. Both feed §6.6's universal `depends_on` derivation. Container-only endpoints in v0.
- **R20** `exposes` and `provides` added to the kernel relation set (R2 eleven → thirteen). Force: Allium View protocol (`exposes:`) and Signal protocol (`provides:`) both express boundary advertisements with cross-methodology analogues — REST/GraphQL/Hex Driving Port for `exposes`; Hex/Pub-Sub/CQRS for `provides`. Originally folded into `Allium::Exposes` / `Allium::Provides` tag-with-back-reference per V3, but applying the same force-and-gate standard as R18/R19 lifts both to kernel for cross-methodology uniformity. Gate: the shape ("Container advertises Field for read at boundary"; "Container advertises Event as entry point at boundary") is uniform across methodologies engaging the View / Signal protocols. Methodology-specific shading (`Allium::Exposes`, `Allium::Provides`, future `Hex::DrivingPort` tag etc.) layers as edge metadata. Together with R18/R19, the three Allium boundary protocols (View, Signal, Call) all land as kernel relations: View via `exposes`, Signal via `provides`, Call via the existing Operation substrate plus `realises`/`uses` for contract relationships.

## Vocabulary mechanism

- **V1** Force-and-gate substrate-principle analogue for contribution-kind selection. A contribution kind belongs at the mechanism seam iff (a) the kernel has explicitly deferred something to methodology and methodologies need a place to land that content, AND (b) the contribution shape is uniform across methodologies that engage this kind of content.
- **V2** Contribution-kind set collapses to three: **tagged content attachment**, **predicate registrations**, **renderers**. Tags and structured content fuse; methodology-typed relations dissolve into tagged content + edge metadata.
- **V3** Methodology-typed relations dissolve, not separate seam. Every concrete case decomposes into vocabulary content with back-references or methodology tags on stored kernel edges. The kernel relation vocabulary stays closed.
- **V4** Cross-vocabulary semantic equivalence is out of scope. The mechanism supports layering distinct vocabularies; it does not align their tags. Inheritance is intra-namespace only.
- **V5** Tag payloads declare kernel-visible schemas using the unified `Type` vocabulary; opaque blobs forbidden.
- **V6** Predicates and renderers share one introspection surface (kernel substrate, kernel relations, tag presence, tag payload, derivations, logical composition).
- **V7** Methodology-author obligation: for a constraint to be checkable, the content it references must first be lifted onto the model via tagged content. The bargain is graded by typedness — opaque-text payloads have technically lifted but offer little leverage.
- **V8** Constraints handoff boundary. This document's vocabulary section owns: registration shape, introspection surface, severity/scope/messaging metadata. §6 owns: predicate expression language, evaluation engine, violation UX, predicate composition.
- **V9** Tag inheritance v0 semantics: payload-schema extension ✅, tag-presence implication ✅, field-override ❌, multi-parent ❌. (Intra-namespace only per V4.)
- **V10** Tags can attach to **stored** kernel relation edges, not only to primitives. Derived edges cannot carry tags. Namespace shared across attachment targets; disambiguation by `applies_to`.
- **V11** Renderer seam committed now; implementation deferred to explorer rebuild.
- **V12** Relational tags — first-class affordance for relationship-shaped declarations. `TagDefinition.relational: RelationalSpec` declares endpoints, symmetry, canonical storage side. Mechanism provides storage canonicalisation, pair-indexed lookups.
- **V13** Coherence queries — kernel-query templates bundled in relational tag definitions. Per-application evaluation answers "does this declaration's claim hold now?" Default `kind: drift, severity: warning`. Depends on the §6 predicate language.
- **V14** Predicate registration `severity` is `error | warning`; new `kind` slot (open identifier; kernel-shipped well-known: `integrity`, `drift`). Severity and kind are orthogonal axes — severity is workbench action; kind is violation category for reporter rendering.
- **V15** Auto-derived per-tag predicates. For each `TagDefinition` loaded by the constraint engine, fukan auto-derives a small set of Datalog predicates as kernel-universal derivations (§6.6): `<ns>_<name>(target)` for presence, `<ns>_<name>_<field>(target, value)` per payload field, `<ns>_<name>(endpoint_a, endpoint_b)` for relational tags. **The derived predicates are macros over the universal tag-introspection vocabulary** (`has_tag` / `tag_payload`); they introduce no methodology-private semantics, no per-vocab transitive closures, and no engine extensions. The universal layer (kernel relations + `has_tag` / `tag_payload`) remains the load-bearing query surface for cross-vocab queries; sugar accelerates the common per-vocab case. Force: predicate authors writing methodology-specific queries needed flatter Datalog than the explicit `has_tag` + `tag_payload` + payload-navigation chain. Gate: derivation rules uniform across all TagDefinitions (presence, per-field, relational shapes); macro-only commitment uniform across all derived predicates. Preserves the cross-vocab querying property that motivated the §5 universal-mechanism design.

## Constraint language

- **C1** Force-and-gate analogue at the language-family altitude. A language feature belongs in the substrate iff (a) some worked-example constraint forces it and (b) the feature's shape is uniform across constraints engaging it.
- **C2** Family: stratified Datalog substrate + two sugar layers. Datalog passes the gate uniformly across all demand classes; Cypher fails on type-sum case analysis; predicate calculus collapses to Datalog under tractability.
- **C3** Negation is stratified under closed-world assumption. ExternalEntity stubs are *in* the model; CWA treats them as model objects with restricted substrate. Externality is a tag, not a semantic mode.
- **C4** Open-world concerns encoded as predicates (`is_local(X)`), not language semantics.
- **C5** Aggregation committed to the language; operator set TBD (starting cut `count`, `min`, `max`, `sum`).
- **C6** Quantification is implicit Datalog idiom, with surface sugar for ∀/∃.
- **C7** Sort system uses the Type vocabulary (`Primitive | Enum | Record | Ref | List`) one altitude up. Sort guards are unary predicates.
- **C8** Fukan ships a *fukan-side closed* set of kernel-universal derivations (transitive closures, `chains`, `depends_on`, substrate navigation, tag indexing, type-vocabulary discrimination). Per V15, this set is **extended at engine startup** by per-tag predicates auto-derived from loaded `TagDefinition`s — these are macros over the universal tag-introspection vocabulary and add no new semantics beyond what `has_tag` / `tag_payload` already express.
- **C9** Methodology-derived properties are plugin-authored Datalog rules over the V6 introspection surface. No special mechanism for methodology-private derivations.
- **C10** Coherence queries are parameterised Datalog rules; head arguments come from the relational tag's endpoint payload fields.
- **C11** Path navigation sugar — four forms (substrate dot-access, substrate set-access, relation arrows, tag-payload access). Linear chains only.
- **C12** Type-sum case-analysis sugar — one `matches` operator; patterns mirror the six Type cases (`Scalar`, `Enum`, `Composite`, `Collection`, `Union`, `Ref`). `Collection` patterns can match by `semantics` (`Sequential | Unique | Keyed(K)`).
- **C13** Materialisation is implementation tuning; R13 unchanged. Hot derivations may be incrementally materialised; cold ones evaluate on demand.
- **C14** Bindings drive violation reporting; no separate reporting mechanism. `message_template` substitution per V8.
- **C15** Project-defined constraints use the same registration shape as methodology-shipped constraints (V8). Composition mechanism (activation, severity overrides, bundles, versioning) deferred per the cut list in §10.3. **Directional, not load-bearing for MVP.**
- **C16** Severity (`error | warning`) and kind (open identifier) are orthogonal registration-time axes. Severity is workbench action; kind is intrinsic violation category for reporter rendering.

## Projection vocabulary

- **P1** Output vocab (projection targets) is separate from input vocab (modelling techniques) — two distinct mechanisms with parallel structure but distinct roles.
- **P2** Projection vocab is fukan-owned, closed-sum, parallel-kernel evolution lane. Not opened to plugin extension in this scope. Evolves additively, locally, with bounded impact.
- **P3** Projection vocab architected with plugin-extensibility seams (sum-type shape, per-case identity isolation, case-dispatched comparators, per-case `projection_kind` vocabulary, no cross-case coupling).
- **P4** v0 Artifact ontology: one category — Code (Function, DataStructure). Two leaf cases. Identity per §7.3. Infra and Documentation categories come back via the §7.5 sum-extension seam when their respective analyzers join (§10.1, §10.5).
- **P5** v0 `projection_kind` enum: five values (`rule | operation | invariant | schema | test`). Function-shaped projections name their source primitive kind; the schema projection names its artifact shape; test is uniform across sources. Infra-flavour (`endpoint`, `resource`) and Documentation-flavour (`documentation`, `diagram`) values come back together with their producing analyzers.
- **P6** Implementation linkage uses *convention-driven name resolution* exclusively — no code-side annotations, no out-of-band binding files. Project sets one root-prefix knob; all other mapping is mechanical (per DESIGN.md's Implementation linkage section; substrate-level shape in §7.6). Strict enforcement: single canonical address per spec primitive; multiple matches or scatter is a lint violation.
- **P7** Surface and Contract have no direct code projection — their operations project individually. Polymorphic dispatch implementations are code-side organisational choices, not spec-driven.
- **P8** Clojure schemas project to malli-style top-level defs (`(def Name <schema-expr>)`), not defrecords. Recognition is structural (var presence at expected name); the data-shape of malli schemas enables finer-grained drift detection later as a non-breaking enhancement.

---

*Section references throughout (§3, §4, §6, §7.6, §8.2, §10.x etc.) point into [MODEL.md](./MODEL.md).*
