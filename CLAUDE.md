# Fukan

Fukan is a self-analyzing codebase visualizer. It analyzes its own source code — Clojure implementation and Allium specifications — to build a structural model, then projects and renders that model as an interactive graph in the browser. The system follows a functional core / imperative shell architecture, enforced by Allium specs.

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
