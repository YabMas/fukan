# Infrastructure Lifecycle Specification

## Purpose

Decoupled lifecycle management via Integrant components: model and server are independent subsystems wired through dependency injection.

## Model Component (`:fukan.infra/model`)

- **init**: Create model-state atom, optionally load model from configured src path
- **load-model!(model-state, src-path)**: Run analysis on source path, build graph model, store in atom
- **refresh!(model-state)**: Rebuild model from last stored src-path (no arguments beyond atom)

State: atom holding `{:model M, :src path}`. Model is nil until first load. The atom is passed to the handler via Integrant config.

## Handler Component (`:fukan.web/handler`)

- **init**: Receives model-state atom via `#ig/ref`, creates Ring handler that derefs atom per-request

## Server Component (`:fukan.infra/server`)

- **init**: Receives handler via `#ig/ref`, starts http-kit on configured port. Returns `{:stop-fn f, :port p}`.
- **halt**: Calls stop-fn to unbind port

## Decoupling Invariant

The handler never imports `infra.model` directly. It receives the model-state atom through Integrant's dependency injection. This means:

- Model can refresh (re-analyze + rebuild) without restarting the server
- Server can stop and restart without losing or rebuilding the model
- The dependency graph is a clean chain: server → handler → model

## Instance Management

Integrant manages component ordering and lifecycle. System configuration lives in `resources/fukan/system.edn`.
