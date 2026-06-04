# Paused: the browser viewer stack

Only one subsystem remains parked here — the **browser explorer / viewer**, set
aside during the lean-kernel rebuild. It is off the active classpath (`deps.edn`
`:paths` does not include `.paused/`, and the test runner only scans `test/`), so
nothing here is loaded, compiled, or tested by the live build. Internal references
to since-removed code (e.g. the old agent handlers) are therefore inert.

**The viewer is DEFERRED INDEFINITELY** — it is fukan's eventual vision (an
interactive structural graph in the browser) but is *not* on the near roadmap and
should not be proposed as a next step. The current focus is exercising the core
via modelling. It is kept because, unlike the other once-parked subsystems, it has
*not* been superseded by the rebuild — it simply hasn't been rebuilt yet.

The other formerly-parked subsystems (the agent surface, the Clojure analyzer +
project registry, the lens substrate, inspect/instruct, code synthesis) were
**removed** once the rebuild reborn their capabilities on the structure substrate
— see the prune commit. They live in git history if ever needed; the live
equivalents are `projection/{probes,probe_code}`, `model/materialize`,
`target/clojure`, `core/{lens,rules}`, and the probe vocabulary.

The directory layout mirrors the repository root, so revival is a move back:

```
.paused/src/fukan/<x>   →  src/fukan/<x>
.paused/test/fukan/<x>  →  test/fukan/<x>
```

## What's parked, and why

| Path | What | Why paused |
|------|------|-----------|
| `src/fukan/web/`, `src/fukan/projection/`, `src/fukan/infra/server.clj` | The browser explorer + its render projection + HTTP server lifecycle | **DEFERRED INDEFINITELY** — fukan's eventual vision, but the core is being exercised extensively first. Do not propose reviving it. |

## The feedback loop in the meantime

The serving daemon is paused, so the loop is in-process at the REPL — over the
**structure substrate** (`fukan.canvas.core.structure`), which is the model:

```clojure
(require '[fukan.canvas.core.structure :as s]
         '[datascript.core :as d])
(def db (s/with-structures (s/within-module "m" #_…)))
(d/q '[:find ... :where ...] db)
(s/check db)   ; run the structures' laws
```

`build-model` ingests the `canvas/**/*.clj` defstructure specs into the model db;
`dev/user.clj` keeps `(go)` / `(refresh)` / `(reset)` / `(status)` (no server).
