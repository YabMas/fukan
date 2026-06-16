# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven
development. The core question it explores: as LLMs handle more low-level coding,
how do humans maintain control over high-level structure and collaborate with LLMs
at that level of abstraction? You define a system's *structure* — its composition
of concepts plus the laws that must hold of it — model abstractions over that
structure, verify the whole as one assertable graph, and project it down toward an
implementation. Specification and implementation live on the **same** graph, so
intended structure and actual structure can be checked against each other.

The approach is **bottom-up language building, top-down design** — the Lisp
tradition of stratified languages: every project grows its own grammar on the one
primitive (the core ships none), and design presses down on it as laws the graph
enforces — against the model and against the LLM-written implementation alike.

The eventual vision is to render that graph as an interactive explorer in the
browser — but that is **deferred indefinitely** (see below). Today fukan is a
REPL-and-canvas tool exercised by modelling.

## ⚠ Current state — lean kernel rebuilt; in a modelling-exploration phase

The radical prune and the rebuild around a single structure-definition primitive
are **done**. `defstructure` is the heart of the kernel: *a structure = its
composition of Nodes/Relations + the datalog laws that must hold of it.* The
structure substrate **is** the model (no separate model-map).

**The lean kernel — `src/fukan/` (the only code on the classpath):**
- `canvas/core/structure.clj` — the `defstructure` primitive (the slot map + laws
  + combinators, `check`, value-identity, the reader/syntax hooks), and
  `canvas/core/{assemble,typing}.clj` — the global assembler and the type-dialect
  plug-point
- `canvas/core/rules.clj` — pure vocab-derived datalog rules (kind/relation/module
  rules) auto-injected into every law so laws read at domain altitude
- `canvas/core/lens.clj` — `evaluate-lens`: run a lens's selection query → its
  focus sub-graph
- `canvas/projection/canvas_source.clj` — ingestion: discover `canvas/**/*.clj`
  defstructure specs and assemble their instance vars into one structure db;
  `canvas/projection/{grammar,instance,overview}.clj` — the two print-duals
  (grammar → defstructure forms; model nodes → authored instance forms) + the
  system map
- `canvas/projection/{finding,probes,probe_code}.clj` — the probe surface (`finding`
  = the Finding output type; read the model → findings; project a probe spec for an
  implementing LLM). Cut-1.
- `model/pipeline.clj` → `build-model`; `model/extraction.clj` — the code→model
  extractor plug-point; `model/materialize.clj` — model→implementation-spec projection
- `target/clojure.clj` — the registered Clojure extractor (clj-kondo analysis);
  `target/correspondence.clj` — the model↔code correspondence laws
- `dialect/malli.clj` — the malli dialect runtime bridges (render, signature
  adherence, refined-value `valid?`)
- `infra/model.clj` (composition root), `core.clj`

**Parked under `.paused/`** (off-classpath): only the **browser explorer / viewer**
(`web/`, top-level `projection/`, `infra/server`). The other once-parked subsystems
(agent surface, old Clojure analyzer, lens substrate, inspect/instruct, code
synthesis) were **removed** once the rebuild reborn their capabilities on the
substrate — they live in git history. The pre-canvas Allium/Boundary specs remain
in `.legacy-allium/` as the baseline to compare against once the transition closes.

**The browser explorer / viewer is DEFERRED INDEFINITELY.** It is fukan's eventual
vision, but it is *not on the near roadmap and should not be proposed as a next
step* — the core is being exercised extensively first. Do not re-suggest reviving it.

**Direction — exercise the core via modelling, not a premature middle layer.**
The work now is authoring a wide variety of models directly on `defstructure`
(canvas specs + demos), to pressure-test the core in every way. We are **not**
building a reusable methodology/middle layer (DDD/hexagonal/C4 vocabularies) yet —
there's no purpose for one today. Keep the core *able* to grow such a layer later
(the refinement mechanism), and prove that mechanism only when a concrete need
arises; otherwise the focus is exploring modelling itself.

## The kernel ships mechanics only — no vocabulary

The core (`src/fukan/canvas/`) ships only the `defstructure` primitive and the
ingestion/projection machinery. It ships **no domain vocabulary**: every modelling
project authors its own grammar on the core. Fukan-on-fukan's *unique* grammar lives
in `canvas/subject.clj` (its pure-grammar self-portrait); each demo owns its grammar
under `demos/<domain>/vocab/`.

Reusable, domain-general vocabulary — vocab that is *not* unique to any one project —
lives in an **opt-in stdlib** at `lib/` (namespaces `lib.*`, reachable via the `.`
classpath root, parallel to `canvas.*`). It is **required, not auto-discovered**, so it
contributes grammar only when a model opts in. Current entries: `lib.code` (Kind / Effect
/ Operation / Module — standard code-structures, where a `Module` is one code namespace:
an API surface + owned types), `lib.grouping` (Grouping — the most abstract membership
primitive — and Connected), `lib.lens` (the opt-in USE-SIDE act grammar: `Lens` focuses a
graph → sub-graph, `Finding` is a reading through a lens, `Projection` renders a graph to a
target artifact — the authoring surface for analytical instruments, distinct from the
abstract `Lens`/`Projection` concept portraits in `canvas.subject`), `lib.type.malli` (the
malli type dialect — first entry in the
`lib.type.*` pluggable type-authoring surface), `lib.grammar` (GRAMMAR REFLECTION:
`with-grammar` projects the registry onto the model db — every defstructure in the model's
namespace closure becomes a `Structure` node, slots as `:slot/<card>`-kinded labeled edges
(scalar/refined targets reify as content-deduped `Schema` values), laws as nodes with
their datalog as a `:val/form` payload, one `Vocabulary` node per grammar namespace; the
join rule `(of-structure ?i ?s)` is in `lib.grammar/rules`; fukan's `build-model` always
reflects, demos opt in). The grouping ladder is deliberately
levelled: `Grouping` (bare membership) ⊂ `Module` (a code namespace) ⊂ `Subsystem` (a
cluster of modules realizing a capability — reserved, not yet defined). This keeps
`canvas/subject.clj` focused on
what is genuinely fukan-specific. The stdlib is deliberately *not* a methodology/middle
layer (DDD/hexagonal/C4) — it's primitive, reusable structures, grown only on real need
(e.g. add a `lib.code` batch like `in-process`/`event-driven` when a second consumer needs
it). Tags are namespace-qualified, so a `lib` grammar never collides with a consumer's —
the one remaining edge is law scoping, which rides short-name rules.

A `defstructure` is a composition of **slots** plus **laws**:

- Slots are ONE map of `rel → type-expr`; cardinality is a quantifier: a bare target
  is one (`:reads Model`), `[:? T]` optional, `[:* T]` zero+ ordered, `[:+ T]` one+
  ordered, `[:set T]` unordered (no `:rel/order`; order and duplicate targets are
  excluded from value identity). A scalar target — a bare malli keyword
  (`:string`/`:int`/`:boolean`) — stores a leaf value with an auto-generated
  type-check law; any other
  vector (`[:enum "a" "b"]`, `[:int {:min 1}]`) is a REFINED scalar: the core stores
  the type form verbatim and the generated law checks values through the registered
  type dialect (`fukan.canvas.core.typing`, the kernel's third plug-point;
  `lib.type.malli` registers `:valid?` at load). Never hand-write a membership/range law.
- Slot options ride the props position: `[:? {:payload :q} :string]` (`:payload` =
  a companion code-form stored as a sibling `:val/` datom); for cardinality one,
  lead with the props map: `[{:payload :q} :string]`. `(reader f)` expands authoring
  data-literals (e.g. the malli dialect's Schema expands native malli forms); a
  `(syntax f)` hook (map → map) rewrites an instance's slots map before parsing
  (e.g. `lib.code/Operation` rewrites `:signature` into `:in`/`:out`).
- INSTANCES mirror defstructure position-for-position: `(Structure name "doc"?
  {slot → value}? nested…)` — a top-level def-emitting form (the symbol is the var
  AND the entity name; `^{:name "…"}` metadata overrides, e.g. a name the var can't
  carry). One map of `slot → value`: a plural slot takes a VECTOR of targets
  (authoring order = `:rel/order`; the bracket mirrors the quantifier), a labelled
  target is a `[label target]` pair, a payload slot takes `[value payload]`, reader
  literals pass as values. Anonymous/inline instances are the same form without the
  symbol: `(Structure "doc"? {…})`. Nested member instances trail where
  defstructure's laws sit and route by target-type into the container's slots.
- `^:value` structures are content-deduped, inline-anonymous nodes (structurally
  equal values collapse to one node) — used for nameless compound data.
- `(law "desc" :offenders '[?x] :where '[…])` is a datalog constraint; `:scope
  :global` opts a law out of self-scoping. The recurring shapes have COMBINATORS —
  `(law "desc" (matched-by R :from S? :when {k v}? :scope T?))`, `(has R :when …?)`,
  `(has-any R1 R2 …)`, `(target R {k v})`, `(at-most-one R)` — which expand to
  datalog with negation routed through rules (the datascript empty-relation
  `not-join` gotcha is encapsulated in the kernel; never hand-write these shapes).
  `(structure/check db)` runs every law → violations.

The current catalog is the source — or just run `(grammar)` in the REPL: the
print-dual renders every vocabulary live. The files: `canvas/subject.clj` for
fukan's own grammar (one pure-grammar stratum: the substrate Node/Relation/Graph, the
grammar Structure/Slot/Law/Form/Vocabulary, the Model, the Source, and the use-side
Lens/Projection concepts), `lib/*.clj` for the reusable stdlib
(code / grouping / type.malli / grammar / lens), and the demo vocabs.

## Spec locations

The self-model is laid out by **altitude**, not by pipeline role:

- `lib/<grammar>.clj` (ns `lib.*`) — the opt-in reusable stdlib: domain-general vocab
  (`lib.code`, `lib.grouping`, `lib.type.malli`, `lib.grammar` — the reflection that
  projects the registry onto the model, `lib.lens` — the use-side act grammar
  Lens/Finding/Projection). Required by consumers, not auto-discovered.
- `canvas/subject.clj` — fukan as an *abstract* system: ONE stratum of pure-grammar
  PORTRAITS (zero instances) — the substrate (Node/Relation/Graph), the grammar
  (Structure/Slot/Law/Form/Vocabulary), the Model, the Source, and the use-side
  Lens/Projection concepts. fukan's own grammar is authored here, not in a separate
  vocabulary tree.
- `canvas/instruments.clj` — fukan as a *user of itself*: its own use-side INSTANCES
  (the concrete lenses, findings, and projections it points at itself — `survey`/`drift`/…,
  `Blueprint`/`DriftClose`), authored against the `lib.lens` grammar.
- `canvas/architecture/<subsystem>.clj` — fukan as a *built* system: one self-spec per
  implementation subsystem (`kernel`/`pipeline`/`infra`/`target`/…), plus `acts.clj`
  (the realization seam that runs the modelled instruments).
- `canvas/correspondence.clj` — the seam between the two altitudes: the laws asserting
  each subject concept is realized by its Module(s). It alone sits at the canvas root.
- `demos/<domain>/{vocab,model}/…` + a regression test; run with `clj -M:demos`.
- `.legacy-allium/` — pre-canvas Allium/Boundary specs (read-only archive; not on
  the classpath; not loaded).

Canvas files under `canvas/**/*.clj` are **auto-discovered** — adding a spec is a
single file drop (no registry edit).

## Cross-spec references

Instances are top-level `def`s holding values; references between specs are ordinary
**var references** (require + var capture; `declare` for forward refs in one ns) —
the global assembler resolves them, there is no merge/cross-ref pass. Structure tags
are namespace-qualified (identity = defining ns + name), so co-loaded projects may
share short names; the remaining edge is law SCOPING, which rides short-name rules.

## Conventions

**Ownership-on-owner.** Module ownership flows via `:child` relations on the
owner, not via back-references on the owned entity. Nested authoring routes members
into the container's slots automatically (`(Module m … (Operation f …))`).

**`^:export` for dynamically-invoked vars.** Vars reached only through dynamic
dispatch — any var called from a law's `:where` clause (a datalog predicate) or a
`(syntax …)` hook — carry `^:export`. Both
`.clj-kondo/config.edn` and `.lsp/config.edn` honor `:exclude-when-meta #{:export}`,
so the metadata alone suffices. Prefer `^:export` over a per-namespace exemption.

**Per-namespace lint exemptions live in BOTH `.clj-kondo/config.edn` and
`.lsp/config.edn`.** clojure-lsp doesn't honor clj-kondo's per-namespace config, so
namespace-wide `unused-public-var` exemptions must be mirrored in both files. The
only standing case is law-only test structures whose generated macro is never
called. When adding a namespace to one file's list, add it to the other.

**clj-kondo CLI is ground truth.** The defstructure DSL is taught to clj-kondo via
`:hooks` (each new `defstructure` macro is registered there). Editor
`not-a-function` / `unused-public-var` flashes on defstructure bodies are false
positives without the hook cache; the canonical full-classpath
`clojure -M -m clj-kondo.main --lint src test canvas demos` is authoritative.

## REPL workflow

The serving daemon is paused, so the loop is **in-process** (`clj -M:dev`), over the
structure substrate which is the model:

- `(go)` — load the model (`build-model`, defaults to a `"src"` code-root so the
  Clojure extractor merges code onto the design graph).
- `(refresh)` — reload changed code + rebuild the held model. Use after editing a
  canvas spec or any `src/` code.
- `(reset)` — reload + rebuild from scratch. Use after adding a new canvas file or
  removing/renaming a var (a removed `defmethod`/`defn` lingers until a clean reset).
- `(status)` — report model state. `(drift)` — report unrealized modelled
  capabilities via the correspondence laws.
- `(show 'name)` — print a node as its AUTHORED form (the instance print-dual);
  `(focus '[(Operation ?n) …])` — render a datalog-selected slice as authored
  forms (the textual model explorer); `(check)` — violations with each offender
  quoted as its form.
- Build a db directly: top-level instance `def`s + `(a/assemble-vars [#'x …])`, query with
  `d/q`, run `(s/check db)`.
- **Never** use `remove-ns`, `require :reload`, or `(reload/reload)` directly.

nREPL runs on port 7889 (`clj -M:nrepl`).

## Build pipeline

`build-model code-root` (`model/pipeline.clj`): ingest the `canvas/` design specs
(`canvas-source/build`); when a `code-root` exists AND an extractor is registered,
merge the extracted code structures onto the same graph and re-resolve cross-refs.
`(structure/check db)` then runs all laws — including the correspondence laws — so
model↔code drift surfaces as violations. The legacy Allium/Boundary parse phases
and the old Phase 4–6 analyzer are retired.

## Jujutsu workflow conventions

This repo uses Jujutsu (jj). Always check `jj st` before starting work. If `@` has
existing changes, run `jj new` to start clean. Commit per logical change:

```
jj desc -m "type(scope): short description"
jj new
```

Never use git commands directly — jj and git have different object models and
mixing them corrupts history.

## Key Files

- `dev/user.clj` — REPL helpers (`go`/`refresh`/`reset`/`status`/`drift`)
- `src/fukan/infra/model.clj` — model lifecycle + the composition root (registers
  the Clojure extractor)
- `src/fukan/model/pipeline.clj` — `build-model` (canvas ingestion + extraction merge)
- `src/fukan/canvas/core/structure.clj` — the `defstructure` primitive + `check`
- `src/fukan/canvas/projection/canvas_source.clj` — canvas discovery, merge, cross-refs
- `src/fukan/model/materialize.clj` — model→implementation-spec projection
- `src/fukan/target/{clojure,correspondence}.clj` — code extraction + correspondence laws
- `lib/` (ns `lib.*`) — the opt-in reusable stdlib vocab (code / grouping / type.malli /
  grammar / lens)
- `canvas/{subject,instruments,correspondence}.clj` + `canvas/architecture/` —
  fukan-on-fukan's grammar portrait, use-side instruments, the seam, and the subsystem
  self-specs
