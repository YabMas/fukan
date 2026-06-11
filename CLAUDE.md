# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven
development. The core question it explores: as LLMs handle more low-level coding,
how do humans maintain control over high-level structure and collaborate with LLMs
at that level of abstraction? You define a system's *structure* — its composition
of concepts plus the laws that must hold of it — model abstractions over that
structure, verify the whole as one assertable graph, and project it down toward an
implementation. Specification and implementation live on the **same** graph, so
intended structure and actual structure can be checked against each other.

The eventual vision is to render that graph as an interactive explorer in the
browser — but that is **deferred indefinitely** (see below). Today fukan is a
REPL-and-canvas tool exercised by modelling.

## ⚠ Current state — lean kernel rebuilt; in a modelling-exploration phase

The radical prune and the rebuild around a single structure-definition primitive
are **done**. `defstructure` is the heart of the kernel: *a structure = its
composition of Nodes/Relations + the datalog laws that must hold of it.* The
structure substrate **is** the model (no separate model-map).

**The lean kernel — `src/fukan/` (the only code on the classpath):**
- `canvas/core/structure.clj` — the `defstructure` primitive (slots + laws,
  `with-structures`, `within-module`, `check`, value-identity, the reader hook)
- `canvas/core/rules.clj` — pure vocab-derived datalog rules (kind/relation/module
  rules) auto-injected into every law so laws read at domain altitude
- `canvas/core/lens.clj` — `evaluate-lens`: run a lens's selection query → its
  focus sub-graph
- `canvas/projection/canvas_source.clj` — ingestion: discover `canvas/**/*.clj`
  defstructure specs, build + merge into one structure db, resolve cross-module refs
- `canvas/projection/probes.clj`, `probe_code.clj` — the probe surface (read the
  model → findings; project a probe spec for an implementing LLM). Cut-1.
- `model/pipeline.clj` → `build-model`; `model/extraction.clj` — the code→model
  extractor plug-point; `model/materialize.clj` — model→implementation-spec projection
- `target/clojure.clj` — the registered Clojure extractor (clj-kondo analysis);
  `target/correspondence.clj` — the model↔code correspondence laws
- `infra/model.clj` (composition root), `core.clj`, `utils/files.clj`,
  `libs/coordinate.clj`

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
project authors its own grammar on the core. Fukan-on-fukan's *unique* vocabulary lives
in `canvas/vocabulary/`; each demo owns its grammar under `demos/<domain>/vocab/`.

Reusable, domain-general vocabulary — vocab that is *not* unique to any one project —
lives in an **opt-in stdlib** at `lib/` (namespaces `lib.*`, reachable via the `.`
classpath root, parallel to `canvas.*`). It is **required, not auto-discovered**, so it
contributes grammar only when a model opts in. Current entries: `lib.code` (Kind / Effect
/ Operation / Module — standard code-structures, where a `Module` is one code namespace:
an API surface + owned types), `lib.grouping` (Grouping — the most abstract membership
primitive — and Connected), `lib.type.malli` (the malli type dialect — first entry in the
`lib.type.*` pluggable type-authoring surface), `lib.grammar` (GRAMMAR REFLECTION:
`with-grammar` projects the registry onto the model db — every defstructure in the model's
namespace closure becomes a `Structure` node, slots as `:slot/<card>`-kinded labeled edges
(scalar/refined targets reify as content-deduped `Schema` values), laws as nodes with
their datalog as a `:val/form` payload, one `Vocabulary` node per grammar namespace; the
join rule `(of-structure ?i ?s)` is in `lib.grammar/rules`; fukan's `build-model` always
reflects, demos opt in). The grouping ladder is deliberately
levelled: `Grouping` (bare membership) ⊂ `Module` (a code namespace) ⊂ `Subsystem` (a
cluster of modules realizing a capability — reserved, not yet defined). This keeps
`canvas/vocabulary/` focused on
what is genuinely fukan-specific. The stdlib is deliberately *not* a methodology/middle
layer (DDD/hexagonal/C4) — it's primitive, reusable structures, grown only on real need
(e.g. add a `lib.code` batch like `in-process`/`event-driven` when a second consumer needs
it). NB: tags are still a single global namespace — a shared `lib` tag is safe only while
every consumer uses the same one; per-project namespaced tags are deferred until a
consumer needs to diverge.

A `defstructure` is a composition of **slots** plus **laws**:

- Slots are ONE map of `rel → type-expr`; cardinality is a quantifier: a bare target
  is one (`:reads Model`), `[:? T]` optional, `[:* T]` zero+ ordered, `[:+ T]` one+
  ordered, `[:set T]` unordered (no `:rel/order`; order and duplicate targets are
  excluded from value identity). Multi-slots author as varargs — authoring order IS
  the sequence order, recorded as `:rel/order`. A scalar target (`:Bool`/`:Int`/
  `:String`) stores a leaf value with an auto-generated type-check law; any other
  vector (`[:enum "a" "b"]`, `[:int {:min 1}]`) is a REFINED scalar: the core stores
  the type form verbatim and the generated law checks values through the registered
  type dialect (`fukan.canvas.core.typing`, the kernel's third plug-point;
  `lib.type.malli` registers `:valid?` at load). Never hand-write a membership/range law.
- Slot options ride the props position: `[:? {:payload :q} :String]` (`:payload` =
  a companion code-form stored as a sibling `:val/` datom); for cardinality one,
  lead with the props map: `[{:payload :q} :String]`. `(reader f)` expands authoring
  data-literals (e.g. the malli dialect's Schema expands native malli forms). A
  reified relation's label comes from an authored `[label target]` element, not a
  slot option.
- `^:value` structures are content-deduped, inline-anonymous nodes (structurally
  equal values collapse to one node) — used for nameless compound data.
- `(law "desc" :offenders '[?x] :where '[…])` is a datalog constraint; `:scope
  :global` opts a law out of self-scoping. `(structure/check db)` runs every law →
  violations.

The current catalog is the source: read `canvas/vocabulary/*.clj` for fukan's own
grammar (`perspective` = Faculty + Phase + view-map; `act` = Lens + Probe + Projection;
`meta` = the reflexive schema layer), `lib/*.clj` for the reusable
stdlib (code/grouping/type.malli), and the demo vocabs.

## Spec locations

The self-model is laid out by **altitude**, not by pipeline role:

- `lib/<grammar>.clj` (ns `lib.*`) — the opt-in reusable stdlib: domain-general vocab
  (`lib.code`, `lib.grouping`, `lib.type.malli`). Required by consumers, not
  auto-discovered.
- `canvas/vocabulary/<grammar>.clj` — the grammars *unique to fukan* (vocab-only specs:
  a `defstructure` grammar, ingests no instances).
- `canvas/domain/<concept>.clj` — fukan as an *abstract* system: the `Faculty` hub
  (`faculties.clj`) and the use-side act instances (`lens`/`probe`/`projection`/`flow`).
- `canvas/realization/<subsystem>.clj` — fukan as a *built* system: one self-spec per
  implementation subsystem (`kernel`/`pipeline`/`infra`/`target`/…), plus `acts.clj`
  (the mechanism that runs the modelled acts).
- `canvas/correspondence.clj` — the seam between the two altitudes: the laws asserting
  each domain faculty is realized by its subsystem(s). It alone sits at the canvas root.
- `demos/<domain>/{vocab,model}/…` + a regression test; run with `clj -M:demos`.
- `.legacy-allium/` — pre-canvas Allium/Boundary specs (read-only archive; not on
  the classpath; not loaded).

Canvas files under `canvas/**/*.clj` are **auto-discovered** — adding a spec is a
single file drop (no registry edit).

## Cross-module references

A model spec refers to entities in another module with `(across "<module>")` (the
module node) or `(across "<module>" "<name>")` (a named child). These resolve
*post-merge* in `canvas_source/resolve-cross-refs` (an unresolved ref throws). The
structure registry is a **single global tag namespace**, so co-loaded projects
can't share tag names (fukan's data layer is `Kind`, not `Type`, because a test
fixture co-loads a `Type`) — eventual per-project namespacing is a standing finding.

## Conventions

**Ownership-on-owner.** Module ownership flows via `:module/child` relations on the
owner, not via back-references on the owned entity. The `within-module` helper in
`core/structure.clj` emits `:module/child` automatically.

**`^:export` for dynamically-invoked vars.** Vars reached only through dynamic
dispatch — every canvas module's `build-canvas` (registry discovery) and any var
called from a law's `:where` clause (datalog predicate) — carry `^:export`. Both
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
- Build a db directly: `(s/with-structures (s/within-module "m" …))`, query with
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
- `lib/` (ns `lib.*`) — the opt-in reusable stdlib vocab (code / grouping / type.malli)
- `canvas/{vocabulary,domain,realization}/` + `canvas/correspondence.clj` —
  fukan-on-fukan's unique grammars, abstract model, subsystem self-specs, and the seam
