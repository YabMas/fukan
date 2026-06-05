# Fukan — Design

**Status:** Design principles — *how* the system is shaped.

**Companion to** [VISION.md](./VISION.md) (the why), [MODEL.md](./MODEL.md) (the
substrate spec), and [DECISIONS.md](./DECISIONS.md) (the decision trace).

---

## Purpose

This chapter records the design principles of the lean kernel: what the core ships,
how a project layers its own vocabulary on it, how models are ingested into one
graph, and the two directions across the model↔code seam (extraction up, materialize
down) joined by correspondence laws.

## The core ships mechanics only — no vocabulary

The single most load-bearing decision: `src/fukan/canvas/` ships only the
`defstructure` primitive and the ingestion/projection machinery. It ships **no
domain vocabulary** — no `Type`, no `Function`, no architectural kinds. Every
modelling project authors its own grammar on the core:

- **fukan-on-fukan's** vocabulary lives in `canvas/vocab/` (a data layer, a
  computation layer, a schema layer, an architecture layer, plus probe / projection
  / collaboration / lens layers).
- **Each demo** owns its grammar under `demos/<domain>/vocab/`.

The core knows no kinds. This keeps the substrate a small, honest floor and forces
every modelling exercise to confront "what is the clearest vocabulary for *this*?"
— which is the point of the current phase.

A consequence and standing finding: the structure registry is a **single global tag
namespace**, so co-loaded projects cannot share a tag name. (Fukan's data layer
names its leaf type `Kind`, not `Type`, only because a test fixture co-loads a
`Type`.) Per-project tag namespacing is deferred until it earns its keep.

## The `defstructure` surface

A structure is a *composition of slots* plus *datalog laws*.

- **Slot cardinalities:** `(one T)`, `(optional T)`, `(many T)`, `(some T)`,
  `(ordered T)`. A slot whose target is a scalar (`(one :Bool)`) stores a leaf value
  with an auto-generated type-check law; a slot whose target is another structure
  reifies a relation.
- **Slot options:** `:payload` carries a companion code-form alongside a scalar
  slot's leaf value (stored as a sibling `:val/` datom on the node); `(reader f)`
  lets a structure expand authoring data-literals (fukan's `Shape` reads `Foo` /
  `[X]` / `{:f X}`). A reified relation's label comes from an authored `[label
  target]` clause, not a slot option.
- **`^:value` structures** are content-deduped, inline-anonymous nodes:
  structurally-equal values collapse to a single node (identity = a content hash).
  Used for nameless compound data — list/record/shape descriptions — where an
  entity-style named stand-in would erase the structure.
- **Laws:** `(law "desc" :offenders '[?x] :where '[…])` is a datalog constraint.
  `:scope :global` opts a law out of its structure's self-scoping (needed for
  cross-cutting laws like correspondence). `(structure/check db)` runs every law and
  returns the violations.

## Laws read at domain altitude — vocab-derived rules

Laws should read in the vocabulary's own terms, not in raw substrate patterns. The
core derives a set of **datalog rules from the live vocabulary** (`core/rules.clj`,
pure): a kind rule per structure (`(Stage ?e)`), a relation rule per relation slot
(`(calls ?a ?b)`), plus fixed substrate rules (`in-module`, `named`). `check`
auto-injects these into every law's query, so a law can say `(Stage ?s) (in-module
?s ?m)` instead of navigating `:structure/of` and reified `:rel/*` triples by hand.

## Ownership-on-owner

Module ownership flows via `:module/child` relations on the **owner**, not via
back-references on the owned entity. The `within-module` helper emits `:module/child`
automatically. Owned entities carry no `:module` field.

## Ingestion — many specs, one graph

Canvas files under `canvas/**/*.clj` are **auto-discovered** (`canvas_source`): each
is required (registering its vocabulary) and, if it defines `build-canvas`, called
to produce a per-spec structure db. The per-spec dbs are merged into one
(schema-driven: identity-bearing entities carry across, ref-typed attrs become
identity lookup-refs). Adding a spec is a single file drop — no registry edit.

**Cross-module references** are authored by name — `(across "<module>")` or
`(across "<module>" "<name>")` — and resolved *post-merge* (`resolve-cross-refs`);
an unresolved reference throws. References are by-name and build-time by design;
compile-checked var-references would be a whole-ref-system change, deferred.

## The model↔code seam — two directions

Fukan's thesis (one assertable graph) is realized by projecting both specification
and implementation onto the same substrate, then checking them against each other.

- **Extraction (code → model), up.** `model/extraction.clj` is a vocabulary-blind
  plug-point: a project registers one extractor (`register-extractor!`); the
  pipeline runs it over a code-root and merges the result. Fukan's own extractor
  (`target/clojure.clj`) reads clj-kondo analysis → Module + Operation structures.
- **Materialize (model → code), down.** `model/materialize.clj` projects a modelled
  primitive into a target representation. `render-base` is a multimethod dispatching
  on `[base kind]` (`[projection-base (:structure/of node)]`) — the base dimension
  (Blueprint → impl specs, Docs → reference docs) re-presents the same focus per
  target; each project supplies per-`[base, kind]` `defmethod`s (the instruction text
  stays out of the pure vocabulary), and composition along references falls out of
  re-dispatch under the same base. A projection is either a base or a
  *contextualization* that renders THROUGH a base and frames its output (DriftClose =
  Blueprint framed as drift-to-close). `compose` / `materialize-view` /
  `materialize-projection` compose the renders over a lens's focus.
- **Correspondence (verify).** `target/correspondence.clj` holds the laws that link
  the two — e.g. every modelled Stage is realized by an Operation of the same name
  in the corresponding module. Correspondence is its **own** concern, not a slot on
  any domain structure: a domain's laws describe that domain's own behaviour;
  "does the model realize in code" is orthogonal and lives apart (with `:scope
  :global` to escape self-scoping).

## Lens, probe, and projection — acts through a lens

A **Lens** carries a `:focus` (prose) and an optional selection `:query` (one
datalog `:where` clause-vector binding `?n`). `evaluate-lens` runs it against the
vocab-derived rules → the focus **sub-graph** (a genuine sub-graph: the selected
nodes and induced relations). Selection and traversal are one expression; there is
no seed/closure split. A focus can be narrowed by a further query (`refine`,
set-intersection) and the refined focus passed forward into a probe or projection —
acts chain over a refined focus rather than through a named orchestrator.

There are two **complementary** acts on the graph — analysis and synthesis:

- A **Probe** *reads* the model and observes it, yielding a **Finding**: a list of
  sub-graphs of interest. Observations carry `{:focus …  :as …  :note …}`. `check`
  is the canonical integrity probe (a finding that *gates* action is a trust signal).
  `projection/probe_code.clj` projects a probe's spec for an implementing LLM.
- A **Projection** *re-presents* the model in a target form — an implementation spec,
  docs, a materialize output. `render-base` is the projection multimethod (see the
  model↔code seam section above).

They compose through the **focus** (a sub-graph): a probe surfaces foci, a projection
consumes them. Probe until something is of interest; project it into a better shape;
enact it. The genuine duality across the whole system is extraction ⊣ projection —
lifting code *in*, lowering the model *out* — not probe vs projection, which are
complementary (analysis vs synthesis) within the one graph.

Adding an act is dropping a file: a probe is a `run-probe` defmethod
(`(defmethod run-probe "name" [db _ focus] …)`); a projection is a `render-base`
defmethod (`(defmethod render-base [base kind] [db base eid] …)`, dispatching on `[base kind]`).

## Conventions

- **`^:export`** marks vars reached only by dynamic dispatch — every `build-canvas`
  (registry discovery) and any var called from a law's `:where` (datalog predicate).
  Both lint configs honor `:exclude-when-meta #{:export}`.
- **Lint exemptions are mirrored** in `.clj-kondo/config.edn` and `.lsp/config.edn`
  (clojure-lsp doesn't honor clj-kondo per-namespace config).
- **clj-kondo CLI is ground truth.** The `defstructure` DSL is taught via `:hooks`;
  editor false-positives on DSL bodies are expected without the hook cache.

## REPL loop

The serving daemon is paused, so the loop is in-process (`clj -M:dev`):
`(go)` builds the model (canvas specs + the Clojure extractor over `src/`),
`(refresh)` reloads + rebuilds, `(status)` reports state, `(drift)` reports modelled
capabilities not yet realized in code. Build a db directly with `with-structures` /
`within-module`, query with `d/q`, check with `(s/check db)`.

---

*See [MODEL.md](./MODEL.md) for the substrate spec and [DECISIONS.md](./DECISIONS.md)
for why these shapes were chosen.*
