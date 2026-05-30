# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven development. It analyzes a target codebase — implementation code and behavioral specifications — to build a unified structural model, then projects and renders that model as an interactive graph in the browser. The core question it explores: as LLMs handle more low-level coding, how do humans maintain control over high-level structure and collaborate with LLMs at that level of abstraction?

The system is generic and pluggable: language analyzers (currently Clojure) register via multimethod dispatch, and the build pipeline is language-agnostic. Specifications and implementation are projected onto the same model so that intended structure (boundaries, contracts, guarantees) and actual structure (namespaces, functions, schemas) appear together. Documentation is a first-class input — it flows into the model to make the explorer meaningful, not just structural.

The system follows a functional core / imperative shell architecture, enforced by canvas specs.

## Querying the Model as an agent

Fukan exposes its Model to coding agents through `bin/fukan`. When working on or with Fukan, prefer querying the spec graph over grepping the codebase.

- **Primer:** [AGENTS.md](AGENTS.md) — read it before the first agent query. Covers the `fukan.agent.system` / `fukan.agent.api` surface, L0/L1/L2 query layering, the edit→refresh→query loop, `.fukan/agent-views.clj`, and sandbox limits.
- **Quick reference:** `fukan status` (daemon health), `fukan eval '<expr>'` (run a query), `fukan primer` (print AGENTS.md). Requires a running daemon (`clj -M:run …`).
- **Live catalog:** inside `fukan eval`, call `(help)` for the current fn surface — trust it over `AGENTS.md` if they disagree.

## Spec Locations (source of truth)

Canvas specs live at `canvas/<subsystem>/<module>.clj`. Canvas specs are the sole spec source as of Phase 3. 62 modules are ported across all subsystems.

Legacy `.allium` / `.boundary` files are archived in `.legacy-allium/` (read-only reference; not on the classpath; not loaded by the build pipeline).

## Canvas tree layout

```
canvas/                        Canvas specs — the design surface for fukan-itself
  agent/                       Agent API + query engine specs
  constraint/                  Constraint evaluation specs
  infra/                       Infra subsystem specs (model lifecycle, server lifecycle)
  libs/                        Vendored library specs (allium/parser, boundary/parser, coordinate)
  model/                       Model subsystem specs (pipeline, build, effect, etc.)
  project_layer/               Project layer specs (defaults, registry)
  target/                      Clojure target analyzer specs
  utils/                       Utility module specs
  validation/                  Phase 4 validation specs
  vocabulary/                  Vocabulary analyzer specs (legacy, ported for reference)
  web/                         Web subsystem specs (handler, views)

src/fukan/canvas/              Canvas machinery — the implementation
  core/                        Substrate machinery (primitives, store, helpers, defconstructor, shape, check, defquery)
  construction.clj             Non-opt-out lifts: function, record, value, exports
  vocab/                       Opt-in methodology vocabularies
    behavioral.clj             invariant, rule
    lifecycle.clj              getter
    validation.clj             checker
  projection/
    canvas_source.clj          Projects canvas datoms into the model map (Phase 0)
```

## Canvas vocabulary catalog

**Always available** (`fukan.canvas.construction`):

| Constructor | What it produces |
|-------------|-----------------|
| `function` | Affordance with arrow shape (`takes`/`gives`), role `:fukan.canvas.monolith/exposed-call` |
| `record` | Type with `:record` kind and field pairs |
| `value` | Type with `:atomic` kind (opaque named type) |
| `exports` | Tags named declarations as `:exported` (module API closure) |

**Opt-in** (`fukan.canvas.vocab.*`):

| Namespace | Form | When to use |
|-----------|------|-------------|
| `vocab.behavioral` | `invariant` | Timeless behavioral commitment with a `holds-that` prose clause |
| `vocab.behavioral` | `rule` | Reactive declaration with a `when` trigger signature |
| `vocab.lifecycle` | `getter` | Zero-arg `Optional<T>` accessor; shape is baked in |
| `vocab.validation` | `checker` | `(Model) -> [Violation]` entry point; shape is baked in |

## Shape expression grammar

Shape expressions appear in `takes`/`gives`/`field`/`getter` positions:

| Expression | Meaning |
|-----------|---------|
| `:Keyword` | Atomic type (`:String`, `:Integer`, `:Unit`, etc.) |
| `:ns/Name` | Cross-module reference (`:model/Model`, `:agent/Violation`) |
| `(optional :T)` | Optional value |
| `(list-of :T)` | Ordered list |
| `(set-of :T)` | Unordered set |
| `(sum-of :A :B ...)` | One-of sum type |
| `(map-of :K :V)` | Key-value map |
| `(ref-to :ns/Name)` | Explicit reference form |
| `(record-of [:n :T]+)` | Inline anonymous record |

**Cross-module ref convention:** `:model/Model` refers to an entity named `"Model"` in a module whose path contains `"model"`. The namespace portion is the last path segment of the canvas module that owns the type.

**Short form vs. fully-qualified form.** The resolver accepts both `:cluster/NodeId` (short, last-segment match) and `:distributed.cluster/NodeId` (fully-qualified, exact module-name match). It tries exact match first, then falls back to segment match — so a fully-qualified ref is never accidentally shadowed by a segment collision. Prefer the fully-qualified form when a canvas has modules that share segments (e.g. `accounts.users` vs `users.accounts`); the short form remains the convenient default when segments are unique.

## Three-tier inclusion rules

| Tier | Module | May depend on |
|------|--------|--------------|
| `core/` | Substrate machinery | Nothing inside `canvas/` |
| **construct-kit** (`vocab/registry`, `vocab/construct`) | Vocabulary *machinery*: the tag-definition registry (data) + the registry-driven `construct/build` interpreter | `core/` only |
| `construction.clj` | Non-opt-out lifts | `core/` + construct-kit |
| `vocab/*` | Opt-in vocabularies | `core/` + construct-kit; never `construction.clj`, never each other's methodology |

The **construct-kit** is the one shared dependency `construction.clj` and `vocab/*` may take *beyond* `core/`. It is machinery for *declaring* vocabularies — the registry as data plus the generic interpreter that turns a tag-definition + payload into substrate datoms — not a methodology, so depending on it does not couple one methodology to another (which is what "never each other" protects). The interpreter is kind-agnostic (it reads tag-definitions; it hardcodes no kinds); the registry is the fukan-specific vocabulary data it consumes.

Canvas ports (in `canvas/`) may require any combination of `construction` and `vocab.*` namespaces, plus `core/helpers` and `core/shape` directly when needed.

## Ownership-on-owner principle

Module ownership flows via `:module/child` Relations on the owner, not via back-references on the owned entity. Affordances, States, and Types carry no `:module` field. The `within-module` helper in `core/helpers.clj` emits `:module/child` datoms automatically.

## Canvas conventions

**Name+role disambiguation.** A canvas module may declare multiple entities with the same `:entity/name` provided they have distinct `:affordance/role` values. The canonical example is the rule + invariant pair in `canvas/validation/*` — a single behavioral commitment expressed from two angles: a reactive `(rule "X" ...)` (role `:canvas/rule`) and a timeless `(invariant "X" ...)` (role `:canvas/invariant`). Reference resolution disambiguates such pairs via the `(name, role)` tuple, where role is unambiguous from context (a `when`-trigger position resolves to the rule, a `holds-that` position to the invariant). `canvas-source/build-canvas-db` emits an informational stderr warning for each collision so the author can confirm the distinct roles are intentional; it never throws.

**`^:export` for dynamically-invoked vars.** Vars that are reachable only through dynamic dispatch — the SCI sandbox surface (`fukan.agent.api`, `fukan.agent.system`) or registry-style discovery (every canvas module's `build-canvas`) — should carry `^:export` metadata. Both `.clj-kondo/config.edn` and `.lsp/config.edn` honor `:exclude-when-meta #{:export}`, so the metadata alone is enough; no per-namespace config edit needed. Prefer `^:export` over per-namespace exemption entries whenever the var-level claim is honest.

**Per-namespace lint exemptions live in both `.clj-kondo/config.edn` AND `.lsp/config.edn`.** clojure-lsp doesn't honor clj-kondo's per-namespace config, so namespace-wide `unused-public-var` exemptions must be mirrored in both files. The cases that warrant a namespace-wide exemption rather than `^:export`: trial-run scaffolding (e.g. `fukan.distributed.*`), test fixtures, and the rare namespace where every public var is sandbox surface and decorating each one would be noisier than naming the namespace. When adding a namespace to one file's exemption list, add it to the other.

## REPL workflow

- **After editing a canvas spec**: use `(refresh)` — reloads changed code + rebuilds model (Phase 0–6).
- **After editing handler/routes/infra**: use `(reset)` — full server restart (recreates handler).
- **After adding a new canvas file**: use `(reset)` (or `bin/fukan reset`). Canvas files under `canvas/**/*.clj` are auto-discovered — no registry edit required.
- **After adding a new projection/lens/scenario file** under `src/fukan/canvas/{project,lens,instruct}/`: edit the loader's `:require` list (the per-subsystem loader namespace) AND run `(reset)`. The reset path reloads dynamic-load loaders, so the new defmethod/var registers without a JVM restart.
- **After adding a new agent-api fn** in `src/fukan/agent/api.clj`: run `(reset)`. The reset path reloads `fukan.agent.api` and rebuilds the SCI context so the new fn appears in `bin/fukan eval` calls without a JVM restart.
- **`require :reload` caveat**: REMOVING a var (projection-defmethod, agent-api defn, etc.) requires a full JVM restart (`clj -M:run`). `require :reload` re-evaluates top-level forms but does NOT unmap removed defs — the var lingers in the namespace. Similarly, `defmulti` has defonce semantics — `(remove-method …)` is needed to clear a registration, and `(reset)` doesn't do that. Adding new vars/files is the supported case; removing/renaming is the JVM-restart case.
- **Never** use `remove-ns`, `require :reload`, or `(reload/reload)` directly.
- `(status)` shows server/model state.
- nREPL runs on port 7889, web UI on port 8080.

The REPL cycle for canvas work: **edit canvas file → `(refresh)` → browser refresh**.

## Architecture

- Handler fetches model per-request via `(infra-model/get-model)` — no restart needed for model changes.
- `defonce` atoms in infra survive reloads — `remove-ns` destroys them and orphans servers.
- clj-reload tracks file timestamps; `{:only :loaded}` forces reload regardless.
- fukan analyzes itself: canvas files are both the design surface AND the analysis target.

## Build pipeline

Phase 0: canvas ingestion (`canvas-source/build`) → Phase 4: structural validation → Phase 5: constraint evaluation → Phase 6: Clojure analyzer.

Legacy Allium/Boundary parse phases (1–3) are retired.

## Jujutsu workflow conventions

This repo uses Jujutsu (jj). Always check `jj st` before starting work. If `@` has existing changes, run `jj new` to start clean. Commit per logical change:

```
jj desc -m "type(scope): short description"
jj new
```

Never use git commands directly — jj and git have different object models and mixing them corrupts history.

## Key Files

- `dev/user.clj` — REPL helpers (start/stop/restart/refresh/status)
- `src/fukan/infra/model.clj` — model lifecycle (load/refresh/get)
- `src/fukan/infra/server.clj` — HTTP server lifecycle
- `src/fukan/web/handler.clj` — Ring routes, creates handler at server start
- `src/fukan/model/pipeline.clj` — build pipeline (Phase 0–6)
- `src/fukan/canvas/projection/canvas_source.clj` — canvas ingestion + projection to model map
- `src/fukan/canvas/construction.clj` — non-opt-out canvas lifts
- `src/fukan/canvas/vocab/` — opt-in vocabulary libraries
