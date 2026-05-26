# /demo — Canvas substrate stress-tests

This directory contains paradigm stress-tests for the canvas substrate.
Each subdirectory is a self-contained demo of one architectural paradigm.
Demo content is **NOT on the default classpath** — it is intentionally
isolated from fukan's own canvas tree.

## What is here

| Directory | Paradigm | Purpose |
|-----------|----------|---------|
| `event_driven/` | Event-driven service (5 modules) | Upper-bound stress: Event/Handler vocabulary, reactive firing, payload shapes |
| `static_lib/` | Pure static library (5 modules) | Lower-bound sanity: types + pure functions, no behavioral content |

## How to load demos

### REPL (interactive)

Start a REPL with the `:demo` alias active:

```bash
clj -M:dev:demo
```

Then in the REPL:

```clojure
(user/load-demo "static-lib")
(user/load-demo "event-driven")
```

`load-demo` returns `{:db <datascript-db> :modules <count> :name <name>}`.

### Test suite

```bash
clj -M:demo
```

Runs thin smoke tests for each demo module (namespace loads, `build-canvas`
returns non-empty db).

## Findings

See `doc/plans/2026-05-26-stress-test-findings.md` for the Sprint 3
stress-test analysis.
