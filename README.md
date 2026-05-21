# Fukan

Fukan is a structural exploration workbench for the layer humans own as LLMs handle more of the low-level coding — behavioural intent, module composition, boundary contracts, invariants, and the relationships between them.

It analyses a target system to build a unified structural Model, then renders it as an interactive graph in the browser. Behavioural specifications, architectural composition, and (later) implementation reality are projected onto the same Model so that what the system *is meant to do* and what it *actually does* appear together in one place.

> **A new chapter is in design.** Fukan is being re-foundationed as a specification-led structural explorer. The Allium specification language becomes the shape of the Model; structural composition gets its own language (`.boundary`); code joins later as a realisation layer. See [doc/VISION.md](doc/VISION.md) for the framing and roadmap, and [doc/DESIGN.md](doc/DESIGN.md) for the technical specification. The sections below describe the current implementation, which is being incrementally migrated.

## Three altitudes of specification

The next-chapter design separates three concerns into three languages, each at a different altitude:

- **`*.allium`** — Behavioural specifications: what each module does. Entities, behaviours, invariants, surfaces, contracts, actors, triggers.
- **`*.boundary`** — Structural specifications: how modules compose into subsystems, and which surfaces each subsystem exposes externally. (In the next chapter, `.boundary` shifts from its current role as a small API IDL to owning subsystem composition.)
- **`*.infra`** — *(later)* Implementation specifications: how the system materialises mechanically — endpoints, storage, wire formats, transports, deployment topology.

Each layer is opt-in, each enriches the same unified Model, and dependencies flow one way: higher altitudes reference lower ones, never the reverse.

A separate concern, orthogonal to altitude, is the **guidance layer** — where a project makes explicit how it uses the generic spec primitives. Guidance entries serve three audiences from the same content: human readers orienting to the project, LLMs designing or extending spec, and (where machine-checkable) the build pipeline validating conformance. See [doc/DESIGN.md](doc/DESIGN.md) for the full specification.

## Key ideas

- **Spec-led Model.** The Model's vocabulary is determined by what the spec says exists. Code joins later as the realisation layer beneath, with realisation edges bridging spec and reality. (Currently transitional: the implementation Model still has its origins in code-graph thinking; see [doc/DESIGN.md](doc/DESIGN.md) for the target shape.)
- **Boundaries as first-class.** Each Surface declares one boundary between two parties, with three protocols crossing it: View (passive read), Signal (event-shaped action), Call (typed invocation). Each protocol connects to the behavioural core differently.
- **Topology as design surface.** With `.boundary` owning structural composition, restructuring the system is a one-file edit, not a refactor. Try multiple subsystem decompositions side-by-side without disturbing behavioural specs.
- **Documentation as structure.** Descriptions, guarantees, and prose annotations flow into the Model. The explorer conveys understanding, not just shape.

## Usage (current implementation)

```
clj -M:run --src /path/to/project/src --analyzers clojure,allium --port 8080
```

| Flag | Description | Default |
|------|-------------|---------|
| `--src PATH` | Path to source directory (required) | — |
| `--analyzers KEY,KEY,...` | Comma-separated analyzer keys (required) | — |
| `--port PORT` | Server port | 8080 |

Then open `http://localhost:8080` in a browser.

The Clojure analyzer (and code-analysis machinery generally) will be retired during the next-chapter migration. Future releases will analyse `.allium` and `.boundary` files only, until implementation analysis re-joins in a subsequent chapter.

## For coding agents

Fukan exposes the same Model to coding agents (Claude Code, Cursor, Codex, etc.) through a small CLI. Once a daemon is running, agents query the spec graph instead of grepping the codebase.

[AGENTS.md](AGENTS.md) is the primer — read it first. It covers the `fukan.agent.system` / `fukan.agent.api` surface, the L0 / L1 / L2 query layering, the edit→refresh→query loop, persisted views in `.fukan/agent-views.clj`, and the eval sandbox limits.

### Install the CLI

`bin/fukan` is a [babashka](https://babashka.org) script. Put it on `PATH`:

```bash
ln -s "$PWD/bin/fukan" /usr/local/bin/fukan      # or any dir on PATH
export FUKAN_HOME="$PWD"                          # so `fukan primer` finds AGENTS.md
```

`FUKAN_URL` overrides the daemon address (default `http://127.0.0.1:8080`). `FUKAN_HOME` lets `fukan primer` print `AGENTS.md` from anywhere; without it, run `fukan primer` from the repo root.

### Commands

| Command | What it does |
|---------|--------------|
| `fukan status` | Daemon health + loaded-model summary |
| `fukan eval '<expr>'` | Run an L0/L1/L2 query in the SCI sandbox |
| `fukan primer` | Print `AGENTS.md` to stdout |
| `fukan init` | Add a Fukan section to the current project's `AGENTS.md` (creates the file if missing), pointing other agents at `fukan primer` |

The daemon must be running (`clj -M:run …`) for `status` and `eval`.

### Quick check

```bash
fukan status
fukan eval '(vocabulary)'
fukan eval '(drift)'
```

## Development

Requires Clojure CLI (`clj`) and [clj-kondo](https://github.com/clj-kondo/clj-kondo) on PATH.

```bash
# Start nREPL (port 7889)
clj -M:dev:nrepl

# In the REPL
(start)          ; analyze + start server
(refresh)        ; reload changed code + rebuild model
(reset)          ; full server restart
(status)         ; show server/model state

# Run tests
clj -M:test
```

## Navigating the graph

The graph renders modules as compound nodes that contain functions, schemas, and nested modules. Navigation works at two levels: **selection** (inspecting within the current view) and **navigation** (changing what you're viewing).

| Action | Effect |
|--------|--------|
| **Click** a node | Select it — sidebar shows details (boundary, signatures, docs, dependencies) |
| **Double-click** a module | Navigate into it — the module becomes the view, its children are shown |
| **Right-click** a module | Expand/collapse — reveal or hide children inline without navigating |
| **Shift + right-click** an expanded module | Toggle private visibility — show or hide private functions and schemas |
| **Click** an edge | Select it — sidebar shows the underlying relationships (function calls, dispatches, schema refs) |
| **Click** the background | Deselect — clears selection |
| **Breadcrumb** click | Navigate to an ancestor module |

### Edge modes

The toolbar switches between edge modes (single-select):

- **Code flow** — shows function call and dispatch edges; only function nodes visible
- **Type reference** — shows schema reference edges; only schema nodes visible

Module nodes are always visible regardless of mode. The sidebar always shows full details for a selected entity.

The next-chapter Model expands edge modes from two to five (one per edge class), respecting visibility derived from `.boundary` composition. See [doc/DESIGN.md](doc/DESIGN.md) for the target.

### Visual indicators

- **Expand arrow** on modules: `▶` collapsed, `▼` expanded
- **Double border** on expanded modules: private children are hidden (shift+right-click to reveal)
- **Dashed border, gray background**: a private node currently visible

## Project structure

```
src/fukan/
  model/           Model construction — schemas, build pipeline, analyzers
    analyzers/     Language-specific analyzers (Clojure, Allium)
    spec.allium    Current Model spec (to be re-foundationed; see doc/DESIGN.md)
  projection/      Pure computation from model to visible subgraph
  web/             HTTP/SSE transport and view rendering
  infra/           Server and model lifecycle
  libs/            Vendored libraries (Allium parser, Boundary parser)
doc/
  VISION.md        Next-chapter framing and roadmap
  DESIGN.md        Next-chapter technical specification
```

Allium specs (`.allium` files) are the authoritative description of system behaviour. When spec and code disagree, the spec is right. The next-chapter Model makes this principle structural rather than aspirational.

## License

TBD
