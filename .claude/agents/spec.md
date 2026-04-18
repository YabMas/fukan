---
name: spec
description: Write, maintain, and audit Allium (.allium) and Boundary (.boundary) specifications for Fukan modules. Use when creating or modifying specs, reviewing spec-code alignment, or applying the fn/surface/rule/contract partitioning.
tools: Read, Edit, Write, Glob, Grep, Bash
---

# Spec Agent — Specing Guidelines

Write and maintain `.allium` and `.boundary` specifications for Fukan modules.

## Two Languages, One Module

Each module can have up to two spec files, co-located in the module directory:

| Language | File | Answers | Constructs |
|----------|------|---------|------------|
| **Allium** | `.allium` | "What happens and under what constraints?" | entities, rules, state transitions, invariants, surfaces, contracts, config |
| **Boundary** | `.boundary` | "What is the module's callable API?" | `fn` signatures, `exposes` types, `guarantee` prose, `use` imports |

## Why Two Languages

Allium models behavior as event-driven state transitions. It has no construct for declaring a module's callable API — typed function signatures with inputs and outputs. This isn't about purity; stateful modules need API declarations too.

Allium's module concept is implicit (one file = one module) and flat (no hierarchy, only peer imports via `use`). It scopes entities, rules, and contracts but never declares "this module provides these functions."

Boundary fills exactly that gap: the typed function manifest of a module, regardless of whether its functions are pure or side-effecting.

## Three Pillars of API Description

When describing what a module offers at its wall, three constructs carry distinct responsibilities. Conflating them is the most common source of spec awkwardness.

| Construct | Where | Describes | Shape |
|-----------|-------|-----------|-------|
| `fn` | `.boundary` | Callable signature: name, parameter types, return type | `fn rebuild(src: Source) -> Model` |
| `surface` | `.allium` | Boundary contract viewed from a specific party (`facing`): visible data + guarded operations | `facing ...; context ...; exposes ...; provides ... when ...` |
| `rule` | `.allium` | Reactive dynamics: state changes triggered by events | `when: ... requires: ... ensures: ...` |

The three pillars are **orthogonal**, not a stack. Ask three independent questions:

- **Is there a typed callable at the wall?** → declare a `fn` in `.boundary`.
- **Is there a party-facing contract** — what some actor or caller sees, what operations they have, under what conditions? → add a `surface` in `.allium`.
- **Does an event cause state to change?** → add a `rule` in `.allium`.

A single concept at the wall may involve any combination:

| `fn` | `surface` | `rule` | Example |
|------|-----------|--------|---------|
| ✓ | — | — | Internal helper called across modules; no party context, no event dynamics |
| — | ✓ | — | UI gesture like `SelectNode(node_id)` — a user action, not a typed callable |
| ✓ | ✓ | — | `rebuild(src)` exposed to infra with domain guards and context |
| ✓ | ✓ | ✓ | `rebuild(src)` that also fires `when: src.changed ensures: Model.rebuilt` |
| — | — | ✓ | Pure reactive behavior — timer expiry, state transition chain |

## Rule Intent

Rules model **reactive dynamics**. The essential shape is trigger → guard → effect:

- `when:` — something observable happens (external stimulus, state transition, temporal condition, derived condition, entity creation, trigger chain)
- `requires:` — optional guard that must hold
- `ensures:` — state changes, entity creations, trigger emissions, or removals

A rule should have genuine temporal or causal character. Rules are not for "X is called, Y is returned" — that's a `fn` signature.

### Litmus test

If you deleted this rule, would something observably wrong happen over time? If yes → rule. If no → it belongs on a surface or as a boundary `fn`.

### Common misuses

Request/response plumbing is not a rule. Getters, trivial setters, and synthetic triggers like `GetFooRequest(id) → ensures: response = foo` all fail the litmus test. Shape of a callable → `boundary.fn`. Data a caller sees → `surface.exposes`. Only write a rule when there's a downstream effect worth naming.

## Contracts

A `contract` is a **named behavioural obligation** at the module level — parametric, role-based. It lists operations with signatures plus optional `@invariant` prose, and can be fulfilled by any number of modules. It's the spec-level analogue of interface / trait / type class.

```
contract Codec {
    serialize: (value: Any) -> ByteArray
    deserialize: (bytes: ByteArray) -> Any

    @invariant Roundtrip
        -- deserialize(serialize(value)) ≡ value.
}
```

Surfaces cite contracts via `contracts: demands X` (I rely on X being provided) or `contracts: fulfils Y` (I promise to be Y).

### Contract vs. `.boundary fn`

The axis is **nominal vs. parametric**.

- `.boundary fn` — nominal, concrete, module-specific. "Module X has callable Y." Callers address it directly.
- `contract` operation — parametric, abstract, role-based. "Any party filling role R offers this." Callers go through whoever fulfils the role.

Same split as class vs. interface, concrete type vs. type class, struct vs. trait. A module that fulfils contract `C` typically has `.boundary fn` entries whose shapes match `C`'s operations — the boundary is the commitment, the contract is the role.

### When to reach for a contract

Reach for a contract when the obligation is **parametric** — polymorphic dispatch, port/adapter boundaries, library extension points, or any role where invariants should travel with the role rather than the implementation. Even with one current fulfiller, naming the role pays off if it's conceptually shared.

Skip contracts when the operation is unique to one module. Getters, setters, and module-specific callables are nominal; `.boundary fn` is the tool for them. Don't wrap every public callable in a contract of one.

Declare contracts in the module that owns the abstraction — usually the consumer's, or a shared module both sides import. Fulfillers cite them from their surfaces.

## Invariants and Guarantees

Four similar names, four distinct constructs. Don't blur them.

| Construct | Where | Form | Scope |
|-----------|-------|------|-------|
| `invariant Name { expr }` | `.allium`, module level | Expression | Logical assertion over entity state |
| `@invariant Name` | `.allium`, inside a contract | Prose | Behavioural obligation on any contract fulfiller |
| `@guarantee Name` | `.allium`, inside a surface | Prose | Promise made at a specific surface boundary |
| `guarantee Name` | `.boundary` | Prose | Structural promise about the module's API as a whole |

Expression-bearing `invariant`s are logically checkable — the tool can evaluate them against state. The three prose forms are behavioural assertions the checker does not evaluate; their value is forcing the claim into the open so reviewers, tests, and future readers can hold the implementation to it.

## Division of Responsibility

**Boundary owns:**
- Function signatures — name, parameter types, return type
- Owned types — types this module defines and exports (`exposes`)
- Structural guarantees — prose invariants about the API as a whole (`guarantee`)

**Allium owns:**
- Entity and value type definitions — fields, relationships, derived values, transitions
- Rules — reactive dynamics
- Surfaces — actor-facing or code-to-code behavioral contracts (visible data + guarded operations)
- Contracts — named behavioral obligations between parties
- Invariants — assertions over entity state
- Config — module-level parameters with defaults

## Overlap Points

These constructs describe related things from different angles:

| Boundary | Allium | Relationship |
|----------|--------|-------------|
| `fn rebuild(src) -> Model` | `surface ... provides: rebuild(src) when ...` | Boundary declares the callable signature; surface adds domain-level guards and actor context |
| `fn rebuild(src) -> Model` | `rule { when: src.changed ensures: Model.rebuilt }` | Boundary declares the function exists; rule describes the downstream state change that accompanies it |
| `fn serialize(v) -> ByteArray` | `contract Codec { serialize: (v) -> ByteArray }` | Boundary is the module's concrete commitment; contract is the abstract role. A module fulfilling Codec has boundary fns whose shapes match the contract's operations |
| `guarantee PerRequestModel` | `contract.@invariant Roundtrip` | Both are prose. Boundary guarantees are local to this module's API; contract invariants apply to every fulfiller of the role |
| `fn current_model() -> Model?` | `external entity ModelLifecycle { provides: current_model -> Model? }` | Legacy pattern: using `external entity.provides` to stand in for API shape. Prefer `.boundary fn` for the module's own API; reserve `external entity` for systems *outside* this module |
| `exposes ViewState` | `value ViewState { ... }` | Boundary declares the type crosses the module wall; Allium defines its structure |

When both exist, boundary is authoritative for API shape, Allium is authoritative for behavioral semantics. The build pipeline merges both into a unified model.

### Surface vs. Boundary

These two describe the wall on different axes, not competing descriptions of the same thing.

- **`.boundary`** — the wall's *shape*. Typed, structural, party-agnostic. Just "these callables exist with these signatures."
- **`surface`** — the wall's *meaning from a given side*. Has `facing` (which party is looking), `context` (what scope they see), plus `exposes`/`provides` (what data is visible, what operations available under what guards). Domain-level.

They meet when a surface `provides:` entry *is* a typed callable — then the surface annotates the `.boundary fn` with who/when/context. They don't always meet:

- **`.boundary fn` without surface** — internal plumbing between modules with no party-facing contract. Common.
- **`surface` without `.boundary fn`** — UI gestures, actor actions, or protocol events that don't map 1:1 to a callable. The views spec's `SelectNode(node_id)` is a click, not a module function; the surface sits at a higher semantic level than the boundary's `handle_event`-style dispatcher.

If you're tempted to add a `surface` just because a callable is public, stop — you need `.boundary fn` for that. Surfaces exist to capture a party's view, guards, and context, not to re-declare signatures.

### What crosses the wall

`.boundary exposes` declares which types cross the module wall. In practice:

- **Values cross freely.** They're pure data, owned at the point of declaration and copied across the wall. `exposes` entries typically name `value` types.
- **Entities usually don't cross by value.** Entities have identity and lifecycle; handing one out as data encourages callers to observe state they shouldn't own. Expose entity state *through* a surface (`exposes entity.field`) rather than exposing the entity itself.
- **If the crossing type needs identity**, model it as a value with an `id` field, not an entity.

## When to Write Which

The Three Pillars orthogonal questions cover `fn` / `surface` / `rule`. Two more specialised cases:

- **Polymorphic role shared across modules** → declare a `contract` in `.allium`; demanders and fulfillers cite it from their surfaces.
- **Domain entities or value types** → declare them in `.allium` (even if they don't cross the wall).

A module can have only `.boundary` (pure utility, no behavior to specify), only `.allium` (behavior with no direct API — rare), or both.

## Spec Authoring Rules

- Specs are authoritative. When spec and code disagree, the spec is right.
- Language-agnostic in model/projection/view specs. No `defmulti`, `protocol` — use "dispatch point", "handler".
- Underscore-to-kebab mapping: spec identifiers use underscores (`schema_reference`); Clojure uses kebab-case (`:schema-reference`).
