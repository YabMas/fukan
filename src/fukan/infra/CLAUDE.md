# Infra Implementation Guide

Read `spec.allium` in this directory for the full infrastructure lifecycle specification.

## Module Purpose

Lifecycle management for the model and HTTP server. This is the orchestration layer that wires together model building and server startup.

## Architecture: Two Independent Lifecycles

```
infra/model.clj  — build, store, refresh the Model
infra/server.clj — start/stop HTTP server
```

These are deliberately separate. The server can restart without rebuilding the model, and the model can refresh without restarting the server (the server calls `get-model` on each request).

## State Management

Both files use **private atoms** (`defonce ^:private state`). State is never exposed directly — only through accessor functions.

- `model.clj` state: `{:model Model, :src String}`
- `server.clj` state: `{:stop-fn fn, :port int}` or `nil`

## Dependency Direction

```
infra/model.clj → model.pipeline (single entry point for model construction)
web/handler.clj → infra.model (to call get-model on each request)
web/handler.clj → web.sse, web.views (to build routes)
```

Nothing calls into infra except `user.clj` (the dev namespace) and `web.handler` (which reads the model atom via `get-model`). Infra is the top of the dependency tree at runtime.

## Key Patterns

- `load-model` prints progress to stdout — this is intentional for REPL feedback
- `start-server` only takes `:port`; the handler calls `infra.model/get-model` directly on each request
- `refresh-model` reuses the last `src` path — no args needed for re-analysis
- Guard against double-start: `start-server` checks `@state` before binding a port
