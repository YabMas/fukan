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

## Division of Responsibility

**Boundary owns:**
- Function signatures — name, parameter types, return type
- Owned types — types this module defines and exports (`exposes`)
- Structural guarantees — prose invariants about the API as a whole (`guarantee`)

**Allium owns:**
- Entity and value type definitions — their fields, relationships, derived values, transitions
- Rules — when X happens, ensure Y
- Surfaces — actor-facing behavioral contracts (what an actor sees and can do, with guards)
- Contracts — named behavioral obligations between parties
- Invariants — assertions over entity state
- Config — module-level parameters with defaults

## Overlap Points

These constructs describe related things from different angles:

| Boundary | Allium | Relationship |
|----------|--------|-------------|
| `fn rebuild(src) -> Model` | `external entity` with `provides: rebuild(src) -> Model` | Boundary declares the signature; Allium adds guarantees, failure modes, state semantics |
| `exposes ViewState` | `value ViewState { ... }` | Boundary declares the type crosses the module wall; Allium defines the type's structure |
| `guarantee PerRequestModel` | `@guarantee DataInEventsOut` | Both are prose. Boundary guarantees are about the API contract; Allium guarantees are about the behavioral boundary |

When both exist, boundary is authoritative for API shape, Allium is authoritative for behavioral semantics. The build pipeline merges both into a unified model.

## When to Write Which

- **Module has callable functions** → write a `.boundary` (always)
- **Module has behavioral rules, state transitions, or entity definitions** → write an `.allium`
- **Module has both** → write both, keep them consistent

A module can have only `.boundary` (pure utility with no interesting behavior), only `.allium` (behavioral spec with no direct API — rare), or both.

## Spec Authoring Rules

- Specs are authoritative. When spec and code disagree, the spec is right.
- Language-agnostic in model/projection/view specs. No `defmulti`, `protocol` — use "dispatch point", "handler".
- Underscore-to-kebab mapping: spec identifiers use underscores (`schema_reference`); Clojure uses kebab-case (`:schema-reference`).
