# Fukan — Design

**Status:** Application-design specification — design principles, build pipeline, canvas layering.

**Reading order:** Read [VISION.md](./VISION.md) first if you're new (motivation and framing). This document covers the canvas three-tier layering principle, the ownership-on-owner substrate principle, the build pipeline, the constraint language, and the project layer. [MODEL.md](./MODEL.md) is the authoritative substrate spec (kernel primitives, vocabulary mechanism, constraint language, projection mechanic). [DECISIONS.md](./DECISIONS.md) preserves the design-phase decision trace.

---

## Purpose

This document specifies the application-level design — the choices that sit *between* the substrate and the implementation:

- The **three-tier canvas layering** (`core` / `construction` / `vocab.*`) and per-tier inclusion rules
- The **ownership-on-owner substrate principle** — how Module ownership flows through `:module/child` Relations
- The **build pipeline** (Phase 0 canvas ingestion through Phase 6 Clojure analysis)
- The **constraint language** and how it sits over the substrate
- The **project layer mechanics** (idioms + constraints)
- The **Clojure lens** — code analysis (the `projects`/drift surface) and on-demand code-spec projection (`spec` / `instruct`)

Substrate-level content (kernel primitives, kernel relations, edge identity, vocabulary mechanism, constraint language, projection vocabulary, projection mechanic) lives in [MODEL.md](./MODEL.md).

---

## Canvas — three-tier layering

The canvas machinery lives in `src/fukan/canvas/` and is structured in three tiers with distinct inclusion rules. The rule of three governed each lift addition to `vocab.*`: every lift was justified by three or more corpus instances before shipping.

### Tier 1 — `core/`

The substrate machinery. Every canvas consumer depends on this tier.

| File | Contents |
|------|----------|
| `core/substrate.clj` | Six substrate primitives as Clojure records: `Module`, `Affordance`, `State`, `Type`, `Relation`, `Tag`. Zero architectural vocabulary. |
| `core/substrate/store.clj` | Datascript-backed store: schema, `->datoms` wiring, public query API. |
| `core/helpers.clj` | Construction helpers: `with-canvas`, `within-module`, `declare-affordance`, `declare-type`, `declare-relation`. |
| `core/defconstructor.clj` | `defconstructor` macro — form grammar + `produces` block DSL for defining vocabulary lifts. |
| `core/shape.clj` | Shape expression grammar: `parse` turns shape expressions into edn maps. |
| `core/check.clj` | `fc/check` — constraint runner + structured violation output. |
| `core/defquery.clj` | `defquery` — Datalog name-resolution extension mechanism. |

### Tier 2 — `construction.clj`

Non-opt-out construction primitives. Every canvas port uses at least some of these.

| Constructor | What it produces | When to use |
|-------------|-----------------|-------------|
| `function` | Affordance with arrow shape, role `:fukan.canvas.monolith/exposed-call` | Any synchronous cross-module callable |
| `record` | Type with `:record` kind and field pairs | Structured data types with named fields |
| `value` | Type with `:atomic` kind | Opaque named types (no exposed fields) |
| `exports` | Tags named declarations as `:exported` in the current module | Module API closure |

`exports` is a macro rather than a `defconstructor`-built lift because its body consists of bare positional names rather than form-grammar clauses — a deliberate special case for the closure mechanism.

### Tier 3 — `vocab/`

Opt-in methodology vocabularies. Require only the namespaces whose lifts your module uses.

| Namespace | Lifts | What it models |
|-----------|-------|----------------|
| `vocab.behavioral` | `invariant` | Named timeless behavioral commitments (produces Affordance with role `:canvas/invariant`, formal-expression carrying the `holds-that` clause) |
| `vocab.behavioral` | `rule` | Reactive declarations with trigger signatures (produces Affordance with role `:canvas/rule`, formal-expression carrying `{:when [...]}`) |
| `vocab.lifecycle` | `getter` | Zero-arg `Optional<T>` read accessors (baked-in arrow shape; produces Affordance with role `:canvas/getter`) |
| `vocab.validation` | `checker` | Validation entry points with the standard signature `(Model) -> [Violation]` (fully baked-in shape; produces Affordance with role `:canvas/checker`) |

**The rule of three was satisfied for every tier-3 lift before it shipped:**
- `invariant` — 13+ instances in Phase 1 pilots; well past threshold.
- `rule` — 49 deferred instances surfaced across broader porting in Sprint 2; shipped 2026-05-26.
- `getter` — appeared in every stateful module (`get_model`, `get_src`, `get_port`, etc.); shipped in Phase 2.
- `checker` — 7 instances in `validation/phase4` alone; shipped in Phase 2.

### Per-tier inclusion rules

| Tier | Who depends on it | What it may depend on |
|------|------------------|-----------------------|
| `core/` | Everyone | Nothing inside `canvas/` |
| `construction.clj` | All canvas ports | `core/` only |
| `vocab/*` | Canvas ports that opt in | `core/` only; never `construction.clj`, never each other |

Vocabularies are independent of each other by design. A canvas port that needs both `invariant` and `checker` requires both namespaces directly; neither needs the other.

---

## Shape expression grammar

Canvas construction forms (`function`, `record`, `getter`) accept shape expressions in their type positions. The grammar lives in `core/shape.clj`.

| Expression | Parsed kind | Example |
|-----------|-------------|---------|
| `:Keyword` (no namespace) | `:atomic` | `:String`, `:Integer`, `:Unit` |
| `:ns/Name` (namespaced) | `:ref` to a cross-module type | `:model/Model`, `:agent/Violation` |
| `(optional :T)` | `:optional` wrapping `:T` | `(optional :Integer)` |
| `(list-of :T)` | `:list` of `:T` | `(list-of :agent/Violation)` |
| `(set-of :T)` | `:set` of `:T` | `(set-of :String)` |
| `(sum-of :A :B ...)` | `:sum` of variants | `(sum-of :ParsedAllium :ParseFailure)` |
| `(map-of :K :V)` | `:map` with key and value types | `(map-of :String :Any)` |
| `(ref-to :ns/Name)` | `:ref` (explicit form) | `(ref-to :model/Model)` |
| `(record-of [:n :T]+)` | `:record` with inline fields | `(record-of [:id :String] [:name :String])` |

Cross-module references use the `:<module-name>/<TypeName>` convention. The module-name is the last path segment of the canvas module's ns (e.g. `canvas.model.spec` → `:model/...`). The projection layer resolves these by entity-name lookup in the unified Datascript db after all canvas modules have been built.

---

## Ownership-on-owner substrate principle

**Module ownership flows via `:module/child` Relations on the owner.** Owned entities — Affordance, State, Type — carry no back-reference to their owning Module. This principle was established in Phase 3 Sprint 3 and applies uniformly to all three entity kinds.

Rationale:
- Ownership is a property of the owner-child relationship, not a property of the owned entity itself. Storing it on the child creates two authoritative sources (the child's `:module` field and the parent's children collection), which can drift.
- The `within-module` helper in `core/helpers.clj` emits `:module/child` datoms automatically when any `declare-*` helper is called inside it. Canvas ports do not need to track parentage explicitly.
- The projection layer queries `:module/child` uniformly for all child kinds — one query instead of three per-kind queries.

Concrete implication: there are no `:affordance/module`, `:state/module`, or `:type/module` schema attributes in the Datascript store. To find which module owns an affordance, query the module that has it as a `:module/child`.

---

## Build pipeline

The pipeline runs six phases. Phase 0 (canvas ingestion) produces the initial model; Phases 4–6 validate, constrain, and project it.

```
Phase 0 — Canvas ingestion
  canvas-source/build-canvas-db: require all 62 canvas namespaces; call their
                                  build-canvas fns; merge per-module Datascript
                                  dbs into one unified db
  canvas-source/project:         project unified db to model map
                                  (Modules → :primitive/container nodes;
                                   Affordances → role-dispatched :primitive/rule
                                   or :primitive/operation;
                                   States, Types → :primitive/container;
                                   Relations → :edges vector)

  ── Initial model established ──
  Primitives keyed by stable string ids (module-name/entity-name convention).
  Legacy Allium/Boundary parse phases (1-3) are retired.

Phase 4 — Structural validation
  Sub-phases 4a–4g run sequentially; each aggregates violations.
  Gate G2: halt if any :error-severity violation exists.
  Reports aggregated violations in model :violations key.

Phase 5 — Constraint evaluation
  Methodology-shipped well-known constraints (signal_gap,
  external_must_have_wrapper) registered via defaults.
  Project-side constraints registered via project layer.
  All run; violations are non-gating outputs surfaced by explorer.

Phase 6 — Clojure Target Analyzer
  Walks source .clj files; projects spec primitives to Code.* Artifacts
  with per-edge :validity. Non-gating; missing projections appear as
  :absent drift edges.
```

**REPL workflow:** Edit a canvas file → `(refresh)` in the REPL → browser refresh. clj-reload detects the timestamp change, reloads the canvas namespace, and the next `(infra-model/refresh-model)` call re-runs Phase 0–6, picking up the new content automatically.

**New canvas file:** add a `require` entry in `canvas-source/canvas-builders` and call `(reset)` (not `(refresh)`) to pick up the new namespace.

### Canvas entity type → kernel primitive kind mapping

| Canvas `:entity/type` | `:affordance/role` | Projected `:kind` |
|----------------------|-------------------|-------------------|
| `:Module` | — | `:primitive/container` |
| `:Affordance` | `:canvas/invariant` | `:primitive/rule` |
| `:Affordance` | `:canvas/rule` | `:primitive/rule` |
| `:Affordance` | `:canvas/getter` | `:primitive/operation` |
| `:Affordance` | `:canvas/checker` | `:primitive/operation` |
| `:Affordance` | `:fukan.canvas.monolith/exposed-call` | `:primitive/operation` |
| `:Affordance` | nil / other | `:primitive/operation` |
| `:State` | — | `:primitive/container` |
| `:Type` (`:record`) | — | `:primitive/container` |
| `:Type` (`:atomic`) | — | `:primitive/container` |

---

## Core constraints

Every design choice in this chapter is derived from three constraints:

1. **Every concept a human would choose to inspect must be addressable.** If you cannot click on it, you cannot reason about it as a unit. This determines what is a kernel primitive.
2. **Every relationship a human would choose to follow must be traversable.** If a connection only exists in one party's prose, the model has no leverage. This determines what is a kernel relation or a relational tag.
3. **The artefact must reveal where intent and reality diverge.** If you cannot see drift, the workbench is a viewer, not an instrument. This determines what bridges layers.

The canvas vocabulary provides the vocabulary of intent. The Clojure lens — both reading existing code and projecting new code from the canvas — provides the vocabulary of reality. Fukan's job is to make both expressible in one Model with the bridge visible and bidirectional.

---

## The three boundary protocols (substrate-level design)

The following sections describe the kernel-level protocol semantics that the canvas substrate encodes. These protocol distinctions — View, Signal, Call — live in the substrate design (kernel relations, edge kinds) and are relevant to anyone extending the canvas vocabulary or writing constraints over the model. They are not the canvas authoring vocabulary — canvas ports use `function`, `invariant`, `rule`, `getter`, `checker` from the construction and vocab layers.

---

The boundary has three structurally distinct protocols. Each represents a different *protocol* by which information crosses the wall. They are not variations of the same thing; each connects to the behavioural core differently.

Each protocol lands on a kernel relation; the historical per-clause mapping the retired Allium vocabulary used is recorded in [MODEL.md §8](./MODEL.md#8-methodology-contributions-historical--alliumboundary-retired). This section covers the *protocol semantics* layered over those relations.

### Protocol summary

| Protocol | Kernel relation | Shape | Direction | What crosses | Behavioural-core involvement |
|---|---|---|---|---|---|
| **View** | `exposes` | passive | party ← system | Data the party can see | None — reads do not change state |
| **Signal** | `provides` | event (no return) | party → system | Named stimuli the party emits | Should have a subscribing Rule; absence is a detectable gap |
| **Call** | `realises` / `uses` (Operations) | typed function | bidirectional | Invocations with arguments and return value | Implementation may use Rules, may not — internal concern |

### View — passive read

A View declares that a party can *see* some data; the act of viewing changes nothing and no Rule fires. The substrate landing is an `exposes: Container → Field` kernel edge (R20), with the Field reached as a `SubstrateAddress` on its owning Container. The structural fact is the kernel edge; no Rule involvement.

### Signal — event-shaped stimulation

A Signal declares a named action a party can perform — fire-and-forget, no return value; effects are observable through subsequent reads. The substrate landing is a `provides: Container → Event` kernel edge (R20).

A Container with an outgoing `provides → Event E` edge but no `triggers: E → Rule` edge anywhere in the model is a **detectable gap** — a boundary advertises an action that nothing handles. This is the canonical motivating example for the R20 lift: with `provides` as a kernel relation, the gap is expressible directly as `provides(S, E) ∧ ¬∃R. triggers(E, R)`, traversed once over kernel edges rather than via tag-namespace walks.

Rules triggered by typed-subject conditions (entity created, field transitions, etc.) do not produce or consume Events. They land as `Rule —observes→ Container/Field` edges. These are internal triggers — they have no boundary involvement at all.

### Call — function-shaped invocation

A Call is a typed invocation across the wall — parameters in, typed result out. The substrate landing is an Operation on a Boundary, with the providing/consuming sides connected by `realises` / `uses` edges. In the canvas vocabulary this is what a `function` declares: a typed callable on a module's API.

A Rule internally invoked by an Operation's implementation is *not* a structural edge in the model unless `triggers: Operation → Rule` (kernel relation R4) is declared. The connection to actual implementation appears via `projects` edges from canvas declarations to `Code.*` artifacts (per [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments)).

### Asymmetric Rule requirements

Stated explicitly because the asymmetry matters:

| Protocol | Rule required? | What absence means |
|---|---|---|
| View (`exposes`) | No | Nothing — reads do not trigger work |
| Signal (`provides`) | Should — not enforced | **Detectable gap** worth surfacing in fukan |
| Call (Operation) | Possibly, possibly not | Implementation may be pure computation, behaviour-driven, or a mix |

Rules can also fire **without any boundary involvement**. The trigger taxonomy splits along whether the trigger is a *named event* or a *typed-subject condition*:

| Flavour | Kind | Substrate landing |
|---|---|---|
| External stimulus → boundary `provides` | named event | `provides: Container → Event` (R20) arrives at the boundary; `Event —triggers→ Rule` fires inside |
| Chained emission from another rule | named event | `Event —triggers→ Rule` |
| Entity creation | typed-subject | `Rule —observes→ Container` |
| State transition | typed-subject | `Rule —observes→ Field` |
| State becomes | typed-subject | `Rule —observes→ Field` |
| Temporal (field crosses `now`) | typed-subject | `Rule —observes→ Field` |
| Derived condition flips | typed-subject | `Rule —observes→ Field` |

**Wall-clock periodic triggers (cron-style scheduling) fit row 1** — external stimulus. The scheduler is an external Actor; a boundary `facing` that Actor `provides` named scheduled Events (e.g., `NightlyMaintenance`); Rules trigger on those Events through the ordinary `Event —triggers→ Rule` path. The substrate is identical to any other external-stimulus trigger — no new kernel relation, no new trigger kind. The schedule expression itself (`0 9 * * MON`) is Infra-altitude content, landing in `.infra` when it arrives. The "temporal" typed-subject row above is reserved for **data-driven** time — a stored Field crossing `now`; periodic wall-clock cadence has no subject Field and does not belong there. Signal-protocol gap detection still surfaces "scheduled Event with no consumer" automatically.

Internal-only Rules (named or typed-subject) are common and supported. The Model must not assume every Rule has a boundary entry point.

---

## CRUD mapping

The boundary protocols compose cleanly into typical CRUD patterns:

| Operation | Protocol | Path through the Model |
|---|---|---|
| **Create** | Signal | `provides → CreateOrder` Event → Rule that creates the Order |
| **Read** (simple) | View | `exposes` edge to a Field on a Container → no Rule |
| **Read** (complex / aggregate) | View (computed) or Call | Derived value on a Container, OR an Operation returning the result |
| **Update** | Signal | `provides → UpdateOrder` Event → Rule that writes the field |
| **Delete** | Signal | `provides → DeleteOrder` Event → Rule that destroys the Order |
| **Search / lookup** | Call | Operation `search: (query) -> [Result]` |

The asymmetry: **mutations are event-shaped (Signal); reads are passive (View) or call-shaped (Call) but never event-shaped**. This is a property of the rule model — Rules produce *effects on state*, not return values. Anything that needs to return a value either makes the value visible via `exposes` (and lets the party read it) or lives in an Operation whose typed signature has a return type.

---

## The three spec altitudes (substrate design context)

This section describes the altitude model that shaped the substrate design. In the canvas-first architecture, canvas specs produce content at these altitudes via the canvas vocabulary lifts. The `.allium`/`.boundary` spec languages that originally authored content at these altitudes are retired; this framing remains relevant as substrate context.

Three spec altitudes, committed in [MODEL.md §11](./MODEL.md#11-substrate-commitments): **Behaviour** (highest, most abstract — what happens), **Structure** (the scaffolding behaviour enfolds over — Operations, Boundary, composition), **Infra** (deployment commitments). Implementation isn't a fourth altitude — it's the projection across all three (Code | Infra-Artifact | Documentation flavours).

### Altitudes and the reference rule

Canvas specs produce content at these altitudes via the vocabulary lifts: `invariant` / `rule` at Behaviour; `function` / `getter` / `record` / `value` / `exports` at Structure; Infra-altitude content awaits the deferred `.infra` layer (below).

**The altitude-reference rule** (strict one-up, no skipping, no downward): each altitude references only the altitude immediately above. Behaviour content cannot reference Structure (downward is forbidden); Structure references Behaviour upward (e.g. an Operation bound to the Rule it invokes); Infra references Structure upward. Substrate primitives (Container, Field, Actor) are altitude-spanning — referenced from any altitude.

> **Historical note.** The altitude model was originally authored across two retired spec languages — `.allium` (Behaviour + partial Structure: Rules, Events, Invariants, Surfaces, Contracts, Operations) and `.boundary` (Structure-altitude binding + composition: the `fn` callable, `exports` closure, `subsystem` grouping). Both are archived in `.legacy-allium/`; the canvas vocabulary lifts now author all of it — `function` / `exports` cover what `.boundary` handled, `invariant` / `rule` / `record` / `value` cover Allium's. The construct→kernel mappings are in [MODEL.md §8](./MODEL.md#8-methodology-contributions-historical--alliumboundary-retired) and git history; the reasoning trace in [DECISIONS.md](./DECISIONS.md).

### `.infra` responsibilities — deferred but pre-positioned

`.infra` is the Infra-altitude spec layer. It owns *declarative commitments* about deployment:

- Endpoint specifications (path, method, request/response schemas, status codes)
- Storage declarations (table/collection structure, indexes, constraints)
- Wire formats (codec choices at specific points)
- Transport bindings (HTTP, gRPC, AMQP, etc.)
- Deployment topology (services, queues, caches)
- Network / auth policies

Lands as Containers tagged `Infra::*` (Service / Endpoint / Storage / Channel / Policy), via the same tag-overlay mechanism any vocabulary uses to refine the substrate. Tag payloads carry kind-specific fields and **upward references to Structure-altitude content** (Operations) per the altitude-reference rule.

**Tied to this seam.** The `Infra(Endpoint | Resource)` cases of the Artifact ontology and the `endpoint`, `resource` projection_kind values come back together with `.infra` (deferred from V0 — [MODEL.md §7.2](./MODEL.md#72-v0-artifact-ontology), [§10.5](./MODEL.md#105-future-projection-vocab-additions)). They represent *observed deployed reality* — real services, real network endpoints reported by a future live-cluster analyzer. Infra-spec `projects` to Infra-Artifacts when both exist (drift detection at Infra altitude). The spec/Artifact axes are independent ([MODEL.md §11](./MODEL.md#11-substrate-commitments)).

The architectural seam is open; no Model substrate changes are needed today to accommodate it.

---

## Build pipeline (design context)

The live pipeline is documented above under [Build pipeline](#build-pipeline): Phase 0 (canvas ingestion) followed by Phases 4–6 (structural validation, constraint evaluation, Clojure analysis). Canvas ingestion is deterministic data-loading, not parsing.

The original design ran a different shape: three independent Phase-1 parsers (`.allium`, `.boundary`, Clojure), a Phase-2 cross-reference resolution, a Phase-3 merge, then post-merge structural validation (sub-phases 4a–4g — composition, event, binding, module/subsystem visibility, export closure, cross-module reference visibility) and constraint evaluation, with two halt gates (G1 before merge, G2 before constraints) so problem attribution stayed clean. Those parse/resolve/merge phases and the `.boundary`/`.allium`-specific validation catalogue retired with the spec languages; the detail is preserved in git history (commit `af4885d`).

Two general principles carried forward into Phases 4–6: **aggregate all violations within a phase before advancing** (so a fix-then-re-run loop on cosmetic issues is avoided), and **`severity ∈ error | warning`** where errors block and warnings never do.

---

## Project layer

The canvas vocabulary is deliberately minimal and architecture-neutral: a `function` or an `invariant` says *what* a module exposes and commits to, not *how* this project renders it in code. Two teams can author the same system with the same vocabulary and still make different rendering choices — malli vs. spec, property tests vs. example tests, where ids get their shape.

The project layer is where a project makes those choices explicit — which idioms it applies to which situations, and how canvas declarations materialise as concrete code in the target language:

> "Records render as `(def ^:schema Name [:map …])` malli schemas, not defrecords."
>
> "`Money` renders as `[:and :int [:>= 0]]`; entity ids are tagged uuid shapes."
>
> "For `invariant` projections, use property-based testing with `clojure.test.check`."
>
> "Functions are `kebab-case`; schema vars are `PascalCase`."

Each entry names a situation and the way it is applied — optionally with a machine-checkable expression or a target-language rendering. The layer serves three audiences from the same content:

- **Human readers** — orientation: how does this project use the canvas vocabulary, and how do those choices land in code?
- **Implementing LLMs** — context: which patterns should new canvas content or generated code conform to? Project idioms feed the lens projection that `(spec …)` and `(instruct …)` render.
- **The build pipeline** — verification: does the Model conform? Do code artifacts match what the canvas projects?

The primary purpose is making the project's design vocabulary explicit. Validation and generation are useful consequences of having made it explicit.

### Position

The project layer is **not** a peer vocabulary at a different altitude. The canvas declares what the system is; the project layer describes how this project chooses to render those declarations in target-language code. The relationship is annotation, not peerage.

The layer carries **two sub-loci**, both queryable on the live surface ([AGENTS.md](../AGENTS.md)):

1. **Idioms** (`(idioms)`) — projection inputs: address-resolution knobs, type-translation overrides, and per-primitive-kind / per-projection-kind / per-address-match rendering patterns. All variants of one mechanism: "how kernel concept X projects concretely in this project, in this target language." Consumed by the lens projection (Implementation linkage section below) when it assembles a `(spec …)` for an element.
2. **Constraints** (`(constraints)`) — `PredicateRegistration` entries ([MODEL.md §5.3](./MODEL.md#53-predicate-registrations)) in the single constraint language ([MODEL.md §6](./MODEL.md#6-the-constraint-language)). Range from soft preferences (`severity = warning` — naming styles, project idioms) to hard architectural laws (`severity = error` — module isolation, layering). Same registration shape as vocabulary-shipped constraints; the locus differs, not the language.

For fukan-on-fukan both are currently empty — the system runs on the Clojure extension's defaults and ships no project-side overrides yet.

What's *not* in the project layer:

- **External-system enrichment** is not a project-layer concern. Externality is a tag on a Type, not project-side configuration (per [MODEL.md §3.6](./MODEL.md#36-derived--not-kernel-primitives)); non-entity externals (services, storage, libraries) are deferred — see [MODEL.md §9.2](./MODEL.md#92-external-system-container). When the deferral closes they land structurally, not as config.
- **Module-scoped architectural rules** are constraints scoped (via `TagScope`) to a module or its `exports` closure — the same `PredicateRegistration` shape, narrower scope.

### Projection inputs — one mechanism, contextual selection

Address-resolution, type-translation, and idioms are not separate categories. They are all instances of "how does kernel X project concretely in this target." The project layer has one projection-input bucket; different *content* lives at different sub-routes, but registration is uniform.

| Sub-route | Examples |
|---|---|
| Address-resolution | Root-prefix knob (canvas module coord → target-namespace prefix, e.g. `model.vocabulary` → `fukan.model.vocabulary`); kind-sensitive transliteration overrides |
| Type-translation overrides | `:Money` → project's Money rendering; `:OrderId` → project's id-shape |
| Per-primitive-kind idioms | "for `function` projections, follow this handler pattern"; "for Types, prefer X" |
| Per-projection-kind idioms | "for `invariant+property-test` projections, use `clojure.test.check`"; "for `rule` projections, prefer pure functions" |
| Per-address-match idioms | Patterns matching specific addresses or modules — narrower applicability |

The lens projection (Implementation linkage section below) selects applicable entries by matching the current `(primitive-kind, projection-kind, address-match)` against each entry's routing predicate. Multiple matching entries compose; conflict-resolution mechanics defer to forcing examples.

Defaults ship with the Clojure lens; the project layer overrides per-project. The same shape is the seam for future target-language lenses (TypeScript, Java) without substrate change.

### Constraints — one registration shape across loci

The constraint language is single. Three authoring loci share one registration shape:

| Locus | Default scope | Owner |
|---|---|---|
| Vocabulary extension | Model-wide (or `TagScope` against the vocabulary's marker tag) | Travels with the vocab lift |
| Project layer | Model-wide (or `TagScope` against any tag the project chooses) | Persisted in the project |
| Module-scoped | `TagScope` against a module or its `exports` closure | Registered against the module |

Severity is per-registration (`error | warning`). Constraints from any locus surface as violations with severity (queryable via `(violations :severity …)`; the fukan-on-fukan canvas currently reports zero). Fukan-shipped well-known constraints (per [MODEL.md §10.3](./MODEL.md#103-the-project-layer--sub-loci-and-composition)) — e.g. `no_dependency`, `no_circular_refs`, `naming_convention` — are available for projects to register and for module-scoped rules to parameterise without re-authoring. The trust-tier integrity and coverage checks (`(integrity)`, `(canvas-coverage)`) are a parallel, built-in surface over the same Model.

### Surfacing

```
┌─────────────────┐
│  Idioms         │  fed into
│  (project layer)│ ──────────► Lens projection  (spec id)
└─────────────────┘                       │
                                          ▼
                                 ┌─────────────────┐    consumed by
                                 │  Projection     │ ──────► implementing LLM
                                 │  {:target       │          (via instruct +
                                 │   :template     │           scenario)
                                 │   :prose        │ ──────► drift check
                                 │   :context}     │          (verification)
                                 └─────────────────┘
                                          │
                                          │ projects edges carry validity
                                          ▼
                                 ┌─────────────────┐
                                 │  per-edge       │  surfaced by
                                 │  validity       │ ──────► (canvas-drift)
                                 │  on projects    │          (coverage)
                                 │  edges          │          → drift markers
                                 │  (valid/absent/ │            in the explorer;
                                 │   stale/unknown)│            close-drift loop
                                 └─────────────────┘

┌─────────────────┐
│  Constraints    │  read
└─────────────────┘ ──────────► Model
        │
        │ produce
        ▼
┌─────────────────┐
│  Violations     │  surfaced by
│  (errors /      │ ──────────► (violations); explorer
│   warnings)     │              sidebar markers
└─────────────────┘
```

The two sub-loci surface differently. Constraints produce discrete violations with severity. Idioms have no severity — their effect manifests as per-edge `validity` on `projects` edges ([MODEL.md §4.2](./MODEL.md#42-per-relation-semantics)), rendered in the explorer as red drift markers on canvas declarations whose canonical address is missing (`absent`) or has diverged (`stale`). An `absent` drift marker is the entry point for on-demand code generation — `(instruct …)` composes the element's `(spec …)` projection with a scenario into a full instruction the implementing LLM consumes.

### Architectural style enforcement

The role architectural styles played in earlier ADL work — UniCon's connector typing, Hex's Core/Adapter discipline, DDD's layering rules, C4's dependency constraints — is a **constraint** concern in fukan, not a kernel concern. The kernel stays style-neutral; styles are sets of constraints registered through the project layer (project-wide) or scoped to a module (per the loci table above). A Hex rule like "no Rule in a `Hex::Core` Container writes to a `Hex::Adapter` Container" is one constraint registration; a project that adopts Hexagonal Architecture is the project that registers that constraint set. No new substrate primitive (`ConnectorType`, `StyleDefinition`) is introduced for this — the constraint language plus the tag-presence introspection surface (MODEL.md §6) is sufficient.

This is the same direction the substrate-vs-vocabulary force-and-gate (MODEL.md §3.7) takes: a feature lives at substrate altitude only when its shape is uniform across the cases that engage it. Style-typed composition rules vary widely across methodologies — Hex's port/adapter typing, DDD's bounded-context rules, layered architecture's directional dependencies — they share the *shape* "tag-conditioned constraint over kernel relations" but not the *vocabulary*. That's why they live in the project layer, parametrised per methodology.

### Coupling — explicit

The project layer reads the Model and configures the projection mechanic; the Model does not know about the project layer. This is the only direction. Constraint violations and projection-input-driven drift markers are both *annotations over the Model*, not *content of the Model*.

### Forward compatibility

The Clojure lens's projection inputs (address-resolution, type-translation, idioms) are the seam for future target languages — a TypeScript lens would register its own defaults under the same shape, with no substrate change. The same goes for future vocabularies adding domain-specific constraints (DDD layering rules, Hex port-adapter discipline) — same `PredicateRegistration` shape; new namespaces.

The composition mechanics that let methodology-shipped idiom bundles override project-shipped idioms (and vice versa), and the multi-profile / severity-override / versioning machinery, defer per [MODEL.md §10.3](./MODEL.md#103-the-project-layer--sub-loci-and-composition). The single-shape registration commitment makes those additions purely additive.

---

## Implementation linkage — the Clojure lens

Canvas declarations and Clojure code meet through two directions over the same convention, with no code-side annotations and no out-of-band binding files:

- **Analyzer direction (code → model).** The build pipeline reads Clojure source, emits `Code.Function` and `Code.DataStructure` artifacts, and draws `projects` edges from canvas declarations to those artifacts with per-edge `:validity` (`:valid` / `:stale` / `:absent` / `:unknown`). This is the drift surface — `(canvas-drift)`, `(coverage)`, and `(drift)` read these edges. Code aligns with the canvas, or fukan flags the gap.
- **Lens direction (model → code).** For a given canvas element, the Clojure lens projects a *code spec* on demand — the deterministic target shape an implementing LLM should land. `(spec <stable-id>)` returns it.

Substrate-level commitments (which primitives project, what each projection kind means, drift semantics, the no-direct-projection rule for interface-only Containers) live in [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments) / [§7.7](./MODEL.md#77-the-target-language-extension--analyzer-and-projector). This section covers the application-design choices: convention-driven addressing, type translation, the projection shape, scenario-composed instructions, and enforcement.

### The lens projection (Layer A)

`(spec <stable-id>)` projects a canvas element through the active Clojure lens and returns a structured map:

| Field | What it carries |
|---|---|
| `:model-element-kind` / `:model-element-id` | the canvas element being projected (`Type`, `Rule`, `function`, …) |
| `:projection-kind` | the lens dispatch chosen (e.g. `clojure/type-to-malli`) |
| `:lens-id` | the active lens (`clojure`) |
| `:target` | `{:path :namespace :symbol}` — the canonical code address |
| `:template` | the deterministic code the LLM should land |
| `:prose` | the canvas declaration's doc — semantic intent |
| `:context` | source refs and shape metadata (canvas file, field count, …) |

A real projection — the `ServerOpts` record in `canvas/infra/server.clj`:

```clojure
(spec "infra.server/type/ServerOpts")
;; => {:projection-kind "clojure/type-to-malli"
;;     :lens-id "clojure"
;;     :target {:path "src/fukan/infra/server.clj"
;;              :namespace "fukan.infra.server"
;;              :symbol "ServerOpts"}
;;     :template "(def ^:schema ServerOpts
;;                  [:map {:description \"HTTP server configuration.\"}
;;                   [:port {:optional true} :int]])"
;;     :prose "HTTP server configuration."
;;     :context {:canvas-source-ref "canvas/infra/server.clj" :field-count 1 …}}
```

`(canvas-projections)` lists the registered Layer-A dispatches — one `defmethod` per `[lens-id dispatch-key]` pair: `Type/atomic`, `Type/record`, `canvas/event`, `canvas/getter`, `canvas/handler`, `canvas/invariant`, `canvas/invariant+property-test`, `canvas/checker`, `canvas/rule`, and `fukan.canvas.monolith/exposed-call`. A new target language registers its own lens under the same contract; a new projection kind drops in as an additional dispatch.

### Convention-driven addressing

A project sets one knob — the **Clojure root-namespace prefix** relative to canvas module coordinates (`fukan.` for fukan-on-fukan; empty when the layout is identity). All other mapping is mechanical:

- **Module coordinate → namespace.** Canvas module `model.vocabulary` → `fukan.model.vocabulary`.
- **Identifier transliteration is kind-sensitive.** Type-shaped projections (records, values, events) preserve **PascalCase** (`ServerOpts` → `ServerOpts`). Function-shaped projections (functions, getters, checkers, rules, invariants, handlers) go **snake/Pascal → kebab-lower** (`has_tag_with_ancestors` → `has-tag-with-ancestors`).

So canvas `function has_tag_with_ancestors` at module `model.vocabulary` projects to `fukan.model.vocabulary/has-tag-with-ancestors`; a `record ServerOpts` projects to the `ServerOpts` schema var in `fukan.infra.server`.

### Type translation

The Clojure lens renders substrate `Type` cases as malli. Records project to a top-level `(def ^:schema Name [:map …])` — a malli *value*, not a defrecord, so the schema is itself data and field-shape drift is detectable (`(canvas-drift)` runs a record shape-drift check comparing each canvas record's `:type/fields` against the code-side schema's fields, after PascalCase↔lowercase normalisation). Default renderings cover the shape-expression grammar:

| Shape expression | Default malli rendering |
|---|---|
| `:String` / `:Integer` / `:Boolean` / … (atomic) | `:string` / `:int` / `:boolean` / … |
| `(optional :T)` | field marked `{:optional true}` |
| `(list-of :T)` / `(set-of :T)` | `[:vector …]` / `[:set …]` |
| `(map-of :K :V)` | `[:map-of …]` |
| `(sum-of :A :B …)` | `[:or …]` |
| cross-module `:ns/Name` | reference to that element's schema var |

Vocabularies and projects override per scalar name (`:Money` → a project-specific malli sub-schema; `:OrderId` → the project's id shape) as project-layer idiom entries. A missing translation is a structural error — the lens cannot complete the projection. The same registry shape is the seam for other target-language lenses (a TypeScript lens would render `:String` → `string`, `(list-of :T)` → `T[]`, …) with no substrate change.

### Scenario composition (Layer B)

The `(spec …)` projection is the *what*. A **scenario** supplies the *framing* — why this code is being written and what style anchors apply. `(instruct <finding-or-id> <scenario-id>)` composes a Layer-A projection with a Layer-B scenario into a full instruction (`:code-spec`, `:scenario-context`, `:rendered` markdown) an implementing LLM consumes directly. `(canvas-scenarios)` lists the registered scenarios:

- **`:code-side/drift-close`** — close a gap drift surfaced; the canvas is the design, the target file is where the missing definition lands, the file's existing siblings are the style anchor.
- **`:code-side/cold-write`** — write a module's implementation from scratch; convention plus matching neighbours supply the style.

This composition is the on-demand generation path: an `:absent` projection is the entry point, `(instruct …)` renders the instruction, the implementing LLM lands the code, and the next refresh re-evaluates the `projects` edge.

### Drift, coverage, and the close-drift loop

The analyzer direction's `projects` edges drive a built-in trust-tier surface:

- `(canvas-drift)` — every canvas declaration whose code-side counterpart is missing or shape-drifted; each finding names **both sides** (canvas stable-id + expected code path + expected symbol) so the reader can weigh whether canvas or code should move. Findings are `:warning` — discrepancy is fact, resolution is judgment.
- `(coverage)` — spec→code coverage for public functions. (Today: 314 public functions, ~55 % covered, 414 absent edges — the canvas describes more than the code yet realises.)
- `(close-drift-plan …)` / `(close-drift-verify …)` / `(close-drift …)` — the orchestrated closure loop: plan a per-finding instruction (grouped by target file), dispatch implementing-LLM work, re-run drift to classify outcomes.

### Strict enforcement

Exactly one definition per projecting canvas declaration must exist at its canonical address; multiple definitions at the same address is a lint error. The discipline is the point: canvas authority depends on one canonical address per declaration. "Work scattered across helpers" is intentionally **not** mechanically policed — helpers appear as unprojected `Code.Function` artifacts (visible, bound to no canvas declaration), and the canonical entry's `:validity` is sufficient signal. Whether to fold helpers into the canonical entry is editorial.

### Couplings

The convention presupposes one-canvas-module ↔ one-Clojure-namespace discipline (modulo root prefix). Cross-module placement (a canvas module implemented in a non-matching namespace) is not supported — refactor the layout. Code matching no expected address appears as an unprojected artifact: visible in the model, bound to no canvas declaration.

---

## Explorer / projection layer

### Edge filtering

The explorer surfaces the kernel's thirteen relations (`triggers`, `observes`, `reads`, `writes`, `creates`, `destroys`, `emits`, `realises`, `specialises`, `uses`, `exposes`, `provides`, `projects`) plus tag-applied projections (e.g., views scoped to one vocabulary namespace, or relational tags from [MODEL.md §5 V12](./MODEL.md#5-the-vocabulary-mechanism)). The kernel surface is fixed; *which* relations a given model populates depends on its canvas. fukan-on-fukan — a functional-core monolith of pure functions — mainly exercises `uses` (type/reference dependencies) and `projects` (the Clojure analyzer's code linkage); the behavioural relations (`triggers`, `observes`, `emits`, the effect edges) are exercised by reactive/event-driven canvases such as the `event_driven` demo. `(vocabulary)` reports each relation-kind with an `:in-use?` flag so a reader can distinguish kernel surface from what this model observes.

Concrete UI grouping is design TBD — likely a small set of conceptual groupings (causation, data flow, boundary, cross-altitude) rather than nine individual switches. Single-mode-at-a-time toggle works as today; multi-select (show two groupings at once, with visual distinction) is a polish improvement.

### Visibility-aware projections

A module's `exports` declaration (the `exports` lift) names its public closure. The explorer respects it:

- **Internal view** (inside the module) — every declaration visible.
- **External view** (the module seen from outside) — only the `exports`'d declarations visible.
- **Default** — match the user's vantage point in the navigation tree.

`(canvas-coverage)` surfaces the gaps this visibility implies — exports nobody consumes, modules with no `(exports …)` declaration at all.

### Drift markers

`projects` edges carry drift signal in MVP via per-edge `validity ∈ 'valid' | 'absent' | 'stale' | 'unknown'` ([MODEL.md §4.2](./MODEL.md#42-per-relation-semantics)). The projection layer renders this: green for `valid` (artifact exists at expected address), red for `absent` (missing implementation or missing test), neutral for `unknown` (not yet evaluated). Finer-grained `stale` checks (e.g., malli-schema field shape vs spec entity fields) hook in additively without further projection-layer work. When `.infra` arrives, the same machinery extends to Infra Artifact drift without changes.

### Sidebar content per kernel primitive

Each kernel primitive carries richer content than today's Function/Schema nodes. Sidebars are populated by reading the kernel substrate, the kernel relations, and the tag applications attached to each primitive. The kernel groups primitives by **face-role** (`(vocabulary)` reports the role per kind), which shapes what a sidebar shows:

- **Container** (`face-host`) — a module or other host. Child counts by kind, the `exports` closure (public API, with internal declarations visually distinct), declared invariants and rules, derived metrics. Where it owns children, the `:module/child` relations.
- **Type — record / value** (`face-component`) — fields with their shape expressions, incoming `uses` references (who depends on this type), and the `projects` edge to its code-side schema with `:validity`. For records, the malli-schema projection (`(spec …)`) and any field shape-drift `(canvas-drift)` reports.
- **Affordance — function / getter / checker** (operation-role, `face-component`) — the `takes` / `gives` signature, the canonical code address and `:validity`, the applicable project-layer idiom, and incoming `uses`.
- **Affordance — rule / invariant** (rule-role, `face-component`) — for an `invariant`, its `holds-that` clause and (where projected) its `invariant+property-test`; for a `rule`, its `when`-trigger signature and, in a behavioural canvas, the outgoing effect edges (`writes` / `creates` / `destroys` / `emits`).
- **Operation** (`face-component`) — parameters, return type, the Boundary (and Container) it sits on.
- **Event** (`face-peer`) — parameters; in a behavioural canvas, emitters (incoming `emits` from Rules) and consumers (incoming `triggers`).
- **Actor** (`face-peer`) — its identifying / contextual attributes and the interfaces it acts on (computed).
- **Any projecting declaration** — outgoing `projects` edges with `:projection-kind` and `:validity` per edge; drift markers (red for `:absent`, green for `:valid`, neutral for `:unknown`) inline. This is the `(canvas-drift)` finding made visual.

This is the largest UX surface area change.

---

## Couplings — explicit

Application-level couplings the design pushes onto adjacent decisions. Substrate-level couplings (Event identity, edge identity, relation endpoint shapes, projection vocabulary scope) live in MODEL.md's decisions log and substrate commitments.

1. **Single-owner module ownership.** Ownership flows via `:module/child` relations on the owner (the ownership-on-owner principle); a declaration has one owning module. Multi-membership is intentionally unsupported.
2. **Project-layer scope.** Project-wide entries (idioms + constraints) register against the shared mechanism; narrower constraints scope (via `TagScope`) to a module or its `exports` closure. The split is by *scope*, not mechanism. All are read-only annotations over the Model or inputs consumed by the lens projection.
3. **No filesystem inference.** Module structure is declared in canvas (`within-module`), not inferred from file layout. Topology is a design surface, not a side effect of where files sit.
4. **Code linkage resolves by convention.** Every canvas declaration's code address resolves via mechanical name resolution against the Clojure lens's addressing rules plus project-layer idioms (one root-prefix knob + kind-sensitive transliteration). No code-side annotations; no out-of-band binding files. Strict enforcement — single canonical address per declaration; multiple matches or scatter is a lint violation. See the Implementation linkage section above.

---

## Open questions

Application-design questions not resolved in this chapter. Substrate-level TBDs live in [MODEL.md §13](./MODEL.md#13-tbds-consolidated).

1. **Stale-shape comparator depth.** `:absent` drift is fully wired; `:stale` runs a record field shape-drift check today (`(canvas-drift)`). How far the comparator should go — function signatures, idiom conformance, narrower aspects of code vs. the lens template — is open, and hooks in additively on the same `projects`-edge substrate.
2. **Project-layer authoring locus.** Idioms and constraints are queryable (`(idioms)`, `(constraints)`) but fukan-on-fukan registers none. The on-disk authoring shape for project-side overrides — likely an EDN file at the project root with sections for address-resolution, type-translations, idioms, and constraint registrations — is unspecified. Concrete shape lands when a project first needs an override.
3. **Module-API rendering.** When a reader views a module from outside, what does the sidebar show — the full `exports` closure, an aggregated summary, or both? Likely both, but the relative emphasis matters for usability.
4. **Interactive generation UX.** The non-interactive path is in place (`(instruct …)` → implementing LLM → `(close-drift-verify …)`). The interactive *browser* affordance — clicking an `:absent` drift marker to summon the projection, diff preview, accept/reject — is part of the deferred explorer rewrite (VISION.md).

---

## Summary

The application design layers cleanly onto MODEL.md's substrate. The canvas — three-tier layered Clojure (`core` / `construction` / `vocab.*`) — is the design surface; its vocabulary lifts (`function`, `record`, `value`, `exports`, `invariant`, `rule`, `getter`, `checker`) populate the kernel primitives, and module ownership flows via `:module/child` on the owner. Three boundary protocols (View / Signal / Call) connect boundary relations to behavioural content asymmetrically — mutations event-shaped, reads passive or call-shaped. Three altitudes — Behaviour, Structure, Infra — with strict one-up reference; Implementation is projection across them, with `.infra` still architecturally seamed.

Code linkage runs in two directions over one convention: the analyzer draws `projects` edges with `:validity` (the drift surface — `(canvas-drift)`, `(coverage)`), and the Clojure lens projects each element to a code spec on demand (`(spec …)`, composed with a scenario by `(instruct …)` for implementing LLMs). A project layer carries two sub-loci — idioms (`(idioms)`) and constraints (`(constraints)`) — both making the project's design vocabulary explicit for human readers, implementing LLMs, and the build pipeline. The explorer respects `exports` visibility, surfaces gaps, and renders drift markers with the close-drift loop behind them.

Each addition is justified by a constraint that admits no other clean solution; each deferral preserves the architectural seam for later. The system runs canvas-plus-code end-to-end in both directions (analysis + generation); subsequent chapters add layers (`.infra`, more vocabularies, more target-language lenses, project-layer composition mechanics) without rewriting the substrate.

---

*See [VISION.md](./VISION.md) for the framing, motivation, and roadmap. See [MODEL.md](./MODEL.md) for the substrate spec.*
