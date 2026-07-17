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
  + combinators, `check` — which dispatches to the registered check-engine —
  value-identity, the reader/syntax hooks), and
  `canvas/core/{assemble,typing}.clj` — the global assembler and the type-dialect
  plug-point
- `canvas/core/rules.clj` — pure vocab-derived datalog rules (kind/relation/module
  rules) auto-injected into every law so laws read at domain altitude
- `canvas/core/lens.clj` — `evaluate-lens`/`projection-focus`: resolve a selection (a lens's, or a Projection's own inline `:select`) → its
  focus sub-graph; also OWNS the act grammar (`Lens`/`Projection`/`Check`)
- `cozo/` — the query engine. The model db **is a CozoDB**, and CozoScript (datalog)
  is what every query, law, and reader compiles to: `cozo/query.clj` — the kernel query
  primitive (`q`/`entity`), fukan's datalog subset → CozoScript over a typed-EAV view;
  `cozo/law.clj` — compiles every defstructure law → CozoScript and registers the
  check-engine plug-point (so `structure/check` runs on Cozo); `cozo/build.clj` —
  the native model→CozoDB build; `cozo/{db,mirror,rules}.clj` — the db handle, datom
  loaders, and the shared CozoScript rule substrate
- `canvas/ingestion/canvas_source.clj` — ingestion: discover the instance specs under the
  configured spec-dirs (`*spec-dirs*`, default `["canvas"]`) and assemble their instance vars into
  one structure db (the `fukan.common` grammar itself loads by *require*, not discovery);
  `canvas/projection/{grammar,instance,architecture}.clj` — the inspection print-duals
  (grammar → defstructure forms; model nodes → authored instance forms) + the system map.
  (The DOWNWARD projection — model→artifact materialization + the readings/Findings — was CUT
  2026-07-15 to focus scope on verified modelling; the act grammar `Lens`/`Projection`/`Check`
  stays in `core/lens.clj` as the dormant seam for when the projection side is re-expanded.)
- `model/pipeline.clj` → `build-model`; `model/extraction.clj` — the code→model
  extractor plug-point (a registry slot, blind to the language)
- `infra/model.clj` (composition root — registers the project extractor + loads the dialect), `core.clj`

fukan's own **vocabulary** — the code grammar (Kind/Effect/Operation/Module/Subsystem) + its
model↔code correspondence — plus its Clojure extractor, its malli type dialect, and grammar
reflection — is NOT in `src/`. It is the **`fukan.common.*` library tier** at `common/fukan/common/**`
(its own shipped classpath root): `vocab/` (the grammar), `typing/` + `extraction/` (the
dialect/extraction plugins), and `reflect/` (reflection) — see "The fukan.common grammar tier" below.
It loads via the `fukan.common` index ns (a *require*), not by discovery. `src/fukan/` is kernel
mechanics + the build pipeline + the three plug-points (extraction, typing, render) only.

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
work now is authoring fukan's own structure directly on `defstructure` in `common/fukan/common/vocab/`,
pressure-testing the core in every way. The grammar was extracted into the reusable **`fukan.common`
tier** (2026-07-14) because a second project is about to consume it — fukan-modelling-fukan is now
just the *first consumer* (its self-model lives in `canvas/architecture/`, off the shipped surface).
The standing discipline still holds: do *not* spend time abstractly designing reusable/methodology
layers (DDD/hexagonal/C4) ahead of a concrete case — grow the vocab opportunistically *from*
modelling, never ahead of it. The tier extraction was pulled by a concrete second-project need, not
speculation.

## The fukan.common grammar tier (the kernel ships none)

The core (`src/fukan/canvas/`) ships only the `defstructure` primitive and the
ingestion/projection machinery. It ships **no domain vocabulary**. fukan's own
vocabulary — the grammar it models *itself* with — is the **`fukan.common.*` library tier** at
`common/fukan/common/**` (root `common/` + the `fukan/common/…` namespace tree), a **shipped**
classpath root (base `:paths`) *outside* the `src/` code-root, so fukan's own extractor never reads
the grammar as if it were modelled code. The tier loads via the `fukan.common` **index ns** (a
`require` that pulls in every element) — NOT by discovery; discovery (`*spec-dirs*`, default
`["canvas"]`) is reserved for a project's *instance* specs. A consuming project depends on fukan,
`(:require [fukan.common])`, and authors its own model against `fukan.common.vocab.*`. Organized
**by element**: each file is the complete story of one element — its `defstructure` + the
laws/correspondence about it. (The Clojure extraction that *populates* the code grammar is a
separate seam, `common/fukan/common/extraction/`; the type dialect is
`common/fukan/common/typing/malli.clj`.)

- `common/fukan/common/vocab/grouping.clj` — `Grouping` (the most abstract membership primitive) +
  `Connected` (a flow-node facet). The structural primitives the rest builds on.
- `common/fukan/common/vocab/code/{kind,effect,operation,module,subsystem}.clj` — the code grammar
  (Kind / Effect / Operation / Module / Subsystem). **PURE DESIGN — language-neutral.** Each element
  file carries its structure and the laws that are its own slot semantics, and knows nothing about
  what language the code is written in.
  **The design↔code correspondence is NOT here** (since 2026-07-17): it maps design INTO a SPECIFIC
  language's FACT theory. The codomains are real `defstructure`s in that language's extractor —
  `Operation ↦ Fn` (a Clojure function: `:calls` graph + `:private`/`:export`/`:test-support` metadata
  conventions), `Module ↦ Ns` (a namespace) — in
  `common/fukan/common/extraction/clojure/{operation,module}.clj`, declared from outside via the
  external `(correspond Design Fact …)` hook. The two strata are DISTINCT tags: the twin is a genuine
  cross-tag map, not the Operation-tag-with-a-provenance-flag graft it was before (that graft is why
  the demands are still six bespoke forms — a morphism needs a codomain, now it has one). Keeping the
  fact theory here means the shipped, language-neutral vocab exports no Clojure constructs; a project
  loading the vocab with a different extractor correctly gets no `Fn`/`:calls` at all.
  Everything is still GENERATED — the plugin hand-writes no mechanism. Adherence is the kernel's
  generic `(agrees {:by :structural :over [:in :out]})` (no hand-written comparator); call realization is
  `(delegates {:realized-by :calls :faithful true})`, generating an OP-ALTITUDE TRANSITIVE
  `:corresponds/Operation.delegates-realized` law (every cross-module design delegation must be realized
  by a `:calls+` PATH between the endpoints' own twins — the kernel reuses the `calls+` closure the
  `:transitive` fact-slot already emits) plus the module-altitude `delegates-faithful` reverse. A caller
  wanting any demand as a worklist names its stable law key through `law/violation-names` directly (as
  `dev/user.clj` does) — no per-demand reader wrapper is kept.
  `extraction/clojure/module.clj` holds the `Ns` codomain + the bridged twin ROOT that `Fn` twins nest
  within — `(correspond Module Ns (bridge :qualified-suffix))`. The bridge is a name-match STRATEGY KEYWORD the
  kernel's generic `name-match` builtin lowers (canvas short-name is a separator-agnostic dotted
  suffix of the code ns) — no hand-written CozoScript, no name-bridge fn; the generated Operation twin
  correlates modules with the same `(name-match :qualified-suffix …)` inside the injected `twin` rule.
  The effect-correspondence check is the generated `:corresponds/Operation.performs-covered` demand
  (from `(performs {:covered-from [:calls* :performs]})`). The vocab laws reach shared vocab via datalog
  injection (no compile cycle, since the `fukan.common` index requires every element). A law that is a
  declaration's SLOT SEMANTICS rides the declaring structure itself: `Subsystem` carries the
  `:may-depend` conformance/acyclicity teeth **plus** the rehomed module-graph acyclicity +
  membership-totality demands (module-graph laws ride the clustering concept).

The type dialect and the extraction seam are NOT part of the code vocabulary — each is a
self-contained **plugin** in its own area (both realize a kernel plug-point; a plugin owns a
SPECIALIZED vocabulary + its mechanism together, not scattered into general vocab):
- `common/fukan/common/typing/malli.clj` (ns `fukan.common.typing.malli`) — the malli type DIALECT,
  one honest file: the whole dialect is malli top-to-bottom, so it lives under the malli name (not a
  generic `typing` layer — the neutral SPI is the kernel's `fukan.canvas.core.typing`). Three sections:
  the shape VOCABULARY (`Schema`/`SchemaChoice`/`SchemaField` — malli modelled as content-deduped
  `^:value` structures — + the authoring readers), the runtime BRIDGES (`render`/`valid?`), and the
  dialect wiring. The HOOK side of the `typing` SPI; requiring it self-registers the full dialect at load.
  `common/fukan/common/typing/` is the dialect AREA (room for a sibling realization if one ever appears).
- `common/fukan/common/extraction/` (ns `fukan.common.extraction.*`) — the Clojure EXTRACTION SEAM: `core.clj`
  (orchestration: clj-kondo `analyze` + `op-eid`, calling each element's builder) +
  `clojure/{effect,module,operation}.clj` (the per-element extraction builders). Mints no structures;
  the HOOK for the extraction plug-point; the composition root registers `extract-roots`.

`src/fukan/canvas/core/reflect.clj` (ns `fukan.canvas.core.reflect`) — GRAMMAR REFLECTION, kernel-native
**CORE machinery** (NOT the reusable `fukan.common` vocab): `reflect`/`with-grammar` reifies the registry
→ model db (every defstructure → a `Structure` node, slots as `:slot/<card>` edges, laws as `:val/form`
payload nodes, one `Vocabulary` per ns). It is grammar-AGNOSTIC (reifies whatever registry exists) and
the build ALWAYS runs it, so it belongs with the machinery, beside the act grammar in `core/lens.clj`.
The runtime never consults the reflected nodes — they exist only so the grammar is viewable as data (the
print-dual, `unused-structures`). The meta-grammar it mints — `Structure`/`Law`/`Vocabulary`/`Relation`
— is the tool's own vocabulary for describing grammars (same category as the act grammar), hence core.
It reaches the type dialect only through the neutral SPI (`core/typing`), so it depends on no concrete
dialect (the composition root wires that).

(The Lens-act `Coverage` law that a projection's focus once needed was DISSOLVED 2026-06-29: a
projection now carries its focus ITSELF — an inline `:select`, a named `Lens` only when a focus is
genuinely shared, none = the whole model — so the guarantee (a reading's selection cannot drift from
the reading) is structural, not a law.)

**The `canvas/principles/` layer was CUT (2026-07-13)** to focus scope on vocab-building +
verifiable models before re-widening ambition. Its genuine principle content (parse-don't-validate
TrustBoundary + totality, declared-effects/deep-modules/operation-surface demands and the
Boundary/Depth/latent-boundaries readings) is gone (git history preserves it); the foundation it had
bundled was kept — the two module-graph enforcement laws rehomed onto `Subsystem`, the correspondence
call-readers onto `operation.clj` (with the rest of Operation's correspondence), the
effect-correspondence reader onto `effect.clj`.

The grouping ladder is levelled: `Grouping` (bare membership) ⊂ `Module` (a code namespace:
an API surface + owned types) ⊂ `Subsystem` (a cluster of modules realizing a capability, with
a declared `:may-depend` DAG it enforces ITSELF — conformance + acyclicity are its slot-semantics
laws — against the extracted code graph). There is **no convenience umbrella** — Clojure can't re-export the generated
instance-constructor macros, so consumers `require` the specific elements they use; structure
tags are verbose (`:fukan.common.vocab.code.operation/Operation`). Grow this vocab **only under
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
  `fukan.common.typing.malli` registers `:valid?` at load). Never hand-write a membership/range law.
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
  datalog emitting `not-join` directly (the Cozo query compiler lowers stratified
  negation correctly, so the combinators need no negation-routing dance; never
  hand-write these shapes). `(structure/check db)` runs every law → violations.
- Correspondence is a CROSS-TAG MORPHISM, declared EXTERNALLY via `(correspond Design Fact …)` (both
  concepts' own `defstructure`s stay pure identity — there is no inline correspondence form). `Fact` is
  the CODOMAIN — a real `defstructure` whose slots are the extracted constructs (design `Operation` ↦
  Clojure `Fn`; design `Module` ↦ `Ns`). The two strata are DISTINCT tags, so the twin is a genuine map
  between two theories, not one tag split by a provenance flag. `Fact` may be omitted, defaulting to
  `Design` — a same-tag IDENTITY correspondence (a concept recognised in code rather than realized by a
  distinct construct). Demands are declared per-node or per-relation — `(realized …)` / `(covered …)` /
  `(agrees …)` node demands; `:realized-by` / `:faithful` on a relation slot (op-altitude transitive
  call demand); `:covered-from [R* S]` on a relation slot (path demand: every target the twin reaches
  over R*·S must be declared) — and their laws are GENERATED. Never hand-write `realized` / `covered` /
  `call-realization` / `fidelity` / `covered-from` shapes by hand. (The block is STILL six bespoke forms
  — giving it a codomain is the precondition for collapsing it to a map's components, the next step.)

The current catalog is the source — or just run `(grammar)` in the REPL: the
print-dual renders every vocabulary live. The files are under `common/fukan/common/vocab/**`.

A `defrelation` (in `core/structure.clj`, sibling of `defstructure`) declares a RELATION as an
ELEMENT — the relation itself, not a slot that happens to use it. Two forms:

- **Character** — `(defrelation :child "doc" {:isa :contains})` — a PRIMITIVE relation (its edges
  come from the `:rel/kind` of whatever structures declare a slot of that name) declaring how it
  relates to OTHER relations: `:isa <genus>` emits the subsumption rule `(genus ?a ?b) ⇐ (rel ?a ?b)`
  so every law over the genus sees the species' edges for free; `:transitive` emits the `rel+`
  closure. Character is a property of the RELATION, so it is declared ONCE — never repeated on each
  slot. **The kernel names no relation of its own:** the `contains` genus, its closure, and
  `in-module` are vocab elements (`vocab/grouping`, `vocab/code/module`), not kernel emission.
- **Derived** — `(defrelation :module-depends "doc" '[?m ?n] '[…])` — a named custom-bodied datalog
  rule `check` auto-injects into every law/query (like the vocab-derived rules), the way several laws
  share one join without each re-inlining it. A recursive body is allowed (Cozo's semi-naive fixpoint
  terminates where datascript diverged — the old non-recursion guard is gone), but prefer
  non-recursive: the rule pays the fixpoint on every check.

A relation's tag is UNQUALIFIED (`:contains`) because its rule name is global — so two vocabularies
declaring the same relation name collide silently, and anything scoping by tag namespace must fall
back to the declaring `:ns` (as the declarations golden does). `:transitive` still rides the slot for
relations that are not elements yet (`:delegates`, `:calls`).

## Spec locations

Laid out by **tier** (shipped library vs fukan's own use) and, within a tier, by **altitude**:

- `common/fukan/common/vocab/**` (ns `fukan.common.vocab.*`) — the shared VOCABULARY (the grammar):
  the structural primitives (`grouping`) and the code grammar by element
  (`code/{kind,effect,operation,module,subsystem}`). Loaded via the `fukan.common` index (require), NOT discovered.
- `common/fukan/common/typing/malli.clj` (ns `fukan.common.typing.malli`) — the malli type DIALECT plugin
  (its own area, not general vocab; realizes the `typing` SPI). `common/fukan/common/extraction/**`
  (ns `fukan.common.extraction.*`) — the Clojure EXTRACTION SEAM plugin (realizes the extraction SPI).
  Both in the `fukan.common` index; the whole tier is a shipped classpath root (`common/`), so a second
  project reuses it as a dependency.
- `src/fukan/canvas/core/reflect.clj` (ns `fukan.canvas.core.reflect`) — grammar REFLECTION (registry →
  model db). Kernel-native **CORE** machinery, NOT the reusable vocab: grammar-agnostic, the build always
  runs it, beside the act grammar in `core/lens.clj`.
- `canvas/principles/` — **CUT (2026-07-13).** The adopted-principles layer was removed to focus
  scope on vocab-building + verifiable models; its two module-graph enforcement laws rehomed onto
  `Subsystem`, its correspondence readers onto `operation.clj`/`effect.clj`. (git history preserves it.)
- `canvas/instruments/` — fukan as a *user of itself*: its use-side INSTANCES (Lens/Projection
  tool-definitions authored against the act grammar in `core/lens.clj`). Currently **PARKED —
  fukan ships none** (the dir is empty/absent). The DOWNWARD projection (materialization + the
  readings) was CUT 2026-07-15; the act grammar (`Lens`/`Projection`/`Check` + the selection
  engine) stays as the dormant seam, kept live only for inspection — the print-duals resolve
  their focus through `core/lens.clj`'s `focus-nodes`.
- `canvas/architecture/<area>/…` — fukan as a *built* system: one self-spec per `src/`
  module, grouped by area (`kernel`/`ingestion`/`cozo`/`projection`/`orchestration`), plus
  `subsystems.clj` (the capability clusters + the declared `:may-depend` DAG the
  architecture-quality laws enforce). Models ONLY fukan's `src/` — the vocab, dialect, and
  extractor are tools fukan *uses*, not part of its built design.
- `.legacy-allium/` — pre-canvas Allium/Boundary specs (read-only archive; not on
  the classpath; not loaded).

Instance-bearing spec files under the configured spec-dirs (`*spec-dirs*`, default `["canvas"]` →
`canvas/**/*.clj`, fukan's self-model) are **auto-discovered** — adding a spec is a single file drop
(no registry edit). The `fukan.common` grammar tier is not discovered; it loads by *require*.

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
`clojure -M:lint --lint src common canvas test` is authoritative (the `:lint` alias carries the
`.` root so `hooks.fukan.structure` resolves — a bare `-M` misses it now that `.` is off the base
`:paths`).

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
- `(correspondence)` — the design↔fact seam as one card: twin ladder + every demand with its stable law key.
- Build a db directly: top-level instance `def`s + `(a/assemble-vars [#'x …])`, query with
  `d/q`, run `(s/check db)`.
- **Never** use `remove-ns`, `require :reload`, or `(reload/reload)` directly.

nREPL runs on port 7889 (`clj -M:nrepl`).

## Build pipeline

`build-model code-root` (`model/pipeline.clj`): discover + ingest the project's instance specs
(`*spec-dirs*`, default `["canvas"]` → fukan's self-model; the `fukan.common` grammar tier is loaded
by *require* at the composition root, not discovered); when a `code-root` exists AND an extractor is
registered, merge the extracted code structures onto the same graph and re-resolve cross-refs.
`(structure/check db)` then runs all laws — including the correspondence laws — so
model↔code drift surfaces as violations. The legacy Allium/Boundary parse phases
and the old Phase 4–6 analyzer are retired.

**Classpath tiers (`deps.edn`).** Base `:paths ["src" "common" "resources"]` is the *shipped* surface —
`fukan.*` (core) + `fukan.common.*` (grammar). The `.` root (fukan's self-model `canvas/`, `tasks/`,
`hooks/`) lives on the fukan-local aliases (`:dev`/`:test`/`:kondo`/`:lint`/`:nrepl`/`:run`), so a
consuming project inherits only core + grammar. **Consuming fukan from another project:** depend on
fukan, `(:require [fukan.common])` (or the specific `fukan.common.vocab.*` elements), bind
`fukan.canvas.ingestion.canvas-source/*spec-dirs*` to your own spec dir, and call `build-model` with
your own code-root (the default composition in `fukan.infra.model` registers the Clojure extractor +
malli dialect; write your own composition to vary either).

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
- `src/fukan/canvas/ingestion/canvas_source.clj` — canvas discovery, merge, cross-refs
- `common/fukan/common.clj` (ns `fukan.common`) — the grammar INDEX: one require that registers the
  whole `fukan.common.*` tier (vocab + typing + extraction)
- `common/fukan/common/vocab/` (ns `fukan.common.vocab.*`) — fukan's vocabulary: the code grammar by
  element + grouping. **Pure design, language-neutral — carries NO correspondence.** The membership
  relations are ELEMENTS here: the `contains` genus + `:child` in `grouping.clj`,
  `:exposes`/`:owns`/`:offers` + the derived `in-module` in `code/module.clj`. The
  module-dependency-graph relations + readers (`module-owns`/`module-depends`/`module-dependencies`)
  live with the architecture laws that consume them in `code/subsystem.clj`
- `common/fukan/common/extraction/clojure/` — the FACT theory + the design↔Clojure map: `operation.clj`
  holds the `Fn` codomain (a Clojure function) + `(correspond Operation Fn …)` (the `(agrees {:by
  :structural})` adherence demand + the `(delegates {:realized-by :calls :faithful true})` op-altitude
  transitive call-realization demand, all GENERATED — no hand-written readers); `module.clj` holds the
  `Ns` codomain + `(correspond Module Ns (bridge :qualified-suffix))`, the twin root
- `src/fukan/canvas/core/reflect.clj` (ns `fukan.canvas.core.reflect`) — grammar REFLECTION (registry → model db); kernel-native CORE machinery, not the reusable vocab
- `common/fukan/common/typing/malli.clj` (ns `fukan.common.typing.malli`) — the malli type DIALECT plugin, one file
  (shape vocab + bridges + wiring); realizes the `typing` SPI
- `common/fukan/common/extraction/` (ns `fukan.common.extraction.*`) — the Clojure EXTRACTION SEAM plugin: `core.clj`
  orchestration + `clojure/{effect,module,operation}.clj` (realizes the extraction SPI)
- `canvas/architecture/` — fukan-on-fukan's built-system self-specs (modules + subsystems +
  `:may-depend` DAG); the use-side instruments area (`canvas/instruments/`) is currently PARKED
