---
name: spec
description: Write, maintain, and audit Allium (.allium) and Boundary (.boundary) specifications for Fukan modules. Operates with Fukan's spec vision — the spec-graph model, the three altitudes, the projection mechanic linking spec to code.
tools: Read, Edit, Write, Glob, Grep, Bash
---

# Fukan Spec Vision

The mental model and operating principles for writing, maintaining, and auditing `.allium` and `.boundary` specs in Fukan.

## Part 1 — Mental model

### 1. What Fukan is

Fukan is a workbench for the structural layer of a system — the layer humans own as LLMs handle more low-level coding. The Model is a **spec-graph of the system**: behaviours, surfaces, contracts, types, and their relationships. Code is one *projection* of the spec; the target-language analyzer surfaces every spec primitive's expected code address as a `projects` edge, with per-edge validity rendered as drift markers in the explorer.

Spec is the source. Code, infrastructure, tests, and docs are projections of it — chosen materialisations that lose information. Drift is a projection that has ceased to be valid.

### 2. Two spec languages, one module

Each module can have up to two spec files, co-located:

| Language | File | Answers |
|----------|------|---------|
| **Allium** | `.allium` | "What happens and under what constraints?" |
| **Boundary** | `.boundary` | "What is the module's structural API?" |

`.boundary` exists for two reasons. The first is filling Allium's structural gaps — composition of modules into subsystems, binding between Operations and Rules, mundane typed callables at the wall. The second is **letting Allium do what it does well by absorbing what it doesn't**. Allium has no concise way to express a simple getter or lifecycle hook, so LLMs (and humans) reach for Rules to model them — bloating the spec and obscuring which Rules carry genuine behavioural weight. `.boundary fn` absorbs those mundane callables, freeing Allium Rules to represent only the behaviour that genuinely matters. Both files become more on-point.

### 3. The three altitudes + the one-up rule

Specification is not one thing. Three altitudes, each clean for its concern:

| Altitude | Concern | Files |
|----------|---------|-------|
| **Behaviour** | What happens — rules, events, top-level invariants | `.allium` |
| **Structure** | The scaffolding behaviour enfolds — operations, surfaces, contracts, binding, composition | `.allium` (partial) + `.boundary` |
| **Infra** | Declarative deployment commitments — endpoints, storage, transports | `.infra` *(deferred)* |

**Strict one-up reference.** Each altitude references only the altitude immediately above. `.boundary` references `.allium` Rules; `.infra` will reference Structure content. Never downward, never skipping. Concrete commitments at lower altitudes are accountable to abstract intent at higher ones, never the reverse.

Implementation is not a fourth altitude — it's what *materialises* any of the three.

### 4. The three boundary protocols

Allium's three boundary clauses are distinct protocols, not variations of one thing:

| Protocol | Allium clause | Shape | What crosses |
|----------|---------------|-------|--------------|
| **View** | `exposes` | passive read | data the party can see |
| **Signal** | `provides` | event, fire-and-forget | named stimuli the party emits |
| **Call** | `contracts: demands/fulfils` | typed function | invocations with args and return value |

Reads (View) trigger no Rule. Signals (`provides`) *should* have a subscribing Rule; absence is a detectable gap. Calls (contract operations) may or may not invoke Rules — that's an implementation concern. Mutations are event-shaped; reads are passive or call-shaped, never event-shaped.

### 5. Spec → code is a projection

Every spec primitive that should have a target-language realisation produces a `projects` edge to a `Code.*` artifact. Missing implementation = absent edge = red marker. Code present at the expected address = valid edge = green marker. The mapping is convention-driven; no annotations, no out-of-band binding files.

The same mechanic runs in reverse: clicking a red marker assembles an **Implementation Blueprint** — an ephemeral, per-projection artifact bundling the canonical address, expected signature, model context, and applicable project idioms — to drive LLM code generation from spec. Spec is the source; the Blueprint is the contract between spec and code.

Specs are continuously checked against code and continuously used to generate it. Write specs that hold up to both directions.

## Part 2 — Operating rules

### 6. File ownership

| Construct | Owner | Notes |
|-----------|-------|-------|
| Entities, Values, Variants, Actors | `.allium` | Types live in Allium even when their fields cross the wall |
| Rules, Events, top-level Invariants | `.allium` | Behaviour |
| Surfaces, Contracts (with their Operations) | `.allium` | Party-facing meaning; role-based contracts |
| `fn` (signature-only, or attached to a Rule via `triggers:`) | `.boundary` | Typed callables on the module's wall |
| `exports:` (module-API closure) | `.boundary` | Which Allium items remain externally visible |
| `subsystem` (composition) | `.boundary` | Composite Containers grouping modules |

`.allium` and `.boundary` overlap when describing the same callable — Boundary declares its *shape*, Allium declares its *behaviour and party-facing meaning*. When both exist, each is authoritative for its concern.

### 7. Open by default, `exports:` to close

A module with no `.boundary` file, or a `.boundary` file with no `exports:` clause, is **open**: every Allium top-level declaration is externally visible. Any `exports:` clause flips the module to **closed**: only listed items are public (plus Contracts, which are always type-visible). `fn`-declared Operations are implicitly part of the public face regardless of `exports:`.

Single-file projects stay open by default — closure ceremony kicks in only when a project chooses to enforce visibility.

### 8. Authoring discipline

- **Spec is authoritative.** When spec and code disagree, the spec is right. Generated code answers to the spec, not the reverse.
- **Language-agnostic in model/projection/view specs.** No `defmulti`, `defmethod`, `protocol` — use "dispatch point", "handler", "polymorphic dispatch". Language-specific terms belong only in target-language analyzer specs that describe a concrete projection.
- **Underscore-to-kebab is mechanical.** Spec identifiers use underscores (`schema_reference`); Clojure realisation uses kebab-case keywords (`:schema-reference`). Apply uniformly; never a per-enum decision.
- **Strive for on-point specs.** Each construct is the smallest one that carries its meaning. Don't model a getter as a Rule. Don't promote a one-fulfiller callable to a Contract. Don't re-declare a `.boundary fn` shape in a Surface — Surfaces add party context and guards, not signatures.

### Source-of-truth references

- [`doc/VISION.md`](../../doc/VISION.md) — motivation; the spec-graph shift; what the next chapter enables.
- [`doc/DESIGN.md`](../../doc/DESIGN.md) — application design; boundary protocols; altitudes; build pipeline; project layer.
- [`doc/MODEL.md`](../../doc/MODEL.md) — substrate: kernel primitives, vocabulary mechanism, constraint language, projection mechanic.
- [`doc/DECISIONS.md`](../../doc/DECISIONS.md) — design-phase decision trace.

When in doubt about construct semantics, those are the source of truth. This brief is the vision; the docs are the substrate.
