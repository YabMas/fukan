# Spec-to-Code Alignment via Per-Impl-File Spec Split — Design Spec

**Status:** Brainstormed, pre-implementation. Ready for plan-out.
**Date:** 2026-05-21
**Audience:** Engineers and agents picking this up cold. Read this before the plan.
**Related:** [VISION.md](../VISION.md), [DESIGN.md](../DESIGN.md), [MODEL.md](../MODEL.md), [2026-05-20-llm-agent-surface-design.md](./2026-05-20-llm-agent-surface-design.md)

---

## 1. What this commits to

Bring Fukan-on-Fukan from **0%** spec-claimed operation coverage to **≥80%** by reorganising the multi-implementation-file `spec.allium`/`spec.boundary` files into per-implementation-file specs that line up 1:1 with the existing convention in `src/fukan/target/clojure/address.clj`.

**No** changes to `address.clj`, the project-layer registry, or any address-resolution mechanism. The fix is purely a content reorganisation in the spec files plus two implementation function renames in `web/views/`.

This is deliberately the **minimum-mechanism** fix. An earlier brainstorming branch considered a project-layer idiom for declaring sibling-scan resolution; that was rejected in favour of restructuring the specs to fit the existing 1:1 convention. Future projects whose layout cannot be restructured this way will motivate the project-layer idiom; Fukan-on-Fukan does not.

---

## 2. Why

`fukan/target/clojure/address.clj` maps Allium module coord `<dir>/<stem>` to Clojure namespace `<dir-with-dots>.<stem>`. Where the spec file's stem matches a `.clj` file, the convention works (`model/pipeline.allium` ↔ `model/pipeline.clj`). Where the stem is a catch-all like `spec` and implementation lives across sibling files, every `:projects` edge lands at a non-existent namespace and resolves as `:absent`.

Today on Fukan-on-Fukan:

```
./bin/fukan eval '(coverage)'
;; => {:total-public-functions 197 :covered 0 :unprojected 197
;;     :expected-not-realised 0 :absent-edge-count 108 ...}
```

All 108 `:projects` edges land `:absent` for one of three reasons:
1. The spec module coord (`fukan/infra/spec`) has no corresponding namespace — operations live in sibling files (`infra/model.clj`, `infra/server.clj`).
2. Operation names in `spec.boundary` don't match the implementation defn names (e.g., `render_app_shell` vs `render-shell`).
3. The spec module coord is conceptual, with no intended Clojure realisation (e.g., `model/spec.allium`'s legacy code-graph types) — out of scope here.

---

## 3. Approach

For each spec pair where multiple implementation files realise distinct operations, **split the spec into per-implementation-file pairs**. For each pair where the implementation function name doesn't match the operation name, **rename the implementation** to match the spec. After the work, every spec-declared operation either:

- Has a `:valid` projects edge pointing at its now-aligned namespace + name, or
- Has a `:absent` projects edge that is legitimately so (the operation is declared in spec but not yet implemented).

The spec language stays authoritative; the implementation realigns to match it where alignment requires only mechanical renames.

---

## 4. Scope

### In scope

| Module | Action |
|---|---|
| `src/fukan/infra/spec.{allium,boundary}` | Split into `model.{allium,boundary}` + `server.{allium,boundary}` |
| `src/fukan/web/spec.{allium,boundary}` | Rename to `handler.{allium,boundary}` |
| `src/fukan/web/views/spec.{allium,boundary}` | Split into `shell` + `graph` + `sidebar` + `cytoscape` + `breadcrumb` `.{allium,boundary}` files (5 new pairs) |
| `src/fukan/web/views/shell.clj` `render-shell` | Rename to `render-app-shell` |
| `src/fukan/web/views/sidebar.clj` `render-entity` | Rename to `render-sidebar-html` |
| `src/fukan/model/pipeline.boundary` | Drop unimplemented ops; align `build_model` to renamed impl |
| `src/fukan/model/pipeline.clj` `load-source` | Rename to `build-model` |
| Call sites in `infra/model.clj`, `web/handler.clj` | Update to renamed names |
| Cross-file comment refs and `use` lines in spec files | Retarget to new file names |
| Corpus test references | Retarget to new file names |

### Out of scope

- `model/spec.allium` — the legacy code-graph types (Node / Edge / Module / Function / Schema / TypeExpr variants) are conceptually being replaced per [VISION.md](../VISION.md). Aligning them is a separate refactor.
- `model/pipeline.allium` content — already aligns by namespace; only the boundary's op names need fixing (covered above).
- `web/views/projection.allium` — deliberate stub for cross-file alias resolution.
- Modules with no spec at all (`agent/`, `projection/`, `project_layer/`, `target/clojure/`) — aligning them requires writing new specs, not realigning existing ones.

### Success criterion

After the work, on Fukan-on-Fukan:

- `(coverage)` reports **≥11 of 12** declared operations with a `:valid` projects edge (≥90% of the operation-realisation metric — well above the 80% target).
- The remaining absent operation (`render_breadcrumb`) is legitimate — no impl exists yet.
- `:projection-kind/test` edges remain mostly absent because the corresponding test files don't exist; these are also legitimate gaps.
- Constraint violations do not increase.
- All existing tests pass, including the touched corpus tests (`test/fukan/libs/allium/parser_test.clj`, `test/fukan/vocabulary/allium/pipeline_test.clj`) and `test/fukan/agent/*`.

---

## 5. Per-module designs

### 5.1 `fukan/infra/` — split

**Before:**
- `infra/spec.allium` — `guarantee` declarations (SnapshotIsolation, SingleModelSource, ModelServerDecoupled, SingleServerInstance) + failure-mode prose for both model and server.
- `infra/spec.boundary` — seven `fn` declarations covering both model and server.

**After:**

**`infra/model.allium`** (new)
- `guarantee SnapshotIsolation`
- `guarantee SingleModelSource`
- `guarantee ModelServerDecoupled` — kept here because the property describes the model side's independence
- Model failure-mode prose: `not_loaded`, `rebuild_failed`

**`infra/model.boundary`** (new)
```
-- boundary: 1

use "../model/spec.allium" as model

fn load_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model
fn get_model() -> Model?
fn refresh_model() -> Model?
fn get_src() -> FilePath?
```

**`infra/server.allium`** (new)
- `value ServerOpts { port: Integer? }` — mirrors the `^:schema` def in `server.clj`
- `value ServerInfo { port: Integer }` — same
- `guarantee SingleServerInstance`
- Server failure-mode prose: `port_in_use`, `not_running`

**`infra/server.boundary`** (new)
```
-- boundary: 1

fn start_server(opts: ServerOpts) -> ServerInfo?
fn stop_server() -> Unit
fn get_port() -> Integer?

exports:
    ServerOpts
    ServerInfo
```

**Resolution after split:**
- `fukan/infra/model :: load_model` → `fukan.infra.model/load-model` ✓
- `fukan/infra/server :: start_server` → `fukan.infra.server/start-server` ✓
- `fukan/infra/server :: ServerOpts` → `fukan.infra.server/ServerOpts` ✓
- (and so on for the 7 ops + 2 schemas)

### 5.2 `fukan/web/` — rename only

**Before:** `web/spec.allium` (guarantees + ViewTransport surface) + `web/spec.boundary` (one `fn create_handler`).

**After:**
- `web/handler.allium` — full content preserved.
- `web/handler.boundary` — full content preserved.

**Resolution:**
- `fukan/web/handler :: create_handler` → `fukan.web.handler/create-handler` ✓
- `fukan/web/handler :: create_handler` test projection → `fukan.web.handler-test/create-handler-test` ✓ (test file already exists at `test/fukan/web/handler_test.clj`)

`agent_handlers.clj` and `sse.clj` are handler collaborators — their publics remain unprojected, which is correct. They are not the module's wall-crossing API.

### 5.3 `fukan/web/views/` — split

**Before:** 459-line `views/spec.allium` (5 value types + GraphViewer surface + 8 interaction rules + 8 rendering invariants + visual-indicator prose) + `views/spec.boundary` (4 render ops + 5-item `exports:`).

**After (5 new spec pairs):**

| New file | Boundary | Allium content (distributed from the old `spec.allium`) |
|---|---|---|
| `shell.{allium,boundary}` | `fn render_app_shell() -> Html` | (minimal — no rules/invariants are shell-specific) |
| `graph.{allium,boundary}` | `fn render_graph(projection, editor_state) -> CytoscapeGraph`; `exports: ViewState, NavigationState` | `value ViewState`, `value NavigationState`; `surface GraphViewer` (full block); `given { nav, viewer }`; all 8 rules (SelectNode, NavigateToNode, NavigateToAncestor, ExpandToggle, TogglePrivateVisibility, SelectEdgeMode, SelectEdge, Deselect); invariants `RenderingPurity`, `AtomicUpdate`, `GraphSelectionDefault`; visual-indicator prose |
| `sidebar.{allium,boundary}` | `fn render_sidebar_html(detail: EntityDetails) -> Html` | invariants `SidebarSectionOrder`, `EdgeRendererSections`, `SidebarEmptyState`, `ClickableSchemaRefs` |
| `cytoscape.allium` (no boundary — open) | — | `value CytoscapeGraph`, `value CytoscapeNode`, `value CytoscapeEdge` |
| `breadcrumb.{allium,boundary}` | `fn render_breadcrumb(path: EntityPath) -> Html` | invariants `BreadcrumbShortLabels`, `BreadcrumbCurrentItem`, `BreadcrumbClickableItems` |

**Placement rationale:**
- Rules go with the surface that emits them. The GraphViewer surface is the source of all 8 interaction events; the rules consume them. They co-locate.
- `RenderingPurity` and `AtomicUpdate` are graph-scoped because graph-render is where the multi-component SSE delivery happens.
- Per-component invariants (sidebar, breadcrumb) go with their component file.
- `CytoscapeGraph`/`CytoscapeNode`/`CytoscapeEdge` are pure data shapes — they get their own minimal `cytoscape.allium` with no boundary (open by default, all top-level decls externally visible — preserves the current import sites).

**Impl renames** (in `src/fukan/web/views/`):
- `shell.clj`: `render-shell` → `render-app-shell`
- `sidebar.clj`: `render-entity` → `render-sidebar-html`

**Resolution after split:**
- `fukan/web/views/shell :: render_app_shell` → `fukan.web.views.shell/render-app-shell` ✓
- `fukan/web/views/graph :: render_graph` → `fukan.web.views.graph/render-graph` ✓ (no impl rename needed)
- `fukan/web/views/sidebar :: render_sidebar_html` → `fukan.web.views.sidebar/render-sidebar-html` ✓
- `fukan/web/views/breadcrumb :: render_breadcrumb` → `fukan.web.views.breadcrumb/render-breadcrumb` — **legitimately absent** (no impl yet).

`cytoscape.clj` has one public `graph->cytoscape` that is not currently spec'd. It stays unprojected — it's an internal transform used by `render-graph`, not module API.

### 5.4 `fukan/model/pipeline/` — op-name fix

**Before:**
- `pipeline.boundary` — `fn build_model(...)`, `fn check_contracts(...)`, `fn format_report(...)`
- `pipeline.clj` — only `load-source` exists; the other two ops have no impl.

**After:**
- `pipeline.boundary` declares only `fn build_model(src, analyzers) -> Model` (drop the two unimpl'd ops).
- `pipeline.clj` renames `load-source` → `build-model`.
- `infra/model.clj` caller updates `pipeline/load-source` → `pipeline/build-model`.

**Resolution:** `fukan/model/pipeline :: build_model` → `fukan.model.pipeline/build-model` ✓.

If `check_contracts` and `format_report` become genuine module API later (a Phase 5 surface), they re-land in `pipeline.boundary` and earn impl then.

---

## 6. Cross-reference and corpus test updates

### Spec-file cross-references

Comment references inside spec files that mention renamed paths need updating. The grep:

```
src/fukan/infra/spec.allium:2:           -- infra/spec.allium        → header in new file
src/fukan/model/pipeline.allium:11:      see infra/spec.allium       → see infra/model.allium
src/fukan/model/spec.allium:11:          see infra/spec.allium       → see infra/model.allium
src/fukan/infra/spec.allium:11:          see web/spec.allium         → see web/handler.allium
src/fukan/web/views/projection.allium:11: web/views/spec.allium      → web/views/graph.allium (primary referencer)
```

No live `use "..."` statements currently import any of the files being renamed or split (the existing `use "../model/spec.allium"` references target the kept-as-is `model/spec.allium`). Inside the **new** split files, add the `use` lines they need for their own type references (e.g., `web/views/graph.boundary` needs `use "./cytoscape.allium" as cyto` to reference `CytoscapeGraph`).

### Corpus test references

- `test/fukan/libs/allium/parser_test.clj` — six references to `src/fukan/infra/spec.allium`, `src/fukan/web/spec.allium`, `src/fukan/web/views/spec.allium`. Retarget each to the new file that contains the content the test exercises:
  - Annotation prose test at line 805 → `src/fukan/web/views/graph.allium` (GraphViewer annotations land here)
  - Multi-file corpus assertions → choose one representative new file per renamed source.
- `test/fukan/vocabulary/allium/pipeline_test.clj:24` — module-id assertion expecting `"fukan/infra/spec"`. Replace with `"fukan/infra/model"` (or with the full set of new ids).

### Implementation call-sites

After renames, update:
- `web/handler.clj` — find callers of `render-shell`, `render-entity` (likely in `handle-graph`, `handle-sidebar` private handlers) and update to `render-app-shell`, `render-sidebar-html`.
- `infra/model.clj` — update `pipeline/load-source` → `pipeline/build-model`.
- (No production code calls `pipeline/check-contracts` or `pipeline/format-report` because they don't exist; nothing to update.)

---

## 7. Execution order

Keeping the model bootable throughout so `(coverage)` can be probed incrementally:

1. **Infra split** — create the four new spec files, delete the two old ones, retarget callers and corpus tests. Lowest blast radius.
2. **Web rename** — single rename, no impl changes.
3. **Views split + impl renames** — five new spec pairs, two impl renames in `web/views/`, two call-site updates in `web/handler.clj`. The big chunk.
4. **Pipeline op-name fix** — rename `load-source` → `build-model`, drop the two unimpl'd boundary ops, update the `infra/model.clj` caller.
5. **Final coverage probe** — `clj -M:run --src src`, then `./bin/fukan eval '(coverage)'` to confirm the 80%+ figure on the spec-claimed-operations metric.

Run `clj -M:test` after each step (or at least after 1, 3, 4) so any breakage is attributable to a single change.

---

## 8. Risks

- **`.boundary` sibling rule.** DESIGN.md requires module-bound `.boundary` to be sibling to a `.allium` at the same coordinate. Each new pair satisfies this. `cytoscape.allium` has no sibling `.boundary`; it's "open" per DESIGN.md §4d.
- **Surface placement is a judgment call.** The GraphViewer surface lives in `graph.allium` because `render_graph` produces what GraphViewer consumes. Could argue for `cytoscape.allium` (Cytoscape-shaped data is what GraphViewer accepts). Going with `graph.allium`; revisit if it feels off in practice.
- **Tests for ops still mostly absent.** Test projections (`<ns>-test/<name>-test`) only become valid where matching test files exist. Today, only `test/fukan/web/handler_test.clj` matches. The remaining test projections stay absent — these are real test gaps, surfaced by the alignment. Not a regression.
- **Rule/invariant projections to fn impls.** The analyzer emits one fn-shaped projection per Rule and per labeled Invariant. After alignment, those still land absent because rules/invariants don't have 1:1 Clojure defn implementations in this codebase. They're not in the "operation-realisation" metric; they remain in the larger absent set and that's expected.

---

*See [DESIGN.md](../DESIGN.md) for project-layer mechanics, the address-resolution convention this design relies on, and the broader spec language responsibilities.*
