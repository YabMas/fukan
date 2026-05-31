# Paused subsystems

Code parked here is **paused, not deleted** — set aside during the lean-kernel
rebuild so it stops weighing on every iteration, kept intact as reference for
when its own design cycle comes. This mirrors the `.legacy-allium/` convention:
the tree is off the active classpath (`deps.edn` `:paths` does not include
`.paused/`, and the test runner only scans `test/`), so nothing here is loaded,
compiled, or tested by the live build.

The directory layout mirrors the repository root, so revival is a move back:

```
.paused/src/fukan/<x>   →  src/fukan/<x>
.paused/test/fukan/<x>  →  test/fukan/<x>
```

## What's parked, and why

| Path | What | Why paused |
|------|------|-----------|
| `src/fukan/web/`, `src/fukan/projection/`, `src/fukan/infra/server.clj` | The browser explorer + its render projection + HTTP server lifecycle | The interactive graph is the heaviest consumer of the model shape; it churns hardest while the substrate is still moving. Returns once the core stabilises. |
| `src/fukan/agent/` | The agent interaction surface (`api`/`system`/SCI sandbox) | To be largely rebuilt in its own design cycle once the new primitives have settled. |
| `src/fukan/canvas/lens/` | The lens substrate (patterns / consistency / tar-pit) | The idea is sound and kept — but redesigned against the new kernel, in a later design cycle. |
| `src/fukan/canvas/inspect/` | integrity / coverage / drift checks | Consumers of the old classification/vocab; rebuilt alongside the new constraint mechanism. |
| `src/fukan/canvas/instruct/` | cold-write authoring guidance | Rebuilt with the agent surface. |
| `src/fukan/canvas/project/` | Clojure code *synthesis* (model → defn/malli/…) | The reverse of the kept `target/clojure` analyzer; rebuilt later. |

## The kernel feedback loop in the meantime

The serving daemon is paused, so the loop is in-process at the REPL:

```clojure
(require '[fukan.canvas.core.helpers :refer [with-canvas]]
         '[datascript.core :as d])
(def db (with-canvas ...))   ; build a store from a structure
(d/q '[:find ... :where ...] db)
```

`dev/user.clj` keeps `(go)` / `(refresh)` / `(reset)` / `(status)` as
model-build/reload helpers (no server).
