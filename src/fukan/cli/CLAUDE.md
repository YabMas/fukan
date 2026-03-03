# CLI Implementation Guide

Read `spec.allium` in this directory for the full CLI command specification.

## Key Principle: Projection Delegation

Commands are pure functions of `(model, state, args)`. **All model access goes through `fukan.projection.api`** — the command layer never reads `model[:nodes]` or `model[:edges]` directly. This is the same discipline the web handler follows.

```clojure
;; RIGHT: go through projection API
(proj/inspect model entity-id)   ; existence check, kind check, details
(proj/search model pattern 50)   ; text search
(proj/overview model)            ; model statistics
(proj/navigate model opts)       ; graph + path for a view
(proj/find-root model)           ; root node

;; WRONG: reach into model internals
(get-in model [:nodes id])       ; encapsulation violation
(vals (:nodes model))            ; encapsulation violation
```

## Module Structure

```
commands.clj  — pure command dispatch: (model, state, args) → {:response :state-update}
gateway.clj   — stateful REPL gateway: wraps commands with persistent session atom
explorer.clj  — stdin/stdout session loop for subprocess use
```

## Session State

```clojure
{:view-id      String?       ;; current container (nil = root)
 :history      [String]      ;; stack of previous view-ids for back navigation
 :expanded     #{String}     ;; containers whose children are visible (user-controlled)
 :show-private #{String}     ;; expanded containers that also show private children
 :src          String?}      ;; source path of the analyzed project
```

## Response Shape

Every response is an EDN map with at minimum:
- `:ok` — boolean success/failure
- `:command` — keyword command name

Successful responses merge command-specific data alongside these keys.

## Commands

| Command | Args | State mutation | Projection calls |
|---------|------|---------------|-----------------|
| `ls` | none | none | navigate |
| `cd` | `<id>` or `..` | view-id, history | inspect, navigate |
| `back` | none | view-id, history | navigate |
| `info` | `<id>` | none | inspect |
| `find` | `<pattern>` | none | search |
| `overview` | none | none | overview |
| `expand` | `<id>` | expanded set | inspect, navigate |

## Two Modes

**Gateway** (`gateway.clj`): For REPL-based agent workflows via `clj-nrepl-eval`. Session state persists in a `defonce` atom. Model is fetched fresh per call from `infra.model`.

**Explorer** (`explorer.clj`): For subprocess use. Reads commands from stdin, prints EDN responses to stdout. Model is passed at startup and held for the session duration.
