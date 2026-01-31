# Infrastructure Lifecycle Specification

## Purpose

Decoupled lifecycle management: model and server are independent subsystems with separate state.

## Model Lifecycle

- **load(src-path)**: Run analysis on source path, build graph model, store result. Persists src-path for later refresh.
- **get**: Return current model (nil if not loaded)
- **refresh**: Rebuild model from last stored src-path (no arguments needed)

State: single atom holding `{model, src-path}`. Model is nil until first load.

## Server Lifecycle

- **start(get-model-fn, port?)**: Create HTTP handler, bind port, store stop-fn. Requires a callback to fetch the current model.
- **stop**: Call stored stop-fn, clear state
- **running?**: Check if state is non-nil

State: single atom holding `{stop-fn, port}` or nil when stopped. Idempotent start (guards against double-bind) and stop.

## Decoupling Invariant

The server never holds a direct reference to the model. It receives a `get-model-fn` callback at startup and calls it per request. This means:

- Model can refresh (re-analyze + rebuild) without restarting the server
- Server can stop and restart without losing or rebuilding the model
- Either lifecycle can be operated independently from the REPL

## Instance Management

Both subsystems use `defonce` for their state atoms — at most one model and one server per JVM process.
