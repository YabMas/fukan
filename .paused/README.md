# Paused subsystems

Code parked here is **paused, not deleted** — set aside during the lean-kernel
rebuild so it stops weighing on every iteration, kept intact as reference for an
eventual rebuild on the new structure substrate. This mirrors the
`.legacy-allium/` convention: the tree is off the active classpath (`deps.edn`
`:paths` does not include `.paused/`, and the test runner only scans `test/`), so
nothing here is loaded, compiled, or tested by the live build.

**Revival is deliberate and unscheduled.** Each subsystem comes back only on its
own future design cycle, rebuilt on the structure substrate — none is planned.
In particular, the **browser explorer / viewer is DEFERRED INDEFINITELY**: it is
fukan's eventual vision but is *not* on the near roadmap and should not be
proposed as a next step. The current focus is exercising the core via modelling.

The directory layout mirrors the repository root, so revival is a move back:

```
.paused/src/fukan/<x>   →  src/fukan/<x>
.paused/test/fukan/<x>  →  test/fukan/<x>
```

## What's parked, and why

| Path | What | Why paused |
|------|------|-----------|
| `src/fukan/web/`, `src/fukan/projection/`, `src/fukan/infra/server.clj` | The browser explorer + its render projection + HTTP server lifecycle | **DEFERRED INDEFINITELY** — fukan's eventual vision, but the core is being exercised extensively first. Do not propose reviving it. |
| `src/fukan/agent/` | The agent interaction surface (`api`/`system`/SCI sandbox) | Rebuilt on the structure substrate in its own future cycle. |
| `src/fukan/target/`, `src/fukan/project_layer/` | The Clojure analyzer (code → model) + its project registry | Wrote the old store/model-map; rebuilt to write structure instances when code-analysis is wanted again. |
| `src/fukan/canvas/lens/` | The lens substrate (patterns / consistency / tar-pit) | The idea is sound and kept — redesigned against the structure substrate later. |
| `src/fukan/canvas/inspect/` | integrity / coverage / drift checks | Consumers of the old classification/vocab; rebuilt on the structure substrate. |
| `src/fukan/canvas/instruct/` | cold-write authoring guidance | Rebuilt with the agent surface. |
| `src/fukan/canvas/project/` | Clojure code *synthesis* (model → defn/malli/…) | The reverse of the analyzer; rebuilt later. |

## The feedback loop in the meantime

The serving daemon is paused, so the loop is in-process at the REPL — over the
**structure substrate** (`fukan.canvas.core.structure`), which is the model:

```clojure
(require '[fukan.canvas.core.structure :as s]
         '[fukan.canvas.structures :refer [Type Function ...]]
         '[datascript.core :as d])
(def db (s/with-structures (s/within-module "m" (Function "f" (gives ...)))))
(d/q '[:find ... :where ...] db)
(s/check db)   ; run the structures' laws
```

`build-model` ingests the `canvas/**/*.clj` defstructure specs into the model db;
`dev/user.clj` keeps `(go)` / `(refresh)` / `(reset)` / `(status)` (no server).
