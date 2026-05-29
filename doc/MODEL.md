# Fukan — Model

**Status:** Authoritative substrate specification — the *how* foundation underlying everything else.

**Reading order:** Read [VISION.md](./VISION.md) first for motivation if you're new, then [DESIGN.md](./DESIGN.md) for the three-tier canvas layering principle, the ownership-on-owner substrate principle, and the build pipeline. This document is the substrate kernel itself — primitives, relations, vocabulary mechanism, constraint language, projection vocabulary. [DECISIONS.md](./DECISIONS.md) preserves the design-phase decision trace; K\*/R\*/V\*/C\*/P\* identifiers cited throughout this document resolve there.

This document defines the substrate on which canvas specs and code analysis project their content. It is the authoritative description of what the Model is, what neutralities it commits to, what vocabulary content it permits, what constraints can be expressed against it, and what its projection targets are.

**Current state (Phase 3 canvas-first):** The Model is built from canvas specs — Clojure data files in `canvas/<subsystem>/<module>.clj` — projected via Phase 0 (canvas ingestion) into the model map the graph viewer consumes. The spec languages described in the design-phase documents (`.allium`, `.boundary`) are retired to `.legacy-allium/` and no longer loaded by the build pipeline. The substrate kernel described below is unchanged; what changed is the *source* of content that populates it. Canvas ports project their datoms into kernel-shaped primitives and edges via `src/fukan/canvas/projection/canvas_source.clj`. See [DESIGN.md §Build pipeline](./DESIGN.md#build-pipeline) for the Phase 0–6 pipeline shape.

---

## 1. Stance

The Model represents **one architectural object** — a software system — **viewable through many vocabulary lenses**. Borrowing ISO/IEC/IEEE 42010's meta-shape: there is one system; it is described through multiple viewpoints; viewpoints supply vocabulary, but they are not the system.

Two commitments fall out:

1. **The Model is *of the system*, not *of a spec language*.** A spec language contributes evidence about the Model; the Model is not a graph rendition of any one grammar. The same Model is contributable-to by `.allium`, by `.boundary`, by future `.infra`, and by code analysis when it returns.

2. **The Model is methodology-neutral at vocabulary level; explicitly committed at substrate level.** Any methodology contributes typed vocabulary that refines the substrate's primitives. The substrate itself takes positions on a few unavoidable questions (see [§11 Substrate commitments](#11-substrate-commitments)).

Currently, canvas vocabulary lifts (`function`, `invariant`, `rule`, `record`, `value`, `getter`, `checker`, `exports`) are the primary contributors. Code analysis contributes via the Clojure Target language extension. Future vocabularies (additional methodology libraries, `.infra` when it arrives) contribute additively without substrate change.

**Substrate-defaults choice (Path A).** Where the kernel's substrate must take a position on type-system shape (named vs anonymous shapes, closed vs open, predicate-typed vs not), it commits to Allium-aligned defaults: shapes are named, closed, composed by inclusion. Methodologies that want different semantics (Clojure-spec-style openness, predicate-typed fields, schemas-as-first-class-values) supply them via vocabulary overlay. This is a commitment, not neutrality — explicitly named and to be revisited before any major substrate change. **The position is also the floor, not the midpoint.** Named-closed-included is the most restrictive of the common type-shape choices, so methodologies can selectively relax it (opening, adding predicates, anonymising) and the substrate sees the relaxed view through the vocabulary overlay; the inverse — tightening what a permissive substrate already allows — is unreachable from above without breaking earlier vocabulary content. The floor choice keeps the kernel a checkable, conservative baseline; loosenings live where they belong, in the methodology that needs them.

### Force-and-gate

A recurring criterion applied at every altitude of this design:

> A feature belongs at altitude X iff
> **(a)** something has forced it — a worked example that cannot be expressed without it, AND
> **(b)** its shape is uniform across the cases that engage it.

**(a)** is the force; without it, the feature is speculative.
**(b)** is the gate; without it, the feature is methodology framing wearing a universal mask.

The criterion appears at four altitudes in this document:
- Substrate vs relation vs vocabulary (§3.7)
- Kernel-relation vs methodology-relation (§4.2)
- Contribution kind at the vocabulary seam (§5.1)
- Language feature in the constraint substrate (§6.2)

---

## 2. Implemented scope

The spec described in this document is the substrate. This section reflects what is currently implemented (Phase 3 canvas-first state).

**Implemented:**
- The kernel substrate (primitives, value records, Type vocabulary, the thirteen relations, edge identity)
- The vocabulary mechanism (TagDefinition / TagApplication / PredicateRegistration / renderer seam)
- The constraint language (stratified Datalog substrate + path sugar + type-sum sugar, kernel-universal derivations)
- The projection vocabulary as an active target — `Code(Function | DataStructure)` Artifact cases and the five V0 `projection_kind` values (§7.2, §7.4)
- **Canvas spec ingestion (Phase 0)** — the primary source of model content. Canvas specs in `canvas/<subsystem>/<module>.clj` build Datascript stores via the canvas vocabulary libraries; `canvas-source/build` projects those stores into kernel-shaped primitives and edges. Replaces the retired Allium and Boundary spec parsers.
- The **Clojure Target language extension** (§7.7) — the Analyzer producing `projects` edges from spec primitives to `Code.*` artifacts via convention-driven name resolution (Phase 6). The Projector (on-demand Implementation Blueprint generation) is architecturally seamed.
- The **project layer** in two sub-loci (§10.3) — projection inputs and constraints.
- Phase 4 structural validation (sub-phases 4a–4g) and Phase 5 constraint evaluation.

**Retired (Phase 3 Sprint 4):**
- The Allium spec parser and Allium Vocabulary extension — replaced by canvas specs. Legacy `.allium` files are archived in `.legacy-allium/`.
- The Boundary spec parser and Boundary Vocabulary extension — replaced by canvas exports mechanism. Legacy `.boundary` files are archived in `.legacy-allium/`.

**Architecturally seamed (deferred):**
- `.infra` layer entirely (§10.1)
- The `Infra(...)` and `Documentation(...)` Artifact cases, and the `endpoint`, `resource`, `documentation`, `diagram` projection_kind values
- Extension registry mechanics (§10.2) — extensions are integrated directly; no manifest format
- Renderer plug-in mechanism (concrete shape arrives with explorer rebuild)
- Composition mechanics of project-layer entries (severity overrides, profiles, bundles, transitive imports, versioning)
- Cross-methodology stress-test vocabularies (DDD / Hex / C4) — they validated the substrate at design time; they are **not** committed support targets

**Architectural commitments preserved through the cuts:**
- The constraint language is **single**. Whatever path projects use to ship their constraints lands on the same language.
- The kernel does not change shape for any deferred layer. Each deferred layer is additive content within the committed substrate.
- The vocabulary mechanism's introspection surface (V6 below) is the *only* domain accessible to predicates and renderers, regardless of altitude.

**Relation population.** The kernel relation set is unchanged; the canvas vocabulary lifts are the current contributors. `function` / `getter` / `checker` and `invariant` / `rule` populate the causation, observation, and effect relations through the Affordances they emit; `record` / `value` and shape expressions populate the type-reference relations; `exports` and `within-module` (`:module/child`) carry ownership and API closure. The Clojure Target language extension's Analyzer contributes `projects` edges with per-edge `:validity` (§7.6). The historical per-relation mapping the retired Allium/Boundary vocabularies realised is recorded in §8. Future methodology vocabularies (Hex, DDD, …) would add more edges to the same relations without changing kernel shape.

Re-opening triggers for the deferred work are recorded in [§10 Architectural seams](#10-architectural-seams).

---

## 3. The kernel

The kernel is the universal, fixed substrate. It defines the primitives, value records, type vocabulary, relation set, and edge identity machinery that every methodology projects content into.

### 3.1 Primitives

Nine primitives. Each justified by one or both of (a) addressability — a thing a human chooses to inspect as a unit, (b) structural distinctness — a navigation pattern that diverges from any other primitive's.

```
Container
  id, label
  description:  Text?
  intent:       Intent?                  -- Container-level claims
  children:     Set<Container>
  fields:       List<Field>              -- typed data members
  events:       Set<Event>               -- declared events owned here
  behaviour:    Behaviour?
  boundary:     Boundary?

Actor
  id, label
  description:  Text?

Behaviour
  id, label
  rules:        List<Rule>
  intent:       Intent?

Rule
  id, label
  description:  Text?
  intent:       Intent?                  -- prose Clauses + Bool assertions (per §3.8)
  body:         RuleBody?                -- definitions + effects (per §3.8)

Boundary
  id, label
  operations:   List<Operation>
  intent:       Intent?

Operation
  id, label
  description:  Text?
  parameters:   List<Parameter>
  return_type:  Type?                    -- absent for fire-and-forget command-shaped
  intent:       Intent?

Intent
  id, label
  clauses:      List<Clause>             -- prose claims
  assertions:   List<Expression: Bool>   -- logical claims (per §3.8; env from host)

Clause
  id, label?
  body:         Text

Event
  id, label
  description:  Text?
  parameters:   List<Parameter>
```

#### Per-primitive notes

**Note on ownership terminology.** Several primitives below are *owned by* another primitive — Rule by Behaviour; Operation by Boundary; Clause by Intent; Event by its qualifying Container. They are first-class kernel primitives despite their owned location (each has its own id and full primitive standing), distinct from §3.2's *sub-substrate* (Field, Parameter) which carries composite identity tied to its owner. The "sub-unit of X" / "sub-structure of X" phrasing in the per-primitive notes below refers to location, not identity — these are kernel primitives that happen to live inside another primitive's slot.

**Container** — universal addressable structural unit. Substrate carries three optional faces (Data via `fields`, Behaviour, Boundary), plus `children` for hierarchical composition, plus `events` for declared event ownership, plus Container-level `intent`. No substrate booleans for identity/state — these derive from realising graph structure + vocabulary tags. No `parent` slot — children-on-parent stores ownership once, no drift. Container's `intent` holds claims about the Container as a whole (entity-level `@invariants`, module-level invariants). Different methodologies populate different slots: data-shape Containers use `fields`; module-shape Containers use `children`; interface-shape Containers use only `boundary`; orchestrator-shape Containers use `behaviour`. All are Containers.

**Actor** — agency-bearing party outside the ownership hierarchy. Primarily for human roles/personas. Substrate is identity + description. Methodology-specific framings (Allium's `identified_by`, `within`) live in vocabulary. Choose Actor when the counterparty bears agency (users, roles, named external services with personhood); choose an `Allium::ExternalEntity`-tagged Container when the external thing has data shape or callable surface to type-check against but no agency role. Actor originates no kernel relations — its role is as the typed counterparty a Surface `facing:` orients toward, anchoring the external-observability derivation.

**Behaviour** — singular aggregate of dynamic logic per host Container. Each Container has at most one Behaviour; multiplicity lives in its Rules. Aggregate-level `intent` attaches claims spanning the Rules.

**Rule** — sub-unit inside a Behaviour. Substrate: identity + description + intent + body. The Rule's *Bool claims* (`requires:` preconditions, `ensures:`-as-predicate postconditions) live as `Expression: Bool` entries in `intent.assertions`; prose `@guidance` lives in `intent.clauses` (per §3.8). The substrate also accommodates additional rule-body Bool claims (e.g., postconditions distinct from `ensures:`) that Allium v3 does not currently surface; reserved slots stay empty under Allium-only loading. The Rule's *named definitions* (`let` typed bindings) live in `body.definitions`, in scope across `intent.assertions` and `body.effects`. The Rule's *effects* (writes, creates, destroys, emits) live as `Effect` records in `body.effects` — each materialised from a corresponding Expression in `intent.assertions` per the §3.8 kernel invariant, and each surfacing as a kernel edge with identity `(rule_id, kind, target)` per §4. The Rule's *trigger* lives as a relation (`triggers` for Event → Rule; `observes` for typed-subject conditions).

**Boundary** — singular aggregate per host Container; the typed interface that crosses the wall. Substrate is `operations` + aggregate `intent`. Datashapes (what the Boundary exposes), advertised events (what events it provides), facing (who it serves), related (what it depends on) all live as relations or vocabulary. The Allium boundary protocols (View / Signal / Call) emerge from substrate + relations + vocabulary tag combinations rather than from named primitive kinds (see §3.6 below).

**Operation** — sub-structure of Boundary; a typed signature. Substrate carries the signature (`parameters` + `return_type`); cross-primitive linkage (`triggers` a Rule, `realises` a Port) lives in relations. `return_type` is optional: present for call-shaped (request/response), absent for fire-and-forget command-shaped operations.

**Intent** — meaning attached to a host. Singular per host (Container, Behaviour, Rule, Boundary, Operation). Carries two parallel lists: `clauses` (prose claims — descriptions, guidance, open-questions) and `assertions` (Bool-typed Expressions per §3.8 — invariants, guarantees, preconditions, postconditions). Kinds of prose claim (guidance, open-question) and kinds of Bool claim (invariant, guarantee, precondition, postcondition) are vocabulary tags on the respective items, not kernel slots. The host primitive determines the Environment (§3.8.5) in which assertions are typed: OneState for Container / Behaviour / Boundary / Operation; TwoState for Rule.

**Clause** — sub-unit of an Intent. Optionally labelled for addressability; body is opaque text. Vocabulary supplies kind tags and structured parses where applicable.

**Event** — discrete temporal occurrence. Owned by the qualifying Container via `Container.events`. Substrate is identity + description + parameters. Two Events with the same local name in different Containers are distinct primitives — namespace qualification is the qualifying Container. **In Allium, the qualifying Container is the Module** (the `Allium::Module`-tagged Container): Events declared at `when:`, `provides:`, or `emits:` sites within a module are owned by that module, not by the Surface/Contract/Rule where the site appears. Cross-module references via `alias.EventName` resolve through the module alias to the module's owned Event. Other methodologies may choose different qualifying levels; the kernel mechanism is altitude-agnostic.

### 3.2 Substrate value records

```
record Field
  name:         String
  type_ref:     Type                     -- the unified Type vocabulary (§3.3)
  optional:     Boolean

record Parameter
  name:         String
  type_ref:     Type
  optional:     Boolean
  ordinal:      Integer

record Definition
  name:         String                   -- bound name in scope across Rule.intent.assertions
                                         --   and Rule.body.effects (Rule-internal scope)
  expression:   Expression               -- per §3.8; typed by §3.3 (not necessarily Bool)

record RuleBody
  definitions:  List<Definition>         -- `let` bindings
  effects:      List<Effect>             -- materialised state-change records per §3.8.2
```

Composite identity for sub-substrate:
- Field identity: `(Container.id, Field.name)`
- Parameter identity: `(parent_id, Parameter.name)` where parent is an Operation or an Event
- Definition identity: `(Rule.id, Definition.name)`
- Effect identity: `(rule_id, kind, target)` per §3.8.2

RuleBody is a singular sub-substrate aggregate per Rule (cardinality pattern same as Behaviour on Container) — its identity reduces to the host Rule's id; no independent identity slot.

Clause is **not** sub-substrate — it is a kernel primitive (per §3.1) with its own id. Its `label?` is a human-facing name within its parent Intent (used for addressability in tools and projection targets); it is not part of identity. Moving a Clause from one Intent to another preserves its id. (See K15a in the decisions log; resolves the §13 TBD on first-class Clause addressability.)

### 3.3 Type vocabulary

One vocabulary serves both substrate slots (`Field.type_ref`, `Parameter.type_ref`, `Operation.return_type`) and vocabulary payload schemas (`TagDefinition.payload_schema`). Six cases. The substrate stays generic at the target-language level — substrate Types commit to *structural shape*, not to "what kind of integer" or "which Clojure collection." Per-target-language translation lives in the project layer's translation-guidance registry (registered via §10.3; per-language registry detail in [DESIGN.md](./DESIGN.md)).

```
Type =
  | Scalar(name: String)                              -- atomic leaf; name is methodology-supplied
  | Enum(values: List<String>)                        -- closed set of literal values
  | Composite(shape: CompositeShape)                  -- structured aggregate (product of slots)
  | Collection(of: Type, semantics: CollectionSemantics)
                                                      -- zero-or-more of the same shape (functor)
  | Union(types: List<Type>)                          -- sum of distinct Type cases
  | Ref(target: RefTarget, where: TagConstraint?)     -- reference to a kernel target

CompositeShape =
  | Named(container: ContainerRef)                    -- shape from a value-typed Container's fields
  | Inline(fields: List<FieldSpec>)                   -- anonymous inline shape

CollectionSemantics =
  | Sequential                                        -- ordered, duplicates allowed
  | Unique                                            -- unordered, no duplicates
  | Keyed(key_type: Type)                             -- key→value association

RefTarget =
  | KernelPrimitive(kinds: Set<KernelKind>)           -- Container | Event | Actor | Rule | Operation
                                                      -- | Behaviour | Boundary | Intent | Clause
  | Substrate(within_kind: KernelKind,                -- Field | Parameter
              slot_kinds: Set<SubstrateKind>?)

TagConstraint = Set<TagRef>                           -- target must carry all listed tags

FieldSpec { name: String, type: Type, optional: Boolean }
```

The six cases are the three type-theoretic combinators (product, sum, functor) plus three leaf shapes (atomic, closed-literal, link). No two are reducible to each other without semantic loss:

| Case | Combinator | What it commits |
|---|---|---|
| `Scalar` | leaf | An atomic value; the methodology-supplied `name` is opaque to substrate (translation handles `"Integer"` → `:int`, `"Money"` → custom). |
| `Enum` | leaf (closed sum of literals) | A value chosen from a known fixed set. Distinct from Union: Enum cases are *literals* with no internal type; Union cases are *full Types*. |
| `Composite` | product | A bundle of typed slots, all present; cardinality fixed at the slot count. `Named(C)` reuses a value-typed Container's fields; `Inline` declares anonymous slots. |
| `Collection` | functor | Zero-or-more values of one inner Type; cardinality variable. `semantics` distinguishes the three fundamental collection shapes (ordered/unique/keyed). Tuple-style positional Composite is the future fix when forced. |
| `Union` | sum | A value typed as exactly one of several Types. Each case stays structurally distinct (no implicit coercion). |
| `Ref` | link | An identity pointer to an addressable kernel target (primitive or substrate position). `where: TagConstraint` filters admissible targets by tag presence. |

**Positions of the design:**

- `Scalar` keeps the `name` field opaque to substrate. Methodology guidance assigns semantics: Allium's `"Integer"`, `"String"`, `"Boolean"` are well-known leaf scalars; Allium's `"Money"`, `"DateTime"`, etc., earn translation overrides via the project layer.
- `Composite(Named(C))` is "shape comes from value-typed Container C's fields"; `Composite(Inline([…]))` is an anonymous inline shape. Predicates and renderers traverse `Composite` uniformly. (Tuple — positional Composite — defers until a worked example forces it; the `CompositeShape` sum extends additively.)
- `Collection` carries an explicit `semantics` discriminator because the three shapes (ordered, unique, keyed) affect what predicates can reason about: iteration order, element duplicates, key access. Methodologies that map to a single target form (Allium's `List<X>` always being Sequential) commit explicitly to that semantics; methodologies wanting Set or Map declare it. Target-language rendering ([:vector T], [:set T], [:map-of K V] in malli; Java ArrayList/HashSet/HashMap) is project-layer translation, not substrate.
- `Union` covers heterogeneous payloads like `Boundary::ModuleApi.exported` (Surfaces ⊕ Entities ⊕ Events ⊕ Actors ⊕ Operations) and `Allium::Surface.facing` (Actor ⊕ Container). Cross-kind heterogeneity without a common parent tag does not collapse to multi-kind `Ref`. Consumers dispatch on case via the §6.8 `matches` sugar; the §6.6 `type_refs` derivation has a Union arm that unions per-case reachability.
- `Ref` reaches all kernel primitive kinds, not only Containers — payloads need to reference Events, Actors, Operations, Rules, none of which are Containers. `where: TagConstraint` filters admissible targets by tag presence.
- `Ref(Substrate(...))` mirrors the `SubstrateAddress` machinery in §4.3 at the schema level — payloads can type slots as references to specific Fields / Parameters. The *type-level* `Ref(Substrate(within_kind=Container, slot_kinds={Field}))` declares "a reference to some Field on some Container"; the corresponding *value* at runtime is a `SubstrateAddress(container_id, [{slot:'field', key:'<name>'}])` per §4.3, carrying the specific Container and slot identifiers. (Clauses are reached via `Ref(KernelPrimitive({Clause}))` since Clause is a primitive — see K15a.)
- **Optionality lives on `FieldSpec`, not as a `Type` case.** Slot-level "can be null" is a property of the slot. Nested optionality (a Collection whose elements may be null) defers until forced.

#### Value-vs-Reference is explicit

For a methodology with both value-typed and entity-typed Containers (Allium has `value` and `entity`), the slot-type semantics are explicit:

- **Value-typed target**: `Composite(Named(C))` — the slot's value has C's shape inline.
- **Entity-typed target**: `Ref(KernelPrimitive({Container}), where={Allium::Entity})` — the slot holds a reference.

Methodologies drive the choice via their vocabulary tag on the target Container; predicates can ask "is this slot Value or Ref?" and get a kernel-level answer without inspecting target tags.

#### Worked examples

| Slot | `Type` |
|---|---|
| `Order.owner: User` (entity target) | `Ref(KernelPrimitive({Container}), where={Allium::Entity})` |
| `Order.shipping_address: Address` (value target) | `Composite(Named(Address))` |
| `Order.status: pending\|done` | `Enum(["pending","done"])` |
| `Order.line_items: List<LineItem>` (value LineItem) | `Collection(of: Composite(Named(LineItem)), semantics: Sequential)` |
| `Order.tags: Set<String>` | `Collection(of: Scalar("String"), semantics: Unique)` |
| `Order.shipping_options: Map<String, ShippingMethod>` | `Collection(of: Composite(Named(ShippingMethod)), semantics: Keyed(Scalar("String")))` |
| `Boundary::Exports { exported }` (heterogeneous list — Surfaces, Entities, Events, Actors, Operations) | `FieldSpec("exported", Collection(of: Union([Ref(Container, where={Allium::Surface}), Ref(Container, where={Allium::Entity}), Ref(Container, where={Allium::Value}), Ref(Container, where={Allium::Variant}), Ref(Event), Ref(Actor), Ref(Operation)]), semantics: Sequential))` |
| `Allium::Surface { facing?, … }` (payload — accepts an Actor or an entity-tagged Container) | `FieldSpec("facing", Ref(KernelPrimitive({Actor, Container})), optional=true)` |
| `Allium::Surface { related }` (payload — list of peer Surfaces) | `FieldSpec("related", Collection(of: Ref(KernelPrimitive({Container}), where={Allium::Surface}), semantics: Sequential))` |
| `Boundary::ModuleApi { exported }` | same shape as `Boundary::Exports.exported` above (see §8.2) |
| Hypothetical tag payload pointing at a Field on a Container | `FieldSpec("target_field", Ref(Substrate(within_kind=Container, slot_kinds={Field})))` |

### 3.4 Cardinality patterns

- **Singular aggregate** (one per host, internal substructure): Intent (Clauses), Behaviour (Rules), Boundary (Operations)
- **Plural collection on host** (named first-class units owned directly): Container's `children`, `fields`, `events`; Operation's `parameters`

Pattern: *aspects of the host's own being* → singular aggregate; *named first-class units* → plural collection.

**Operational form of the substrate principle:** *Aggregated/owned content → substrate. Cross-referenced content → relations.* If a sub-thing cannot exist without its host (Field on Container, Operation on Boundary, Rule in Behaviour, Clause in Intent, Parameter on Operation, Event on its qualifying Container), it is substrate. If a sub-thing has independent identity and the host merely references it (the Event a Rule emits when distinct from the host Container's owned Events, a Field exposed by a Boundary, a Container in a `manages` derivation), it is relational.

### 3.5 Asymmetries — intentional

- **Singular for unified meaning, plural for distinct units.** Intent is singular (the meaning of the host) even when it carries many Clauses. Container's `children` and `events` are plural (each is its own thing). Both encode different relationships and are correctly cardinality'd.
- **Container-level ownership is uniform across children, fields, events.** All three are direct ownership on the qualifying Container — no separate qualification relation.

### 3.6 Derived — not kernel primitives

**External** is not a kernel kind. *Derived* from topology: a Container referenced from outside its owning hierarchy is "external" *from the perspective of the referencing side* — but it stays a Container. Externality is a property of the *crossing*, not of the node.

Allium's `external entity` declaration is a stub at the use site that creates a cross-module reference. The actual referent (if defined elsewhere in the analysed model) is a Container in its owning module. If it isn't defined, the stub is a Container with vocabulary tag `Allium::ExternalEntity` and minimal substrate content. Closed-world predicate evaluation (see §6.2) treats stubs as model objects with restricted substrate.

**Stub-resolution semantics: unconditional merge.** The merge step (per the pipeline in DESIGN.md) is a function `Model = merge(analyzer_outputs)` — the pre-merge analyzer outputs are inputs; the loaded Model is the projection. In that projection, an `external entity` stub at use site U pointing at name N is identified with the real Container defined elsewhere if one exists: the Model contains a single Container under the real id, and all references — edges, tag payloads — resolve to it. The real Container retains its full substrate; the `Allium::ExternalEntity` tag does not propagate to it (the stub's external-ness was a use-site framing, not a property of the entity itself). When no real Container exists, the stub appears in the projection as a Container with `Allium::ExternalEntity` tag and restricted substrate — visible to predicates as a normal model object under CWA. (Read imperatively, the operation is identity-rewrite-plus-retarget over a working buffer; the substrate prefers the projection framing — the Model is a value (see §11), the merge step is the function that computes it.)

The merge is unconditional with respect to the target module's visibility — closed-module / private-target cases do not change merge behaviour. DESIGN.md's pipeline catches the "stub pointing at a closed-private target" case at the *cross-module reference visibility* rule (Phase 4g), which inspects the original `external entity` site against the target module's `module exports:` and rejects the reference when the target is not listed. DESIGN.md's *export closure* rule (Phase 4f) is the upstream guarantee that makes 4g enforcement consistent: if a closed module passes closure, no signature reachable from outside it exposes a private name, so any stub naming a private target is by construction a fabrication (or a stale reference) and is correctly caught at 4g. Both rules run after merge in Phase 4 — the validator preserves the original cross-module reference sites independently of merge's identity-rewrite, so 4g can attribute violations to the referencing site.

**The three Allium boundary protocols (View / Signal / Call)** are not kernel primitive *kinds*, but two of the three now have direct kernel-relation landings (R20). They are recognised by edge presence on a Boundary-bearing Container, optionally decorated by methodology tags:

| Protocol | Allium keyword | Kernel signature |
|---|---|---|
| **View** | `exposes` | Container with Boundary; outgoing `exposes: Container → Field` edges, each reaching a Field on another Container. Optional `Allium::Exposes` tag on the edge for methodology-specific shading. |
| **Signal** | `provides` | Container with Boundary; outgoing `provides: Container → Event` edges. Optional `Allium::Provides` tag on the edge. |
| **Call** | `contracts` | Container with Boundary; Boundary has Operations with parameters and (typically) `return_type`. Contract relationships expressed via `realises: Surface → Contract` (`fulfils`) and `uses: Surface → Contract` (`demands`) — both kernel relations. |

REST / GraphQL / gRPC / Pub/Sub / CQRS map analogously by combining the same substrate kinds with methodology-specific tags.

### 3.7 The substrate principle (kernel-vs-vocabulary)

A slot belongs at kernel substrate iff it is **essential to understanding the primitive in isolation**. Cross-primitive interactions live in relations. Design/intentional framing (which party is served, in what context, under what guarantees) lives in vocabulary.

Examples:
- An Operation's signature is essential — without it, the Operation is just a name. → substrate
- A Rule's trigger is interaction — the Rule's pre/postconditions still make sense without it. → relation
- A Boundary's facing actor is design context — the typed interface still stands without it. → vocabulary
- A Container's fields are essential to a data-shape Container in isolation. → substrate
- A Container's emitted events (when not declared on the Container) are cross-referenced. → relation
- Whether a Container is a `DDD::Specification` or an `Allium::Surface` is methodological framing — the underlying Boundary-only Container is the same. → vocabulary
- An Expression's *structure* (shape, free variables, operator at each node) is essential to its meaning. → substrate
- An Expression's *role* in its host (whether it was authored as `requires:` vs `ensures:`, etc.) is methodological framing. → vocabulary
- An Effect's `kind` / `target` / `value` / `source` are essential to understanding what the Rule changes. → substrate

### 3.8 Expression and Effect substrate

Sub-substrate that primitive slots can hold. **Expressions** carry logical content (predicates, named bindings, computed values); **Effects** carry materialised state-change declarations. Together they replace the "opaque text" treatment earlier drafts deferred to a future expression-parser pass.

The substrate is universal — used by Rule bodies, Intent assertions on any host, and the §6 constraint language. What differs across callsites is the binding **Environment**, not the Expression substrate.

#### 3.8.1 Expression

```
record Expression {
  label?:  String                  -- optional human-facing name for addressability and
                                   --   projection (parallels Clause's label? per K15a);
                                   --   not part of structural identity
  form:    ExpressionForm
}

ExpressionForm =
  | Var(name)                                             -- reference to an Environment binding
  | Ref(target: RefTarget)                                -- reference to a kernel target (per §3.3)
  | Lit(type: Type, value: Value)                         -- typed literal
  | Apply(op: String, args: List<Expression>)             -- operator / function application
  | Let(name, source: Expression, body: Expression)       -- local binding within an Expression
  | If(cond: Expression, then: Expression, else: Expression)
  | Match(scrutinee: Expression, arms: List<MatchArm>)
  | Forall(var, source: Expression, body: Expression)
  | Exists(var, source: Expression, body: Expression)
  | Aggregate(kind: AggKind, source: Expression, projection: Expression)

AggKind  = Count | Sum | Min | Max
MatchArm = { pattern: TypePattern, body: Expression }

TypePattern =                                             -- patterns mirror the six Type cases (§3.3)
  | Scalar(name: StringPattern)                           -- literal or bound variable
  | Enum(values: ListPattern)
  | Composite(shape: CompositeShapePattern)               -- Named(C) or Inline(?Fs)
  | Collection(of: TypePattern, semantics: SemanticsPattern)
  | Union(types: List<TypePattern>)
  | Ref(target: RefTargetPattern, where: TagConstraintPattern?)

-- Each position in a TypePattern is one of:
--   * a literal (matches that specific value),
--   * a bound variable `?V` (matches anything, binds the matched value to V), or
--   * a nested TypePattern (for the structural cases).
-- Surface syntax for TypePattern is the `matches` operator in §6.8.
```

Every Expression has a type per §3.3, determined by its `form`. Predicates are Expressions whose result type is `Scalar("Boolean")`. Compositional typing follows §3.3 plus standard operator signatures.

**Operators are extensible; canonicalisation patterns (§3.8.4) are not.** Kernel-committed core operators: arithmetic (`+`, `-`, `*`, `/`), comparison (`=`, `≠`, `<`, `≤`, `>`, `≥`), logical (`And`, `Or`, `Not`), set membership (`In`, `Contains`), presence (`IsPresent`, `IsAbsent`). Methodology operators register additively; the registration shape parallels §5.3 `PredicateRegistration` and concrete tokenisation is deferred until a methodology forces it (§13).

#### 3.8.2 Effect

```
Effect = {
  kind:    EffectKind                          -- Create | Write | Destroy | Emit
  target:  SubstrateAddress | PrimitiveRef     -- per §4.3 endpoint shape
  value:   Expression?                         -- absent for Destroy
  source:  ExprId                              -- identity of originating Expression
                                               --   in Rule.intent.assertions
}
```

Effects live only inside `Rule.body.effects` (per §3.1, Rule grows the `body` slot). Edge identity for the `writes` / `creates` / `destroys` / `emits` kernel edges sourced by an Effect is `(rule_id, kind, target)` — stable across semantically-equivalent rewrites of the originating Expression.

#### 3.8.3 Effect as materialised view of Expression

Every Effect's content is the canonicalised materialisation of an Expression of recognised shape (§3.8.4) in `Rule.intent.assertions`, evaluated against the Rule's two-state Environment. **No qualifying Expression in such an assertion list lacks a corresponding Effect; no Effect lacks a `source` Expression resolving back to one.**

Read with a relational lens: Effect is a **materialised view over Expression** — pre-computed by the analyzer pass in the role a denormalised projection plays in a relational store. The §3.8.4 patterns determine the view's content; the invariant above is the integrity rule that protects the view from drifting against its source.

The materialisation is committed at kernel level — rather than treating Effect as a query-time derivation in the manner of `chains` (§4.6) — because Effect is the source of the stored kernel edges `writes` / `creates` / `destroys` / `emits` (§4), and those edges are themselves first-class substrate consumed by graph queries (sidebar, drift detection, edge-filtered views). Pulling Effect behind a Datalog rule would push the materialisation one level deeper without removing it. The two faces serve distinct consumers — **Effect** for kernel edges and direct queries; **Expression** for predicates, derivations, and structural introspection — and storing both is the V0 commitment. Exposing Effect as a kernel-shipped derivation in a later revision is non-breaking: the substrate values are unchanged; only the production locus moves.

#### 3.8.4 Effect canonicalisation patterns

The analyzer materialises an Effect from a Bool-typed Expression iff the Expression matches one of these patterns in a two-state Environment:

| Pattern | Effect |
|---|---|
| `post.X.f = E` | `Effect(Write, X.f-as-SubstrateAddress, E, source)` |
| `post.X = T.created(field_bindings…)` where `pre.X` denotes no existing instance | `Effect(Create, X typed T, T-with-bindings, source)` |
| `not exists post.X` where `pre.X` denoted an existing instance | `Effect(Destroy, X, –, source)` |
| `emitted(E, args…)` | `Effect(Emit, E, args, source)` |

`E` in the Write pattern may reference `pre.X.f` freely — the canonical Allium write `account.balance = account.balance + 50` lifts to `post.account.balance = pre.account.balance + 50` and matches the pattern. Post-side self-reference (`post.X.f = post.X.f + 1`, circular by construction) is *not* guarded at substrate level: input-vocabulary analyzers are responsible for either canonicalising or rejecting such forms before producing substrate Expressions. Allium's grammar disallows `post.*` on the RHS of state-change assignments by definition (per [Allium's Postconditions section](#81-allium--kernel-mapping) — RHS always reads pre-rule values), so the case cannot arise from Allium-produced content.

Expressions that do **not** match any pattern remain as pure assertions (e.g., `post.order.total > 0` — a comparison, not an assignment; a guarantee, not a write).

The patterns are kernel-fixed (not methodology-extensible). New patterns earn kernel review and a §12 decisions-log entry. Matching is exact-shape; shape-ambiguous Expressions are structural errors the analyzer flags.

#### 3.8.5 Environment

The typed binding context that distinguishes callsites. Three flavours; the substrate Expression is uniform underneath.

```
Environment =
  | OneState(bindings: Bindings)
  | TwoState(pre: Bindings, post: Bindings, params: Bindings)
  | ModelIntrospection(bindings: Bindings)

Bindings = Map<String, Type>
```

- **OneState** — Intent assertions on Container, Behaviour, Boundary, Operation. Bindings reach the host's substrate at one moment (Container fields, Operation parameters, …). `self` is bound to the host primitive.
- **TwoState** — Rule.intent.assertions and Rule.body. Bindings reach `pre` and `post` simultaneously (`pre.X.f` and `post.X.f` are distinct values of the same identity X). `params` carries the Rule's trigger parameters from event-shaped `when:` clauses; `Rule.body.definitions` extend `params` with named local bindings visible to assertions and effects. This last extension is **the only cross-slot scope rule in the kernel** — a deliberate ergonomic non-minimality: `let` exists precisely to share named subexpressions across pre/postconditions and effects, and forcing each use site to re-derive the binding via nested `Let` (§3.8.1) would defeat the construct's purpose. Precedent in Operation.parameters being visible to Operation.intent; the exception is documented rather than disguised.
- **ModelIntrospection** — §6 predicate bodies. Bindings reach the §5.4 introspection vocabulary atemporally. Predicate head parameters appear as additional bindings.

Environment is part of the *callsite type*, not of the Expression itself. An Expression typed against a OneState environment cannot reference `post.X`; type-checking enforces this at parse time. An Expression authored against `Rule.intent.assertions` is implicitly in TwoState by virtue of its host.

#### 3.8.6 Mode

Directional evaluation, borrowed from logic programming. When an Expression appears in a §6 rule body, each variable and reference within it has a *mode* — input (bound at evaluation) or output (to be solved). Forward evaluation (compute output from input) is the standard mode; backward evaluation (compute input that produces the output) is admissible under §6's stratification rules.

**Mode is callsite-typed, not substrate-stored.** Like Environment (§3.8.5), mode lives in the §6 rule structure that *uses* an Expression, not in the Expression record itself — the substrate Expression (§3.8.1: `{ label?, form }`) has no mode slot. A given Expression can be used at one callsite in forward mode (all variables input-bound) and at another in backward mode (some variables output-solved); these are properties of how the Expression participates in §6's rule body, not properties of the Expression value. Structural identity (§3.8.7) is therefore mode-free: two Expressions with the same shape are the same Expression regardless of how either callsite uses them. The Effect's kernel edge in the graph remains directed `Rule → Target` regardless of mode.

#### 3.8.7 Identity

| Element | Identity |
|---|---|
| Expression | Structural — two Expressions with the same `form` (shape and free-variable references) are the same Expression. Within a host, addressable via `ExprId` (position-and-shape within the host's slot). **`label?` is not part of structural identity** — same shape with different labels is one Expression; the label is a human-facing addressability hook for tooling and projection (parallels K15a's `label?` on Clause). |
| Effect | `(rule_id, kind, target)`. |
| Definition (per §3.2) | `(Rule.id, Definition.name)`. |
| Source-to-Effect linkage | `Effect.source: ExprId` resolves to an Expression in `Rule.intent.assertions`. |

`ExprId` is kernel-internal — `Effect.source` is what uses it. Predicates do not address Expressions by `ExprId`; they query Expressions structurally (matching on shape, tags, or position in a host's list per §6).

#### 3.8.8 Cross-cutting commitments

- **One calculus, three callsites.** Rule bodies, top-level Intent assertions on any host, and §6 constraint predicates all use Expression; Environments distinguish them.
- **§6's constraint language uses Expression as its sublanguage.** §6 wraps Expressions in Datalog rule heads + body literals; the underlying typed value/predicate calculus is identical (§6 cross-references this section).
- **No imperative machinery at substrate level.** Expression has no sequencing form, no I/O, no mutable references. Effects declare state changes; Expressions compute values referentially-transparently. Runtime semantics are a projection concern.

---

## 4. Kernel relations

Thirteen relations. Each grounded by a specific substrate push and a uniform cross-methodology semantic.

### 4.1 The relation set

```
triggers   : Event | Operation              →  Rule
observes   : Rule                           →  Container | Field
reads      : Rule                           →  Container | Field
writes     : Rule                           →  Container | Field
creates    : Rule                           →  Container
destroys   : Rule                           →  Container
emits      : Rule                           →  Event
realises   : Container | Operation          →  Container | Operation
specialises: Container                       →  Container
uses       : Container                       →  Container
exposes    : Container                       →  Field
provides   : Container                       →  Event
projects   : Container | Operation | Rule | Event | Clause | Expression   →  Artifact
```

### 4.2 Per-relation semantics

**`triggers` — occurrence-based causation.** A discrete occurrence at the source causes the target Rule to fire. Multi-subscriber capable (one Event firing triggers many Rules). Source `Operation` covers operation-invocation triggers (the Operation being invoked is the occurrence point). Identifying metadata: none — source endpoint discriminates.

**`observes` — state-based causation.** The Rule has standing interest in a condition on the target. Per-Rule semantics (no shared firing point). Identifying metadata: `condition` (typed `Expression: Bool` per §3.8, evaluated against a OneState environment binding the observed Container/Field — e.g., `T.created`, `T.status transitions_to shipped`, `T.expires_at <= now`). The observation-mode discriminator (creation / transition / becomes / temporal / derived) is *derivable* from the condition pattern and not stored on the edge.

**`reads` — data dependency.** The Rule looks at the target's state while computing its effects. Not a trigger, not an effect — a dependency. Specifically retained as a kernel relation because spec-to-code drift detection needs `reads` as a contract surface (an implementing function reading beyond what its Rule's spec declares is a drift violation). Identifying metadata: `condition` (typed `Expression: Bool` per §3.8, derived from the Expression in `Rule.intent.assertions` that referenced this target).

**`writes` / `creates` / `destroys` / `emits` — effects.** Rule outputs. Each such edge is **sourced by an Effect record** in `Rule.body.effects` (per §3.8.2); the edge's identity `(rule_id, kind, target)` is the Effect's identity, stable across semantically-equivalent rewrites of the originating Expression in `Rule.intent.assertions`.

| Relation | Endpoints | Semantic |
|---|---|---|
| `writes` | `Rule` → `Container \| Field` | State-modifying on existing identity |
| `creates` | `Rule` → `Container` | Identity-introducing |
| `destroys` | `Rule` → `Container` | Identity-terminating |
| `emits` | `Rule` → `Event` | Event production |

Each carries different downstream meaning for architectural rules: *"No Rule in `Hex::Core` may `create` instances of `Hex::Adapter`"* is a distinct rule from *"No Rule in `Hex::Core` may `write` to `Hex::Adapter` state."* Collapsing loses the identity-vs-state distinction. `emits` is Rule-only — a Boundary that "advertises" an Event (Allium `provides`) is a different relationship (methodology framing, lifted as a tag with back-references). Identifying metadata: `condition`, `scope` — values derived from the Effect's `source` Expression per §3.8.

`creates` and `destroys` target Container only, not Field. Lifecycle of a Field independent of its Container is not a kernel concept. Field-value creation / destruction is `writes` (writing a value, including null). The asymmetry in the relation table is intentional.

**`realises` — same-altitude structural realisation.** X is the concrete part that completes Y's abstract specification. Both endpoints live at the same altitude (both spec, both code). Hex Adapter→Port is the motivating case. Container-level realisation derives Operation-level matching by signature; explicit per-Operation realisation as an alternative. Identifying metadata: none — endpoints discriminate.

**`specialises` — same-altitude structural extension.** X extends Y's structure: X inherits Y's fields and may add fields or narrow field semantics. Same-altitude — both endpoints at Structure altitude. Edge direction is child → parent (the specialising side is the source). Motivating case: Allium variant→parent (`variant T from P` produces `specialises: T → P`). Distinct from `realises` by intent: `specialises` is extension (child enriches parent's structure); `realises` is fulfilment (concrete completes abstract specification). Container-only endpoints in v0; Operation/Event specialisation deferred until forced. Cross-methodology pressure (DDD value-object hierarchies, OO inheritance, sum-type constructors) joins additively on the same edge shape. Identifying metadata: none — endpoints discriminate.

**`uses` — same-altitude declared structural dependency.** X declares a dependency on Y's interface: X's behaviour or operations are intended to invoke operations on Y's Boundary. Same-altitude — both endpoints at Structure altitude. Edge direction is user → used (the dependent side is the source). Motivating cases: Allium `contracts: demands` on a Surface (Surface → Contract); DDD Customer-Supplier downstream → upstream; Hex Application → Driven Port; C4 "depends on" arrows. Distinct from the derived `depends_on` (§4.6): `uses` is **declared at spec altitude**; `depends_on` is **observed** from Rule effects, type references, and `realises` edges. Both feed `depends_on` in §6.6's universal derivations. Container-only endpoints in v0; Operation-level usage deferred until forced. Identifying metadata: none — endpoints discriminate. Methodology-specific shading (Customer-Supplier upstream-vs-downstream, ACL, Conformist, Published Language) layers as edge tags or relational tags on the underlying `uses` edge.

**`exposes` — boundary advertisement of read-access.** X advertises Field F (on some Container) as readable at X's boundary. The View protocol's kernel form. Same-altitude — Structure. Edge direction is advertiser → advertised (Surface → Field). Motivating cases: Allium `exposes:` (Surface → Field); REST GET endpoints exposing resource fields; GraphQL query fields; DDD read models exposing state; Hex Driving Port state-query operations. The endpoint on the Field side is a `SubstrateAddress` (§4.3) — `exposes: Container → Field` reaches a specific Field on a (typically different) Container. Identifying metadata: none — endpoints discriminate. Methodology-specific shading layers as edge tags (`Allium::Exposes` carrying optional access-context metadata).

**`provides` — boundary advertisement of event-entry.** X advertises Event E as an entry point at X's boundary: external parties can inject E by interacting with X. The Signal protocol's kernel form. Same-altitude — Structure. Edge direction is advertiser → advertised (Surface → Event). Motivating cases: Allium `provides:` (Surface → Event); Hex Driving Port accepting commands; REST POST endpoints accepting events; Pub/Sub publish points; CQRS command handlers. Distinct from `triggers` (Event → Rule) — `provides` is the boundary-level declaration that the Event has an external entry point; `triggers` is what fires inside the system when the Event arrives. A typical Signal-protocol flow: `provides: Surface → Event`, then `triggers: Event → Rule`. Identifying metadata: none — endpoints discriminate. Methodology-specific shading layers as edge tags (`Allium::Provides` carrying e.g. parameter binding metadata).

**`projects` — spec-to-realisation projection.** A spec primitive *projects* to a realisation artifact: a chosen materialisation that *loses information* from the spec source. Spec is the source; code / infra / tests / docs are realisations of it. Per K23, implementation is not a fourth altitude — Artifacts have *flavours* (Code | Infra | Documentation per §7), not altitudes. Drift is a projection that has ceased to be valid. Identifying metadata: `projection_kind` (closed enum from projection vocab; see §7). Non-identifying metadata (kernel-committed): `validity ∈ 'valid' | 'stale' | 'absent' | 'unknown'`, `evidence: List<EvidenceClue>`, `verified_at: Timestamp?`, `source_version: VersionRef?`. **`validity` is a memoised derivation** — see §7.6 — over the `(spec_primitive, expected_address, code_at_address)` tuple, refreshed on every model rebuild. It is not authoritative state, only a cache of the comparator's result, kept on the edge for query convenience; the comparator function is the source of truth.

### 4.3 Endpoint addressing — primitive references and substrate addresses

Endpoints of a kernel relation are not always primitive ids. They form a sum:

```
RelationEndpoint =
  | PrimitiveRef(id: PrimitiveId)                       -- Container, Rule, Event, Operation, …
  | SubstrateAddress(container_id, path: List<PathSegment>)

PathSegment = {
  slot: String        -- substrate slot name: 'field' | 'operation' | 'parameter'
                      --                    | 'behaviour' | 'boundary' | 'intent' | …
  key:  String?       -- identifier within slot (omitted when slot is singular on its host)
}
```

`Field` is the immediate driver — it's a value record on `Container.fields`, not a primitive, but `reads`, `writes`, `observes`, and `exposes` (R20) all need to point at specific Fields. V0 populates only the single-segment-Field case; the general form is committed once so the kernel doesn't change shape when Parameter earns first-class addressability. (Clause is a primitive — reached as `PrimitiveRef`, not via `SubstrateAddress` — per §3.1 / §3.2.)

Concrete addresses:

| Sub-substrate target | Path |
|---|---|
| Field `Order.priority` | `[{slot: 'field', key: 'priority'}]` |
| Parameter `place_order(order)` on Order's boundary | `[{slot: 'boundary'}, {slot: 'operation', key: 'place_order'}, {slot: 'parameter', key: 'order'}]` |

Typed segments rather than flat path strings: unambiguous (no parsing required), validatable against the kernel substrate schema, tool-friendly. The verbosity cost for the trivial Field case is mild.

### 4.4 Edge identity and metadata

**Edges are values.** Two edges with the same identity are the same edge — independent of when, by whom, or with what descriptive metadata they were observed.

```
edge_identity = (from, to, kind, identifying_metadata)
```

`identifying_metadata` is the *identifying subset* of an edge's metadata bag, kernel-committed per relation. Non-identifying metadata (timestamps, comparator evidence, rendering hints) mutates without changing edge identity.

**Multi-edges allowed iff identifying metadata differs.** Two `writes` from the same Rule to the same Field with different `condition` content are two distinct edges; with the same (or no) `condition` content they collapse to one. **Methodologies drive granularity** by what distinguishing metadata they supply — a barebones methodology that doesn't track conditions gets coarse single-edges automatically; a rich-condition methodology gets fine edges.

**Identifying-metadata slot names (kernel-committed):**

| Slot | Meaning | Kernel-committed source |
|---|---|---|
| `condition` | Predicate expression that gates / specifies this edge instance | Kernel |
| `scope` | Quantification / iteration context | Kernel |
| `projection_kind` | Role discriminator on `projects` edges | Projection vocab (§7) |

Per-relation applicability:

| Relation | Identifying metadata |
|---|---|
| `triggers` | — |
| `observes` | `condition` |
| `reads` | `condition` |
| `writes` | `condition`, `scope` |
| `creates` | `condition`, `scope` |
| `destroys` | `condition`, `scope` |
| `emits` | `condition`, `scope` |
| `realises` | — |
| `specialises` | — |
| `uses` | — |
| `exposes` | — |
| `provides` | — |
| `projects` | `projection_kind` |

**Slot content is typed `Expression`** (per §3.8). Identity is structural equality on Expression structure — two Expressions with the same shape, free variables, and operator at each node are the same identifying value. Semantic equivalence beyond structural identity (`"status = pending"` vs `"pending = status"`, modulo commutativity) is not a kernel concern; methodologies that want canonicalisation register a normalisation pass against the §3.8 substrate. For `writes` / `creates` / `destroys` / `emits` edges, identifying-metadata values are derived from the sourcing Effect's `source` Expression (per §3.8.2); for `observes` / `reads`, they come from the analyzer's parsed condition Expression directly.

### 4.5 Direction; no inverse-pair vocabulary

Relations have direction. Inverse queries ("what Events trigger this Rule" vs "what Rules does this Event trigger") work by traversing the same edge from the other endpoint — not by storing two relations. The kernel never names two relations as inverse pairs (no `triggered_by`, no `realised_by`). Methodologies that want a directional-inverse vocabulary (Hex's `offers`/`requires`, Allium's `facing`/`backed_by`) carry it in vocabulary.

### 4.6 Derived relations are first-class queries

`chains` (Rule emits Event → other Rule triggered by Event) is the canonical derived relation. It is **not** a kernel relation. It is a documented derivation pattern:

```
chains(R1, R2)  :=  emits(R1, E) ∧ triggers(E, R2)
```

The kernel commits to making derived relations **expressible** and **queryable** through the constraint language (§6), not to storing them as edges. Storing the derivation would risk drift. Tools rendering "Rule R1 chains to Rule R2" compute the derivation at query time.

The same machinery supports cross-Container dependency derivation. Any of `reads`/`writes`/`emits`/`triggers`/`realises`/`projects`/Type references between Rules/Operations in distinct Containers yields a Container-level dependency — derived, not stored. Methodology-specific framings (DDD `manages`, `upstream_of`, `anti_corruption_layer`; C4 technology-tagged dependencies) layer as vocabulary tags on the underlying derivation.

---

## 5. The vocabulary mechanism

The vocabulary mechanism is the seam by which **Vocabulary extensions** layer methodology-specific content onto the kernel substrate. **Overlay-only, no new primitives.** A Vocabulary contributes tags, structured content, predicates, and renderers — no new node types and no escape hatch.

### 5.0 Vocabulary as extension type

"Vocabulary" names one **extension type** with three hooks that travel together: a Vocabulary content contribution (this section), an optional **spec parser** for a file format that authors content tagged with this Vocabulary (§8 covers Allium and Boundary parsers), and an optional **renderer** registration (§5.5). The hooks are bundled per Vocabulary because they share identity — Allium-as-Vocabulary names the tags, the parser, and the renderer for the same methodology lens; the same is true of Boundary-as-Vocabulary. The other extension type fukan recognises is the **Target language extension** (§7.7), which is structurally distinct — it produces and consumes projection content, not Vocabulary content. The two extension types and their registration are catalogued in [§10.2](#102-extension-registry-mechanics).

### 5.1 Contribution kinds

Three contribution kinds. Cross-methodology pressure-testing accumulated five candidates and force-and-gate collapsed two:

| Kind | Outcome | Reason |
|---|---|---|
| Tags | Kept | Methodology classification of kernel primitives is universally shaped. |
| Structured content per tag | Folded into Tagged content attachment | A tag application is `(identity, payload)`; pure-label tags are the empty-payload case. One mechanism, not two. |
| Methodology-typed relations | Dissolved | Every concrete case decomposes into (i) vocabulary content with back-references to kernel primitives or (ii) methodology metadata on a stored kernel edge. No uniform "new edge kind" shape survives. |
| Validation rules | Kept (as registration seam) | Predicate expression language handed off to constraint-language section. |
| Rendering contract | Kept (seam) | Implementation deferred until explorer rebuilds. |

Two consumers — predicates and renderers — read from the surface that tagged content exposes. The mechanism's leverage comes from this: **one introspection surface, two consumers.**

### 5.2 Tagged content attachment

The spine of the mechanism.

```
TagDefinition {
    namespace:       String                       -- "Allium" | future "Boundary" | …
    name:            String                       -- "Surface" | "Provides" | "Entity" | …
    applies_to:      TagTarget                    -- where this tag can be applied
    payload_schema:  Type?                        -- methodology's content shape (kernel-visible)
    parent_tag:      TagRef?                      -- intra-namespace inheritance (optional)
    relational:      RelationalSpec?              -- when the tag declares a relationship
}

RelationalSpec {
    endpoints:        List<PayloadFieldName>      -- payload fields carrying the endpoint refs
    symmetry:         directed | symmetric
    canonical_side:   PayloadFieldName?           -- which endpoint stores the application
                                                  --   (required for directed; symmetric uses
                                                  --    lexicographic order on endpoint ids)
    coherence_query:  Predicate?                  -- kernel-query expressing what observation
                                                  --   would confirm this tag's intent
}

TagApplication {
    tag:      TagRef                              -- which TagDefinition
    target:   KernelTarget                        -- primitive / stored edge / sub-substrate
    payload:  Payload                             -- conforming to tag.payload_schema
}
```

- **`namespace`** scopes the tag identity. `Allium::Surface` and a hypothetical `DDD::Surface` are distinct tag identities even if labels collide.
- **`applies_to`** specifies what kind of target the tag can attach to — kernel primitive, kernel stored-edge kind (e.g. `Edge<emits>`), or sub-substrate via the address machinery in §4.3 (Field, Parameter, …).
- **`payload_schema`** is the methodology's declared content shape — **kernel-visible and introspectable via the Type vocabulary**. Absent for pure-label tags.
- **`parent_tag`** supports intra-namespace inheritance only. Cross-namespace parents are forbidden.

**Multi-tagging is structural, not exceptional.** The same Container may carry several tag applications from one or several namespaces — three independent tag applications, none with privileged status. **Methodology coexistence follows from this.** A project mixing methodologies sees the same kernel primitives carrying multiple namespaced tags simultaneously: a Surface Container can be `Allium::Surface` ∧ `Hex::DrivingPort` ∧ `DDD::AntiCorruptionLayer` at once, with each namespace's predicates and renderers operating against its own tag applications. No privileged methodology; no merging step needed beyond what the kernel already provides via the multi-tagging substrate.

#### Tag inheritance — v0 semantics

V0 commits a minimal inheritance shape:

| Question | v0 answer | Rationale |
|---|---|---|
| Payload-schema extension | ✅ Child inherits parent's payload schema and may add fields | The Allium-end-to-end path uses no inheritance yet, but the C4 hierarchy pressure-test motivates extension; uniform across methodologies that engage inheritance. |
| Tag-presence implication | ✅ Carrying child tag implies carrying parent tag (`has_tag` is reflexive over the parent chain) | Without it, every predicate has to enumerate the hierarchy by hand. |
| Field-override of parent slot types | ❌ Forbidden | No forcing case from worked examples. |
| Multi-parent | ❌ Forbidden | No forcing case from worked examples. |

Re-opening triggers: a concrete methodology needs narrowing (field-override) or diamond resolution (multi-parent).

#### Relational tags and coherence queries

Tag applications carrying kernel back-references in their payload often *are* relationships in disguise. A relational tag declares the relationship shape: which payload fields are endpoints, the symmetry (directed / symmetric), and the canonical storage side. The mechanism then provides:

- **Storage canonicalisation.** The methodology declares which endpoint stores the application; the mechanism enforces it. Symmetric relationships canonicalise by lexicographic order on endpoint ids.
- **Pair-indexed lookups.** Predicates address the relationship as `Tag(endpoint_a=X, endpoint_b=Y)`, jumping to the application directly rather than scanning either primitive's tag list.
- **Coherence checking.** See below.

Multi-aspect on the same pair (multiple relational tags sharing endpoints) is independent applications coexisting by design.

**Coherence queries — declaration vs observation.** A relational tag carries methodology *intent* — "I declare these two BCs stand in a Customer-Supplier relationship." Whether the model *observes* a corresponding pattern (a derived dependency edge between them) is a separate question. The mechanism keeps the two separate but linked: the tag's `coherence_query` is a kernel-query template, parameterised by the tag's endpoints, that expresses what observation would confirm the declaration's intent. Per-application evaluation answers "does this declaration's claim hold now?"

For pure-classification tags (`DDD::AggregateRoot`, `DDD::Subdomain`) coherence queries are typically absent — the declaration is a label, not a claim about kernel-observable structure that can drift. For methodology-supplied tags whose intent is a structural assertion, coherence queries surface drift automatically without separately authored predicates.

Default semantics for coherence-query failures: `kind: drift, severity: warning` (see §5.3). Methodologies may override severity at registration; `kind: drift` is intrinsic.

#### Back-references — the dissolution of "methodology-typed relations"

When a methodology wants to express a relationship the kernel doesn't carry as a relation kind, it lifts the relationship into payload content with kernel back-references. Concrete v0 cases:

**Allium `provides:` (Surface → Event).** `emits` is Rule-only at the kernel level; Boundary→Event is methodology framing. Represented as:

```
TagDefinition {
    namespace: "Allium", name: "Provides",
    applies_to: Container (Boundary-bearing),
    payload_schema: Composite(Inline([
        FieldSpec("events",
            Collection(of: Ref(KernelPrimitive({Event})),
                       semantics: Sequential))
    ]))
}
```

A predicate asking "what Boundaries advertise event E?" traverses this payload's `events` back-references — no new kernel relation needed.

**Allium `exposes:`** — back-reference from Boundary-bearing Container to a Field on another Container:

```
TagDefinition {
    namespace: "Allium", name: "Exposes",
    applies_to: Container (Boundary-bearing),
    payload_schema: Composite(Inline([
        FieldSpec("exposed_field",
            Ref(Substrate(within_kind=Container, slot_kinds={Field})))
    ]))
}
```

**Allium `facing:` / `contracts:`** — back-references to Actor / Contract Containers respectively, with optional `where: TagConstraint`.

In every case the *relationship* survives — but it lives as either payload content with back-references on the source primitive or methodology metadata on a stored kernel edge. No new edge kinds enter the kernel.

#### Tags on stored kernel edges

Stored kernel edges (the thirteen relations) accept tag applications as non-identifying metadata. Useful for `Allium::Trigger { kind: external_stimulus | chained }` on a `triggers` edge, `Boundary::Binding` on operation-invocation triggers from `.boundary`, `Allium::Provides` / `Allium::Exposes` carrying optional methodology-specific shading on `provides` / `exposes` edges, or methodology metadata on a `realises` edge.

**Derived edges do not carry tags** — they are query results, not addressable objects. Methodology-typed relations involving derivations lift to relational tags or back-reference payloads on the source primitive.

Namespace-sharing across attachment targets: edge-tags share namespaces with primitive-tags. Disambiguation is by `applies_to`, not by namespace.

### 5.3 Predicate registrations

The mechanism's commitment to constraints: **everything a methodology lifts onto the model is queryable.** Predicates are the registration shape for methodology-supplied validation; the expression language for predicates is the constraint language (§6).

```
PredicateRegistration {
    namespace:        String                       -- "Allium" | project-specific | …
    name:             String                       -- "VR30" | "ProvidesCompleteness" | …
    severity:         error | warning              -- block vs annotate
    kind:             Kind                         -- "integrity" | "drift" | open identifier
    scope:            ModelScope | TagScope        -- whole-model vs per-tagged-target
    message_template: String                       -- violation rendering, with substitution slots
    predicate:        Predicate                    -- expression in the constraint language (§6)
    applies_to:       TagRef?                      -- when scope = TagScope
}
```

**Severity and kind are orthogonal axes.** Severity is the workbench action (block vs annotate). Kind is the violation category, driving reporter rendering — never the engine. Kernel-shipped well-known kinds: `integrity` (default for general validation), `drift` (default for coherence queries; intrinsic). Methodologies may register custom kinds. Severity is the constraint author's choice at registration; kind is intrinsic to the constraint.

**Scope** is whole-model (one evaluation across the graph) vs per-tagged-target (evaluated once per Container/edge carrying `applies_to`).

**`message_template`** is the violation-rendering hook for the explorer — predicate bindings substitute into the template. The language has no separate "tag-this-for-reporting" mechanism; every bound variable is reportable.

#### Project-side registration — directional, not load-bearing for MVP

The MVP commits the *single constraint language* (§6) and the *registration shape* above. The *path by which a project ships its own constraints* (file format, location, activation, composition with methodology-shipped constraints) is **directional**: such a path will exist; it is not specified in v0. Re-opening triggers for the composition mechanism (project-side severity overrides, multiple profiles, constraint bundles, transitive imports, versioning) are recorded in §10.

### 5.4 The introspection surface

Predicates and renderers consume one shared surface:

| Surface | Examples |
|---|---|
| Kernel substrate | `Container.children`, `Container.fields`, `Container.events`, `Operation.parameters`, … |
| Kernel relations | `triggers`, `observes`, `reads`, `writes`, `creates`, `destroys`, `emits`, `realises`, `specialises`, `uses`, `exposes`, `provides`, `projects` |
| Tag presence | "does target T carry tag X?", "all targets carrying tag X" |
| Tag payload | typed payload navigation per declared schema |
| Derivations | Container-level dependencies, transitive closure, `chains` (per §4.6) — first-class for queries, not stored |
| Logical composition | conjunction, disjunction, **negation**, existential and universal quantification |

Negation is load-bearing. Constraints like "every `provides:` event has at least one Rule consuming it via `when:`" require asserting *absence* of a contradicting structure, not just presence of a matching one.

### 5.5 Renderers

Display contract per tag. Each methodology supplies visual treatment for its tagged primitives and edges; the explorer dispatches per tag application.

```
RendererRegistration {
    tag:        TagRef
    treatments: Map<String, Value>   -- consumer-key → opaque payload
}
```

The `treatments` map is keyed by **consumer key** (a vocabulary-defined String identifying the rendering consumer — `"node"`, `"sidebar"`, `"edge"`, `"layout"`, or any future consumer). The payload `Value` is open at the per-consumer axis: vocabularies declare whatever treatment keys their consumers understand (`"icon"`, `"shape"`, `"colour"`, `"badge"`, etc.). The kernel takes no position on the payload schema; consumer-axis enumeration is also open so new consumers (agent surfaces, doc renderers) don't require kernel changes.

Renderers consume the same introspection surface predicates consume. The difference is purpose, not access: predicates produce a boolean; renderers produce a visual treatment.

**Scope:** seam committed; concrete shape arrives with the explorer rebuild. Renderers must be plugin-supplied (when plugin mechanics arrive), not explorer-hardcoded, so methodologies remain plug-and-play. The projection layer walks all `RendererRegistration`s matching a primitive's tag applications and merges their `treatments[<consumer>]` payloads into a single per-consumer treatment map for the projected node; collisions on a treatment key are surfaced for project-layer precedence resolution.

**Kernel-relation rendering.** Each of the thirteen kernel relations gets a *default* explorer rendering owned by fukan (line style, colour family, arrowhead, label) so a kernel-only Model is fully navigable without any methodology renderer attached. Methodology edge tags (e.g., `Allium::Provides` on a `provides` edge, `DDD::CustomerSupplier` on a `uses` edge) layer additional visual treatment via the same `RendererRegistration` mechanism — the tag's renderer decorates the kernel default, never replaces it.

### 5.6 Cross-cutting commitments

**The methodology-author obligation.** For a constraint to be checkable, the content it references must be lifted onto the model via tagged content. The kernel commits *everything liftable is checkable*; lifting is the methodology author's job. **The bargain is graded by typedness.** A tag with typed payload (`Enum`, `Ref` with tag constraints, `List` of structured refs) exposes a queryable surface predicates can do real work over. A tag with opaque `description: String` payload has technically lifted content, but predicates can do little beyond presence checks. The introspection surface's value scales with how committed methodology authors are to structuring their content.

**Spec altitude expresses effects, not invocation.** Rules at spec altitude describe state effects (`writes` / `creates` / `destroys`) and event emissions (`emits`), not control flow. The kernel `triggers: Operation → Rule` runs in the *handler* direction (Operation invocation triggers Rule firing); there is no `invokes: Rule → Operation` — Rules don't directly invoke Operations at spec altitude. Constraints about control flow ("Core's implementation must not directly invoke Adapter functions") live in the **implementation projection layer** — expressed over `projects` edges plus code-side call-graph derivations when those arrive — not at a "fourth altitude." Per K23, implementation is projection across the three spec altitudes, not an altitude of its own.

**One-way dependency reaffirmed.** Vocabulary references kernel; kernel knows nothing of vocabulary. Predicates and renderers reference kernel substrate and relations; the kernel never references vocabulary. A kernel-only Model remains valid; zero vocabularies attached = a pure structural graph.

**TagDefinitions auto-derive Datalog predicates** (per V15). Each loaded `TagDefinition` extends the §6.6 kernel-universal derivations with per-tag predicates: `<ns>_<name>(target)` (presence), `<ns>_<name>_<field>(target, value)` (per payload field), `<ns>_<name>(endpoint_a, endpoint_b)` (relational tags). These are macros over `has_tag` / `tag_payload`; they accelerate methodology-specific predicate authoring without compromising cross-vocab query uniformity. The universal tag-introspection vocabulary remains the load-bearing layer for any query that doesn't already know which vocabulary it targets.

---

## 6. The constraint language

The single expression language methodology constraints and coherence queries are authored in. Same language regardless of who ships the constraint (methodology vocabulary, project, future plugin).

**Relationship to §3.8.** §6 wraps the §3.8 `Expression` substrate in Datalog rule heads and body literals. The underlying typed value/predicate calculus is identical to what Rule bodies and Intent assertions use; what differs is the surface form (Datalog rule structure for constraints; `Rule.intent.assertions` / `Rule.body.effects` for Rule bodies) and the binding environment (ModelIntrospection for §6 per §3.8.5; OneState / TwoState for Intent assertions and Rule bodies). Sort guards, path sugar, and type-sum sugar layer over the same Expression substrate.

### 6.1 Three layers

```
1. Substrate    — stratified Datalog with negation and aggregation
2. Path sugar   — dot-access, set-access, relation arrows, tag-payload access
3. Type sugar   — one matches operator with patterns mirroring §3.3's Type cases
```

Both sugar layers desugar mechanically to the substrate. The substrate alone is sufficient; the sugars are ergonomics. Methodology authors write at whatever altitude reads cleanest.

### 6.2 Substrate: stratified Datalog with negation and aggregation

**Negation — stratified, closed-world.** The model has a known extent. ExternalEntity stubs are *in* the model (per §3.6); CWA over them sees their small substrate. Externality is a tag, not a semantic mode.

Open-world concerns encode as predicates, not language semantics. Methodology authors who want to ignore unloaded scope opt into a kernel-shipped scope predicate:

```prolog
% Default — closed-world, treats stubs like any other Container:
violation(C, A) :-
    has_tag(C, "Hex::Core"), depends_on(C, A),
    has_tag(A, "Hex::Adapter").

% Scope-aware variant — methodology opts in:
violation_strict(C, A) :-
    has_tag(C, "Hex::Core"), depends_on(C, A),
    is_local(A),
    has_tag(A, "Hex::Adapter").
```

**Stratified specifically.** Forbids non-stratifiable recursion-through-negation (`p(X) :- not q(X). q(X) :- not p(X).`). Every program terminates with a unique well-defined model. Bottom-up materialisation works.

**Closed in this scope:** three-valued logic for open-world; non-stratifiable recursion-through-negation. Revisit only if real cases demand them.

**Aggregation.** Committed as part of the language; concrete operator set TBD. Starting cut: `count`, `min`, `max`, `sum`. Stratified aggregation has the same semantic story as stratified negation — aggregates evaluate in lower strata than their consumers.

### 6.3 Sort system

The language uses the §3.3 Type vocabulary one altitude up. One sort per:

| Sort family | Members |
|---|---|
| Kernel primitive kinds | `Container`, `Actor`, `Behaviour`, `Boundary`, `Rule`, `Operation`, `Event`, `Intent`, `Clause` |
| Sub-substrate kinds (per §4.3, addressable via SubstrateAddress) | `Field`, `Parameter` |
| Substrate sub-content (per §3.8 / §3.2 — quantified structurally within their host's slot, not §4.3-addressable) | `Expression`, `Effect`, `Definition` |
| Stored kernel edge kinds | `Edge<triggers>`, `Edge<observes>`, `Edge<reads>`, `Edge<writes>`, `Edge<creates>`, `Edge<destroys>`, `Edge<emits>`, `Edge<realises>`, `Edge<specialises>`, `Edge<uses>`, `Edge<exposes>`, `Edge<provides>`, `Edge<projects>` |
| Tag-application sorts (parameterised) | `TagApp<Allium::Surface>`, `TagApp<Allium::Provides>`, … |
| Payload values | All six Type cases (`Scalar`, `Enum`, `Composite`, `Collection`, `Union`, `Ref`) |

Sort guards are unary predicates: `is_container(X)`, `is_rule(X)`, `is_edge_emits(E)`, `is_expression(X)`, `is_effect(X)`, `is_definition(X)`. They constrain quantifier scope and make rules readable.

### 6.4 Quantification

Free variables in rule bodies are existential; universal-over-condition is "no counterexample." Surface sugar wraps this:

```
∀ C:Container . has_tag(C, "Hex::Core") ⇒
    ¬∃ A:Container . depends_on(C, A) ∧ has_tag(A, "Hex::Adapter")
```

desugars mechanically to:

```prolog
violation(C, A) :-
    is_container(C), has_tag(C, "Hex::Core"),
    is_container(A), depends_on(C, A), has_tag(A, "Hex::Adapter").
```

Nested alternation deeper than ∀∃ stratifies into named intermediate predicates — a feature, not a bug.

### 6.5 Built-ins committed

| Built-in | Shape |
|---|---|
| `=`, `≠` | Identity comparison |
| `in` | Membership over `List<T>` payloads |
| `member(List, Element)` | Datalog-style equivalent |
| `is_local(X)` | True iff `X` is not an external/stub primitive |
| `has_tag(X, T)` | Tag presence — reflexive over the parent-tag chain (§5.2 inheritance) |
| `tag_payload(X, T, P)` | Binds `P` to the typed payload value |
| `source(E, X)`, `target(E, Y)`, `has_tag_on_edge(E, T)` | Edge addressing |
| Substrate navigation predicates | One per substrate slot — `behaviour_of`, `boundary_of`, `rule_in`, `field_in`, … |

### 6.6 Kernel-universal derivations

The catalogue fukan ships with the substrate. Universal — depend only on kernel substrate (§3) and the closed relation set (§4), with no methodology knowledge. Every predicate gets them for free.

```prolog
% Transitive closure of a kernel relation — endogenous cases (source sort overlaps target sort)
realises_star(X, Y)    :- realises(X, Y).
realises_star(X, Z)    :- realises(X, Y), realises_star(Y, Z).
% Same shape instantiated for `specialises_star` and `uses_star` — the other two endogenous
% kernel relations (all three are Container→Container).
%
% The remaining ten kernel relations (`emits`, `triggers`, `observes`, `reads`, `writes`,
% `creates`, `destroys`, `exposes`, `provides`, `projects`) have target sorts disjoint from
% their source sorts (e.g., `emits`: Rule→Event — no Event is a Rule), so an analogously
% written `*_star` has a recursive clause that never fires; `*_star ≡ *` in those cases.
% Fukan ships the `*_star` instantiation for all thirteen relations for query uniformity
% (authors can write `<rel>_star` without first checking endogeneity), but multi-hop
% reasoning across heterogeneous relation kinds is authored explicitly when a forcing case
% exists (per V15) — see `chains_star` below for the canonical case (Rule → Event → Rule,
% via emits + triggers).

% Inter-Rule chaining
chains(R1, R2)         :- emits(R1, E), triggers(E, R2).
chains_star(R1, R2)    :- chains(R1, R2).
chains_star(R1, R3)    :- chains(R1, R2), chains_star(R2, R3).

% Container-level dependency (union of fine-grained dependencies)
depends_on(A, B) :- rule_in(R, A), reads(R, X),     container_of(X, B), A ≠ B.
depends_on(A, B) :- rule_in(R, A), writes(R, X),    container_of(X, B), A ≠ B.
depends_on(A, B) :- rule_in(R, A), observes(R, X),  container_of(X, B), A ≠ B.
depends_on(A, B) :- rule_in(R, A), creates(R, C),   container_of(C, B), A ≠ B.
depends_on(A, B) :- rule_in(R, A), destroys(R, C),  container_of(C, B), A ≠ B.
depends_on(A, B) :- rule_in(R, A), emits(R, E),     container_of(E, B), A ≠ B.
depends_on(A, B) :- rule_in(R, A), triggers(E, R),  container_of(E, B), A ≠ B.
depends_on(A, B) :- op_in(O, A), realises(O, O'),   container_of(O', B), A ≠ B.
depends_on(A, B) :- realises(A, B), is_container(A), is_container(B), A ≠ B.   % Container-level (e.g. Surface→Contract)
depends_on(A, B) :- specialises(A, B), A ≠ B.                                  % variant T extends parent P
depends_on(A, B) :- field_in(F, A), type_refs(F, B), A ≠ B.
depends_on(A, B) :- uses(A, B).
depends_on(A, B) :- exposes(A, F), field_in(F, B), A ≠ B.
depends_on(A, B) :- provides(A, E), event_in(E, B), A ≠ B.
depends_on_star  :- transitive closure of depends_on.

% Substrate navigation
parent_container(X, C) :- C.boundary = X.
parent_container(X, C) :- C.behaviour = X.
parent_container(X, C) :- C.children contains X.
behaviour_of(B, C)     :- C.behaviour = B.
boundary_of(B, C)      :- C.boundary = B.
rule_in(R, C)          :- C.behaviour.rules contains R.
field_in(F, C)         :- C.fields contains F.
op_in(O, C)            :- C.boundary.operations contains O.
event_in(E, C)         :- C.events contains E.

% container_of — the Container that owns (or IS) X. Used heavily in depends_on.
container_of(C, C)     :- is_container(C).
container_of(F, C)     :- field_in(F, C).
container_of(E, C)     :- event_in(E, C).
container_of(O, C)     :- op_in(O, C).
container_of(R, C)     :- rule_in(R, C).

% Tag indexing
has_tag(X, T)     :- ⟨lookup TagApplication where tag = T (or descendant of T), target = X⟩.
tag_payload(X, T, P) :- ⟨lookup payload⟩.

% Tag-constraint satisfaction — B carries every tag in the constraint set W
satisfies_constraint(B, W) :- ⟨for every tag T in W, has_tag(B, T)⟩.

% Type-vocabulary discrimination (auxiliary predicates the type-sum sugar desugars to)
type_scalar(T, Name)
type_enum(T, Vs)
type_composite_named(T, C)
type_composite_inline(T, Fs)
type_collection(T, ElementType, Semantics)
type_union(T, Ts)
type_ref_kernel(T, Kinds, W)
type_ref_substrate(T, K, Slots)

% Type-reachability — Field F's type expression reaches Container B. Delegates to
% type_refs_in for the Type-level (un-Field-wrapped) cases.
type_refs(F, B)     :- type_refs_in(F.type_ref, B).

% Type-level reachability — Type T reaches Container B (closure over Composite(Named(...)),
% Ref(KernelPrimitive(...)), Collection, Inline-Composite, and Union cases). Enum and
% Scalar cases never reach a Container and produce no rows.
type_refs_in(T, B)  :- T matches Composite(Named(B)).
type_refs_in(T, B)  :- T matches Ref(KernelPrimitive(?Kinds), where=?W),
                       is_container(B), Container in ?Kinds, satisfies_constraint(B, ?W).
type_refs_in(T, B)  :- T matches Collection(of: ?E, semantics: _), type_refs_in(?E, B).        % unwrap Collection element type
type_refs_in(T, B)  :- T matches Collection(of: _, semantics: Keyed(?K)), type_refs_in(?K, B). % keyed-collection key type
type_refs_in(T, B)  :- T matches Composite(Inline(?Fs)), F' in ?Fs, type_refs_in(F'.type, B).
type_refs_in(T, B)  :- T matches Union(?Ts), T' in ?Ts, type_refs_in(T', B).
```

Methodology-flavoured dependencies (DDD `manages`, `upstream_of`, Hex `backed_by` — when they arrive) layer as tag-presence predicates over `depends_on`.

**Reference-typed fields and dependency breadth.** A field typed as `Ref(KernelPrimitive({Container}), where=W)` — e.g., an entity-pointer like `Order.owner: User` typed as `Ref(KernelPrimitive({Container}), where={Allium::Entity})` — produces `depends_on` edges to *every* Container satisfying W's tag constraints, not just to specific instances bound at runtime. This is honest to what type-level reachability says: such a field can hold any qualifying Container, so the dep graph reflects the full set. The over-coupling is intentional at this layer; predicates that need finer-grained coupling analysis (e.g., "this field specifically targets `Order`, not `Customer`") refine by combining `type_refs` with additional tag filters or by inspecting value-level bindings when those land.

#### Auto-derived per-tag predicates

The catalogue above is **fukan-shipped and closed**. It extends with one **open** mechanism: for each `TagDefinition` loaded by the constraint engine, fukan auto-derives a small set of Datalog predicates that compile to `has_tag` and `tag_payload`. These predicates are kernel-universal in the same sense as the rest of §6.6 — they depend only on the kernel substrate, kernel relations, and the universal tag-introspection vocabulary; they are **macros**, not engine extensions.

Derivation rules:

| TagDefinition shape | Auto-derived predicates |
|---|---|
| Pure classification (no payload) | `<ns>_<name>(target)` — presence |
| With typed payload fields | `<ns>_<name>(target)` — presence; plus `<ns>_<name>_<field>(target, value)` per payload field (multi-row for collection fields; no row when an optional field is absent) |
| Relational (`RelationalSpec` present) | `<ns>_<name>(endpoint_a, endpoint_b)` — binary from the endpoint payload fields |

Naming convention: namespace and tag name lowercased and snake-cased; payload field names taken verbatim. Examples:

```prolog
% From TagDefinition(namespace="Allium", name="Surface",
%                    payload_schema={facing?, context?, related, timeout?}):
allium_surface(C)                          % presence
allium_surface_facing(C, X)                % field — X is Actor or Container (the Union of Ref targets);
                                           %         row iff payload's facing is set
allium_surface_context(C, X)               % field
allium_surface_related(C, S)               % field — one row per element in related
allium_surface_timeout(C, T)               % field — row iff timeout is set

% From TagDefinition(namespace="DDD", name="CustomerSupplier", relational={...}):
ddd_customer_supplier(Upstream, Downstream)   % binary (relational tag)
```

Each auto-derived predicate desugars uniformly to `has_tag` + `tag_payload`. The cross-vocab query surface (kernel relations + universal `has_tag`/`tag_payload`) is unchanged; sugar accelerates methodology-specific predicate authoring.

**Macro-only invariant** (per V15). Auto-derived predicates carry **no methodology-private semantics**. They cannot introduce per-vocab transitive closures, per-vocab traversal patterns, or anything beyond what `has_tag`/`tag_payload` + kernel relations express. Methodologies that need richer derived semantics author them as plain Datalog rules layered over the universal vocabulary — same as fukan-shipped derivations above. The auto-derivation is sugar; the universal layer is load-bearing.

### 6.7 Path navigation sugar — four forms

```
Form 1: Substrate dot-access (singular aggregates)
  X.behaviour.intent           % Container → Behaviour → Intent
  R.intent                     % Rule → Intent
  B.boundary                   % Container → Boundary

Form 2: Substrate set-access (plural collections)
  F in C.fields                % iterate Fields on Container
  R in C.behaviour.rules       % iterate Rules in Behaviour
  E in C.events                % iterate Events on Container
  P in O.parameters            % iterate Parameters on Operation

Form 3: Relation arrows
  A -[:emits]-> E              % forward edge
  R <-[:triggers]- E           % reverse edge (same edge, traversed backwards)
  C -[:depends_on*]-> D        % transitive closure of a single relation
  C -[:depends_on*1..3]-> D    % bounded depth
  A -[e:emits]-> E             % bind edge value (for edge-tag queries)

Form 4: Tag-payload access
  X@Allium::Surface              % shorthand for tag_payload(X, "Allium::Surface", P), binds P
  X@Allium::Surface.facing_actor % drills into the typed payload — P.facing_actor
```

Payload navigation is sort-checked against the tag's `payload_schema` — drilling into a slot that doesn't exist on the schema is a static error.

**Linear chains only.** Branching/disjunction/cycles drop back to plain Datalog rule bodies. Branching forces the author to name what they're computing, which is usually clearer anyway.

### 6.8 Type-sum case-analysis sugar

One operator: `matches`. Patterns are the `TypePattern` grammar defined in §3.8.1 — the same grammar `Match` arms use inside Expressions. The six pattern shapes mirror the six `Type` cases:

```
T matches Scalar("String")                                 % literal match
T matches Scalar(?Name)                                    % bind scalar name
T matches Enum(?Vs)                                        % bind value list
T matches Composite(Named(?C))                             % bind named container
T matches Composite(Inline(?Fs))                           % bind inline field specs
T matches Collection(of: ?E, semantics: ?S)                % bind element type + semantics
T matches Collection(of: ?E, semantics: Sequential)        % match ordered collections
T matches Collection(of: ?E, semantics: Unique)            % match set-shaped collections
T matches Collection(of: ?V, semantics: Keyed(?K))         % match keyed collections (bind K,V)
T matches Union(?Ts)                                       % bind case list (each ?Ts[i] recursively matchable)
T matches Ref(KernelPrimitive(?Kinds))                     % bind kind set
T matches Ref(KernelPrimitive(?Kinds), where=?W)           % bind kinds + tag constraint
T matches Ref(Substrate(within_kind=?K, slot_kinds=?S))    % bind substrate-address shape
```

Each position is a bound variable (`?V`), a literal, or a nested `matches` pattern. Desugars to the per-case auxiliary predicates in §6.6.

### 6.9 Coherence queries are parameterised Datalog rules

A `coherence_query` slot in `RelationalSpec` (§5.2) is a rule whose head arguments come from the relational tag's endpoint payload fields. Per-application evaluation binds the endpoint variables to the application's payload and asks "does the rule body hold?"

No separate sublanguage. The same rule shape with a convention about how the engine invokes it per-application.

### 6.10 Worked examples

These illustrate the constraint language. Some reference tag namespaces from the retired Allium vocabulary (`Allium::*`) or the design-time pressure-test methodologies (`Hex::*`); they are kept because the constraint *shapes* are vocabulary-agnostic — the same queries hold over any vocabulary that populates the kernel relations.

**Allium VR30** (historical Allium validation rule) — "Every trigger referenced in a Surface's `provides:` must be defined as an external-stimulus trigger in a rule of the same module."

```prolog
violation(B, E) :-
    provides(B, E),
    parent_container(B, M),
    not (R in M.behaviour.rules,
         E -[:triggers]-> R,
         E@Allium::Event.kind = "external_stimulus").
```

**Signal-protocol gap (`signal_gap`)** — every Event advertised by a Surface has at least one Rule consuming it. Fukan-shipped well-known predicate. The R20 lift makes this expressible directly over kernel edges, without tag-namespace traversal:

```prolog
violation(B, E) :-
    provides(B, E),
    not (E -[:triggers]-> _).
```

**Rule purity** — Rules in a module tagged `Fukan::PureCore` may have no effect edges (purity = absence of `writes`/`creates`/`destroys`/`emits`):

```prolog
has_effect(R) :- writes(R, _).
has_effect(R) :- creates(R, _).
has_effect(R) :- destroys(R, _).
has_effect(R) :- emits(R, _).

violation(R) :-
    rule_in(R, M),
    has_tag(M, "Fukan::PureCore"),
    has_effect(R).
```

**Architectural layering** — Hex Core may not depend on Hex Adapter (uses the §6.6 derived `depends_on`):

```prolog
violation(C, A) :-
    has_tag(C, "Hex::Core"),
    depends_on(C, A),
    has_tag(A, "Hex::Adapter").
```

**External state isolation** — Core Rules may not mutate state owned by external-entity-tagged Containers:

```prolog
violation(R, X) :-
    rule_in(R, M),
    has_tag(M, "Hex::Core"),
    (writes(R, X) ; creates(R, X) ; destroys(R, X)),
    container_of(X, C),
    has_tag(C, "Allium::ExternalEntity").
```

These three illustrate the Rule-purity commitment in §11: purity is queryable as a structural property (effect-edge presence); cross-module/cross-layer dependency is queryable via `uses`/`depends_on`; effect-target classification combines effect relations with tag-presence filters. No edge from Rule to anything Structure-altitude is needed or used.

### 6.11 Cross-cutting commitments

**Methodology-derived properties are plugin-authored Datalog rules over the introspection surface.** Every concrete methodology-internal derivation decomposes into kernel-universal predicate + tag-presence/payload filter:

| Methodology-internal derivation | Lands as |
|---|---|
| Allium composite-API (Module's external API = union of Surface-tagged children's boundaries) | `composite_api(M, B) :- child(M, S), has_tag(S, "Allium::Surface"), boundary_of(S, B).` |
| Allium `exposes` reachability | Direct kernel `exposes` edge traversal (post-R20 lift); methodology shading available via `Allium::Exposes` edge tag if needed |
| Allium provides-completeness | Above |
| Methodology-flavoured cross-Container deps (when applicable) | Universal `depends_on` + methodology tag |

The framework is designed to make the class "exists in input but doesn't fall out of kernel" empty. If a methodology author hits a case where they can't express their derivation, the diagnosis is V7's lifting obligation: lift more typed content.

**Bindings drive violation reporting.** Every bound variable in a matching rule is reportable via `message_template` substitution. The language has no separate "tag-this-for-reporting" affordance.

**Materialisation vs identity.** Derived relations not stored as kernel edges is about *identity*, not *materialisation*. Caching the bottom-up materialisation of a Datalog predicate is implementation tuning, invisible to the model.

**Limits of sugar.** Three places where methodology authors fall back to plain Datalog rule bodies:
1. Branching / disjunction in a path
2. Tag-payload navigation past a `where` constraint
3. Aggregates over path results

---

## 7. The projection vocabulary (output side)

The projection vocab is the **target ontology** of the `projects` relation: the closed, fukan-owned catalogue of artifact shapes that spec constructs project to. Per P1, the output vocab is a distinct mechanism from the input (methodology) vocabularies of §5.

### 7.1 Why parallel kernel, not pure kernel

Three properties distinguish it from primitives/relations (per P2):

1. **Faster evolution lane.** Primitives and relations evolve slowly because changes ripple. Projection-vocab evolution is **additive** (new Artifact sub-kinds), **local** (each sub-kind is self-contained), and **bounded-impact** (a new sub-kind doesn't reshape existing edges).
2. **Fukan-owned, but architected for plugin extensibility.** This scope keeps the projection vocab closed and fukan-owned for queryability, drift-detection confidence, and ontological clarity. Future opening to plugins is preserved as a refactor path (sum-extension), not a redesign.
3. **Separate from input vocabularies.** Methodologies plug into the kernel as input vocabularies; projection targets are a distinct concern.

### 7.2 v0 Artifact ontology (per P4)

```
Artifact =
  | Code( language: String, sub: CodeSub )

CodeSub =
  | Function( qualified_name: String, source_location: SourceLocation? )
  | DataStructure( qualified_name: String, source_location: SourceLocation? )
```

The `Infra(...)` and `Documentation(...)` cases come back when `.infra` and a documentation analyzer respectively join (§10.1, §10.5). The sum-extension shape (§7.5) means adding them later is additive on the projection-vocab lane, with no impact on existing cases.

### 7.3 Identity per case

Two Artifacts are equal iff their case + key fields match. `source_location` and other position metadata are non-identity.

| Case | Identity tuple |
|---|---|
| `Code.Function` | `(language, qualified_name)` |
| `Code.DataStructure` | `(language, qualified_name)` |

### 7.4 `projection_kind` enum (per P5)

Lives as an identifying-metadata slot on `projects` edges:

```
projection_kind =
  | 'rule'         -- Rule       → Code.Function           (enactment)
  | 'operation'    -- Operation  → Code.Function           (execution)
  | 'invariant'    -- Invariant  → Code.Function           (check)
  | 'schema'       -- Container | Event → Code.DataStructure
  | 'test'         -- Rule | Operation | Invariant → Code.Function (verification)
```

Naming logic: function-shaped projections name their *source primitive kind* (`rule`, `operation`, `invariant`); the artifact-shaped projection names its *artifact shape* (`schema`). `test` is uniform across the three behavioural sources — the source endpoint discriminates what's being tested. The test cut excludes Entity / Value / Variant / Event per §7.6 (schemas are data, verified via the tests of the behavioural primitives that consume them).

A spec primitive may produce multiple `projects` edges with different `projection_kind` values (e.g. a Rule produces both a `rule` projection to its enactment function and a `test` projection to its test function). The `(from, to, kind, identifying_metadata)` edge identity per §4.4 keeps them distinct.

Adding a new kind (e.g. `endpoint`, `resource`, `documentation`, `diagram` when their producing analyzers join — §10.1, §10.5) is additive on the projection-vocab lane.

### 7.5 Plugin-extensibility seams (per P3)

The projection vocab is closed in this scope but **architected** so future opening to plugin extension is a refactor, not a redesign:

1. **Sum-type shape.** Adding a case is sum-extension; existing cases unaffected.
2. **Per-case identity is self-contained.** No cross-case coupling.
3. **Comparators are case-dispatched.** `compare(spec_primitive, artifact) → ValidityResult` dispatches on artifact case. Replacing a fukan-owned comparator with a plugin-supplied one is one-line at the dispatch.
4. **`projection_kind` is also a closed enum per case.** Each case enumerates valid kinds. Plugins would supply per-case kinds for their case.
5. **No content shared across cases that would force kernel changes when a case is added or replaced.**

### 7.6 Producing projections — substrate-level commitments

The MVP producer is the **Clojure Target language extension** (§7.7), whose Analyzer reads Clojure source and emits `Code.Function` and `Code.DataStructure` Artifacts plus `projects` edges from spec primitives to those Artifacts, and whose Projector emits Implementation Blueprints on demand for code generation. Mechanical detail — address-resolution rules, identifier transliteration, type-translation registry, idiom selection, enforcement policy, the concrete Blueprint record shape — is application-design content and lives in [DESIGN.md](./DESIGN.md); the convention-driven name-resolution strategy (per P6) and the malli-defs-over-defrecords schema shape (per P8) are application-design commitments registered there. The substrate-level commitments below are what the projection vocabulary itself mandates.

**Which spec primitives produce `projects` edges, and at which `projection_kind` values.** Every primitive listed here is the *from* side of one or more `projects` edges in a successful MVP build.

| Spec primitive | Artifact case | `projection_kind` |
|---|---|---|
| Container tagged `Allium::Entity \| Value \| Variant` | `Code.DataStructure` | `schema` |
| Event | `Code.DataStructure` | `schema` |
| Operation on a Contract's Boundary | `Code.Function` | `operation` |
| Rule | `Code.Function` | `rule` |
| Expression in `Container.intent.assertions` tagged `Allium::Invariant` (with non-null `label?` per K31) — covers both top-level (module-Container) and entity-level (entity-Container) invariants, since both share the unified tag per §8.1 | `Code.Function` | `invariant` |
| Rule \| Operation \| Invariant Expression (above) | `Code.Function` | `test` |

**Test projection cut — behavioural primitives only.** Rule, Operation, and Invariant Expression project to test artifacts; Entity / Value / Variant / Event do **not**. Schemas are data — their structural correctness is verified by the type system (malli) at load time; their *use* is verified through the tests of behavioural primitives that consume them. The per-kind cut can later move to a convention entry if a project needs to override it; MVP commits the cut as policy.

**Surface and Contract have no direct projection (per P7).** Both are Boundary-only Containers (§9.1) — spec-level groupings rather than realising primitives. Their *Operations* project individually. Within-module operation-name collisions across multiple Surfaces are spec-side lint errors; rename or move to different modules.

**Drift output — per-edge `validity` (memoised derivation).** Each `projects` edge carries `validity ∈ 'valid' | 'absent' | 'stale' | 'unknown'` per §4.2. The field is a **cached evaluation**, not authoritative state: the comparator function `compare(spec_primitive, expected_address, code_at_address) → validity` is the source of truth; the stored field memoises its result for query-time convenience. Refresh runs on every model rebuild; on-demand evaluation between rebuilds is a post-MVP optimisation but introduces no substrate change — only an alternative trigger for the same comparator. Semantics:

- `valid` — comparator found the expected artifact at the expected canonical address.
- `absent` — comparator found no artifact at the expected canonical address (a Rule with no matching function is an implementation gap; no test function is a missing-test gap).
- `stale` — finer-grained drift (e.g., malli-schema field-shape divergence from spec entity fields) — reserved for a future shape-comparator pass, not populated in MVP.
- `unknown` — comparator has not yet evaluated this edge (transient between analyzer phases in incremental builds); not the steady state after a successful pass.

**Test projections: `valid` means presence, not coverage.** For `projection_kind = 'test'`, `validity = valid` indicates only that a function exists at the canonical test address — it does **not** verify the function actually exercises the spec primitive it projects from. Coverage analysis is a future shape-comparator concern, not part of MVP. The explorer's green marker on a `test` edge reads as "test function present," not "test coverage verified."

The `Infra(...)` and `Documentation(...)` Artifact cases — and the `endpoint`, `resource`, `documentation`, `diagram` projection_kind values that would target them — are deferred per §7.2; the kernel relation set is unchanged either way.

### 7.7 The Target language extension — Analyzer and Projector

A **Target language extension** is fukan's second extension type (the first being a Vocabulary; §5.0). It binds to a specific implementation language (Clojure in MVP; future TypeScript, Java) and is the producer-and-consumer of `projects` edges. Each Target language extension exposes two operations sharing one body of configuration:

| Operation | Direction | Phase | Output |
|---|---|---|---|
| **Analyzer** | reads code → Model content | Phase 1 (code-side) | `Code.*` Artifacts + `projects` edges with per-edge `validity` (§4.2 / §7.6) |
| **Projector** | reads spec → generated code | On demand | Implementation Blueprints consumed by LLM-driven code generation (or by the Analyzer for verification) |

Both operations apply the same universal projection mechanic; they differ only in direction. The application-design commitments for the Clojure-specific case (address-resolution rules, identifier transliteration, type-translation registry defaults, malli-as-schema-shape, concrete Blueprint record shape) live in [DESIGN.md](./DESIGN.md); the substrate commitments are the two-operation shape, the universal projection mechanic, and the Blueprint protocol below.

#### The projection mechanic (universal)

For one specific projection — one spec primitive, in one target language, with one `projection_kind` — the mechanic produces an **Implementation Blueprint** by assembling six components:

1. **Canonical address** — resolved via the target language's address-resolution rules.
2. **Artifact kind** — the Artifact case (`Code.Function` | `Code.DataStructure` | future Infra/Documentation cases) for this `(primitive_kind, projection_kind)`.
3. **Expected signature** — mechanically derived from the spec primitive (Rule's `when:` event shape; Operation's parameter list and return type; Container's field map for schema projections).
4. **Type renderings** — every substrate Type the signature touches, rendered via the target language's type-translation registry including any project-layer overrides (§10.3).
5. **Surrounding model context** — related primitives reachable from this one (related primitives, types referenced transitively, dependencies).
6. **Selected idioms** — applicable project-layer idiom entries matched by routing predicate (primitive kind × projection kind × address match).

The Blueprint is the per-call output of this mechanic — ephemeral, on-demand, materialising this specific projection. The mechanic is universal: every Target language extension applies the same six-step assembly; what varies is the per-language content each step resolves (a Clojure Projector renders types as malli, a future TypeScript Projector renders the same types as TS interfaces — same mechanic, different translation registry). The concrete Blueprint record shape (serialisation, exact field representations) is application-design content per [DESIGN.md](./DESIGN.md).

#### Implementation Blueprint — ephemeral, on-demand, per projection

A Blueprint is the per-call output of the projection mechanic. It is **not persisted**; it is regenerated on each request from the current state of (spec primitive, project layer entries, model context). Two consumers, one source of truth:

- **LLM (code generation):** `system_prompt + Blueprint + spec_primitive → generated code`. The system prompt is generic target-language-aware boilerplate; the Blueprint is per-projection; together they form full instructions for one generation call.
- **Analyzer (verification):** `Blueprint → expected shape → compare against actual code → validity`. The Analyzer materialises a partial Blueprint to compute the expected canonical address and presence-check; a future shape-comparator pass will use a fuller Blueprint to detect finer-grained drift.

A human may request a Blueprint for inspection ("show me what the LLM would receive for projecting Rule X as a test") as an explorer affordance — but inspection is read-only; the Blueprint is not user-editable as a persistent artifact. Project projection inputs that authors *do* persist live in the project layer (§10.3) and feed every Blueprint produced.

The substrate commitments are the six-component composition (above), the lifecycle (ephemeral, per-projection, never persisted), and the two-consumer protocol.

#### Generation as MVP

Both Analyzer and Projector operations are MVP for the Clojure Target language extension. Code generation is not deferred; only `.infra` (and the Documentation-flavour Artifact cases that travel with a documentation analyzer) defer. Specific MVP entailments:

- The project layer's projection-input sub-locus (§10.3) is load-bearing — address-resolution knobs, type-translation overrides, and idioms are committed in declarative form; only the *composition mechanics* (severity overrides for project-imported constraints, profiles, bundle composition) defer.
- Drift markers (`absent` validity on a `projects` edge) carry a generation affordance in the explorer — clicking summons the Projector for that primitive.
- Spec evolution + generated code: when a `projects` edge transitions from `valid` to `absent`, the explorer surfaces the divergence; regeneration is one explorer action. Detection of human-side edits to generated code (so that regeneration warns before overwriting) is a future-shape-comparator concern, not MVP.

The seam to other Target language extensions (future TypeScript, Java) is fully open — each registers its own address-resolution rules, type-translation defaults, idiom defaults; the projection mechanic and Blueprint protocol are universal.

---

## 8. Methodology contributions (historical — Allium/Boundary retired)

**Phase 3 state:** The Allium and Boundary spec parsers described in §8.1 and §8.2 are retired. Canvas specs now populate the kernel substrate directly via the canvas vocabulary lifts (`function`, `invariant`, `rule`, `record`, `value`, `getter`, `checker`, `exports`). The canvas vocabulary's substrate mapping is straightforward: `function`/`getter`/`checker` → `:primitive/operation`-role Affordances; `invariant`/`rule` → `:primitive/rule`-role Affordances; `record`/`value` → Types; `exports` → `:exported` tag on the named entity. The detailed kernel-mapping documentation below is preserved as historical substrate context — it describes the substrate design commitments that the canvas vocabulary builds on.

Per K1–K3, the Model is contributable-to by multiple vocabularies — each producing kernel content (primitives, relations, sub-substrate) plus its own namespaced tag applications via the §5 vocabulary mechanism. The Model itself is uniform; methodologies plug into it as overlays.

The original MVP carried two input-vocabulary methodologies: **Allium** (§8.1), the behavioural-and-partial-structural source vocabulary, and **Boundary** (§8.2), the Structure-altitude binding vocabulary. Each section maps its source-language constructs onto the kernel substrate; the application-design framing lives in [DESIGN.md](./DESIGN.md).

### 8.1 Allium → kernel mapping

Allium was the behavioural-and-partial-structural source vocabulary. Its constructs — `module`; `entity` / `value` / `variant`; `rule` (with `when:` triggers, `requires:` / `let` / `ensures:` bodies); top-level and entity-level `invariant`; `surface` (with `facing` / `exposes` / `provides` / `contracts:`); `contract`; `actor`; and Events synthesised from `when:` / `provides:` / `emits:` sites — each projected onto the kernel substrate documented above: Containers, Rules, Expressions and Effects (§3.8), and the `triggers` / `observes` / `realises` / `uses` / `exposes` / `provides` edges, with `Allium::*` tags carrying methodology framing.

The canvas vocabulary lifts now populate the same kernel content: `record` / `value` → Types; `invariant` / `rule` → `:primitive/rule`-role Affordances; `function` / `getter` / `checker` → `:primitive/operation`-role Affordances; `exports` → the `:exported` tag. The substrate commitments the Allium mapping exercised (named-closed-included Types, the reserved-but-unpopulated Bool-assertion slots on Intent-hosts per K30, Event identity per K16) are unchanged — only the source vocabulary changed.

The full construct→kernel mapping table is preserved in git history (commit `af4885d`) and the archived `.legacy-allium/` reference.

The Boundary-only Container pattern (Surface / Contract / future Specification / Port / …) is documented in §9 as a convergence pattern across methodologies. Allium contributes the first instances.

### 8.2 Boundary → kernel mapping

Boundary was the Structure-altitude vocabulary. It existed because Allium's structural coverage was partial — Allium's primitives (Contract Operations, Surfaces, Rules) carry behavioural framing that's overkill for the *mundane structural callables* every system has: getters, setters, renders, lifecycle operations, pure transforms. `.boundary` filled that gap with one primitive — `fn`, a typed callable carrying no behavioural weight, optionally attachable to an Allium Rule via `triggers:` / `returns:` — plus two adjacent capabilities at the same altitude: module-API closure (`exports:`) and subsystem composition (`subsystem`). Its tags lived in the `Boundary::*` namespace (distinct from the kernel `Boundary` slot, K11/K13).

It contributed: Operation primitives on module Boundaries (which Allium left empty), `triggers: Operation → Rule` edges (R4), `Boundary::ModuleApi` tags flipping a module from open to closed, composite Containers for subsystems, and subsystem-scoped PredicateRegistrations.

The canvas `function` and `exports` lifts cover what `.boundary` handled: `function` is the signature-bearing callable on a module's API; `exports` tags the module's public closure. Subsystem composition maps to module ownership via `:module/child` Relations (the ownership-on-owner principle). The full construct→kernel mapping table is preserved in git history (commit `af4885d`) and `.legacy-allium/`. The reasoning trace lives in [DECISIONS.md §Boundary language](./DECISIONS.md#boundary-language).

**External-system enrichment.** Non-entity externals (third-party services, vendor storage, imported libraries) were not modelled by `.boundary`; entity-shaped externals were carried by Allium's `external entity` (per §3.6). The originally-designed `Boundary::External::Service` / `Storage` / `Library` enrichment is deferred — see §9.2 for the convergence-pattern framing that survives the deferral.

---

## 9. Convergence patterns

Across the cross-methodology pressure-test (DDD, Event Storming, Hexagonal, C4), two structural patterns recurred consistently. Neither is promoted to a primitive — they are documented as canonical shapes the universal Container correctly serves.

### 9.1 Boundary-only Container

A Container that populates only its `boundary` slot — no `fields`, no `behaviour`, no `children`. Represents a pure interface: an addressable structural unit whose substance is the operations *and/or* intent of its Boundary. **The Boundary's `operations` list may be populated or empty** — Boundary-only Containers split into two sub-shapes:

- **Declaring** (Operations populated): the Container is the canonical declaration site of its Operations. Allium `contract` is the motivating case — Contract owns its typed signatures.
- **Realising** (Operations empty): the Container is an interface point whose Operations are reached via outgoing `realises` edges (R6) to a declaring Container. Allium `surface` is the motivating case — a Surface's Operations live on the Contracts it fulfils; the Surface points to them via `realises` rather than holding them directly. The Boundary's `intent` slot is still meaningful — Surface `@guarantee` Clauses live in `boundary.intent`.

The shape is the same primitive (Boundary-only Container) with two population patterns. Predicates and the explorer treat them uniformly; queries for "Operations at this Container" traverse `boundary.operations` directly *and* through outgoing `realises` edges (a derived view, not stored).

Methodological occurrences:

- Allium `contract` — declaring (Operations populated)
- Allium `surface` — realising (Operations reached via `realises` to fulfilled Contracts)
- (Validation) DDD `Specification` — declaring (one `evaluate(subject): Boolean` operation)
- (Validation) Hexagonal `Port` (Driving and Driven) — declaring (Operations owned by the Port; Adapters realise the Port)
- (Validation) C4 light touches at component-interface level

### 9.2 External-System Container

A Container tagged as outside-the-modelled-system, referenced from inside the system, with no internal structure modelled. Represents an external dependency, third-party service, persistence we don't own, or system-context boundary. The shape applies uniformly whether the external thing is entity-shaped (a vendor's data type we import), service-shaped (a third-party API), storage-shaped (object storage / managed DB), or library-shaped (imported code we depend on but don't own).

Methodological occurrences:

- Allium `external entity` — Container declared at use site, full definition outside scope; `Allium::ExternalEntity` is the §3.6 marker for the pattern. **MVP coverage.**
- (Deferred) Non-entity-shaped externals (third-party APIs, vendor storage, imported libraries). The originally-designed `Boundary::External::Service` / `Storage` / `Library` enrichment with vendor/documentation payload (and the corresponding "module-as-wrapper rule") is deferred from MVP — `.boundary` MVP grammar carries no `external <kind>` construct. Non-entity externals compose via methodology tags until a concrete need re-opens the seam. The structural pattern (a tagged Container with no internal substrate, referenced from inside) remains available — only the dedicated `Boundary::External::*` shading awaits.
- (Validation) DDD external dependencies in Context Maps
- (Validation) Event Storming External System (pink)
- (Validation) C4 Level-1 External System
- (Validation) Hexagonal Driven-Port targets

The marker tag (`Allium::ExternalEntity` or methodology equivalent) is what makes the Container an External-System under §3.6's "externality is a tag" commitment. When `.infra` lands (§10.1), `Infra::Service` / `Infra::Storage` directly subsume the non-entity-external role on the appropriate Container — Container identity does not churn; the enrichment tag arrives with its eventual home, sidestepping the deferred `Boundary::External::*` interim layer entirely.

### 9.3 Pressure-test record

Coverage that validated the substrate at design time. **Validation evidence; not committed support targets** — only Allium's coverage is implemented in MVP.

| Scope | Constructs validated |
|---|---|
| Event Storming Big Picture | Aggregates, Commands, Domain Events, Policies, Read Models, Hot Spots, Pivotal Events, External Systems, Actors, Bounded Contexts, temporal ordering, workflow grouping |
| DDD Strategic Design | Bounded Contexts, Context Map (Shared Kernel, Customer-Supplier, Conformist, ACL, OHS, Published Language, Partnership, Separate Ways, Big Ball of Mud), Subdomains, Ubiquitous Language |
| Hexagonal | Multiple Adapters per Port, directional offers/requires, Application Service composition |
| C4 | Free-text relationship labels, four-level taxonomy, Person→System interaction |

Three deliberately hard cases survived: DDD Separate Ways (assertion of *absence*, no underlying derived edge to tag), DDD multi-aspect on a single BC pair (CS + ACL + Published Language), ES Policy → Command (the Event-vs-Command kernel distinction).

Substrate adjustments driven by the pressure-test: (i) the V12 relational-tag affordance, motivated by DDD multi-aspect; (ii) the V13 coherence-query / methodology-predicate split, motivated by DDD Separate Ways; (iii) the typedness-grading commitment, motivated by C4 free-text labels.

---

## 10. Architectural seams

Work deferred from the MVP scope but architecturally seamed — each has a concrete re-opening trigger, and none requires kernel substrate change to admit.

### 10.1 `.infra` layer — Infra-altitude spec

Infra-altitude spec content: declarative commitments about deployment. Likely Container population pattern: Containers tagged `Infra::Service`, `Infra::Endpoint`, `Infra::Storage`, `Infra::Channel`, `Infra::Policy`, with tag payloads carrying kind-specific fields and upward references to the Structure-altitude Operations/Surfaces they realise. Same kernel-content pattern as Allium contributes at Behaviour and Structure altitudes; no new kernel primitives needed.

**Tied to this seam.** The `Infra(Endpoint | Resource)` cases of the Artifact ontology and the `endpoint`, `resource` projection_kind values come back together with `.infra`. They represent *observed deployed reality* (real services, real endpoints) reported by a future live-cluster analyzer. Infra-altitude spec `projects` to Infra-Artifacts when both exist — same drift-detection pattern as Behaviour-spec projecting to Code Artifacts. The spec-altitude and Artifact-flavour axes are independent (see §11).

Re-opening trigger: project's stakeholders need the implementation-contract altitude alongside live-deployment observation.

### 10.2 Extension registry mechanics

Fukan recognises two **extension types**, structurally distinct:

| Extension type | Hooks | Examples (MVP) | Examples (future) |
|---|---|---|---|
| **Vocabulary** (§5.0) | Vocabulary content (tags, predicates, operators, renderers) + optional spec parser + optional renderer | Allium, Boundary | DDD, Hexagonal, C4, fukan-native rule-body plugins |
| **Target language extension** (§7.7) | Analyzer (code → projects edges) + Projector (spec → Implementation Blueprint) sharing address-resolution / type-translation / idiom configuration | Clojure | TypeScript, Java, Python |

Each extension type has its own registration shape. A Vocabulary's content is registered through the §5 mechanism (TagDefinitions, PredicateRegistrations, operator registrations, renderer registrations); its optional spec parser plugs into Phase 1's spec-side. A Target language extension registers its two operations plus address-resolution / type-translation / idiom defaults.

**Registry manifest format, namespace ownership, conflict resolution, two-phase load, project-side configuration:** out of scope for MVP per the YAGNI cut — Allium, Boundary, and Clojure are integrated directly.

Re-opening triggers:
1. A third Vocabulary becomes real (e.g., DDD/Hex/C4 gains an authoring path), or a second Target language extension joins.
2. Project-local extensions become real and need namespace ownership to avoid collisions.
3. The hardcoded path develops friction that namespace ownership would resolve.

### 10.3 The project layer — sub-loci and composition

The **project layer** is the seam where a project parameterises fukan over the registered extensions. It is *not* a fourth language at a different altitude — it annotates extension-supplied baselines rather than peering with them. The layer carries two sub-loci, each with a distinct lifecycle, consumer, and composition story:

| Sub-locus | What it is | Consumed by | Lifecycle |
|---|---|---|---|
| **Projection inputs** | Address-resolution knobs, type-translation overrides, and idioms (per-primitive-kind patterns, per-projection-kind patterns, per-address-match patterns) — all variants of "how kernel concept X projects concretely in this project/language" | The projection mechanic (§7.7), which assembles them into the Implementation Blueprint on each call | Persisted, declarative in MVP |
| **Constraints** | `PredicateRegistration` entries in the constraint language (§5.3, §6) | The constraint engine (Phase 5 of the build pipeline) | Persisted; authored in constraint-language AST in MVP, surface-syntax-sugared once tokenisation lands (§13) |

The Target language extension ships projection-input defaults; the project layer overrides per-project. Vocabulary extensions ship constraints as defaults; the project layer adds project-shipped constraints in its own namespace.

**One mechanism for projection inputs.** Address-resolution, type-translation, and idioms are not separate categories — they are all instances of the same question ("how does kernel X project concretely in target Y for this project"). The project layer has one projection-input bucket; different *content* lives at different sub-routes (per primitive kind, per projection kind, per address-match pattern), but the registration mechanism is uniform. Selection at projection-time is context-keyed — the projection mechanic selects applicable entries by matching the current `(primitive_kind, projection_kind, address_match)` against the entry's routing.

**External-system enrichment placement.** Earlier drafts placed `External::*` tag applications in the project layer. They are structural facts (an external dependency *is* a module), not project-side configuration. Entity-shaped externals live in Allium as `external entity` (per §3.6) carrying `Allium::ExternalEntity`. Non-entity-shaped externals (services, storage, libraries) are deferred from MVP — see §9.2 for the deferral.

#### MVP commitments

- The constraint language (§6) is single and shared. Vocabulary-shipped, project-shipped, and `.boundary`-scoped constraints all author against the same language; only the registration locus differs.
- The Datalog substrate of §6 is fully usable from V0 in **AST form** — constraints register as data structures encoding the rule heads and body literals. The path-sugar and type-sum-sugar surface tokenisation (§13) lands at implementation; constraints authored in AST form gain ergonomic surface representation when sugar lands, without re-registration.
- Projection inputs (§7.7 — address-resolution knobs, type-translation overrides, idioms) are **declarative in V0** — their shape is small and fully specified by the Target language extension's schema, so the audience model VISION pitches (humans, LLMs, pipeline all reading the same entries) holds day 1.
- `PredicateRegistration` (§5.3) is the per-constraint registration shape across all loci.

#### Fukan-shipped well-known constraints (V0)

A small set of constraints fukan ships independently of any specific Vocabulary, available for projects to register (and for `.boundary rules:` to parameterise):

| Constraint | What it asserts |
|---|---|
| `signal_gap` | Every `provides: Surface → Event` edge has at least one `triggers: Event → Rule` consumer |
| `no_dependency(from, to)` | The derived `depends_on` relation (§6.6) does not hold from any Container tagged `from` to any Container tagged `to` |
| `no_circular_refs(scope)` | Within the `scope` Container's transitive `children` closure, no `depends_on` cycle exists |
| `naming_convention(target, pattern)` | Every primitive of kind `target` has a label matching `pattern` |
| `external_must_have_wrapper` | Every Container carrying `Allium::ExternalEntity` belongs to a module declared in a `.boundary` file (entity-shaped externals are wrapped by a module boundary). Non-entity-shaped externals are deferred per §9.2; the constraint extends to cover them when the `Boundary::External::*` enrichment lands. |

Concrete predicate bodies and message templates are application-design content in DESIGN.md; the substrate commitment is that these five exist in V0 and extend additively.

#### Composition mechanics — deferred

What the project layer commits in V0 is: the two sub-loci, the registration shapes (the projection-input schema is defined by each Target language extension; constraints use §5.3 `PredicateRegistration`), the constraint language and its locus-agnostic semantics, and the well-known set above.

What it defers is the *composition mechanics* — how project-shipped entries combine with Vocabulary-shipped and Target-language-shipped baselines:

| Cut | What would force it back |
|---|---|
| Project-side severity overrides on imported constraints | A project that legitimately needs different severity for an imported constraint and can't get there by writing a parallel project-local constraint |
| Multiple named profiles (CI vs dev) | A project that needs different active sets per evaluation context and rejects per-run config as the answer |
| Constraint bundles within a Vocabulary | A Vocabulary shipping sub-groups (e.g., strategic vs tactical) wanting bundle-granularity selection |
| Transitive bundle imports | Bundles arriving and wanting to compose each other |
| Versioning | Real Vocabulary upgrades breaking real project entries + projects pinning to old versions |

#### Namespace convention

Vocabulary extensions use their name as the `PredicateRegistration.namespace` (`Allium`, `Boundary`). Project-shipped constraints use a project-scoped namespace; the conventional choice is the project's name (`Fukan` for this project's constraints). The mechanism is free-form — any string is admissible — but consistent project-name namespacing avoids collisions when entries are eventually shared. Standardisation arrives with the registry manifest (§10.2).

#### Constraint evaluation timing

All registered constraints — Vocabulary-shipped, project-shipped, and the coherence queries bundled in relational tag definitions (§5.2) — evaluate **at build-time on every model rebuild**, with results cached. The explorer reads cached results; no on-demand evaluation in MVP. Severity is a reporting attribute, not a timing modifier (both error- and warning-severity constraints evaluate at the same point in the pipeline; severity determines how violations are surfaced, not when they're computed). Incremental re-evaluation on model-edit is a post-MVP optimisation; the cache invalidates wholesale on rebuild in V0.

### 10.4 Renderer plug-in mechanics

The seam is committed (§5.5); concrete shape arrives with the explorer rebuild. Methodologies remain plug-and-play because renderers are plugin-supplied rather than explorer-hardcoded.

### 10.5 Future projection vocab additions

Deferred from V0, tied to specific seams:

- **`Infra(Endpoint | Resource)` Artifact cases + `endpoint`, `resource` projection_kind values** — come back together with `.infra` (§10.1).
- **`Documentation(Page | Diagram)` Artifact cases + `documentation`, `diagram` projection_kind values** — come back together with a documentation analyzer (no current seam in §10).

Future candidates beyond the deferred set, each local-impact and additive on the projection-vocab lane:

- `Code.Module` — namespace/package as projection target, if module-level projection becomes important
- `Code.Macro` — if Lisp-style metaprogramming becomes a major projection class
- `Documentation.DecisionRecord` — ADRs, if they earn structural treatment beyond Page
- `Configuration` — possibly its own category if YAML/TOML/JSON config-as-projection becomes important; or a Code sub-kind; or absorbed into Infra
- Additional `projection_kind` values as new (source, target) pairs arise

### 10.6 Generics / parameterised types

`Repository<Customer>`, user-defined parameterised Container types — `Collection` covers built-in Sequential/Unique/Keyed parameterisation, but user-defined parameterised types (Java generics, Clojure protocols-with-type-params) are not yet expressible. Allium doesn't need them; code analysis will. Likely a `parameters: Collection(of: Type, semantics: Sequential)` slot on a wrapping case, or a `Generic` variant on `Type`. Resolution surfaces with implementation linkage.

### 10.7 Operation errors / failure modes

Operations express call (`return_type` present) vs command (`return_type` absent), but no "may fail with these errors." Reality has this. Likely a relation (`Operation —may_raise→ Container`-tagged-error) so substrate stays minimal and aligns with how Events/effects are modelled. Note for future design.

---

## 11. Substrate commitments

Positions the kernel takes on otherwise-open questions, named explicitly:

- **Kernel minimality is a discipline, not a count.** The kernel surface — nine primitives (§3.1), thirteen relations (§4.1), six Type cases (§3.3), the §3.8 Expression / Effect sub-substrate — is not bounded by a target number; each item is justified individually by the force-and-gate criterion (§1): could this primitive be subsumed by another? does any worked example require keeping them separate? Per-item justifications live with each item (§3.1 per-primitive notes, §3.3 Type table, §4.2 per-relation semantics). This entry names the per-item audit *as* the kernel's minimality test — applied at each addition and revisited at each subtraction. An item earns removal when its forcing example dissolves; an addition earns the same scrutiny.
- **The Model is a value.** Kernel substrate, kernel edges, and tag applications compose into one immutable value — the Model — produced by the merge step (§3.6; pipeline in DESIGN.md) from per-analyzer outputs. Pipeline phases produce new Model values from old; the implementation's storage of the current Model in a mutable slot (e.g. a Clojure atom) is a hosting concern, not a substrate property. Query, projection, and predicate evaluation read the Model as a value at one point in time; the value never mutates in place. Stored derivations on the Model (Effect, §3.8.3; `validity` on `projects` edges, §4.2 / §7.6) are themselves part of the value and follow the same discipline — caches recomputed from inputs, not authoritative state.
- **Containment uniform, not kinded.** `Container.children` is one slot regardless of methodology-specific containment flavour.
- **Time derived, not first-class.** No `precedes` relation in the kernel; methodologies that need temporal ordering derive it (`emits` → `triggers` chains).
- **State-behind-wall derived, not primitive.** No `has_state` Boolean; identity / state come from realising structure + vocabulary tags.
- **Intent first-class** — kernel primitive.
- **Agency first-class** — Actor distinct from Container.
- **Design framing in vocabulary, not kernel.**
- **Type-system shape — Path A** (named, closed, composed-by-inclusion). Methodologies overlay alternatives.
- **Operation vs Event split kept** — the addressability difference (Boundary-scoped vs Container-owned) is genuine.
- **`Container.children` depth unconstrained** — no kernel-imposed hierarchy limit.
- **Single Container primitive** — no specialisation by population pattern (no Interface primitive, no Aggregate primitive). Convergence patterns documented in §9, not promoted.
- **Three spec altitudes; strict one-up reference.** Spec content lives at one of three altitudes — **Behaviour** (Rules, Events, Invariants and Guarantees regardless of host — module-level, entity-level, contract-level, surface-level — all claims about state behaviour), **Structure** (Operations, Boundary, contract/composition content), **Infra** (deployment commitments). Each altitude may reference only the altitude immediately above; never downward; never skipping. Same-altitude references unrestricted. **Implementation is not a fourth altitude** — it's the projection across all three (Code | Infra-Artifact | Documentation flavours per §7).
- **Some substrate elements are altitude-spanning.** Container and Actor (primitives), plus Field (sub-substrate per §3.2), are referenced from any altitude — they do not themselves carry an altitude. Altitude-bound primitives carry the altitude of their semantic role: Rule / Event / Behaviour → Behaviour; Operation / Boundary → Structure; future Infra primitives → Infra. Intent / Clause carry their host's altitude.
- **Altitude is derived, not stored.** A primitive's altitude is determined by its kind and slot occupation; the kernel does not store altitude metadata on primitives or relations.
- **Spec altitude / Artifact flavour orthogonality.** Three spec altitudes (Behaviour | Structure | Infra) and three Artifact flavours (Code | Infra | Documentation) are independent axes when fully populated. "Infra" appears in both axes and means different things: as a spec altitude it's declarative commitment about deployment; as an Artifact flavour it's observed deployed reality. Any spec altitude can project to any Artifact flavour. (V0 carries only Code Artifacts; Infra and Documentation flavours return with their producing analyzers — §10.1, §10.5.)
- **Tag-attachment corollary for cross-altitude references.** Vocabulary tags carrying upward cross-altitude references attach to the lower-altitude side; their payload schemas reference upward. Higher-attaches-with-downward-payload is disallowed.
- **Rule purity is the absence of effect edges.** A Rule is pure iff it has no outgoing `writes` / `creates` / `destroys` / `emits` kernel relations. Rules describe state changes within the modelled system; they do not (and cannot, per K24) reference Operations or other Structure-altitude content. **External reach of a Rule is derived**, not edge-shaped — by traversing the Rule's effect targets through boundary configuration: writing or creating against a Container tagged `Allium::ExternalEntity` (the Rule mutates state in an external Container); or an `exposes:` edge from some Surface to a written / created Field, where the Surface `facing:` an external Actor (the Rule's write becomes observable to that Actor). Outbound observation of *emitted Events* is not modelled at spec altitude in v0 — `provides:` advertises inbound entry (external party fires the Event into the system), not outbound observation; the matching outbound-advertisement substrate is an architectural seam left open. The query crosses altitudes upward (Behaviour effects + Structure boundary), respecting K24. Cross-module dependency at spec altitude is `uses` between Containers + derived `depends_on` (§6.6). At implementation altitude (per K23), spec-vs-implementation purity becomes a `projects` edge `validity` concern: the implementing function may only produce effects its Rule's spec declares — surfaced when call-graph derivations on `projects` edges land (post-MVP).

---

## 12. Decisions log

The design-phase decision trace — every **K\***, **R\***, **V\***, **C\***, **P\*** identifier cited throughout this document — lives in [DECISIONS.md](./DECISIONS.md). It records the forces that produced each substrate commitment and the framings each decision superseded; useful when re-opening a deferred question, not needed when reading the substrate for current state.

Current-state substrate commitments live in [§11](#11-substrate-commitments) above; continuation points (TBDs) live in [§13](#13-tbds-consolidated) below.

---

## 13. TBDs (consolidated)

Items that are aligned-on-direction but not specified — continuation points, not gaps. Each tagged by track and given a re-opening trigger where one is concrete.

### Kernel substrate

- **Function-shaped Containers.** User-defined functions (DDD Domain Services, future C4 code-level Components). Likely Container with Boundary holding one Operation. Allium has no user-defined functions per se.
- **Sub-Clause addressability.** When needed?
- **First-class Parameter addressability.** The §4.3 path form supports it; v0 populates only the trivial Field case. (Clause's first-class addressability is *resolved* — Clause is a kernel primitive per §3.1 / §3.2 / K15a, reached via `PrimitiveRef`.)

### Relations

- **Non-identifying edge metadata schemas.** `projects` has committed-shape non-identifying metadata. Other relations carry no kernel-mandated non-identifying metadata, leaving room for vocabulary content.
- **Visibility/scoping of relations across module/composite boundaries.** Couples with `.boundary` track.
- **Concrete Expression tokenisation.** Substrate Expression ADT (§3.8.1) is committed; the surface tokens for authoring (parens, operator spelling, keyword forms) land at implementation. Allium's existing syntax provides the V0 mapping consumed by the analyzer; bare-substrate authoring conventions arrive when forced. (Replaces the prior "canonicalisation of condition/scope text" TBD, resolved by K28.)
- **Operator registration shape for methodology-supplied operators.** §3.8.1 commits the kernel-core operator set and notes methodology extensibility parallels §5.3 `PredicateRegistration`; concrete registration record shape lands when the first methodology operator forces it.

### Vocabulary mechanism

- **Edge-tag application targets — practical enumeration and durability.** Whether all thirteen stored kernel relations *should* be edge-tag targets in practice (some may be noisy at scale). Whether edge-tag applications survive edge identity changes.

### Constraint language

- **Aggregate operator set.** `count`, `min`, `max`, `sum` is a starting cut. Concrete signatures and stratification rules land when first forced.
- **Path-sugar concrete surface syntax.** Shape settled; precise notation (arrows, brackets, slashes) lands at implementation.
- **Type-sum sugar concrete surface syntax.** Similar — `matches` plus pattern positions is the shape; exact tokenisation at implementation.
- **Performance characteristics and cost annotations.** Classes known to be expensive (unbounded recursion + negation, large cross-products). Cost annotations or runtime ceilings — defer until real plugins surface cost concerns.
- **Disjunction in rule bodies.** Standard Datalog admits multi-clause definitions as the disjunctive form. Whether to also admit inline disjunction (`p(X) :- q(X) ; r(X)`) is an ergonomics call; same semantics.
- **Violation-rendering with binding extraction.** Bindings-drive-reporting commitment settles *what* is reportable; concrete `message_template` substitution syntax (positional? named? typed?) lands with implementation.

### Open commitments deferred to architectural seams (§10)

- `.infra`, plugin registry, project-side composition (declarative form), renderer concrete shape, generics, Operation errors.

---

## 14. Edge-case stress — completed (quick scan)

The substrate held without modification on:

- **Recursive types** — `Composite(Named(C))` pointing to self or mutual Container; the `Type` vocab admits self-reference at the schema level without special-casing.
- **Distributed systems** — spec layer is one Container per concept; physical instances and transport are `.infra`.
- **Event-sourced** — kernel doesn't bias toward stored vs derived state; Field substrate is shape, not storage.
- **Multi-tenant (simple)** — shared structure + data partitioning is purely runtime.

Future-stress points captured in §10.6 and §10.7.

---

*See [VISION.md](./VISION.md) for framing and [DESIGN.md](./DESIGN.md) for the chapter-level technical specification this document grounds.*
