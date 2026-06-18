# Fukan

*Fukan* (俯瞰) — to take in a whole landscape from above.

As LLMs write more of the low-level code, the work that stays human moves up — to the
boundaries, the invariants, the architectural shape of a system. Fukan is a place to
hold and reason about that shape — the **composition of a system's concepts and the
laws that must hold of them** — together with an LLM, at that altitude.

Its one thesis: **specification and implementation live on the same graph.** Intended
structure and actual structure are not two artifacts kept in sync by discipline — they
are one graph whose consistency a machine checks. You **define** a system's structure,
**model** abstractions over it, **verify** the whole by running its laws, and **act**
on it — probing what it is, projecting it toward an implementation.

The approach is **bottom-up language building, top-down design** — the Lisp tradition
of stratified languages: grow the vocabulary the domain wants from one primitive up,
press the design down onto it as laws, and let the graph hold both — and the
LLM-written implementation — to account.

> **Status: lean-kernel + modelling-exploration phase.** Fukan was radically pruned and
> rebuilt around a single primitive, `defstructure`. The interactive browser explorer
> that gives fukan its name — the whole graph rendered as a navigable bird's-eye view —
> is **deferred indefinitely** (parked under `.paused/`); today fukan is a
> REPL-and-canvas tool, exercised by modelling. See [doc/VISION.md](doc/VISION.md) for
> the why and [CLAUDE.md](CLAUDE.md) for the architecture.

## Everything is a structure

A `defstructure` declares a structure as a **composition of slots** plus the **datalog
laws** that must hold of it — and that is the *only* primitive. The substrate **is**
the model: no separate model-map, no privileged kinds. The core ships this primitive
and the ingestion/projection machinery and **no domain vocabulary** — every project
authors its own grammar on the core.

```clojure
(require '[fukan.canvas.core.structure :as s :refer [defstructure]]
         '[fukan.canvas.core.assemble :as a]
         '[lib.type.malli])               ;; opt into the malli scalar type dialect

;; a tiny vocabulary: one structure — its slots as one typed map — plus one law
(defstructure Task
  "A unit of work that may depend on other tasks."
  {:done? :boolean
   :deps  [:* Task]}
  (law "a task cannot depend on itself"
    :offenders '[?t]
    :where '[[?r :rel/from ?t] [?r :rel/kind :deps] [?r :rel/to ?t]]))

;; a model authored against it — the instance form mirrors defstructure:
;; a name symbol (the var AND the entity name) + one {slot → value} map
(Task spec  {:done? true})
(Task build {:done? false :deps [spec]})

(s/check (a/assemble-vars [#'spec #'build]))   ;; => []  (every law holds)
```

Cardinality is a quantifier: a bare target is *one*, `[:? T]` optional, `[:* T]` zero
or more (ordered), `[:+ T]` one or more, `[:set T]` unordered. A scalar slot
(`:boolean`) stores a leaf value with an auto type-check law; a refined scalar
(`[:enum "a" "b"]`, `[:int {:min 1}]`) is checked through a pluggable type dialect
(malli ships); a slot whose target is another structure reifies a *queryable
relation*; `^:value` structures are content-deduped anonymous nodes for nameless
compound data. The model is a datascript db, so a human or an LLM interrogates it
with the **same datalog** — fukan is REPL-native and agent-native by construction.
And the grammar itself is reflected onto the graph: vocabularies are data too, and
`(grammar)` renders the live language reference back as the very forms above — the
print-dual of authoring.

## One graph spanning spec and code

This is what the single graph buys. Fukan **extracts** your real code into the same
substrate — fukan's own extractor reads clj-kondo analysis into `Module` and
`Operation` structures — and merges it onto the design graph. Now a law can quantify
*across* the spec↔code seam:

```clojure
;; every authored Operation must be realized by a same-named extracted Operation
;; in the corresponding code module — a law spanning design and implementation
(law "every authored operation is realized in code"
  :scope :global
  :offenders '[?s]
  :where '[(Operation ?s) (not [?s :val/extracted true])
           (named ?s ?n) (in-module ?s ?cm)
           (not-join [?n ?cm]
             (Operation ?o) [?o :val/extracted true]
             (named ?o ?n) (in-module ?o ?km)
             [(module-corresponds? ?cm ?km)])])
```

Run `(structure/check db)` and **drift surfaces as a law violation** — a modelled
capability with no implementation, on the same footing as any other broken invariant.
The converse query reports the opposite gap: code the model doesn't yet cover. Laws
read in the vocabulary's own terms — `(Operation ?s)`, `(in-module …)` — because the
core derives those rules from the live vocabulary, so a law spans design and
implementation without ever dropping to raw triples. The recurring law shapes have
**combinators** — `(law "…" (matched-by R :from S))`, `(has R)`, `(at-most-one R)` —
that expand to correct datalog so common constraints are one declarative line.

## Acts through a lens

You work the graph through a **lens** — a focus on some sub-graph — with two
complementary acts:

- **Probe** — *read* the graph and observe it. A probe yields a **Finding**: a set of
  sub-graphs of interest, each tagged with what it is — a recurring pattern, a law
  violation, a coverage gap — and a note. Integrity, drift, and coverage are probes
  whose findings gate action; surveys and pattern-scans are probes you reason with.
- **Project** — *re-present* the graph in another form. A projection renders the model
  into a target: an implementation spec an LLM can build from, reference docs, a
  diagram. Materialize — turning a modelled node into a signature-and-intent an LLM
  realizes — is one projection.

The two compose because they share one currency — the **focus**, a sub-graph. A probe
surfaces foci; a projection consumes them. So you probe until something is worth acting
on, project that sub-graph into a shape you can work from, and — optionally — hand it to
an LLM to enact. Analysis and synthesis, over the same graph.

Both acts are open. Adding one is dropping a file: a probe is a `run-probe` method, a
projection a `render-base` method. Nothing in the core privileges fukan's own probes
and projections over the ones you write — which is the point. Fukan is a small set of
ways to act on one graph, and a way to add more.

Define → model → verify → act, all on one graph that holds both what the system is
meant to be and what it actually is.

## Self-model and demos

Fukan is exercised by modelling — including **modelling itself**. The self-model is laid
out by altitude: `canvas/subject.clj` is fukan as an *abstract* system — one stratum of
pure-grammar portraits (the substrate Node / Relation / Graph, the grammar
Structure / Slot / Law / Form / Vocabulary, the Model, the Source, and the use-side
Lens / Projection); `canvas/instruments/` holds fukan's own use-side *instances* — the
lenses, findings, and projections it runs on itself; and `canvas/architecture/` models
fukan as a *built* system, one self-spec per implementation subsystem. Each architecture Module
carries a `:realizes` role naming the subject faculty it builds; the system overview derives the
faculty→module map from those roles (the genuine model↔code drift-check is the op-layer
`target/correspondence`). Canvas files under `canvas/**/*.clj` are
auto-discovered and assembled into one structure db — the model.

Reusable, domain-general vocabulary lives in a separate opt-in stdlib, `lib/` — code
structures (`lib.code`: Operation / Effect / Kind / Module, where a Module is one code
namespace), structural primitives (`lib.grouping`: Grouping / Connected), a pluggable
type-authoring surface (`lib.type.malli`), grammar reflection (`lib.grammar`: the registry
projected onto the graph, so the language is model too), and the use-side act grammar
(`lib.lens`: Lens / Finding / Projection). It is required, not auto-discovered, so fukan's
own canvas vocab stays focused on what is unique to fukan. `clj -M:demos` runs a corpus of
standalone modelling demos (grammar, ER, workflow, access-control, type-system, atlas,
self) that pressure-test the core.

## Development

Requires Clojure CLI (`clj`) and [clj-kondo](https://github.com/clj-kondo/clj-kondo) on
PATH.

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
(overview)  ; the projected system map — the canvas's front door
(grammar)   ; the live language primer — every vocabulary rendered back as source
(drift)     ; modelled capabilities not yet realized in code
(probes)    ; run every probe over the held model, printing each finding
```

## Project structure

```
lib/                  reusable opt-in stdlib vocab: lib.code, lib.grouping, lib.type.malli, lib.grammar, lib.lens
canvas/subject.clj    fukan as an abstract system (pure-grammar portraits)
canvas/instruments/   fukan's own use-side instances (lenses, findings, projections)
canvas/architecture/  fukan as a built system (subsystem self-specs + the acts realization seam; faculty roles on each Module)
demos/                standalone modelling demos (vocab + model + regression)
src/fukan/
  canvas/core/        the defstructure primitive, derived rules, typing plug-point, lens evaluation
  canvas/projection/  canvas ingestion, the print-duals (grammar/instance/overview), the probe surface
  model/              build pipeline, extraction plug-point, materialize
  target/             Clojure code extractor + model↔code correspondence laws
  dialect/            the malli type-dialect bridge
  infra/              model lifecycle + composition root
.paused/              the browser viewer stack (deferred indefinitely)
.legacy-allium/       pre-canvas Allium/Boundary specs (read-only baseline)
doc/                  the vision, design, substrate spec, and decision trace
```

## License

TBD
