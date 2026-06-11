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
domain vocabulary** — no `Type`, no `Function`, no architectural kinds. This is the
bottom-up half of the premise (see VISION.md: *bottom-up language building,
top-down design*) — in the Lisp tradition of stratified languages, authoring a
grammar is every project's first modelling act:

- **fukan-on-fukan's** vocabulary lives in `canvas/vocabulary/` (the subject
  grammar, the act grammar — Lens / Probe / Finding / Projection — and the schema
  layer).
- **Each demo** owns its grammar under `demos/<domain>/vocab/`.
- **Domain-general** vocabulary lives in the opt-in stdlib `lib/` (code structures,
  grouping, the malli type dialect, grammar reflection).

The core knows no kinds. This keeps the substrate a small, honest floor and forces
every modelling exercise to confront "what is the clearest vocabulary for *this*?"
— which is the point of the current phase.

Structure tags are **namespace-qualified** (identity = defining ns + name), so
co-loaded projects may share short names. The remaining edge: law scoping rides
SHORT-name rules, so two same-short-named structures *with laws* deliberately
co-loaded would share a scope predicate — harmless until someone does it on purpose.

## The `defstructure` surface

A structure is a *composition of slots* plus *datalog laws*.

- **Slots are one typed map**, cardinality as a quantifier: a bare target is one
  (`:reads Model`), `[:? T]` optional, `[:* T]` zero+ ordered, `[:+ T]` one+
  ordered, `[:set T]` unordered (order and duplicate targets excluded from value
  identity). A scalar target (`:Bool`/`:Int`/`:String`) stores a
  leaf value with an auto type-check law; any other vector (`[:enum "a" "b"]`,
  `[:int {:min 1}]`) is a **refined scalar** — the core stores the type form
  verbatim and the generated law checks values through the registered type dialect
  (`fukan.canvas.core.typing`, the kernel's third plug-point); a slot whose target
  is another structure reifies a relation.
- **Slot options** ride the props position: `[:? {:payload :q} :String]`
  (`:payload` = a companion code-form stored as a sibling `:val/` datom); for
  cardinality one, lead with the props map. `(reader f)` lets a value structure
  expand authoring data-literals (the malli dialect's `Schema` reads native malli
  forms); `(syntax f)` lets a structure own instance-level sugar — a map → map
  rewrite of the authored slots map (`Operation` rewrites `:signature` into
  `:in`/`:out`).
- **Instances mirror defstructure** position-for-position: `(Structure name "doc"?
  {slot → value}? nested…)`, a top-level def-emitting form — the symbol is the var
  AND the entity name (`^{:name "…"}` metadata overrides). One `{slot → value}`
  map: a plural slot takes a vector of targets (authoring order is the sequence
  order, recorded as `:rel/order` — the bracket mirrors the quantifier), a
  labelled target is a `[label target]` pair, a payload slot takes
  `[value payload]`, reader literals pass as values. The same form without the
  symbol is an anonymous expression instance (inline values, def-wrapped vars).
  Nested member instances trail where defstructure's laws sit, lift to sibling
  `def`s, and route by target-type into the container's slots.
- **`^:value` structures** are content-deduped, inline-anonymous nodes:
  structurally-equal values collapse to a single node (identity = a content hash).
  Used for nameless compound data — list/record/shape descriptions — where an
  entity-style named stand-in would erase the structure.
- **Laws:** `(law "desc" :offenders '[?x] :where '[…])` is a datalog constraint.
  `:scope :global` opts a law out of its structure's self-scoping (needed for
  cross-cutting laws like correspondence). The recurring shapes have
  **combinators** — `(law "desc" (matched-by R :from S? :when {k v}? :scope T?))`,
  `(has R :when …?)`, `(has-any R1 R2 …)`, `(target R {k v})`, `(at-most-one R)` —
  expanding to datalog with negation routed through rules, so the datascript
  empty-relation `not-join` gotcha is encapsulated in the kernel.
  `(structure/check db)` runs every law and returns the violations.

## Laws read at domain altitude — vocab-derived rules

Laws should read in the vocabulary's own terms, not in raw substrate patterns. The
core derives a set of **datalog rules from the live vocabulary** (`core/rules.clj`,
pure): a kind rule per structure (`(Operation ?e)`), a relation rule per relation
slot (`(calls ?a ?b)`), inclusion/realized-as rules, plus fixed substrate rules
(`in-module`, `named`). `check` auto-injects these into every law's query, so a law
can say `(Operation ?s) (in-module ?s ?m)` instead of navigating `:structure/of`
and reified `:rel/*` triples by hand.

## Ownership-on-owner

Module ownership flows via `:child` relations on the **owner**, not via
back-references on the owned entity. Nested authoring routes members into the
container's slots automatically (`(Module m … (Operation f …))` emits the
`:exposes`/`:child` relations). Owned entities carry no module back-reference;
`in-module` resolves over `:child`/`:exposes`/`:owns`.

## Ingestion — many specs, one graph

Canvas files under `canvas/**/*.clj` are **auto-discovered** (`canvas_source`): each
is required — registering its vocabulary and interning its instance `def`s — and the
global assembler scans the interned vars into one structure db. Adding a spec is a
single file drop — no registry edit, no per-spec build fn, no merge pass.

**References between specs are ordinary var references** (require + var capture;
`declare` for forward refs within a namespace). Identity is the qualified var name,
so cross-namespace reference cycles are inexpressible by construction. The earlier
by-name `(across …)`/post-merge-resolution scheme is retired.

## Grammar reflection — the language is on the graph

The structure registry is projected onto the model on every build
(`lib.grammar/with-grammar`): each defstructure in the model's namespace closure
becomes a `Structure` node — slots as `:slot/<card>`-kinded labeled edges whose
scalar/refined targets reify as the type dialect's content-deduped `Schema` values,
laws as nodes carrying their datalog as a payload. The print-dual
(`fukan.canvas.projection.grammar`) renders a reified structure back as its
authoring form — `(grammar)` in the REPL is the live language reference, derived
not maintained — and grammar drift (`unused-structures`: vocabulary no instance
inhabits) becomes an ordinary reading.

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
  the two — every authored Operation is realized by an extracted Operation of the
  same name in the corresponding module, and the realized `:malli/schema` adheres
  to the modelled signature (type-level correspondence, through the same dialect).
  Correspondence is its **own** concern, not a slot on any domain structure: a
  domain's laws describe that domain's own behaviour; "does the model realize in
  code" is orthogonal and lives apart (with `:scope :global` to escape
  self-scoping).

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
`(refresh)` reloads + rebuilds, `(status)` reports state, `(overview)` projects the
system map, `(grammar)` prints the live language primer, `(drift)` reports modelled
capabilities not yet realized in code. Build a db directly with top-level instance
`def`s + `assemble-vars`, query with `d/q`, check with `(s/check db)`.

---

*See [MODEL.md](./MODEL.md) for the substrate spec and [DECISIONS.md](./DECISIONS.md)
for why these shapes were chosen.*
