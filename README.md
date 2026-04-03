# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven development.

As LLMs take on more of the low-level coding work, the human's role shifts toward defining and maintaining high-level structure — module boundaries, contracts, invariants, and the relationships between them. Fukan provides tooling purpose-built for that level of work.

It analyzes a target codebase to build a unified structural model, then renders it as an interactive graph in the browser. Specifications and implementation are projected onto the same model, so you see intended structure and actual structure together in one place.

## Key ideas

- **Unified model.** Behavioral specs (boundaries, contracts, guarantees) and implementation (namespaces, functions, schemas) are merged into a single graph. This is the shared artifact that both human and LLM can reason about.
- **Pluggable analyzers.** Language-specific analyzers register via multimethod dispatch. The build pipeline is language-agnostic — it operates on a common `AnalysisResult` format. Currently ships with Clojure and Allium analyzers.
- **Documentation as structure.** Docstrings, spec descriptions, and surface guarantees flow into the model. The explorer is meant to convey understanding, not just shape.
- **Boundaries as first-class citizens.** The build pipeline infers, merges, and attaches boundaries to modules — the collaboration surface where human intent meets machine output.

## Usage

```
clj -M:run --src /path/to/project/src --analyzers clojure,allium --port 8080
```

| Flag | Description | Default |
|------|-------------|---------|
| `--src PATH` | Path to source directory (required) | — |
| `--analyzers KEY,KEY,...` | Comma-separated analyzer keys (required) | — |
| `--port PORT` | Server port | 8080 |

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

## Project structure

```
src/fukan/
  model/           Model construction — schemas, build pipeline, analyzers
    analyzers/     Language-specific analyzers (Clojure, Allium)
    spec.allium    System model spec
  projection/      Pure computation from model to visible subgraph
  web/             HTTP/SSE transport and view rendering
  infra/           Server and model lifecycle
  libs/            Vendored libraries (Allium parser)
```

Allium specs (`.allium` files) are the authoritative description of system behavior. When spec and code disagree, the spec is right.

## License

TBD
