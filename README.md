# Fukan

Fukan is a structural exploration workbench for the layer humans own as LLMs handle more of the low-level coding — module boundaries, contracts, invariants, behavioural intent, and the relationships between them.

It analyses a target system to build a unified structural Model, then renders it as an interactive graph in the browser. Canvas specs, which describe a system's design in Clojure data, are projected onto the same Model as implementation code, so that what the system is meant to do and what it actually does appear together in one place.

## Design surface

**Canvas specs are fukan's primary spec authoring surface.** A canvas spec is a Clojure file that uses the canvas vocabulary to declare a module's structure:

```clojure
(ns canvas.infra.server
  (:require [fukan.canvas.construction :refer [function record exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant]]
            [fukan.canvas.vocab.lifecycle :refer [getter]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "infra.server"
      (record "ServerOpts" "HTTP server configuration."
        (field port (optional :Integer)))
      (invariant "SingleServerInstance" "At most one HTTP server runs at a time."
        (holds-that "at-most-one server is active"))
      (function "start_server" "Start the HTTP server."
        (takes [opts :ServerOpts])
        (gives (optional :ServerInfo)))
      (exports ServerOpts ServerInfo))))
```

Canvas specs live in `canvas/<subsystem>/<module>.clj`, mirroring the system structure. There are 62 canvas ports covering all of fukan's own modules. Each canvas file is on the classpath and participates in the REPL reload cycle — editing a canvas file and calling `(refresh)` updates the graph.

Legacy `.allium`/`.boundary` files are archived in `.legacy-allium/` (not on the classpath; read-only reference only).

## For coding agents

Fukan exposes its Model to coding agents through `bin/fukan`. When working on or with Fukan, prefer querying the spec graph over grepping the codebase.

[AGENTS.md](AGENTS.md) is the primer — read it first. It covers the `fukan.agent.system` / `fukan.agent.api` surface, the L0/L1/L2 query layering, the edit→refresh→query loop, `.fukan/agent-views.clj`, and sandbox limits.

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
| `fukan init` | Add a Fukan section to the current project's `AGENTS.md` |

The daemon must be running (`clj -M:run …`) for `status` and `eval`.

## Usage

```
clj -M:run --src /path/to/project/src --port 8080
```

Then open `http://localhost:8080` in a browser.

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

The REPL cycle for canvas spec work: **edit a canvas file → `(refresh)` → browser refresh**.

## Project structure

```
canvas/                  Canvas specs — the design surface for fukan-itself
  infra/                 Infra subsystem specs
  model/                 Model subsystem specs
  web/                   Web subsystem specs
  ...                    (62 modules total)
src/fukan/
  canvas/                Canvas machinery (core, construction, vocab libraries)
  model/                 Model construction — pipeline, build, schemas
  projection/            Pure computation from model to visible subgraph
  web/                   HTTP/SSE transport and view rendering
  infra/                 Server and model lifecycle
doc/
  VISION.md              Framing and direction
  DESIGN.md              Design principles (three-tier layering, ownership)
  MODEL.md               Substrate spec (kernel, vocabulary mechanism)
  plans/                 Design decision records (historical; do not modify)
.legacy-allium/          Archived pre-canvas .allium/.boundary specs (read-only)
```

## Navigating the graph

The graph renders modules as compound nodes that contain affordances (functions, invariants, rules) and types. Navigation works at two levels: selection (inspecting within the current view) and navigation (changing what you're viewing).

| Action | Effect |
|--------|--------|
| Click a node | Select it — sidebar shows details |
| Double-click a module | Navigate into it |
| Right-click a module | Expand/collapse children inline |
| Click the background | Deselect |
| Breadcrumb click | Navigate to an ancestor module |

## License

TBD
