# Infra Implementation Guide

Read `spec.md` in this directory for the full infrastructure lifecycle specification.

## Module Purpose

Lifecycle management for the model and HTTP server via Integrant components. This is the orchestration layer that wires together model building and server startup.

## Architecture: Integrant Components

```
:fukan.infra/model   — build, store, refresh the Model (returns atom)
:fukan.web/handler   — Ring handler (receives model-state atom via config)
:fukan.infra/server  — start/stop HTTP server (receives handler via config)
```

Dependency graph: `:fukan.infra/server → :fukan.web/handler → :fukan.infra/model`

These are deliberately separate. The server can restart without rebuilding the model, and the model can refresh without restarting the server (the handler derefs the model-state atom on each request).

## State Management

- `model.clj`: The `:fukan.infra/model` component returns an **atom** `{:model Model, :src String}`. The atom is passed to the handler via Integrant config, breaking the direct dependency from `web.handler` to `infra.model`.
- `server.clj`: The `:fukan.infra/server` component returns `{:stop-fn fn, :port int}`. `halt-key!` calls `stop-fn`.

## Dependency Direction

```
infra/model.clj → model.pipeline (single entry point for model construction)
web/handler.clj → web.sse, web.views (to build routes)
```

The handler no longer imports `infra.model`. It receives the model-state atom through Integrant's dependency injection. Nothing calls into infra except the Integrant system (`ig/init` / `ig/halt!`).

## Key Patterns

- `load-model!` takes a model-state atom and src path — prints progress to stdout for REPL feedback
- `refresh!` takes a model-state atom — rebuilds using the last src path
- The handler derefs the model-state atom per-request, so model refresh takes effect without server restart
- System configuration lives in `resources/fukan/system.edn`
