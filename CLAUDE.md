# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven development. It analyzes a target codebase — implementation code and behavioral specifications — to build a unified structural model, then projects and renders that model as an interactive graph in the browser. The core question it explores: as LLMs handle more low-level coding, how do humans maintain control over high-level structure and collaborate with LLMs at that level of abstraction?

The system is generic and pluggable: language analyzers (currently Clojure and Allium) register via multimethod dispatch, and the build pipeline is language-agnostic. Specifications and implementation are projected onto the same model so that intended structure (boundaries, contracts, guarantees) and actual structure (namespaces, functions, schemas) appear together. Documentation is a first-class input — it flows into the model to make the explorer meaningful, not just structural.

The system follows a functional core / imperative shell architecture, enforced by Allium specs.

## Spec Locations (source of truth)

| Spec file | Scope |
|-----------|-------|
| `src/fukan/model/spec.allium` | System model — node hierarchy, edge semantics, structural invariants |
| `src/fukan/model/pipeline.allium` | Build pipeline — analysis results, analyzer boundaries, build rule |
| `src/fukan/model/analyzers/implementation/spec.allium` | Implementation analyzer boundary reference |
| `src/fukan/model/analyzers/specification/spec.allium` | Specification analyzer boundary reference |
| `src/fukan/infra/spec.allium` | Infrastructure lifecycle — model state, server lifecycle |
| `src/fukan/projection/spec.allium` | Projection — pure computation from model to visible subgraph |
| `src/fukan/web/spec.allium` | HTTP/SSE transport boundary |
| `src/fukan/web/views/spec.allium` | View rendering and interaction semantics |

Specs are the authoritative description of system behavior. Implementation follows specs. Tests encode spec invariants. When spec and code disagree, the spec is right.

## Spec Authoring Rules

- **Language-agnostic model specs.** The model, projection, and view specs describe domain concepts using standard PL terminology. Never use language-specific examples (e.g. `defmulti`, `defmethod`, `protocol`) in these specs — use generic terms like "dispatch point", "handler", "polymorphic dispatch". Language-specific details belong only in analyzer boundary specs where they describe a concrete implementation.
- **Underscore-to-kebab mapping.** Allium identifiers use underscores (`schema_reference`, `function_call`); Clojure implementation maps these to kebab-case keywords (`:schema-reference`, `:function-call`). This is mechanical and universal — not a per-enum decision.
