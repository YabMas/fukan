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
project authors its own grammar on the core. Fukan-on-fukan's vocabulary lives in
`canvas/vocab/`; each demo owns its grammar under `demos/<domain>/vocab/`.

A `defstructure` is a composition of **slots** plus **laws**:

- Slot cardinalities: `(one T)`, `(optional T)`, `(many T)`, `(some T)`,
  `(ordered T)`. A slot whose target is a scalar (e.g. `(one :Bool)`) stores a
  leaf value with an auto-generated type-check law; otherwise it reifies a relation.
- Slot options: `:payload` (carry a companion code-form alongside a scalar slot's
  leaf, stored as a sibling `:val/` datom on the node), `(reader f)` (expand
  authoring data-literals — e.g. fukan's Shape expands `Foo` / `[X]` / `{:f X}`). A
  reified relation's label comes from an authored `[label target]` clause, not a slot
  option.
- `^:value` structures are content-deduped, inline-anonymous nodes (structurally
  equal values collapse to one node) — used for nameless compound data.
- `(law "desc" :offenders '[?x] :where '[…])` is a datalog constraint; `:scope
  :global` opts a law out of self-scoping. `(structure/check db)` runs every law →
  violations.

The current catalog is the source: read `canvas/vocab/*.clj` for fukan's own
grammar (shape/op/meta/arch/probe/projection/collab/lens) and the demo vocabs.

## Spec locations

- `canvas/vocab/<layer>.clj` — fukan-on-fukan's vocabulary (vocab-only specs: a
  `defstructure` grammar, no `build-canvas`).
- `canvas/model/<subsystem>.clj` — fukan-on-fukan's models (each defines a
  `^:export build-canvas` returning a structure db).
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
- `canvas/vocab/`, `canvas/model/` — fukan-on-fukan's vocabulary and models
