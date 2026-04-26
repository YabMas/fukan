# Fukan — Next Chapter Design

**Status:** Application-design specification — the *what* of the next chapter, sitting between framing and substrate.

**Reading order:** Read [VISION.md](./VISION.md) first if you're new (motivation and the spec-graph-of-the-system framing). This document covers boundary protocols, altitudes, build pipeline, project layer, and the Clojure analyzer. [MODEL.md](./MODEL.md) is the authoritative substrate spec (kernel primitives, vocabulary mechanism, constraint language). [DECISIONS.md](./DECISIONS.md) preserves the design-phase decision trace (K\*/R\*/V\*/C\*/P\* identifiers cited throughout).

---

## Purpose

This document specifies the application-level design — the choices that sit *between* the substrate and the implementation:

- The three Allium boundary protocols — View, Signal, Call — and their connection to behavioural content
- The three spec altitudes — Behaviour, Structure, Infra — and the files (`.allium`, `.boundary`, `.infra`) that produce content at each
- The build pipeline shape and what filesystem-inferred structure stops being authoritative
- The project layer mechanics
- What changes in the projection and view layers

Substrate-level content (kernel primitives, kernel relations, edge identity, vocabulary mechanism, constraint language, Allium→kernel mapping, the realisation/projection schema) lives in [MODEL.md](./MODEL.md). A concrete phase-by-phase implementation plan will be written separately once the design is finalised.

---

## Core constraints

Every design choice in this chapter is derived from three constraints:

1. **Every concept a human would choose to inspect must be addressable.** If you cannot click on it, you cannot reason about it as a unit. This determines what is a kernel primitive.
2. **Every relationship a human would choose to follow must be traversable.** If a connection only exists in one party's prose, the model has no leverage. This determines what is a kernel relation or a relational tag.
3. **The artefact must reveal where intent and reality diverge.** If you cannot see drift, the workbench is a viewer, not an instrument. This determines what bridges layers.

The Allium grammar provides the vocabulary of intent. Code analysis (later) provides the vocabulary of reality. Fukan's job is to make both expressible in one Model with the bridge visible.

---

## The three Allium boundary protocols

Allium gives the boundary three structurally distinct clauses (`exposes`, `provides`, `contracts: demands/fulfils`). Each represents a different *protocol* by which information crosses the wall. They are not variations of the same thing; each connects to the behavioural core differently.

For the substrate landing of each clause (which kernel primitive, which kernel relation, which Allium-namespace tag), see [MODEL.md §8.1 — Allium → kernel mapping](./MODEL.md#81-allium--kernel-mapping). This section covers the *protocol semantics* layered over that mapping.

### Protocol summary

| Protocol | Allium clause | Shape | Direction | What crosses | Behavioural-core involvement |
|---|---|---|---|---|---|
| **View** | `exposes` | passive | party ← system | Data the party can see | None — reads do not change state |
| **Signal** | `provides` | event (no return) | party → system | Named stimuli the party emits | Should have a subscribing Rule; absence is a detectable gap |
| **Call** | `contracts: demands/fulfils` | typed function | bidirectional | Invocations with arguments and return value | Implementation may use Rules, may not — internal concern |

### View — passive read

`exposes: assignment.slot.time` declares that the actor can see this data. The act of viewing changes nothing in the system; no Rule fires. The substrate landing is an `exposes: Surface → Field` kernel edge (R20), with the Field reached as a `SubstrateAddress` on its owning Container. `Allium::Exposes` may attach to the edge as optional methodology metadata; the structural fact is the kernel edge. No Rule involvement.

### Signal — event-shaped stimulation

`provides: InterviewerConfirmsSlot(viewer, slot)` declares a named action the actor can perform. The action is fire-and-forget — Allium's grammar does not allow `provides` actions to declare return types. Effects are observable through subsequent reads. The substrate landing is a `provides: Surface → Event` kernel edge (R20); `Allium::Provides` may attach to the edge as optional methodology metadata.

A Surface with an outgoing `provides: Surface → Event E` edge but no `triggers: E → Rule` edge anywhere in the model is a **detectable gap** — a boundary advertises an action that nothing handles. The predicate is the canonical motivating example for the R20 lift: with `provides` as a kernel relation, the gap is expressible directly as `provides(S, E) ∧ ¬∃R. triggers(E, R)`, traversed once over kernel edges rather than via tag-namespace walks.

Rules triggered by typed-subject conditions (`T.created`, `T.field transitions_to V`, etc.) do not produce or consume Events. They land as `Rule —observes→ Container/Field` edges. These are internal triggers — they have no boundary involvement at all.

### Call — function-shaped invocation

`contracts: fulfils EventSubmitter` declares that this surface implements the typed signatures inside `EventSubmitter`, including operations like `submit: (key, name, payload) -> EventSubmission`. Invocations carry parameters and return typed results. The substrate landing is an Operation on the Contract Container's Boundary, with `Allium::Call` tagging.

A Rule internally invoked by the implementation of an Operation is *not* a structural edge in the spec model unless `triggers: Operation → Rule` (kernel relation R4) is declared. The connection to actual implementation appears via `projects` edges from spec primitives to `Code.*` artifacts (per [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments)).

### Asymmetric Rule requirements

Stated explicitly because the asymmetry matters:

| Protocol | Rule required? | What absence means |
|---|---|---|
| View (`exposes`) | No | Nothing — reads do not trigger work |
| Signal (`provides`) | Should — but Allium does not enforce | **Detectable gap** worth surfacing in fukan |
| Call (contract operation) | Possibly, possibly not | Implementation may be pure computation, behaviour-driven, or a mix |

Rules can also fire **without any boundary involvement**. Allium's trigger taxonomy splits along whether the trigger is a *named event* or a *typed-subject condition*:

| Flavour | Kind | Substrate landing |
|---|---|---|
| External stimulus → Surface `provides:` (boundary) | named event | `provides: Surface → Event` (R20) arrives at the boundary; `Event —triggers→ Rule` fires inside |
| Chained emission from another rule | named event | `Event —triggers→ Rule` |
| Entity creation (`T.created`) | typed-subject | `Rule —observes→ Container` |
| State transition (`T.field transitions_to V`) | typed-subject | `Rule —observes→ Field` |
| State becomes (`T.field becomes V`) | typed-subject | `Rule —observes→ Field` |
| Temporal (`T.expires_at <= now`) | typed-subject | `Rule —observes→ Field` |
| Derived condition (`T.is_valid` flips true) | typed-subject | `Rule —observes→ Field` |

Internal-only Rules (named or typed-subject) are common and supported. The Model must not assume every Rule has a boundary entry point.

---

## CRUD mapping

The boundary protocols compose cleanly into typical CRUD patterns:

| Operation | Protocol | Path through the Model |
|---|---|---|
| **Create** | Signal | `provides: CreateOrder(...)` → Event → Rule whose `when:` matches with `ensures: Order.created(...)` |
| **Read** (simple) | View | `exposes: my_orders` → `exposes` edge to a Field on a Container → no Rule |
| **Read** (complex / aggregate) | View (computed) or Call | Derived value on a Container, OR a contract Operation returning the result |
| **Update** | Signal | `provides: UpdateOrder(...)` → Event → Rule with `ensures: order.field = ...` |
| **Delete** | Signal | `provides: DeleteOrder(...)` → Event → Rule with `ensures: not exists order` |
| **Search / lookup** | Call | Operation `search: (query) -> List<Result>` |

The asymmetry: **mutations are event-shaped (Signal); reads are passive (View) or call-shaped (Call) but never event-shaped**. This is a property of Allium itself — Rules produce *effects on state*, not return values. Anything that needs to return a value either makes the value visible via `exposes` (and lets the actor read it) or lives in an Operation whose typed signature has a return type.

---

## The three spec altitudes

Three spec altitudes, committed in [MODEL.md §11](./MODEL.md#11-substrate-commitments): **Behaviour** (highest, most abstract — what happens), **Structure** (the scaffolding behaviour enfolds over — Operations, Boundary, composition), **Infra** (deployment commitments). Implementation isn't a fourth altitude — it's the projection across all three (Code | Infra-Artifact | Documentation flavours).

### Spec files and altitudes

Each spec file pattern produces content at one or more altitudes.

| Spec file | Altitude(s) | Owns | References upward to |
|---|---|---|---|
| `*.allium` | Behaviour + Structure (partial) | Rules, Events, top-level Invariants (Behaviour); Operations in contracts, Surfaces, Contracts (Structure); Types, Actors | substrate (Container / Field / Actor) |
| `*.boundary` | Structure (binding + composition) | Operation↔Rule bindings (R4 materialisation), composite Containers, `exports` visibility, subsystem-scoped rules | `*.allium` Rules (Structure → Behaviour, upward); same-altitude refs to `*.allium` Operations / Surfaces |
| `*.infra` *(deferred)* | Infra | Services, Endpoints, Storage, Channels, Policies — declarative deployment commitments | `*.boundary` / `*.allium` Structure content (Infra → Structure, upward) |

**The altitude-reference rule** (strict one-up, no skipping, no downward): each altitude references only the altitude immediately above. `.allium` Rules cannot reference Operations (Behaviour → Structure downward is forbidden). `.boundary` declares Operation→Rule bindings (Structure → Behaviour upward, allowed). `.infra` declares Endpoint→Operation realisations (Infra → Structure upward, allowed). Substrate primitives (Container, Field, Actor) are altitude-spanning — referenced from any altitude.

**Allium covers Behaviour and partial Structure.** Allium produces both Rules (Behaviour) and Operations-in-contracts / Surfaces (Structure). What Allium *cannot* produce is the binding between them — the awkward middle ground that motivated `.boundary`. `.boundary` is the Structure-altitude binding layer that fills the gap. See [MODEL.md §8.2](./MODEL.md#82-boundary--kernel-mapping).

For MVP, `.allium` and `.boundary` are both implemented, plus the Clojure analyzer producing `projects` edges from spec primitives to `Code.*` artifacts (substrate-level shape per [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments); analyzer mechanics in the Implementation linkage section below). Only `.infra` remains architecturally seamed — see [MODEL.md §10 Architectural seams](./MODEL.md#10-architectural-seams).

### `.allium` responsibilities

Allium is the source of truth for behaviour. Each `.allium` file declares one module's worth of:

- Types (entities, values, variants, contract types)
- Rules (behaviour-bearing units) — with bodies parsed into the Expression and Effect substrate per [MODEL.md §3.8](./MODEL.md#38-expression-and-effect-substrate)
- Invariants (top-level) — landing as Bool Expressions in `Container.intent.assertions`
- Surfaces (with `facing`, `exposes`, `provides`, `contracts:`)
- Actors
- Contracts (with their Operations)
- Implicit Events (named events from `when:`, `provides:`, and `emits:` sites within the module; identity per [MODEL.md §8.1](./MODEL.md#81-allium--kernel-mapping))

Rule body parsing follows MODEL.md §8.1: `requires:` / `where:` / `ensures:` / `guarantees:` produce Expressions in `Rule.intent.assertions` and Definitions / Effects in `Rule.body` with `Allium::Requires` / `Where` / `Ensures` / `Guarantees` source-clause tags. Effect-shaped `ensures:` clauses materialise Effect records that source the corresponding `writes` / `creates` / `destroys` / `emits` kernel edges (identity stable per the §3.8 kernel invariant).

Cross-module references continue via `use "..." as alias` and qualified names (`alias/TypeName`). External entities mark types managed by other modules.

### `.boundary` responsibilities

`.boundary` is the Structure-altitude binding layer. It fills two gaps Allium leaves open:

**1. Operation↔Rule binding (primary).** Allium has Operations (contract signatures) at Structure altitude and Rules (behavioural units) at Behaviour altitude, but no grammar connecting them. `.boundary` declares which Rule fires when which Operation is invoked, materialising `triggers: Operation → Rule` edges (kernel relation R4).

**2. Subsystem composition (secondary).** A logical grouping of `.allium` modules (and possibly nested subsystems) with a declared external API.

**Sketch syntax** (final syntax to be designed; this captures the conceptual primitives):

```
-- auth.boundary
-- Header: -- boundary: 1

use "./oauth.allium" as oauth
use "./session.allium" as session

-- Module-level API declaration: oauth.allium's public surface.
-- Anything declared at oauth's top level but not listed here is module-private —
-- references from other modules to those items are structural errors.
-- Contracts are not listed: they are always cross-module visible at the type level
-- (so `fulfils`/`demands` declarations work); their Operations are individually exported.
module oauth {
    exports:
        OAuthLogin                  -- Surface
        EventSubmitter.submit       -- Operation (on Contract EventSubmitter)
        EventSubmitter.cancel       -- Operation (on Contract EventSubmitter)
        SessionRevoked              -- Event
        AuthError                   -- Variant
}

binding submit_handler {
    operation: oauth/EventSubmitter.submit
    invokes:   session/ProcessSubmission
    returns:   the EventSubmission created by ProcessSubmission
}

subsystem Auth {
    contains:
        ./oauth.allium
        ./password.allium
        ./session.allium

    exports:
        oauth/OAuthLogin
        password/PasswordLogin
        session/SessionManagement

    -- Optional: subsystem-scoped architectural rules
    rules:
        no_dependency from oauth to password
}
```

**Primitives provided by `.boundary`:**

1. **`binding`** — declares an Operation→Rule binding. One Rule per binding; multiple bindings for one→many fan-out (one Operation invokes many Rules). Many→one fan-in (multiple Operations bound to the same Rule) is supported by `(operation_ref, rule_ref)` identity — but the parameter-signature lint (below) applies per binding, so fan-in forces every bound Operation to share parameter shape with the Rule's `when:`. In practice this constrains fan-in to Operations that are *semantically* the same call shape (e.g., a single `submit` Rule fronted by both an HTTP and an internal queue Operation). Identity = `(operation_ref, rule_ref)`. Optional binding name for addressability.
2. **`module`** — declares a single `.allium` module's public API. References the module by its `use`'d alias; lists items that are part of the module's external face via `exports:`. **Exportable kinds** are exactly those Allium primitives with a spec-level cross-module reference site: Surfaces (referenced via Surface `related:`), Entities / Values / Variants (via field type declarations and `variant from <parent>`), Events (via Rule `when:` / Surface `provides:` / Rule `emits:`), Actors (via Surface `facing:`), and individual Operations written `Contract.operation` (via `.boundary` binding `operation:` clauses). **Not exportable:**
    - **Contracts** — always cross-module type-visible (so `fulfils`/`demands` declarations work with full structural inspection of the Contract's signatures); listing a bare Contract name is a structural error.
    - **Rules** — no spec-level cross-module reference site exists. The only cross-module Rule reference is `.boundary` binding `invokes:`, which is the wiring layer and is exempt from visibility checks (a binding's `invokes:` may reach any Rule in any module, open or closed; the *public* thing being invoked is the bound Operation, which IS subject to export). Rule visibility from outside a closed module happens indirectly: through the bound Operation, or through an exported Event the Rule triggers on.
    - **Invariants** — no cross-module reference site at all; they are module-scoped predicate-shaped clauses addressed only by methodology-shipped constraints inspecting the Model.

    **Anything else declared at the module's top level but not listed in `exports:` is module-private** — cross-module references targeting non-exported items are structural errors. The design rule: sub-substrate with independent reference identity (Operations) is individually exportable; sub-substrate without (Fields, Parameters, sub-Clauses) comes along with the parent's type and isn't separately listable. The default for a module with **no** `module` declaration in any `.boundary` file is **open** (every top-level declaration externally visible — matches Allium's stock semantics and preserves single-file projects). Adding any `module X { exports: ... }` for module X **flips X to closed**: the listed items are the entirety of X's public face. This is symmetric with subsystem `exports:` one altitude down — explicit encapsulation as a deliberate tool. Allium itself has no module-level visibility construct today; this primitive is the fukan-side mechanism filling that gap. (If Allium later adopts a module-level `exports:` upstream, the `.boundary` `module` primitive sunsets and the analyzer reads from the Allium source instead.)
3. **`contains`** — declares which modules belong to the subsystem. References `.allium` files (or nested `.boundary` subsystems).
4. **`exports`** *(inside a `subsystem` block)* — names which Surfaces (or Contracts) of the contained modules / nested subsystems are externally visible at this composite's boundary. Surfaces not listed are internal to the subsystem. **Exporting is explicit and non-transitive across composite nesting**: if composite A contains composite B and B exports Surface S, A must list S in its own `exports:` for S to be visible at A's external boundary. This is a deliberate re-encapsulation tool — an outer composite can selectively expose only some of an inner composite's exported surface. Items a subsystem `exports:` must themselves be `module`-exported if their owning module has flipped closed.
5. **`rules`** *(optional)* — subsystem-scoped architectural constraints. Each entry is a reference to a registered constraint (by qualified name `<namespace>/<name>` — methodology-shipped, fukan-shipped well-known, or project-side) plus parameters; the entry is materialised as a `PredicateRegistration` with `scope = TagScope` against the composite Container ([MODEL.md §5.3](./MODEL.md#53-predicate-registrations)). *Scope semantics.* `TagScope` against the composite is about **where the registration lives** (evaluated once per matching composite Container) — not about restricting the constraint body's access. The body has the full §5.4 introspection surface and must scope itself appropriately (e.g., `no_dependency(from, to)` parameterised by the composite's contained-modules set; `no_circular_refs` walking the composite's `children` transitive closure). "Subsystem-scoped" is therefore *editorial* — describing how authors *use* the registration, not what the engine enforces. Concrete in-`.boundary` token syntax is part of the deferred token-level work (open question 1 below). Fukan-shipped well-known constraints for v0: `no_dependency(from, to)`, `no_circular_refs`, `naming_convention(target, pattern)`, `signal_gap` (an outgoing `provides: Surface → Event` edge with no `triggers: Event → Rule` edge — the Signal-protocol gap detection that motivated the R20 kernel lift) — extended additively. Project-wide constraints live in the project layer (file-scope); subsystem-scoped constraints live here (composite-scope). Same registration mechanism; different scope.

**Binding semantics (settled this design phase):**

- **Parameter-signature lint** *(strict, enforced)*. The bound Rule must have **at least one** event-shaped `when:` clause whose parameter **names, positions, and types** match the Operation's parameter signature exactly. The matching clause is selected structurally — by name-and-shape match against the Operation's signature — not by ordering or by author annotation; the binding's parameter mapping is identity by construction with respect to that clause. Other `when:` clauses on the same Rule (additional event-shaped clauses with different shapes, or typed-subject clauses) are unconstrained by the binding and continue to produce their own kernel edges. Zero matching event-shaped clauses is a lint error; multiple matching clauses are unusual but not an error — binding identity is `(operation_ref, rule_ref)`, independent of which clause matched. Typed-subject Rule triggers (state transition, temporal, becomes, derived, creation) cannot serve as the matching clause — bind to an event-shaped Rule whose effects produce the state condition.
- **Return derivation** *(strict)*. `returns:` is required iff the Operation has a `return_type`; forbidden when null. Opaque-text expression initially; typed when an expression parser arrives.
- **Trigger composition**. Each binding produces one `triggers: Operation → Rule` (R4) edge. The Rule's declared `when:` Event continues to exist as a kernel primitive (per [DECISIONS.md K16](./DECISIONS.md#kernel-primitives)) and produces its own `Event → Rule` edge — both trigger paths coexist. Operation invocation fires the Rule directly via the R4 edge; the binding does **not** implicitly emit the Event. A Rule may therefore be reached via Operation invocation, via Event emission elsewhere, or both — and when reachable only via binding, its `when:` Event is a *signature-alignment device* that may never be emitted at runtime. That is a valid pattern (the Event surface enforces parameter-shape discipline on the Rule); an "Event declared in `when:` but neither emitted nor `provides`'d" diagnostic is a candidate constraint, not a structural error.
- **Naming**. Bindings have optional names for addressability. Identity rests on `(operation_ref, rule_ref)`. Anonymous bindings are valid; named bindings useful for constraints and rendering.
- **Reference grammar**. `.boundary` adopts Allium's import-and-qualification shape verbatim. `use "<path>" as <alias>` at the file head (or within a subsystem block) declares cross-module aliases. All cross-module references use Allium's `alias/Name` pattern: `alias/RuleName`, `alias/EntityName`, `alias/ContractName.operation_name` (alias scopes the Contract; dot scopes the Operation within the Contract). Inside a `subsystem` block, each entry in `contains:` implicitly binds the module's filename-stem as an alias (e.g., `./oauth.allium` → `oauth`), so `exports:` and subsystem-scoped bindings can use those names without an explicit `use`. Top-level bindings spanning modules require explicit `use` statements. Unqualified bare names outside a `contains:`-scoped block are a structural error.
- **`use` semantics**. Imports are **non-transitive**: aliases declared in a `.boundary` file do not propagate to other files that `use` it (each file declares its own). **Cycles in `use` imports are structural errors** — A using B and B using A creates resolution ambiguity (Allium follows the same rule).

**What `.boundary` does not do:**
- It does not introduce new behavioural primitives.
- It does not specify implementation mechanism (transport / process / serialisation — those are `.infra` concerns).
- It does not extend Allium grammar. It speaks at the same altitude as Allium's Structure content (Operations, Surfaces, contracts).

### `.infra` responsibilities — deferred but pre-positioned

`.infra` is the Infra-altitude spec layer. It owns *declarative commitments* about deployment:

- Endpoint specifications (path, method, request/response schemas, status codes)
- Storage declarations (table/collection structure, indexes, constraints)
- Wire formats (codec choices at specific points)
- Transport bindings (HTTP, gRPC, AMQP, etc.)
- Deployment topology (services, queues, caches)
- Network / auth policies

Lands as Containers tagged `Infra::*` (Service / Endpoint / Storage / Channel / Policy), parallel to how Allium contributes Containers tagged `Allium::*`. Tag payloads carry kind-specific fields and **upward references to Structure-altitude content** (Operations, Surfaces) per the altitude-reference rule.

**Tied to this seam.** The `Infra(Endpoint | Resource)` cases of the Artifact ontology and the `endpoint`, `resource` projection_kind values come back together with `.infra` (deferred from V0 — [MODEL.md §7.2](./MODEL.md#72-v0-artifact-ontology), [§10.5](./MODEL.md#105-future-projection-vocab-additions)). They represent *observed deployed reality* — real services, real network endpoints reported by a future live-cluster analyzer. Infra-spec `projects` to Infra-Artifacts when both exist (drift detection at Infra altitude). The spec/Artifact axes are independent ([MODEL.md §11](./MODEL.md#11-substrate-commitments)).

The architectural seam is open; no Model substrate changes are needed today to accommodate it.

---

## Build pipeline

Filesystem-inferred structure is dropped. `.boundary` is the sole source of truth for module composition. The MVP pipeline has three analyzers — Allium, Boundary, and Clojure — all producing kernel content into one Model.

### Pipeline shape

```
  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
  │ *.allium files   │  │ *.boundary files │  │  Clojure source  │
  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
           │                     │                     │
           ▼                     ▼                     ▼
  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
  │ Allium analyzer  │  │ Boundary analyzer│  │ Clojure analyzer │
  │ (leaf Container  │  │ (composite       │  │ (Code.* Artifacts│
  │  + Allium tags + │  │  Container +     │  │  + convention-   │
  │  kernel edges)   │  │  Operation↔Rule  │  │  resolved        │
  │                  │  │  bindings)       │  │  projects edges) │
  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
           │                     │                     │
           └─────────────────────┼─────────────────────┘
                                 ▼
                  ┌────────────────────────────┐
                  │   Merge + wire             │
                  │   (one Model)              │
                  └────────────┬───────────────┘
                               ▼
                  ┌────────────────────────────┐
                  │   Constraint evaluation    │
                  │   (methodology + project   │
                  │    constraints +           │
                  │    conventions)            │
                  └────────────┬───────────────┘
                               ▼
                  ┌────────────────────────────┐
                  │   Projection / view        │
                  └────────────────────────────┘
```

Conventions feed the Clojure analyzer (which artifacts to look for at which addresses); constraints run after merge against the unified Model. Both are flavours of project-side project-layer entries ([MODEL.md §10.3](./MODEL.md#103-project-side-constraint-registration--composition)).

### Phase ordering and error semantics

The pipeline runs as a fixed sequence of phases. The three analyzers parse independently in Phase 1 — the visual parallelism in the diagram above is real at that step. The dependency lives at **Phase 2**, where Boundary's cross-analyzer reference resolution targets Allium-produced Operations / Rules / module-Containers, and the Clojure analyzer enumerates expected projections from Allium-produced spec primitives; both consume Allium's Phase 1 output. The full sequence formalises that dependency and adds two strategic halt gates so users see the complete set of attributable problems at each level without downstream noise.

**Within a phase, all violations are aggregated before moving on.** Fail-fast on the first violation would force a fix-then-re-run loop on cosmetic issues; aggregating per phase lets the user fix everything at one level before learning about the next.

**Severity.** Violations carry `severity ∈ error | warning` (the [MODEL.md §5.3](./MODEL.md#53-predicate-registrations) shape, lifted from constraints to a pipeline-wide concept). **Errors trip the gates; warnings never do.**

```
Phase 1 — Per-analyzer parsing
  Allium analyzer    : parse *.allium files; produce kernel content (primitives,
                       kernel edges, Expression and Effect substrate per
                       MODEL.md §3.8) and `Allium::*` tag applications
                       (including `Allium::Requires` / `Where` / `Ensures` /
                       `Guarantees` source-clause tags on Rule-body Expressions);
                       aggregate parse + grammar errors
  Boundary analyzer  : parse *.boundary files; aggregate parse + grammar errors
  Clojure analyzer   : read source; per-file parse failures emit *warnings*,
                       file skipped, no Code.* artifacts produced

Phase 2 — Cross-analyzer reference resolution
  Boundary `binding operation:` / `invokes:` resolve to Allium Operations / Rules
  Boundary subsystem `contains:`            resolves to module-Containers
  Boundary `module <alias>`                 resolves to its module-Container
  Unresolved references → errors, attributed to the .boundary source

  ── Gate G1 ──
  Halt if errors > 0. Report aggregated Phase 1 + Phase 2 violations.

Phase 3 — Merge
  Kernel content unioned by identity
  External-entity stubs unified with real Containers per MODEL.md §3.6
  Identity-based; cannot fail

Phase 4 — Post-merge structural validation
  Sub-phases 4a–4g run sequentially (composition; event; binding; module-
  visibility; subsystem-visibility; export closure; cross-module reference
  visibility). Each sub-phase aggregates all its violations before the next
  begins. Full rules in the validation catalogue below.

  ── Gate G2 ──
  Halt if errors > 0. Report aggregated Phase 1–4 violations.

Phase 5 — Constraint evaluation
  Methodology-shipped constraints (Allium, Boundary)
  Project-side constraints (project layer)
  Coherence queries on relational tag applications
  All run; results cached per MODEL.md §10.3
  Violations are outputs, not blockers — the explorer surfaces them
  alongside the rendered Model

Phase 6 — Projection / render
  Explorer renders the Model
  Drift markers from `projects` edge validity (MODEL.md §7.6) surface
  alongside constraint violations
```

**Why two gates.**

| Gate | Protects | Rationale |
|---|---|---|
| **G1** | Merge (Phase 3) | Unifying analyzer outputs that didn't parse — or whose cross-analyzer references didn't resolve — produces partial nonsense. Halt and let the user fix spec errors first. |
| **G2** | Constraint evaluation (Phase 5) | A Model with unresolved references or broken composition produces noisy or misleading constraint violations downstream of the real cause. Halt at the last point where attribution is still clean. |

**Why Phase 4 sub-phases are ordered.** Composition (4a) produces the topology every later sub-phase reads. Event and binding rules (4b, 4c) validate primitive-level consistency and don't depend on visibility. Module-visibility (4d) must resolve `module exports:` lists before the closure rule (4f) can ask whether they're self-coherent. Closure (4f) must hold before cross-module reference visibility (4g) can trust that every name reachable from outside a module is in fact exported. Sub-phases run in this order so attribution is stable: each violation is reported against the sub-phase that found it, with no upstream ambiguity.

**Analyzer dependency.** Phase 2 is where the analyzer dependency lives. Allium runs alone in Phase 1; Boundary's reference resolution depends on Allium-produced Operations, Rules, and module-Containers; the Clojure analyzer enumerates expected projections from Allium-produced spec primitives. Clojure does *not* depend on Boundary — bindings don't affect spec-primitive identity.

**Asymmetry of the Clojure analyzer.** Clojure parse failures are warnings, not errors. The spec layer is independent of code; missing `projects` edges to unparseable code show up exactly where the drift surface is meant to put them. The pipeline doesn't halt on code — that's the whole point of the drift surface.

### Design-level validation rules

Catalogue of the full rules each Phase 4 sub-phase (4a–4g) evaluates, followed by Phase 5 (constraints). Sub-phase labels match the execution order above.

**4a. Composition rules.**

- Each module-Container has at most one composite parent.
- Modules referenced by no `.boundary` are top-level (warning, not error — single-file projects are valid).
- No cycles in subsystem composition: a composite Container cannot contain (directly or transitively) any subsystem that contains it.
- Subsystem `contains:` paths must reference files that exist and parse without error; unresolved paths are structural errors.
- Subsystem names are unique within a composition root.

**4b. Event rules.**

- Every Event has at least one declaration site within its owning module; an Event with no in-module declaration sites is a structural error.
- All declaration sites of the same Event within a module must agree on parameter shape; disagreement is a structural error (per-module shape consistency).
- Cross-module name collisions are not possible by construction: `module_a/Foo` and `module_b/Foo` are different Events. Allium's namespace rules supply the disambiguation; no global Event namespace exists.
- Per Allium validation rule 30: every trigger referenced in a Surface's `provides:` must be defined as an external-stimulus trigger in a rule of the same module.

**4c. Binding rules.**

- A binding's `operation:` must resolve to an Operation on a Contract's Boundary within the analysable scope; unresolved references are structural errors.
- A binding's `invokes:` must resolve to a Rule within the analysable scope; unresolved references are structural errors.
- A binding's `returns:` is required iff the referenced Operation has a `return_type`; mismatch (present-when-null or absent-when-set) is a structural error.
- A binding's bound Rule must have **at least one** event-shaped `when:` clause whose parameter names, positions, and types match the Operation's parameter signature exactly. Typed-subject `when:` clauses cannot serve as the matching clause. Zero matching event-shaped clauses is a structural error (per the parameter-signature lint above).

**4d. Module-visibility rules.**

- *Declaration validation.* `module <alias> { exports: ... }` declares the named module's public API. At most one `module` declaration per module across all `.boundary` files (multiple declarations for the same module are a structural error). Each `exports:` entry must resolve to one of: a top-level Surface, Entity, Value, Variant, Event, or Actor declared in the module, **or** an Operation written `Contract.operation` where `Contract` is a top-level Contract declared in the module and `operation` is an Operation on that Contract's Boundary. At most one of each name (Surfaces, Entities, etc.) or each `Contract.operation` pair. Unresolvable entries are structural errors. **Contracts, Rules, and Invariants are never `exports:` entries** — Contracts because they are always type-visible; Rules because their only cross-module reference site is bindings (which are wiring-layer, exempt from visibility); Invariants because they have no cross-module reference site. Listing any of those is a structural error.
- *Open/closed default.* A module with no `module` declaration in any loaded `.boundary` file is **open** (every top-level declaration externally visible). A module with a `module` declaration is **closed** — exactly the listed items are public (plus Contracts at the type level, always); cross-module references to non-listed items are structural errors (enforced in 4g). No partial-export state; either there is a `module` declaration with a complete list, or there isn't.
- *Field-level visibility.* Field-level visibility is **not** modelled: visibility is per-Entity. A module's `module exports:` lists Entities (Values, Variants) as units; if an Entity is exported, all its Fields come along. A Surface in another module `exposes:` a Field on an Entity iff the owning Entity is exported (or its module is open). The simpler granularity matches Allium's substrate shape (Fields are sub-substrate, not independent primitives) and avoids a field-visibility model the language has no use for in MVP.

**4e. Subsystem-visibility rules.**

- *Declaration validation.* `exports:` inside a `subsystem` block must list items from directly-contained modules (qualified `<alias>/<item>`) or from directly-contained nested subsystems' own `exports:` lists. Allowed item kinds match the module-level rule: Surfaces, Entities, Values, Variants, Events, Actors, and individual Operations written `<alias>/<Contract>.<operation>`. **Contracts, Rules, and Invariants are never `exports:` entries** at this altitude either — same justifications as at module level. Other primitive kinds are structural errors. Exporting is non-transitive across composite nesting: an outer composite must re-list any inner-subsystem-exported target it wants visible at its own external boundary.
- *Subsystem-module consistency.* If a subsystem `exports:` an item from a closed module, that item must appear in the module's own `exports:` (or, for Operations, must be one of the module's exported `Contract.op` pairs). A subsystem cannot expose what a module has marked private.

**4f. Export closure rule.**

A closed module's `exports:` list must be **self-coherent**: every type referenced from any exported item's signature must itself be reachable through exports — either explicitly listed in the same module's `exports:`, defined in another module that exports it, or pre-declared as `external entity`. The closure is computed transitively across the loaded model.

*Per-kind closure obligations.* For each item listed in `module M { exports: ... }`:

| Exported kind | Types whose closure must hold (when defined in M) |
|---|---|
| **Surface** | The Events referenced in `provides:` (each Event's parameter types); the Operations on Contracts the Surface `fulfils:` / `demands:` (their parameter and return types); the Actor in `facing:`; the Container in `context:`; peer Surfaces in `related:` |
| **Operation `Contract.op`** | Parameter types; return type (when set) |
| **Event** | Parameter types |
| **Entity** | Types of every field; transitive closure through nested `Composite(Named(...))` fields |
| **Value** | Same as Entity |
| **Variant** | Same as Entity, plus the parent Container (via `specialises`) |
| **Actor** | None (`identified_by` / `within` are opaque text in v0) |

Contracts are exempt from the closure as items themselves (always cross-module type-visible, regardless of any `module exports:`), but the **types appearing in their Operations' signatures still close** when an Operation is exported. External-entity dependencies cascade: if M's exported Surface signature references `other/Order`, M's closure is satisfied — M doesn't own Order — but `other`'s exports must list Order (else `other`'s own closure fails).

The closure runs against the unified loaded model at the validation step, so the check is whole-model — partial closures across separately-loaded module sets are not evaluated. Open modules (no `module` declaration) are trivially closure-clean: every top-level declaration is externally visible, so reachability is automatic.

*Example error.*

```
ERROR  orders.boundary:4   export closure violation
  Module 'orders' exports 'PlaceOrder' (Surface).
  Its fulfilled Contract 'OrderSubmission' has operation
    submit(order: Order) -> Confirmation
  Types 'Order' and 'Confirmation' are defined in 'orders'
  but are not exported.

  Fix options:
    1. Add 'Order' and 'Confirmation' to orders' exports, or
    2. Remove 'PlaceOrder' from orders' exports (also private),
       or
    3. Restructure: move 'Order' / 'Confirmation' to a
       separate module that exports them.
```

**4g. Cross-module reference visibility.**

- *Reference enforcement.* Every cross-module reference (in `.allium` `use` + qualified names, including `external entity`; in `.boundary` `use` + qualified names; in subsystem `exports:` lists; and in analyzer-synthetic content) must target an item that is either (a) a Contract (always type-visible), (b) in an open module, or (c) listed in its owning module's `module exports:`. References to closed-module non-exported items (Surfaces, Entities, Events, Rules, Operations, etc.) are structural errors. The build pipeline traverses all cross-module reference sites after merge and validates against the exported API. `external entity` declarations are *not* exempt — they obey reference-visibility uniformly with other content references.
- *Cross-module Operation references in bindings.* `.boundary` binding `operation: <alias>/<Contract>.<op>` must target an exported Operation when the owning module is closed.
- *Binding `invokes:` exempt.* Binding `invokes: <alias>/<RuleName>` references are **wiring-layer** and exempt from visibility checks — they may reach any Rule in any module, open or closed.
- *Closure as upstream guarantee.* The closure rule (4f) is the *upstream* guarantee that makes this enforcement consistent. If module M passes closure, every name reachable from outside M is exported, so the case "module N legitimately references a private type in M" cannot arise — N would have no signature-level path to learn the type's name. References that *do* target closed-module-private items are therefore fabricated (or reference stale/removed names) and are correctly caught here.

**5. Constraints.** *(Phase 5)*

- Constraint violations carry the severity registered with them (`error | warning`, per [MODEL.md §6](./MODEL.md#6-the-constraint-language)); the Model remains complete and navigable in either case.

### What goes away

- Filesystem-derived hierarchy logic in the build pipeline
- The "smart root pruning" step (no longer needed without FS hierarchy)
- The old code-graph machinery: `Function` / `Schema` node kinds, `function_call` / `dispatches` / `schema_reference` edges, runtime reflection, the old merge step combining analyzer outputs — *replaced* by `projects` edges from spec primitives to `Code.*` artifacts per [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments). The Clojure analyzer itself stays, but produces convention-resolved `projects` edges, not the old code-graph.
- The merge-conflict resolution between filesystem and `.boundary` (no second source to conflict with)

---

## Project layer

Allium is intentionally flexible. `provides` actions and contract operations both express "an action at a boundary," with different protocols. `exposes` declares public visibility but doesn't pin down whether reads happen via direct field access or via wrapper operations. Two engineers describing the same system with different primitives can both be technically correct.

The project layer is where a project makes its choices explicit — which primitives it uses for which situations, and the way it applies them:

> "All boundary actions are modelled as contract operations; `provides` is reserved for system-internal events."
>
> "Surfaces facing external users must declare an Actor; surfaces facing internal services may use entity types directly."
>
> "Event names follow `Subject + Verb` convention."

Each entry names a situation, the chosen primitive, and the way it is applied — optionally with a machine-checkable expression. The layer serves three audiences from the same content:

- **Human readers** — orientation: how does this project use the spec languages?
- **LLMs designing or extending spec** — generation context: which patterns should new spec content conform to?
- **The build pipeline** — validation: does the Model in fact conform to the pattern?

The primary purpose is making the project's design vocabulary explicit. Validation and generation are useful consequences of having made it explicit.

### Position

The project layer is **not** a fourth language at a different altitude. The three spec languages declare what the system is; the project layer describes how this project chooses to use those declarations. The relationship is annotation, not peerage.

Project-layer entries carry two flavours: **constraints** register against the constraint language MODEL.md §6 defines (single language, methodologies + projects alike), and **conventions** register as analyzer configuration consumed by the Clojure analyzer (mechanics in the Implementation linkage section below). For MVP, the *registration path* for both flavours is hardcoded ([MODEL.md §10.3](./MODEL.md#103-project-side-constraint-registration--composition)); the declarative-form and composition mechanics (activation, severity overrides, bundles, versioning) wait until forced by concrete project need.

A constraint can be a soft preference (`severity = warning`) — naming styles, conformance to a project idiom — or a hard architectural law (`severity = error`) — module-isolation rules, layering rules, signal-gap detection. Severity is per-registration; the layer makes no commitment about hardness, only about origin (project-side, not kernel, not methodology).

Conventions split into two MVP uses:

- **Address-resolution rules** — how spec primitives address their realising artifacts (the root-prefix knob, kind-sensitive transliteration, single-canonical-address discipline; full rules in the Implementation linkage section below).
- **Type-translation rules** — how substrate `Type` cases render in the target language. Substrate Types are kept generic at the target level (`Scalar(name)` is opaque to substrate; `Collection(of: T, semantics: ...)` commits to shape but not to Clojure-vs-Java collection idioms; `Composite(Named(C))` declares structure but not malli-vs-defrecord). A `TypeTranslation` entry maps a substrate Type pattern to a target-language rendering — how `Scalar("Integer")` becomes Clojure `:int`, how `Collection(of: T, Sequential)` becomes `[:vector T]`, how `Composite(Named(C))` resolves to a malli `def` reference. Default translations ship with the Clojure analyzer covering all six Type cases; methodologies override per `Scalar` name (Allium ships overrides for domain scalars like `"Money"`, `"DateTime"`); projects override per-project for custom domain types. The per-case default table lives in the Implementation linkage section below.

The same TypeTranslation shape is the seam for future target-language analyzers (TypeScript, Java) — each registers its own per-case defaults without substrate change.

```
┌─────────────────┐
│  Constraints    │  read
└─────────────────┘ ──────────► Model
        │
        │ produce
        ▼
┌─────────────────┐
│  Violations     │  surfaced in
│  (errors /      │ ──────────► Explorer (sidebar markers, node emphasis)
│   warnings)     │
└─────────────────┘

┌─────────────────┐
│  Conventions    │  configure
└─────────────────┘ ──────────► Clojure analyzer
                                     │
                                     │ emits `projects` edges
                                     ▼
                            ┌─────────────────┐
                            │  per-edge       │  surfaced in
                            │  validity       │ ──────► Explorer (drift markers
                            │  (valid /       │          on projecting primitives)
                            │   absent /      │
                            │   stale /       │
                            │   unknown)      │
                            └─────────────────┘
```

The two flavours surface differently. Constraints produce discrete violations the explorer renders as sidebar entries with severity. Conventions (analyzer configuration: the root-prefix knob, address-resolution rules) have no severity — their effect manifests as per-edge `validity` on `projects` edges ([MODEL.md §4.2](./MODEL.md#42-per-relation-semantics)), rendered in the explorer as red drift markers on spec primitives whose canonical address is missing. See [MODEL.md §10.3](./MODEL.md#103-project-side-constraint-registration--composition) for the severity-asymmetry note.

### Architectural style enforcement

The role architectural styles played in earlier ADL work — UniCon's connector typing, Hex's Core/Adapter discipline, DDD's layering rules, C4's dependency constraints — is a **constraint** concern in fukan, not a kernel concern. The kernel stays style-neutral; styles are sets of constraints registered through the project layer (project-wide) or `.boundary` `rules:` (subsystem-scoped, per the binding semantics earlier). A Hex rule like "no Rule in a `Hex::Core` Container writes to a `Hex::Adapter` Container" is one constraint registration; a project that adopts Hexagonal Architecture is the project that registers that constraint set. No new substrate primitive (`ConnectorType`, `StyleDefinition`) is introduced for this — the constraint language plus the tag-presence introspection surface (MODEL.md §6) is sufficient.

This is the same direction the substrate-vs-vocabulary force-and-gate (MODEL.md §3.7) takes: a feature lives at substrate altitude only when its shape is uniform across the cases that engage it. Style-typed composition rules vary widely across methodologies — Hex's port/adapter typing, DDD's bounded-context rules, layered architecture's directional dependencies — they share the *shape* "tag-conditioned constraint over kernel relations" but not the *vocabulary*. That's why they live in the project layer, parametrised per methodology.

### Coupling — explicit

The project layer reads the Model; the Model does not know about the project layer. This is the only direction. Constraint violations and convention drift markers are both *annotations over the Model*, not *content of the Model*.

### Forward compatibility

Conventions about *implementation idioms* — how Model concepts translate into sound, testable code in this project's tech stack (e.g., "in this project, prefer core.async for temporal rules") — sit adjacent to this layer but belong to a future chapter. Distinct from the conventions already in MVP, which are about *spec-to-code address resolution* rather than *coding patterns*. The same entry shape and audience model apply; the architectural seam is open. We don't build implementation-idiom conventions now.

---

## Implementation linkage — the Clojure analyzer

The Clojure analyzer reads Clojure source and emits `Code.Function` and `Code.DataStructure` Artifacts plus `projects` edges from spec primitives to those Artifacts, enabling spec-to-code drift detection. The substrate-level commitments — which spec primitives produce projections, what each `projection_kind` means, drift semantics, the test-projection cut, the Surface/Contract no-direct-projection rule — live in [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments). This section covers the application-design choices: address resolution conventions, identifier transliteration, type translation, and enforcement policy.

**Convention-driven binding.** No code-side annotations and no out-of-band binding files. The analyzer resolves each expected projection via mechanical name resolution against a small set of project-level conventions (registered as project-layer entries per the Project layer section above). Spec is the source; code aligns or fukan flags drift.

**The convention rules.** A project sets one configuration knob — the **Clojure root namespace prefix** relative to Allium module names (empty when layout is identity, as in fukan-on-fukan). All other mapping is mechanical and non-negotiable:

| Spec primitive | Projects to | `projection_kind` |
|---|---|---|
| Container tagged `Allium::Entity \| Value \| Variant` | `Code.DataStructure({module-ns}/{Name})` | `schema` |
| Event | `Code.DataStructure({module-ns}/{Name})` | `schema` |
| Operation on a Contract's Boundary | `Code.Function({module-ns}/{operation-name})` | `operation` |
| Rule | `Code.Function({module-ns}/{rule-name})` | `rule` |
| Expression in `Container.intent.assertions` tagged `Allium::Invariant` (with non-null `label?` per K31) | `Code.Function({module-ns}/{label})` | `invariant` |
| Rule \| Operation \| Invariant Expression (above) | `Code.Function({module-ns}-test/{name}-test)` | `test` |

**Identifier transliteration** is kind-sensitive:
- Type-shaped projections (DataStructure for Entity / Value / Variant / Event): **PascalCase preserved**.
- Function-shaped projections (rule, operation, invariant, test): **PascalCase → kebab-lower; snake_case → kebab-lower**.

So Entity `Order` → `Code.DataStructure(ns/Order)`; Rule `ProcessSubmission` → `Code.Function(ns/process-submission)`; Operation `submit` → `Code.Function(ns/submit)`.

**Schemas as malli values, not defrecords.** Clojure `Code.DataStructure` artifacts are recognised as top-level `(def Name <expr>)` forms in the expected namespace, without requiring `<expr>` to be a defrecord. This naturally accommodates malli schemas (`(def Order [:map [:id :string] ...])`), which are *data* and therefore introspectable — enabling finer-grained structural drift detection (does the schema's field list match the spec entity's field list?) as a later enhancement on the same substrate.

**Type-translation registry.** When emitting `Code.DataStructure` projections — and when the future shape-comparator pass compares spec Field types against malli schemas — the Clojure analyzer consults a Type-translation registry to render each substrate `Type` case as malli content. The registry covers the substrate's six Type cases with defaults:

| Substrate Type | Default malli rendering |
|---|---|
| `Scalar(name)` | malli predicate keyed by name (`"String"` → `:string`, `"Integer"` → `:int`, `"Boolean"` → `:boolean`, …) |
| `Enum(vs)` | `[:enum vs...]` |
| `Composite(Named(C))` | reference to the schema `def` at `{module-ns}/{C}` (i.e., the projection of Container C) |
| `Composite(Inline(fs))` | `[:map fs...]` inlined, recursively rendering each field's Type |
| `Collection(of: T, Sequential)` | `[:vector <render T>]` |
| `Collection(of: T, Unique)` | `[:set <render T>]` |
| `Collection(of: V, Keyed(K))` | `[:map-of <render K> <render V>]` |
| `Union(ts)` | `[:or <render ts...>]` |
| `Ref(KernelPrimitive(_), _)` | `:any` by default; a stricter id-type predicate registered per kernel kind raises the bar (e.g., id-uuid for Entities) |
| `Ref(Substrate(...))` | a registered substrate-address predicate (defaults to `:any` until a richer encoding is needed) |

Methodologies override per `Scalar` name (Allium ships overrides for domain scalars like `"Money"`, `"DateTime"`, `"Email"` — each rendering as a methodology-specific malli sub-schema). Projects override per-project for custom domain types (e.g., `"OrderId"` → project's id-shape). Override registration uses the convention-entry shape; a missing translation is a structural error (the analyzer can't render the spec). Default translations cover all six cases; methodology and project entries refine, never remove.

This same registry shape is the seam for other target-language analyzers when they arrive — a TypeScript analyzer would register `Scalar("String")` → `string`, `Collection(of: T, Sequential)` → `T[]`, etc., with no substrate change.

**Strict enforcement.** Exactly one function per Rule, one function per Operation, one var per Entity / Value / Variant / Event must exist at the expected canonical address. Multiple definitions at the same address is a lint error. The discipline is the whole point: spec authority depends on the project committing to one canonical address per spec primitive. Detection of "work scattered across helpers" is intentionally **not** mechanical — helpers appear as unprojected `Code.Function` nodes in the explorer, making the topology visible, and the canonical entry point's drift state (valid / absent per [MODEL.md §7.6](./MODEL.md#76-producing-projections--substrate-level-commitments)) is sufficient signal. Whether to refactor scattered helpers into the canonical entry is editorial, not analyzer-enforced.

**Couplings.** This convention strategy presupposes the project follows one-Allium-module ↔ one-Clojure-namespace discipline (modulo root prefix). Cross-module placement (Allium content implemented in a non-matching namespace) is not supported — refactor the layout. Code that doesn't match any expected projection address appears in the model as an unprojected `Code.Function` / `Code.DataStructure` node — visible in the explorer, not bound to any spec primitive.

---

## Explorer / projection layer

### Edge filtering

The current explorer has two edge modes (`code_flow`, `schema_reference`). The new explorer surfaces the kernel's thirteen relations (`triggers`, `observes`, `reads`, `writes`, `creates`, `destroys`, `emits`, `realises`, `specialises`, `uses`, `exposes`, `provides`, `projects`) plus tag-applied projections (e.g., views scoped to one methodology namespace, or relational tags from [MODEL.md §5 V12](./MODEL.md#5-the-vocabulary-mechanism)). All thirteen are populated in MVP. Allium contributes `realises` via `contracts: fulfils`, `specialises` via `variant from <Parent>` (R18), `uses` via `contracts: demands` (R19), `exposes` via `exposes:` (View protocol, R20), `provides` via `provides:` (Signal protocol, R20), plus the seven causation/effect/projection relations through Rules. The `.boundary` analyzer adds `triggers: Operation → Rule` edges; the Clojure analyzer adds `projects` edges.

Concrete UI grouping is design TBD — likely a small set of conceptual groupings (causation, data flow, boundary, cross-altitude) rather than nine individual switches. Single-mode-at-a-time toggle works as today; multi-select (show two groupings at once, with visual distinction) is a polish improvement.

### Visibility-aware projections

When viewing a composite Container (subsystem), the projection layer respects `exports` visibility:

- **Internal view** (clicked into the composite) — all surfaces of contained modules visible
- **External view** (looking at the composite from outside) — only `exports`'d surfaces visible
- **Default** — match the user's vantage point in the navigation tree

This requires the projection layer to walk up the Container tree to determine "which composite am I currently inside?" and apply the appropriate visibility filter.

### Drift markers

`projects` edges carry drift signal in MVP via per-edge `validity ∈ 'valid' | 'absent' | 'stale' | 'unknown'` ([MODEL.md §4.2](./MODEL.md#42-per-relation-semantics)). The projection layer renders this: green for `valid` (artifact exists at expected address), red for `absent` (missing implementation or missing test), neutral for `unknown` (not yet evaluated). Finer-grained `stale` checks (e.g., malli-schema field shape vs spec entity fields) hook in additively without further projection-layer work. When `.infra` arrives, the same machinery extends to Infra Artifact drift without changes.

### Sidebar content per kernel primitive

Each kernel primitive carries richer content than today's Function/Schema nodes. Sidebars are populated by reading kernel substrate, kernel relations, and the tag applications attached to each primitive. Examples for Allium-tagged content:

- **Container (Allium::Module)** — child counts by tag, top-level invariants (Bool Expressions in `Container.intent.assertions` tagged `Allium::Invariant`), module-level guarantees (Bool Expressions in `Container.behaviour.intent.assertions` tagged `Allium::Guarantee`), declared events (`Container.events`), derived metrics. Modules themselves have no `boundary` slot. If the module carries a `Boundary::ModuleApi` tag (i.e. a `.boundary` file declared `module <this> { exports: ... }`), the sidebar surfaces the **closed public API** — the explicit `exports:` list (Surfaces, Entities, Values, Variants, Events, Actors, individual `Contract.operation` Operations) — with visual distinction from internal items, plus the always-visible Contracts shown separately at the type level, plus an indication of Rules reachable from outside (via exported Events or Operation bindings). Without the tag, the module is **open** and the sidebar shows the full top-level declaration list.
- **Container (Allium::Entity / Value / Variant)** — fields, projections, derived values, state machines, entity-level invariants (Bool Expressions in the Container's `intent.assertions` tagged `Allium::EntityInvariant`), incoming/outgoing relations. For Variant Containers specifically, outgoing `specialises` edge to the parent Container, plus incoming `specialises` from any further child Variants — variant hierarchies surface as a parent/child fold in the sidebar.
- **Rule (Allium::Rule)** — trigger (incoming `triggers` from Event, or outgoing `observes` to Container/Field), preconditions / postconditions / guarantees (Bool Expressions in `Rule.intent.assertions`, distinguished by source-clause tags `Allium::Requires` / `Ensures` / `Guarantees`), definitions (`Rule.body.definitions` with `Allium::Where` tags), effects (Effect records in `Rule.body.effects`, surfacing as outgoing `writes` / `creates` / `destroys` / `emits` kernel edges with identity from the Effect)
- **Container (Allium::Surface)** — `facing` party (Actor or entity-tagged Container) and `context` (`Allium::Surface` payload), outgoing `exposes` to Fields (View protocol), outgoing `provides` to Events (Signal protocol), outgoing `realises` to fulfilled Contracts, outgoing `uses` to demanded Contracts, related Surfaces, timeouts, guarantees (Bool Expressions in `boundary.intent.assertions` tagged `Allium::SurfaceGuarantee`). **Operations available at this Surface** is a *derived view*: traverse the Surface's outgoing `realises` edges to fulfilled Contracts and pull Operations from each Contract's `boundary.operations`. Not stored on Surface directly — Surface's own Boundary has empty `operations` per §9.1 (realising sub-shape).
- **Container (Allium::Contract)** — operations on the Container's Boundary (parameters, return types), contract-level invariants (Bool Expressions in the Container's `intent.assertions` tagged `Allium::ContractInvariant`), `@guidance` Clauses in `intent.clauses`, incoming `realises` from fulfilling Surfaces, incoming `uses` from demanding Surfaces
- **Operation** — parameters, return type, parent Boundary's Container, fulfilling Surfaces (computed)
- **Actor** — `identified_by`/`within` vocabulary content, surfaces facing this Actor (computed)
- **Event** — qualified name (`Container/local_name`), kind, parameters, providers (Surfaces, via incoming `provides`), emitters (Rules, via incoming `emits`), consumers (Rules, via incoming `triggers`)
- **Assertion (Bool Expression in an Intent)** — structure, free variables, source-clause tag (e.g., `Allium::Invariant`, `Allium::Guarantee`), host primitive (Container / Behaviour / Boundary / Operation / Rule), subjects (kernel primitives referenced)
- **Clause (in `Intent.clauses`)** — body (prose), source-clause tag if any (e.g., `Allium::Guidance`)
- **Any projecting primitive** (Container/Entity, Operation, Rule, Event, top-level Invariant Expression) — outgoing `projects` edges with `projection_kind` and `validity` per edge; drift markers (red for absent, green for valid) inline

This is the largest UX surface area change.

---

## Couplings — explicit

Application-level couplings the design pushes onto adjacent decisions. Substrate-level couplings (Event identity, edge identity, relation endpoint shapes, projection vocabulary scope) live in MODEL.md's decisions log and substrate commitments.

1. **Strict containment for composites.** Each module-Container has at most one composite parent. Multi-membership (a module belonging to two subsystems simultaneously) is intentionally unsupported. If needed later, this becomes a non-trivial change to the parent/children mechanism.
2. **Project-layer scope.** Project-wide project-layer entries (constraints + conventions) register against the shared mechanism; subsystem-scoped rules live in `.boundary`. This split is by *scope*, not by mechanism. All are read-only annotations or analyzer configuration over the Model.
3. **No filesystem inference.** Without `.boundary`, modules are flat. This is the *intended* default — projects that want hierarchy write hierarchy. Single-file projects work without `.boundary` at all.
4. **Realisation binding is convention-driven.** Container-to-DataStructure, Rule-to-Function, Operation-to-Function, Invariant-to-Function bindings all resolve via mechanical name resolution against project-level conventions (one root-prefix knob + kind-sensitive transliteration). No code-side annotations; no out-of-band binding files. Strict enforcement — single canonical address per spec primitive; multiple matches or scatter is a lint violation. See the Implementation linkage section above.

---

## Open questions

Application-design questions not resolved in this chapter. Substrate-level TBDs live in [MODEL.md §13](./MODEL.md#13-tbds-consolidated).

1. **Final token-level syntax for `.boundary`.** The reference grammar — `use "..." as <alias>` imports and `alias/Name` qualification (with `contains:` implicit-aliasing inside subsystem blocks) — is settled (see binding semantics above). What remains is token-level polish: precise keyword choices, separator conventions, file header form, whether nested subsystem declarations live in one file or across files. Likely follows Allium's syntactic style for consistency.
2. **Project-layer entry format.** Hardcoded for v0 (both constraints and conventions — per [MODEL.md §10.3](./MODEL.md#103-project-side-constraint-registration--composition)); declarative form in a future iteration. The shape waits until we have enough lived experience to know what's painful.
3. **External-API rendering for composites.** When a user clicks on a composite Container (subsystem), what does the sidebar show — the union of exported surface details, an aggregated boundary summary, or both? Likely both, but the relative emphasis matters for usability.

---

## Summary

The application design layers cleanly onto MODEL.md's substrate. Three Allium boundary protocols (View / Signal / Call) connect Allium's boundary clauses to behavioural content asymmetrically — mutations event-shaped, reads passive or call-shaped. Three spec altitudes — Behaviour, Structure, Infra — with strict one-up reference (lower references upper, never down, never skipping); Implementation is projection across all three. `.allium` covers Behaviour and partial Structure; `.boundary` is the Structure-altitude binding layer that fills Allium's Operation↔Rule gap and adds subsystem composition; `.infra` is the Infra-altitude spec layer. `.allium`, `.boundary`, and the Clojure analyzer (producing convention-resolved `projects` edges to `Code.*` artifacts) are all in MVP; `.infra` is architecturally seamed. A project layer carries two flavours of project-side declaration — constraints over the Model and conventions consumed by the Clojure analyzer — both making the project's design vocabulary explicit for human readers, for LLMs generating spec, and for the build pipeline. The explorer respects visibility, surfaces gaps, and renders cross-altitude drift markers actively.

Each addition is justified by a constraint that admits no other clean solution; each deferral preserves the architectural seam for later. The MVP runs spec-plus-code end-to-end; subsequent chapters add layers (`.infra`, more methodologies, declarative project-layer form) without rewriting the substrate.

---

*See [VISION.md](./VISION.md) for the framing, motivation, and roadmap. See [MODEL.md](./MODEL.md) for the substrate spec.*
