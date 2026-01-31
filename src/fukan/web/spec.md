# Web Layer Specification

## Purpose

HTTP routing + SSE orchestration: request -> projection -> view -> streamed response.

## Architecture

A **handler factory** creates the Ring handler at server startup, capturing a `get-model-fn` callback. The handler defines routes that dispatch to SSE handlers. Each SSE handler orchestrates the full request cycle: parse params, call projections, render views, stream patches.

## Request Flow

```
HTTP request
  -> route match
  -> SSE handler opens connection
  -> parse query parameters (view-id, selected-id, expanded-containers)
  -> call projection functions with parsed params
  -> pass projection data to view renderers
  -> stream HTML patches and script executions via SSE
  -> close connection
```

## Routing

| Route | Purpose |
|-------|---------|
| `/` | Static HTML shell (app skeleton) |
| `/sse/view` | Full view stream: graph + breadcrumb + sidebar |
| `/sse/sidebar` | Sidebar-only stream (selection without navigation) |
| `/sse/schema` | Schema detail stream |
| `/public/*` | Static assets (JS, CSS) |

## SSE Orchestration

SSE handlers are the orchestration layer. They:
1. Parse request parameters into projection options
2. Call projection functions (graph, path, details, schema)
3. Pass projection results to view renderers
4. Deliver rendered output via SSE patches (HTML replacement) and script execution (client-side graph rendering, URL updates)

Views are pure renderers — they receive pre-computed data and return HTML or JSON. They never call projections or access the model directly.

## Model Access

The handler factory receives `get-model-fn` at creation time. SSE handlers call it per request to get the current model. This supports hot-reload: the model can be refreshed without restarting the server, and the next request automatically picks up the new model.

## Parameter Conventions

| Parameter | Source | Purpose |
|-----------|--------|---------|
| `id` | query param | Container to view (navigation target) |
| `select` | query param | Node to highlight (selection target) |
| `expanded` | query param | Comma-separated container IDs with visible private children |
