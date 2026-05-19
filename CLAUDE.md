# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven development. It analyzes a target codebase — implementation code and behavioral specifications — to build a unified structural model, then projects and renders that model as an interactive graph in the browser. The core question it explores: as LLMs handle more low-level coding, how do humans maintain control over high-level structure and collaborate with LLMs at that level of abstraction?

The system is generic and pluggable: language analyzers (currently Clojure, Allium, and Boundary) register via multimethod dispatch, and the build pipeline is language-agnostic. Specifications and implementation are projected onto the same model so that intended structure (boundaries, contracts, guarantees) and actual structure (namespaces, functions, schemas) appear together. Documentation is a first-class input — it flows into the model to make the explorer meaningful, not just structural.

The system follows a functional core / imperative shell architecture, enforced by Allium specs and Boundary definitions.

## Two Spec Languages

Fukan uses two complementary specification languages with distinct responsibilities:

- **`.boundary`** — Structure-altitude language. Answers "what crosses the module wall." Single primitive `fn` (typed callable on the module's Boundary, with optional `triggers:`/`returns:` body attaching to an Allium Rule), plus `exports:` for module-API closure, plus `subsystem` for grouping modules. Reference: [MODEL.md §8.2](doc/MODEL.md), [DESIGN.md `.boundary responsibilities`](doc/DESIGN.md).
- **`.allium`** — Behaviour-altitude specification: describes how things change over time (rules, state transitions, invariants, guarantees, component interaction models). Answers "what happens and under what constraints."

When both exist for a module, `.allium` owns the behavioural semantics and `.boundary` carries the module's structural surface (functions, closure, bindings to behaviour). A module with no `.boundary` is **open** (every Allium top-level decl externally visible); adding `exports:` flips it closed. Both files are co-located in the module directory.

## Spec Locations (source of truth)

### Boundary files (module API definitions)

| Boundary file | Scope |
|---------------|-------|
| `src/fukan/web/spec.boundary` | HTTP/SSE transport — public handler API |
| `src/fukan/infra/spec.boundary` | Infrastructure lifecycle — model/server management API |
| `src/fukan/projection/spec.boundary` | Projection — pure computation API |
| `src/fukan/web/views/spec.boundary` | View rendering — HTML/Cytoscape output API |
| `src/fukan/model/pipeline.boundary` | Build pipeline — model construction API |
| `src/fukan/model/analyzers/implementation/spec.boundary` | Implementation analyzer API |
| `src/fukan/model/analyzers/specification/spec.boundary` | Specification analyzer API |

### Allium files (behavioral specifications)

| Allium file | Scope |
|-------------|-------|
| `src/fukan/model/spec.allium` | System model — node hierarchy, edge semantics, structural invariants |
| `src/fukan/model/pipeline.allium` | Build pipeline — build rules, contract checking rules |
| `src/fukan/model/analyzers/implementation/spec.allium` | Implementation analyzer boundary reference |
| `src/fukan/model/analyzers/specification/spec.allium` | Specification analyzer boundary reference |
| `src/fukan/infra/spec.allium` | Infrastructure lifecycle — external entity contracts |
| `src/fukan/projection/spec.allium` | Projection — rules for graph computation, edge aggregation |
| `src/fukan/web/spec.allium` | HTTP/SSE transport — external entity contracts |
| `src/fukan/web/views/spec.allium` | View rendering — interaction model, component behavior |

Specs are the authoritative description of system structure and behavior. Implementation follows specs. Tests encode spec invariants. When spec and code disagree, the spec is right.

## Spec Authoring Rules

- **Boundary for structure, Allium for behavior.** Wall-crossing function signatures (`fn`) and owned-type exports (`exports:`) go in `.boundary` files. Rules, state transitions, invariants, guarantees, contracts, surfaces, and component interaction models go in `.allium` files. A `fn` that has interesting behavioural semantics attaches to an Allium Rule via its `triggers:` clause; mundane callables (renders, getters, lifecycle) declare only their signature in `.boundary` and live entirely outside Allium.
- **Language-agnostic model specs.** The model, projection, and view specs describe domain concepts using standard PL terminology. Never use language-specific examples (e.g. `defmulti`, `defmethod`, `protocol`) in these specs — use generic terms like "dispatch point", "handler", "polymorphic dispatch". Language-specific details belong only in analyzer boundary specs where they describe a concrete implementation.
- **Underscore-to-kebab mapping.** Allium and Boundary identifiers use underscores (`schema_reference`, `function_call`); Clojure implementation maps these to kebab-case keywords (`:schema-reference`, `:function-call`). This is mechanical and universal — not a per-enum decision.
