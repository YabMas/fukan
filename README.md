# Fukan

Fukan is a structural exploration tool for the layer humans own as LLMs handle
more of the low-level coding — the composition of concepts in a system and the
laws that must hold of it. The question it explores: as LLMs write more of the
code, how do humans stay in control of high-level structure and collaborate with
LLMs at that altitude?

You **define** a system's structure, **model** abstractions over it, **verify**
the whole as one assertable graph, and **project** it toward an implementation.
Specification and implementation live on the *same* graph, so what a system is
meant to be and what it actually is can be checked against each other.

> **Status: lean-kernel + modelling-exploration phase.** Fukan was radically pruned
> and rebuilt around a single primitive, `defstructure`. The browser explorer that
> is fukan's eventual vision is **deferred indefinitely** (parked under `.paused/`);
> today fukan is a REPL-and-canvas tool, exercised by modelling. See
> [CLAUDE.md](CLAUDE.md) for the current architecture in detail.

## The primitive

Everything is a `defstructure`: a structure is a **composition of slots** plus the
**datalog laws** that must hold of it. The core ships only this primitive and the
ingestion/projection machinery — **no domain vocabulary**. Each modelling project
authors its own grammar on the core.

```clojure
(require '[fukan.canvas.core.structure :as s :refer [defstructure]]
         '[datascript.core :as d])

;; a tiny vocabulary
(defstructure Task
  "A unit of work that may depend on other tasks."
  (slot :done? (one :Bool))
  (slot :deps  (many Task))
  (law "a task cannot depend on itself"
    :offenders '[?t]
    :where '[[?r :rel/from ?t] [?r :rel/kind :deps] [?r :rel/to ?t]]))

;; a model authored against it
(def db (s/with-structures
          (s/within-module "plan"
            (Task "spec"  (done? true))
            (Task "build" (done? false) (deps spec)))))

(s/check db)   ;; => []  (no law violations)
```

`(slot :x (one :Bool))` stores a scalar leaf with an auto type-check law;
slots whose target is another structure reify a relation. Cardinalities are
`one` / `optional` / `many` / `some` / `ordered`. `^:value` structures are
content-deduped anonymous nodes. See `canvas/vocab/*.clj` for fukan's own
vocabulary and `demos/<domain>/` for worked examples.

## Self-model and demos

Fukan models *itself*: `canvas/vocab/` holds its vocabulary (data / computation /
schema / architecture / probe / projection layers) and `canvas/model/` holds the
models of its subsystems. Canvas files under `canvas/**/*.clj` are auto-discovered
and merged into one structure db — the model. `clj -M:demos` runs a corpus of
standalone modelling demos (grammar, ER, workflow, access-control, type-system).

## Development

Requires Clojure CLI (`clj`) and [clj-kondo](https://github.com/clj-kondo/clj-kondo)
on PATH.

```bash
clj -M:dev:nrepl        # start an nREPL (port 7889)
clj -M:test             # run the test suite
clj -M:demos            # run the demo regressions
```

In the REPL (`clj -M:dev`):

```clojure
(go)        ; build the model (canvas specs + the Clojure extractor over src/)
(refresh)   ; reload changed code + rebuild
(status)    ; model state
(drift)     ; modelled capabilities not yet realized in code
```

## Project structure

```
canvas/vocab/      fukan-on-fukan's vocabulary (defstructure grammars)
canvas/model/      fukan-on-fukan's subsystem models
demos/             standalone modelling demos (vocab + model + regression)
src/fukan/
  canvas/core/     the defstructure primitive, derived rules, lens evaluation
  canvas/projection/  canvas ingestion + the probe surface
  model/           build pipeline, extraction plug-point, materialize
  target/          Clojure code extractor + model↔code correspondence laws
  infra/           model lifecycle + composition root
.paused/           the browser viewer stack (deferred indefinitely)
.legacy-allium/    pre-canvas Allium/Boundary specs (read-only baseline)
doc/               design-phase chapters and the decision trace (historical)
```

## License

TBD
