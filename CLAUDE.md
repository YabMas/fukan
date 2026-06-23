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
  `canvas/projection/{grammar,instance,architecture}.clj` — the two print-duals
  (grammar → defstructure forms; model nodes → authored instance forms) + the
  system map
- `canvas/projection/{finding,probes}.clj` — the probe surface (`finding`
  = the runtime finding/reading output type; read the model → findings).
- `model/pipeline.clj` → `build-model`; `model/extraction.clj` — the code→model
  extractor plug-point (a registry slot, blind to the language); `model/materialize.clj`
  — model→implementation-spec projection
- `infra/model.clj` (composition root — registers the project extractor + loads the dialect), `core.clj`

fukan's own **vocabulary** — the code grammar (Kind/Effect/Operation/Module/Subsystem),
its model↔code correspondence and its Clojure extractor, the malli type dialect, and grammar
reflection — is NOT in `src/`. It lives in the auto-discovered `canvas/vocab/` self-model (see
"Vocabulary lives in canvas/vocab" below). `src/fukan/` is kernel mechanics + the build pipeline
+ the three plug-points (extraction, typing, render) only.

**Parked under `.paused/`** (off-classpath): only the **browser explorer / viewer**
(`web/`, top-level `projection/`, `infra/server`). The other once-parked subsystems
(agent surface, old Clojure analyzer, lens substrate, inspect/instruct, code
synthesis) were **removed** once the rebuild reborn their capabilities on the
substrate — they live in git history. The pre-canvas Allium/Boundary specs remain
in `.legacy-allium/` as the baseline to compare against once the transition closes.

**The browser explorer / viewer is DEFERRED INDEFINITELY.** It is fukan's eventual
vision, but it is *not on the near roadmap and should not be proposed as a next
step* — the core is being exercised extensively first. Do not re-suggest reviving it.

**Direction — exercise the core by modelling fukan ON itself, organized by element.** The
work now is authoring fukan's own structure directly on `defstructure` in `canvas/vocab/`,
pressure-testing the core in every way. There is deliberately **no reusable `lib/` stdlib**:
the old `lib.*` layer was dissolved into `canvas/vocab/` (2026-06-23) because it had exactly
one consumer — fukan itself — so the opt-in "reuse" ceremony was pure overhead. If a second
project ever shares this vocab, extracting a real stdlib from a *nicely-layered* `canvas/vocab/`
will be easy; until then, don't pre-build for reuse. The standing discipline holds: do *not*
spend time abstractly designing reusable/methodology layers (DDD/hexagonal/C4) ahead of a
concrete case — grow vocab opportunistically *from* modelling, never ahead of it.

## Vocabulary lives in canvas/vocab (the kernel ships none)

The core (`src/fukan/canvas/`) ships only the `defstructure` primitive and the
ingestion/projection machinery. It ships **no domain vocabulary**. fukan's own
vocabulary — the grammar it models *itself* with — lives in `canvas/vocab/` (namespaces
`canvas.vocab.*`, on the `.` classpath root), **auto-discovered** like the rest of
`canvas/**`. Organized **by element**: each file is the complete story of one element —
its `defstructure` + the laws/correspondence about it + how it is extracted from code.

- `canvas/vocab/grouping.clj` — `Grouping` (the most abstract membership primitive) +
  `Connected` (a flow-node facet). The structural primitives the rest builds on.
- `canvas/vocab/type.clj` — the malli type DIALECT: `Schema` modelled as content-deduped
  `^:value` structures + the runtime bridges (`render`/`valid?`/`sigs-adhere?`). The HOOK
  side of the `typing` SPI; requiring it self-registers the full dialect at load. A flat
  primitive (code vocab + grammar reflection both build on it).
- `canvas/vocab/grammar.clj` — GRAMMAR REFLECTION (`with-grammar`: registry → model db,
  every defstructure → a `Structure` node, slots as `:slot/<card>` edges, laws as `:val/form`
  payload nodes, one `Vocabulary` per ns; the join rule `(of-structure ?i ?s)` is in
  `…grammar/rules`). A TOOL, not core: the runtime never consults the reflected nodes — they
  exist only so the grammar is viewable as data (the print-dual, `unused-structures`). The
  build always reflects.
- `canvas/vocab/code/{kind,effect,operation,module,subsystem}.clj` — the code grammar
  (Kind / Effect / Operation / Module / Subsystem). Each element file carries its structure +
  *its* model↔code correspondence laws/readers + *its* Clojure extraction. The cross-element
  correspondence — `module-corresponds?` (the canvas-module↔code-ns name bridge) and `op-twin`
  (the authored-op↔extracted-op pairing, a `defrelation`) — lives in `module.clj`; the
  operation/effect/`fukan` laws reach it via datalog injection (no compile cycle, since the
  build auto-loads every element). `subsystem.clj` also holds the clean-architecture quality
  laws (`ModuleArchitecture`: no-mutual-dependency + `:may-depend` conformance/acyclicity/
  membership) + `latent-boundaries`.
- `canvas/vocab/code/extractor.clj` — the shared Clojure extractor orchestration (clj-kondo
  `analyze` + `op-eid` + `extract`), calling each element's builder. The HOOK for the extraction
  plug-point; the composition root registers `extract`.
- `canvas/vocab/fukan.clj` — TEMPORARY holding pen for the fukan-SPECIFIC correspondence tools
  (`Totality` on the `StructureDb` trust artifact; `LensCoverage` on the `Lens` act + `probe-`
  convention). Bindings not yet lifted — awaits the parameterized-trait groundwork.

The grouping ladder is levelled: `Grouping` (bare membership) ⊂ `Module` (a code namespace:
an API surface + owned types) ⊂ `Subsystem` (a cluster of modules realizing a capability, with
a declared `:may-depend` DAG the architecture-quality laws enforce against the extracted code
graph). There is **no convenience umbrella** — Clojure can't re-export the generated
instance-constructor macros, so consumers `require` the specific elements they use; structure
tags are verbose (`:canvas.vocab.code.operation/Operation`). Grow this vocab **only under
concrete design pressure** — never a methodology/middle layer designed abstractly ahead of real
cases. Methodology-shaped vocab (DDD/Wlaschin/APoSD idioms) is welcome once a concrete case
presses it out.

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
  `canvas.vocab.type` registers `:valid?` at load). Never hand-write a membership/range law.
- Slot options ride the props position: `[:? {:payload :q} :string]` (`:payload` =
  a companion code-form stored as a sibling `:val/` datom); for cardinality one,
  lead with the props map: `[{:payload :q} :string]`. `(reader f)` expands authoring
  data-literals (e.g. the malli dialect's Schema expands native malli forms); a
  `(syntax f)` hook (map → map) rewrites an instance's slots map before parsing
  (e.g. the code `Operation` rewrites `:signature` into `:in`/`:out`).
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
print-dual renders every vocabulary live. The files are under `canvas/vocab/**`.

A `defrelation` (in `core/structure.clj`, sibling of `defstructure`) declares a named
custom-bodied datalog rule that `check` auto-injects into every law/query (like the
vocab-derived rules) — the way several laws share one join (e.g. `op-twin`) without each
re-inlining it. Keep its body non-recursive (vocab-injected rules aren't timeout-guarded).

## Spec locations

The self-model is laid out by **altitude**, not by pipeline role:

- `canvas/vocab/**` — fukan's own VOCABULARY (the grammar it models itself with): the
  structural primitives (`grouping`), the malli type dialect (`type`), grammar reflection
  (`grammar`), the code grammar by element (`code/{kind,effect,operation,module,subsystem}` +
  `code/extractor`), and the temp fukan-specific tools (`fukan`). Auto-discovered.
- `canvas/instruments/<kind>.clj` — fukan as a *user of itself*: its own use-side INSTANCES,
  one file per kind (`lenses.clj`, `projections.clj` — `survey`/`drift`/…,
  `Blueprint`/`DriftClose`), authored against the `Lens`/`Projection` act grammar (in
  `core/lens.clj`). A separated TOOL-DEFINITIONS area, not part of fukan's design.
- `canvas/architecture/<area>/…` — fukan as a *built* system: one self-spec per `src/`
  module, grouped by area (`kernel`/`ingestion`/`reading`/`projection`/`orchestration`), plus
  `subsystems.clj` (the capability clusters + the declared `:may-depend` DAG the
  architecture-quality laws enforce). Models ONLY fukan's `src/` — the vocab, dialect, and
  extractor are tools fukan *uses*, not part of its built design.
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
`:hooks` — one generic `hooks.fukan.structure/instance` hook that every per-structure
instance-constructor macro routes to. Those per-structure `:analyze-call` entries are
**generated, not hand-written**: `tasks.kondo` scans the defstructure forms and writes
`.clj-kondo/generated/config.edn` (merged into `.clj-kondo/config.edn` via
`:config-paths`). After adding or removing a structure, run `clojure -M:kondo` to
regenerate; the `tasks.kondo-test/generated-config-file-is-current` test guards drift.
Editor `not-a-function` / `unused-public-var` flashes on defstructure bodies are false
positives without the hook cache; the canonical full-classpath
`clojure -M -m clj-kondo.main --lint src test canvas` is authoritative.

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
- `canvas/vocab/` (ns `canvas.vocab.*`) — fukan's vocabulary: the code grammar by element
  (each file = structure + correspondence + extraction), the malli dialect (`type`), grammar
  reflection (`grammar`), grouping; the Clojure extractor is `code/extractor.clj`, the
  cross-element correspondence (`module-corresponds?`/`op-twin`) is in `code/module.clj`
- `canvas/{instruments,architecture}/` — fukan-on-fukan's use-side instruments
  (tool-definitions) and the built-system self-specs (modules + subsystems + `:may-depend` DAG)
