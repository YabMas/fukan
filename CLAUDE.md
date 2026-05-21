# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven development. It analyzes a target codebase — implementation code and behavioral specifications — to build a unified structural model, then projects and renders that model as an interactive graph in the browser. The core question it explores: as LLMs handle more low-level coding, how do humans maintain control over high-level structure and collaborate with LLMs at that level of abstraction?

The system is generic and pluggable: language analyzers (currently Clojure, Allium, and Boundary) register via multimethod dispatch, and the build pipeline is language-agnostic. Specifications and implementation are projected onto the same model so that intended structure (boundaries, contracts, guarantees) and actual structure (namespaces, functions, schemas) appear together. Documentation is a first-class input — it flows into the model to make the explorer meaningful, not just structural.

The system follows a functional core / imperative shell architecture, enforced by Allium specs and Boundary definitions.

## Querying the Model as an agent

Fukan exposes its Model to coding agents through `bin/fukan`. When working on or with Fukan, prefer querying the spec graph over grepping the codebase.

- **Primer:** [AGENTS.md](AGENTS.md) — read it before the first agent query. Covers the `fukan.agent.system` / `fukan.agent.api` surface, L0/L1/L2 query layering, the edit→refresh→query loop, `.fukan/agent-views.clj`, and sandbox limits.
- **Quick reference:** `fukan status` (daemon health), `fukan eval '<expr>'` (run a query), `fukan primer` (print AGENTS.md). Requires a running daemon (`clj -M:run …`).
- **Live catalog:** inside `fukan eval`, call `(help)` for the current fn surface — trust it over `AGENTS.md` if they disagree.

## Two Spec Languages

Fukan uses two complementary specification languages with distinct responsibilities:

- **`.boundary`** — Structure-altitude language. Answers "what crosses the module wall." Single primitive `fn` (typed callable on the module's Boundary, with optional `triggers:`/`returns:` body attaching to an Allium Rule), plus `exports:` for module-API closure, plus `subsystem` for grouping modules. Reference: [MODEL.md §8.2](doc/MODEL.md), [DESIGN.md `.boundary responsibilities`](doc/DESIGN.md).
- **`.allium`** — Behaviour-altitude specification: describes how things change over time (rules, state transitions, invariants, guarantees, component interaction models). Answers "what happens and under what constraints."

When both exist for a module, `.allium` owns the behavioural semantics and `.boundary` carries the module's structural surface (functions, closure, bindings to behaviour). A module with no `.boundary` is **open** (every Allium top-level decl externally visible); adding `exports:` flips it closed. Both files are co-located in the module directory.

## Spec Locations (source of truth)

### Boundary files (module API definitions)

Specs live as sibling `.allium` / `.boundary` files at the module's directory (e.g. `infra/model.allium` + `infra/model.boundary`). Subsystem-bound `.boundary` files use the `<dir>/<dir>.boundary` convention inside the directory they group.

#### Subsystem boundary files

| File | Scope |
|------|-------|
| `src/fukan/model/model.boundary` | Subsystem `model` — substrate + build pipeline |
| `src/fukan/infra/infra.boundary` | Subsystem `infra` — imperative-shell lifecycle |
| `src/fukan/web/views/views.boundary` | Subsystem `views` — view rendering modules |
| `src/fukan/web/web.boundary` | Composite subsystem `web` — handler + nested `views` |

#### Module boundary files

| File | Scope |
|------|-------|
| `src/fukan/model/pipeline.boundary` | Build pipeline — `build_model` API |
| `src/fukan/infra/model.boundary` | Model lifecycle — load / refresh / get |
| `src/fukan/infra/server.boundary` | Server lifecycle — start / stop / port |
| `src/fukan/web/handler.boundary` | HTTP routing — `create_handler` |
| `src/fukan/web/views/breadcrumb.boundary` | Breadcrumb rendering API |
| `src/fukan/web/views/cytoscape.boundary` | Cytoscape transformer API |
| `src/fukan/web/views/graph.boundary` | Graph view render + interaction surface |
| `src/fukan/web/views/shell.boundary` | App shell rendering API |
| `src/fukan/web/views/sidebar.boundary` | Sidebar rendering API |

### Allium files (behavioral specifications)

| Allium file | Scope |
|-------------|-------|
| `src/fukan/model/spec.allium` | Kernel substrate — primitives, types, expressions, effects, vocabulary mechanism, kernel relations |
| `src/fukan/model/pipeline.allium` | Build pipeline — phase ordering, gate G2, defaults registration |
| `src/fukan/infra/model.allium` | Model lifecycle — load/refresh guarantees |
| `src/fukan/infra/server.allium` | Server lifecycle — start/stop guarantees |
| `src/fukan/web/handler.allium` | HTTP/SSE transport — view transport surface, per-request model guarantees |
| `src/fukan/web/views/breadcrumb.allium` | Breadcrumb render invariants |
| `src/fukan/web/views/cytoscape.allium` | Cytoscape output value types |
| `src/fukan/web/views/graph.allium` | Graph view interaction model — selection, navigation, expansion rules |
| `src/fukan/web/views/projection.allium` | Projection stub (Plan 2b carry-forward) |
| `src/fukan/web/views/shell.allium` | App shell scope |
| `src/fukan/web/views/sidebar.allium` | Sidebar layout invariants |

Several subtrees are currently unspecced and queued for distillation: `agent/`, `constraint/`, `libs/`, `project_layer/`, `target/`, `utils/`, `validation/`, `vocabulary/`, plus internals of `model/` (artifact, build, effect, expression, primitives, relations, type, vocabulary).

Specs are the authoritative description of system structure and behavior. Implementation follows specs. Tests encode spec invariants. When spec and code disagree, the spec is right.

## Spec Authoring Rules

- **Boundary for structure, Allium for behavior.** Wall-crossing function signatures (`fn`) and owned-type exports (`exports:`) go in `.boundary` files. Rules, state transitions, invariants, guarantees, contracts, surfaces, and component interaction models go in `.allium` files. A `fn` that has interesting behavioural semantics attaches to an Allium Rule via its `triggers:` clause; mundane callables (renders, getters, lifecycle) declare only their signature in `.boundary` and live entirely outside Allium.
- **Language-agnostic model specs.** The model, projection, and view specs describe domain concepts using standard PL terminology. Never use language-specific examples (e.g. `defmulti`, `defmethod`, `protocol`) in these specs — use generic terms like "dispatch point", "handler", "polymorphic dispatch". Language-specific details belong only in analyzer boundary specs where they describe a concrete implementation.
- **Underscore-to-kebab mapping.** Allium and Boundary identifiers use underscores (`schema_reference`, `function_call`); Clojure implementation maps these to kebab-case keywords (`:schema-reference`, `:function-call`). This is mechanical and universal — not a per-enum decision.
