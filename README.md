# Fukan

*Fukan* (俯瞰) — to take in a whole landscape from above. Fukan is a tool for the
layer humans own as LLMs write more of the low-level code: the **composition of a
system's concepts and the laws that must hold of them**. As the typing moves to the
model, the human's work moves up — to the boundaries, the invariants, the
architectural shape — and fukan is a place to hold, verify, and reason about that
shape, together with an LLM, at that altitude.

Its one thesis: **specification and implementation live on the same graph.** Intended
structure and actual structure are not two artifacts kept in sync by discipline —
they are one assertable graph whose internal consistency a machine checks. You
**define** a system's structure, **model** abstractions over it, **verify** the whole
by running its laws, and **project** it back down toward an implementation.

> **Status: lean-kernel + modelling-exploration phase.** Fukan was radically pruned
> and rebuilt around a single primitive, `defstructure`. The interactive browser
> explorer that gives fukan its name — the whole graph rendered as a navigable
> bird's-eye view — is **deferred indefinitely** (parked under `.paused/`); today
> fukan is a REPL-and-canvas tool, exercised by modelling. See
> [doc/VISION.md](doc/VISION.md) for the why and [CLAUDE.md](CLAUDE.md) for the
> architecture.

## Everything is a structure

A `defstructure` declares a structure as a **composition of slots** plus the
**datalog laws** that must hold of it — and that is the *only* primitive. The
substrate **is** the model: no separate model-map, no privileged kinds. The core
ships this primitive and the ingestion/projection machinery and **no domain
vocabulary** — every project authors its own grammar on the core.

```clojure
(require '[fukan.canvas.core.structure :as s :refer [defstructure]])

;; a tiny vocabulary: one structure, one law
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

(s/check db)   ;; => []  (every law holds)
```

A scalar slot (`(one :Bool)`) stores a leaf value with an auto type-check law; a slot
whose target is another structure reifies a *queryable relation*. The model is a
datascript db, so a human or an LLM interrogates it with the **same datalog** — fukan
is REPL-native and agent-native by construction. Cardinalities are `one` / `optional`
/ `many` / `some` / `ordered`; `^:value` structures are content-deduped anonymous
nodes for nameless compound data.

## The payoff: one graph spanning spec and code

This is what the single graph buys. Fukan **extracts** your real code into the same
substrate — fukan's own extractor reads clj-kondo analysis into `Module` and
`Operation` structures — and merges it onto the design graph. Now a law can quantify
*across* the spec↔code seam:

```clojure
;; every modelled Stage must be realized by a same-named Operation
;; in the corresponding code module — a law spanning design and implementation
(law "every modelled Stage is realized in code"
  :scope :global
  :offenders '[?s]
  :where '[(Operation ?o)
           (Stage ?s) (named ?s ?n) (in-module ?s ?cm)
           (not (Operation ?o2) (named ?o2 ?n) (in-module ?o2 ?km)
                [(module-corresponds? ?cm ?km)])])
```

Run `(structure/check db)` and **drift surfaces as a law violation** — a modelled
capability with no implementation, on the same footing as any other broken invariant.
The dual query reports code not yet covered by the model. (Laws read in the
vocabulary's own terms — `(Stage ?s)`, `(in-module …)` — because the core derives
those rules from the live vocabulary.)

Going the other direction, **`materialize`** projects a modelled node *down* into an
implementation specification — a signature, intent, and contract that an implementing
LLM realizes — so the model drives the code as well as checking it.

Define → model → verify → project, all on one graph that holds both what the system
is meant to be and what it actually is.

## Self-model and demos

Fukan is exercised by modelling — including **modelling itself**: `canvas/vocab/`
holds fukan's own vocabulary (data / computation / schema / architecture / probe /
projection / collaboration / lens layers) and `canvas/model/` models its subsystems
on that vocabulary. Canvas files under `canvas/**/*.clj` are auto-discovered and
merged into one structure db — the model. `clj -M:demos` runs a corpus of standalone
modelling demos (grammar, ER, workflow, access-control, type-system) that
pressure-test the core.

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
doc/               the vision, design, substrate spec, and decision trace
```

## License

TBD
