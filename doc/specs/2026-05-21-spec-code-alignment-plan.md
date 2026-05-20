# Spec-to-Code Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Fukan-on-Fukan from 0% spec-claimed operation coverage to ≥80% by reorganising three multi-impl-file spec pairs into per-impl-file specs that line up 1:1 with the existing convention in `src/fukan/target/clojure/address.clj`. Plus two impl renames and a `model/pipeline.boundary` op-name fix.

**Architecture:** Pure content reorganisation. No changes to `address.clj`, the project-layer registry, or any address-resolution mechanism. Spec files are restructured to fit the existing `<dir>/<stem>.allium → <dir-with-dots>.<stem>` convention.

**Tech Stack:** Clojure, Allium/Boundary spec languages, Jujutsu VCS, clj-test, the existing Fukan agent surface (`./bin/fukan eval`) for coverage probing.

**Spec:** [2026-05-21-spec-code-alignment-design.md](./2026-05-21-spec-code-alignment-design.md)

**VCS note:** This repo uses Jujutsu (`.jj/` exists). Before running any VCS commands, load the `jujutsu` skill — git commands will corrupt data.

---

## Pre-flight

### Task 0: Establish baseline

**Files:** none — verification only.

- [ ] **Step 1: Confirm jj is in expected state**

Run: `jj st`

Expected: working copy shows the spec + plan files just added; otherwise the parent stack from the conversation context.

- [ ] **Step 2: Confirm tests are green at baseline**

Run: `clj -M:test 2>&1 | tail -20`

Expected: all tests pass. If anything red, stop and investigate — alignment work must start from green.

- [ ] **Step 3: Confirm baseline coverage**

Start the daemon in a separate terminal if not running:
```bash
clj -M:run --src src
```

Then in this terminal:
```bash
./bin/fukan eval '(coverage)'
```

Expected: `{:total-public-functions 197 :covered 0 :unprojected 197 :expected-not-realised 0 :absent-edge-count 108 ...}` (numbers approximate). Record `:absent-edge-count` — it should drop substantially by the end.

- [ ] **Step 4: Start a fresh changeset**

Per project CLAUDE.md commit-driven workflow:
```bash
jj new
jj desc -m "refactor(spec): align infra spec with per-impl-file convention"
```

---

## Task 1: Split `infra/spec.*` into `infra/model.*` + `infra/server.*`

**Files:**
- Create: `src/fukan/infra/model.allium`
- Create: `src/fukan/infra/model.boundary`
- Create: `src/fukan/infra/server.allium`
- Create: `src/fukan/infra/server.boundary`
- Delete: `src/fukan/infra/spec.allium`
- Delete: `src/fukan/infra/spec.boundary`
- Modify: `src/fukan/model/pipeline.allium:11` (comment ref)
- Modify: `src/fukan/model/spec.allium:11` (comment ref)
- Modify: `test/fukan/libs/allium/parser_test.clj:826` (corpus list)
- Modify: `test/fukan/vocabulary/allium/pipeline_test.clj:24` (module-id set)

- [ ] **Step 1: Write `src/fukan/infra/model.allium`**

```
-- allium: 2
-- infra/model.allium
--
-- Scope: Model lifecycle — load, refresh, snapshot management
-- Includes: SnapshotIsolation, SingleModelSource, ModelServerDecoupled
--   guarantees; model-lifecycle failure modes
-- Excludes:
--   - Server lifecycle (see infra/server.allium)
--   - The Model itself (see model/spec.allium)
--   - Build pipeline (see model/pipeline.allium)
--   - Public API signatures (see model.boundary)

use "../model/spec.allium" as model

------------------------------------------------------------
-- Guarantees
------------------------------------------------------------

-- Consumers read an immutable Model. A rebuild produces a new
-- Model value; in-flight requests see the previous snapshot
-- until the next dereference.
guarantee SnapshotIsolation

-- At most one source path is active. Rebuild replaces the
-- entire model atomically.
guarantee SingleModelSource

-- Server and model lifecycles are independent. Model refreshes
-- do not require server restart. The server calls get_model
-- per-request from its side; this module never touches the server.
guarantee ModelServerDecoupled

------------------------------------------------------------
-- Failure Modes
------------------------------------------------------------
--
-- Model lifecycle failures:
--   not_loaded          -- model requested before first build
--   rebuild_failed      -- source analysis failed during rebuild
```

- [ ] **Step 2: Write `src/fukan/infra/model.boundary`**

```
-- boundary: 1

-- Model lifecycle — load, refresh, snapshot accessors.

use "../model/spec.allium" as model

fn load_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model
  -- Load and build the model from source.

fn get_model() -> Model?
  -- Return the current model snapshot.

fn refresh_model() -> Model?
  -- Rebuild the model from source.

fn get_src() -> FilePath?
  -- Return the configured source path.
```

- [ ] **Step 3: Write `src/fukan/infra/server.allium`**

```
-- allium: 2
-- infra/server.allium
--
-- Scope: HTTP server lifecycle — start, stop, port accessors
-- Includes: SingleServerInstance guarantee; server-lifecycle failure
--   modes; ServerOpts / ServerInfo value types
-- Excludes:
--   - Model lifecycle (see infra/model.allium)
--   - HTTP routing and handlers (see web/handler.allium)
--   - Public API signatures (see server.boundary)

------------------------------------------------------------
-- Value Types
------------------------------------------------------------

value ServerOpts {
    -- HTTP server configuration.
    port: Integer?
}

value ServerInfo {
    -- Running server information.
    port: Integer
}

------------------------------------------------------------
-- Guarantees
------------------------------------------------------------

-- At most one server runs at a time.
guarantee SingleServerInstance

------------------------------------------------------------
-- Failure Modes
------------------------------------------------------------
--
-- Server lifecycle failures:
--   port_in_use         -- requested port already bound
--   not_running         -- stop called while no server active (no-op)
```

- [ ] **Step 4: Write `src/fukan/infra/server.boundary`**

```
-- boundary: 1

-- HTTP server lifecycle.

fn start_server(opts: ServerOpts) -> ServerInfo?
  -- Start the HTTP server on a given port.

fn stop_server() -> Unit
  -- Stop the running HTTP server.

fn get_port() -> Integer?
  -- Return the port the server is listening on.

exports:
    ServerOpts
    ServerInfo
```

- [ ] **Step 5: Delete the old spec pair**

Run:
```bash
rm src/fukan/infra/spec.allium src/fukan/infra/spec.boundary
```

- [ ] **Step 6: Update comment ref in `src/fukan/model/pipeline.allium` line 11**

Find: `--   - Model state management (see infra/spec.allium)`
Replace with: `--   - Model state management (see infra/model.allium)`

- [ ] **Step 7: Update comment ref in `src/fukan/model/spec.allium` line 11**

Find: `--   - Infrastructure lifecycle (see infra/spec.allium)`
Replace with: `--   - Infrastructure lifecycle (see infra/model.allium and infra/server.allium)`

- [ ] **Step 8: Update corpus list in `test/fukan/libs/allium/parser_test.clj` around line 826**

Find:
```clojure
  ["src/fukan/infra/spec.allium"
   "src/fukan/web/spec.allium"
   "src/fukan/web/views/spec.allium"
```

Replace with:
```clojure
  ["src/fukan/infra/model.allium"
   "src/fukan/infra/server.allium"
   "src/fukan/web/spec.allium"
   "src/fukan/web/views/spec.allium"
```

(The `web/*` paths are renamed in later tasks; updating them now would break tests prematurely.)

- [ ] **Step 9: Update `test/fukan/vocabulary/allium/pipeline_test.clj` `pipeline-loads-fukan-corpus` test**

The deftest at line 8 has three things to update:

1. Line 9 docstring: `"loading src/ produces a validated Model covering all 6 fukan modules"` — change `6` to `7`.
2. Lines 20–21 comment: `;; The fukan corpus has 6 .allium files: infra, web, web/views, ;; web/views/projection (Plan 3a stub), model, model/pipeline.` — change `6` to `7` and replace `infra` in the list with `infra/model, infra/server`.
3. Line 22 assertion: `(is (= 6 (count module-tag-apps)))` — change `6` to `7`.
4. Lines 24–30 set assertion: in the expected `#{...}`, replace the literal `"fukan/infra/spec"` with the two new module ids `"fukan/infra/model"` and `"fukan/infra/server"`. Final set:

```clojure
#{"fukan/infra/model"
  "fukan/infra/server"
  "fukan/web/spec"
  "fukan/web/views/spec"
  "fukan/web/views/projection"
  "fukan/model/spec"
  "fukan/model/pipeline"}
```

- [ ] **Step 10: Run tests**

Run: `clj -M:test 2>&1 | tail -40`

Expected: all tests pass. If pipeline_test or parser_test fails because of how the module-id set is asserted (e.g., uses `=` not `subset?`), inspect the exact assertion and fix to include the new ids. Do not bypass — the test exists to detect exactly this kind of corpus drift.

- [ ] **Step 11: Probe coverage**

Restart the daemon (`(reset)` in the REPL, or `Ctrl+C` and rerun `clj -M:run --src src`), then:

```bash
./bin/fukan eval '(coverage)'
```

Expected: `:covered` jumps from 0 to ~7 (load-model, get-model, refresh-model, get-src, start-server, stop-server, get-port). `:absent-edge-count` drops correspondingly.

- [ ] **Step 12: Commit**

```bash
jj st
jj desc -m "refactor(spec): split infra/spec into infra/{model,server} for impl alignment

Previously infra/spec.allium + infra/spec.boundary declared seven
operations whose implementations are split across infra/model.clj and
infra/server.clj. The 1:1 address convention expected ops at
fukan.infra.spec/* — a nonexistent namespace — so every projects edge
landed :absent.

Split into infra/model.{allium,boundary} (load_model, get_model,
refresh_model, get_src) and infra/server.{allium,boundary}
(start_server, stop_server, get_port) so the existing convention
resolves to fukan.infra.model and fukan.infra.server respectively.

ServerOpts/ServerInfo lift from impl-only ^:schema defs to declared
value types in server.allium for schema-projection alignment.

Corpus tests retargeted; module-id assertion in vocabulary/allium/
pipeline_test updated to the two new module ids."
jj new
jj desc -m "refactor(spec): rename web/spec to web/handler for impl alignment"
```

---

## Task 2: Rename `web/spec.*` → `web/handler.*`

**Files:**
- Rename: `src/fukan/web/spec.allium` → `src/fukan/web/handler.allium`
- Rename: `src/fukan/web/spec.boundary` → `src/fukan/web/handler.boundary`
- Modify: `src/fukan/infra/spec.allium` reference — already deleted in Task 1, so check `src/fukan/infra/model.allium` and `src/fukan/infra/server.allium` for any `web/spec.allium` mentions (none expected).
- Modify: `test/fukan/libs/allium/parser_test.clj:827` (corpus list)

- [ ] **Step 1: Move the files**

```bash
mv src/fukan/web/spec.allium src/fukan/web/handler.allium
mv src/fukan/web/spec.boundary src/fukan/web/handler.boundary
```

- [ ] **Step 2: Update the file's self-referential header comment**

Open `src/fukan/web/handler.allium`. Near the top there's a comment `-- web.allium`. Change to `-- web/handler.allium`.

- [ ] **Step 3: Update corpus list in `test/fukan/libs/allium/parser_test.clj` around line 827**

Find: `"src/fukan/web/spec.allium"`
Replace with: `"src/fukan/web/handler.allium"`

- [ ] **Step 3a: Update `test/fukan/vocabulary/allium/pipeline_test.clj` set assertion**

In the `pipeline-loads-fukan-corpus` deftest, replace `"fukan/web/spec"` with `"fukan/web/handler"` in the expected set (around line 25). Count stays at 7 (Task 1 already bumped it from 6 → 7).

- [ ] **Step 4: Confirm no other refs to `web/spec.allium`**

Run: `grep -rn "web/spec\.allium\|web.spec" src/ test/`

Expected: only matches in the new `web/handler.allium` if it still contains its old self-comment (fix per Step 2). Otherwise empty.

- [ ] **Step 5: Run tests**

Run: `clj -M:test 2>&1 | tail -20`

Expected: all tests pass.

- [ ] **Step 6: Probe coverage**

```bash
./bin/fukan eval '(coverage)'
```

Expected: `:covered` jumps by 1 more (create-handler). Total ~8.

- [ ] **Step 7: Commit**

```bash
jj st
jj desc -m "refactor(spec): rename web/spec to web/handler for impl alignment

web/spec.allium + web/spec.boundary declared a single create_handler
operation whose implementation lives in web/handler.clj. Renaming the
spec stem from 'spec' to 'handler' lets the 1:1 address convention
resolve to fukan.web.handler/create-handler.

ViewTransport surface and PureDelegation/PerRequestModel guarantees
travel unchanged into the renamed file."
jj new
jj desc -m "refactor(spec+impl): split web/views/spec, rename render fns"
```

---

## Task 3: Split `web/views/spec.*` into 5 spec pairs

**Files:**
- Create: `src/fukan/web/views/shell.allium`
- Create: `src/fukan/web/views/shell.boundary`
- Create: `src/fukan/web/views/graph.allium`
- Create: `src/fukan/web/views/graph.boundary`
- Create: `src/fukan/web/views/sidebar.allium`
- Create: `src/fukan/web/views/sidebar.boundary`
- Create: `src/fukan/web/views/cytoscape.allium`
- Create: `src/fukan/web/views/breadcrumb.allium`
- Create: `src/fukan/web/views/breadcrumb.boundary`
- Delete: `src/fukan/web/views/spec.allium`
- Delete: `src/fukan/web/views/spec.boundary`
- Modify: `src/fukan/web/views/projection.allium:11` (comment ref)

This is the biggest task. The 459-line `views/spec.allium` decomposes per the design's §5.3 placement table. Per-file content blocks follow.

- [ ] **Step 1: Write `src/fukan/web/views/shell.allium`**

```
-- allium: 2
-- web/views/shell.allium
--
-- Scope: HTML shell rendering — the initial page skeleton
-- Includes: render_app_shell signature (in shell.boundary)
-- Excludes:
--   - Graph rendering (see graph.allium)
--   - Sidebar rendering (see sidebar.allium)
--   - Breadcrumb rendering (see breadcrumb.allium)
--   - Cytoscape value types (see cytoscape.allium)
```

(Minimal — no rules or invariants are shell-specific.)

- [ ] **Step 2: Write `src/fukan/web/views/shell.boundary`**

```
-- boundary: 1

-- HTML shell — renders the initial page skeleton.

fn render_app_shell() -> Html
  -- Render the initial HTML shell for the application.
```

- [ ] **Step 3: Write `src/fukan/web/views/cytoscape.allium`**

```
-- allium: 2
-- web/views/cytoscape.allium
--
-- Scope: Cytoscape.js output value types — the wire shape
--   the frontend graph library consumes
-- Includes: CytoscapeGraph, CytoscapeNode, CytoscapeEdge value types
-- Excludes:
--   - Graph rendering (see graph.allium)
--
-- No boundary file: module is open by default. All three value types
-- are externally visible (CytoscapeGraph is the render_graph return
-- type; CytoscapeNode and CytoscapeEdge are its constituents).

value CytoscapeGraph {
    -- Output payload for the Cytoscape.js frontend.
    -- Domain data transformed to Cytoscape conventions:
    --   from/to → source/target, kebab-case → camelCase.
    nodes: List<CytoscapeNode>
    edges: List<CytoscapeEdge>
    selected_id: String?
    highlighted_edges: List<String>
}

value CytoscapeNode {
    id: String
    kind: String           -- "module", "function", "schema"
    label: String
    parent: String?
    selected: Boolean
    expandable: Boolean
    has_private_children: Boolean
    is_expanded: Boolean
    showing_private: Boolean
    child_count: Integer
    private: Boolean?
    schema_key: String?    -- present for schema nodes
}

value CytoscapeEdge {
    id: String
    source: String
    target: String
    edge_type: String      -- "code-flow" or "schema-reference"
    kind: String           -- "function-call", "dispatches", or "schema-reference"
}
```

- [ ] **Step 4: Write `src/fukan/web/views/graph.allium`**

This is the big one — it absorbs the GraphViewer surface, the 8 interaction rules, the three graph-scoped invariants, the value types (ViewState, NavigationState), the `given { }` block, and the visual-indicator prose.

```
-- allium: 2
-- web/views/graph.allium
--
-- Scope: Graph view — rendering, GraphViewer component, interaction model
-- Includes: GraphViewer surface; ViewState / NavigationState value types;
--   8 interaction rules; RenderingPurity / AtomicUpdate /
--   GraphSelectionDefault invariants; visual-indicator prose
-- Excludes:
--   - Cytoscape data shapes (see cytoscape.allium)
--   - Sidebar rendering (see sidebar.allium)
--   - Breadcrumb rendering (see breadcrumb.allium)
--   - Projection computation (see projection.allium)

use "./projection.allium" as projection
use "./cytoscape.allium" as cyto

------------------------------------------------------------
-- Value Types
------------------------------------------------------------

value ViewState {
    -- Client-owned graph state. Lives inside the GraphViewer
    -- component and is included in every outbound event so the
    -- server can use it for projection computation.
    --
    -- The server never writes these values back — the component
    -- is the sole owner. The server reads them from request
    -- parameters, uses them for projection, and returns the
    -- projected graph data.
    expanded: Set<String>             -- modules whose children are visible
    show_private: Set<String>         -- expanded modules that also show private children
    visible_edge_types: Set<String>   -- active edge mode (single-select, defaults to {code_flow})
}

value NavigationState {
    -- Server-owned navigation state. Determines what entity is
    -- being viewed and what is selected. Updated by SSE responses.
    view_id: String?                  -- module being viewed (null = root)
    selected_id: String?              -- currently highlighted node
}

------------------------------------------------------------
-- GraphViewer Component
------------------------------------------------------------
--
-- The GraphViewer is a web component that wraps the Cytoscape.js
-- graph library. It formalizes the boundary between server-driven
-- state (graph data, selection) and imperative graph rendering.
--
-- Architecture: "data in, events out" (Datastar pattern).
--
-- CurrentProblem: without this boundary, graph updates are
--   delivered via execute-script (injecting JSON into a <script>
--   tag). This is brittle: malformed JSON fails silently,
--   string interpolation breaks on special characters, and the
--   graph library lives outside the framework's reactive model.
--
-- With the component boundary:
--   - Graph data flows in as a signal → observed attribute
--   - User interactions flow out as CustomEvents
--   - View state is encapsulated, not scattered across globals
--   - Failures are explicit (JSON.parse errors are catchable)

surface GraphViewer {
    facing viewer: User

    context graph_data: cyto/CytoscapeGraph

    -- Data in: the component renders whatever CytoscapeGraph it
    -- receives. The server pushes graph data via a Datastar signal;
    -- the component reacts to attribute changes.
    exposes:
        graph_data.nodes
        graph_data.edges
        graph_data.selected_id
        graph_data.highlighted_edges

    -- Events out: each user interaction is emitted as a structured
    -- CustomEvent. Every event includes the current ViewState so
    -- the orchestration layer can forward it to the server.
    provides:
        SelectNode(node_id: String)
            -- Single click on a node. Updates selection locally
            -- and emits event for sidebar update.

        NavigateToNode(node_id: String)
            when graph_data.nodes.find(n => n.id = node_id).expandable
            -- Double click on an expandable module.
            -- Resets expanded and show_private to empty.

        ExpandToggle(module_id: String)
            when graph_data.nodes.find(n => n.id = module_id).kind = "module"
            -- Right click on a module. Toggles expanded state.
            -- Component updates its own ViewState before emitting.
            -- Server re-projects with the updated expanded set.

        TogglePrivateVisibility(module_id: String)
            when module_id in view_state.expanded
            -- Shift+right click on an expanded module.
            -- Toggles show_private for that module.
            -- Server re-projects with the updated show_private set.

        SelectEdgeMode(edge_type: String)
            -- Edge mode selector click. Single-select: switches to the
            -- clicked mode, replacing the previous one.
            -- Updates visible_edge_types in ViewState to contain only
            -- the selected type. Server re-projects with the filtered
            -- node set.

        SelectEdge(source: String, target: String, edge_type: String)
            -- Single click on an edge. Computes edge ID from
            -- source, target, and type for sidebar lookup.

        Deselect()
            -- Click on background. Clears selection.

    @guarantee DataInEventsOut
        -- The component never initiates network requests.
        -- All data arrives through the graph-data attribute.
        -- All interactions are emitted as CustomEvents.
        -- The parent context (Datastar data-on handlers) translates
        -- events into SSE requests and SSE responses into signal
        -- updates. This separation means the component works
        -- identically regardless of transport mechanism.

    @guarantee ViewStateOwnership
        -- The component owns expanded, show_private, and
        -- visible_edge_types. It reads initial values from
        -- the graph data (nodes carry isExpanded, showingPrivate
        -- flags) and maintains them across interactions.
        -- Every outbound event includes the current ViewState
        -- via evt.detail.viewState so the server can compute
        -- the correct projection.
        -- The server never echoes view state back as a separate
        -- concern — it's embedded in the graph data structurally.

    @guarantee RenderModeDetection
        -- The component determines render mode internally by
        -- comparing incoming graph data to current state:
        --   structural change (different node set) → full rebuild
        --   same structure (selection/highlight only) → incremental
        -- For structural changes, the component further distinguishes:
        --   root node changed → navigate (layout from scratch)
        --   root unchanged → expand/collapse (animated transition)
        -- Callers do not control render mode.

    @guarantee AnimatedTransition
        -- Expand/collapse uses incremental diff rendering
        -- (old positions → dagre → animate to new positions) for
        -- spatial continuity.

    @guarantee NodeVisibility
        -- The active edge mode controls which leaf node kinds are
        -- visible in the graph:
        --   code_flow:       functions visible, schemas hidden
        --   schema_reference: schemas visible, functions hidden
        -- Module nodes are always visible regardless of mode.
        -- The sidebar is unaffected — it shows full details for any
        -- selected entity regardless of the active mode.
}

------------------------------------------------------------
-- Given
------------------------------------------------------------

given {
    nav: NavigationState
    viewer: GraphViewer
}

------------------------------------------------------------
-- Interaction Rules
------------------------------------------------------------
--
-- The UI distinguishes selection (highlighting within current view)
-- from navigation (changing the viewed module).
--
-- UnifiedNavigation: every clickable element in the app — sidebar
--   links, breadcrumb items, schema refs, defined-in links — uses
--   the same navigation path: an SSE request that returns graph
--   data + breadcrumb + sidebar. The GraphViewer component receives
--   the graph data via signal; breadcrumb and sidebar are patched
--   as HTML. This eliminates the class of bugs where one piece
--   updates but another doesn't.

rule SelectNode {
    -- Single click on graph node or sidebar link: select a node.
    --
    -- GraphClick: graph component emits SelectNode event, which
    --   triggers a sidebar-only SSE request (no graph rebuild).
    --
    -- SidebarClick: sidebar links trigger a full navigation SSE
    --   request with select parameter, updating graph + breadcrumb
    --   + sidebar atomically.
    --
    -- SelectionDefault: when no explicit selection exists, the
    --   effective selected node defaults to view_id, then to the
    --   first root node in the projection.
    --
    -- SchemaKeyHighlighting: when the selected node is a schema
    --   node, edges are highlighted by matching schema-key rather
    --   than by from/to endpoint.
    --
    -- RegularHighlighting: for non-schema nodes, all edges where
    --   the node is either source or target are highlighted.

    when: viewer.SelectNode(node_id)

    let effective_selected = node_id
        ?? nav.view_id
        ?? projection.nodes.find(n => n.parent = null).id

    ensures:
        nav.selected_id = effective_selected
        -- Sidebar shows details for the selected entity
        -- Edges involving the selection are highlighted
}

rule NavigateToNode {
    -- Double click on graph or breadcrumb click: navigate into a module.
    --
    -- OnlyExpandable: navigation only occurs if the node has children.
    --   Double-clicking a leaf is treated as a single click (selection only).
    --
    -- ClearsSelection: after navigation, no node is selected until the
    --   user clicks one. The new module becomes the viewed entity.
    --
    -- ResetsViewState: navigation resets the component's expanded and
    --   show_private sets to empty. Each view starts fresh.

    when: viewer.NavigateToNode(node_id)

    ensures:
        nav.view_id = node_id
        nav.selected_id = null
        -- GraphViewer internally resets expanded and show_private
}

rule NavigateToAncestor {
    -- Breadcrumb click: navigate to an ancestor module.
    -- The breadcrumb shows the path from smart-root to the viewed module.
    -- Clicking any non-current item navigates there.

    when: NavigateToAncestor(ancestor_id: String?)

    ensures:
        nav.view_id = ancestor_id
        nav.selected_id = null
}

rule ExpandToggle {
    -- Right click on a module: toggle expanded state in ViewState.
    -- Component updates its own state before emitting; server re-projects
    -- with the updated expanded set.

    when: viewer.ExpandToggle(module_id)

    ensures:
        -- Component's expanded set is toggled
        -- Server re-projects with updated expanded set
}

rule TogglePrivateVisibility {
    -- Shift+right click on an expanded module: toggle show_private state.
    -- Server re-projects with the updated show_private set.

    when: viewer.TogglePrivateVisibility(module_id)

    ensures:
        -- Component's show_private set is toggled for that module
        -- Server re-projects with updated show_private set
}

rule SelectEdgeMode {
    -- Edge mode selector click: change the visible edge type filter.
    -- Single-select: switches to the clicked mode.

    when: viewer.SelectEdgeMode(edge_type)

    ensures:
        -- Component updates visible_edge_types to {edge_type}
        -- Server re-projects with the filtered node set
}

rule SelectEdge {
    -- Click on an edge: select it for sidebar inspection.
    -- Component computes edge ID from source, target, and type.

    when: viewer.SelectEdge(source, target, edge_type)

    ensures:
        -- Sidebar shows edge details
        -- Edge is highlighted
}

rule Deselect {
    -- Click on background: clear the current selection.

    when: viewer.Deselect()

    ensures:
        nav.selected_id = null
        -- Sidebar returns to empty state
        -- No edges are highlighted
}

------------------------------------------------------------
-- Rendering Invariants
------------------------------------------------------------

invariant RenderingPurity {
    -- Views receive pre-computed projection data and editor state,
    -- produce HTML/JSON output, and never fetch data or compute
    -- projections.
}

invariant AtomicUpdate {
    -- Each SSE response delivers breadcrumb HTML, sidebar HTML, and
    -- graph data signal as a single stream. All three update
    -- together — no partial states.
}

invariant GraphSelectionDefault {
    -- When render_graph receives no explicit selected_id, the
    -- effective selection falls back first to nav.view_id, then to
    -- the first root node in the projection. Selected node flag is
    -- propagated per-node; highlighted_edges is computed from the
    -- effective selection.
}

------------------------------------------------------------
-- Visual Indicators
------------------------------------------------------------
--
-- ExpandIndicator: small icon in the top-right corner of expandable
--   module nodes. Signals current expand state:
--
--   Collapsed:                        ▶  (right-pointing)
--   Expanded (private hidden):        ▼  (down-pointing)
--   Expanded (no hidden private):     ▼  (down-pointing)
--
--   The icon is the only visual differentiator between expand states.
--   Module shape, color, and size remain constant across states.
--   Only shown on expandable module nodes (child_count > 0).
--
-- ExpandedWithHiddenPrivates: an expanded module that has private
--   children currently hidden (in expanded but not in show_private).
--   DoubleBorder indicator.
--
-- FullyExpanded: an expanded module where all children are visible
--   (in both expanded and show_private, or has no private children).
--   SolidBorder indicator.
--
-- DashedBorderGrayBackground: a private leaf node that is currently
--   visible (its parent is in show_private).
```

- [ ] **Step 5: Write `src/fukan/web/views/graph.boundary`**

```
-- boundary: 1

-- Graph rendering — transforms projection data into CytoscapeGraph.

use "./projection.allium" as projection
use "./cytoscape.allium" as cyto

fn render_graph(projection: projection/Projection, editor_state: EditorState) -> cyto/CytoscapeGraph
  -- Transform projection data into CytoscapeGraph output.

exports:
    ViewState
    NavigationState
```

- [ ] **Step 6: Write `src/fukan/web/views/sidebar.allium`**

```
-- allium: 2
-- web/views/sidebar.allium
--
-- Scope: Sidebar rendering — entity / edge detail panel
-- Includes: render_sidebar_html signature (in sidebar.boundary);
--   sidebar layout / content invariants
-- Excludes:
--   - Graph rendering (see graph.allium)

------------------------------------------------------------
-- Invariants
------------------------------------------------------------

invariant SidebarSectionOrder {
    -- Non-edge sidebar sections appear in this order:
    --   1. Label — entity name + kind badge
    --   2. Defined-in — clickable parent module link (schemas only)
    --   3. Description — from doc, contract, or spec description
    --   4. Guarantees — API-adjacent promises from the module's boundary,
    --      each with name and leading-comment prose (when present)
    --   5. Invariants — structural truths about the module, each with
    --      name, leading-comment prose, and verbatim body (when present)
    --   6. Defined Types — schemas owned by this module that appear
    --      in its operation signatures (modules only)
    --   7. Interface — dispatched by type:
    --      - fn_list (modules): Operations with clickable schema refs
    --      - fn_inline (functions): Inputs, Outputs
    --      - schema_def (schemas): schema detail view with registry
    --      - name_list (interfaces): plain function name list
}

invariant ClickableSchemaRefs {
    -- Schema type references in function signatures and schema
    -- definitions are clickable. Clicking navigates to the schema's
    -- node via full app navigation (graph + breadcrumb + sidebar).
}

invariant EdgeRendererSections {
    -- Edge detail renders by edge_type:
    --   code_flow: label, then conditionally:
    --     "Functions Called" section when called_fns present,
    --     "Dispatched Functions" section when dispatched_fns present.
    --     A single edge may have both sections.
    --   schema_reference: label + "Schema References" list.
}

invariant SidebarEmptyState {
    -- When no entity is selected, the sidebar shows placeholder text.
}
```

- [ ] **Step 7: Write `src/fukan/web/views/sidebar.boundary`**

```
-- boundary: 1

-- Sidebar rendering — entity or edge detail panel.

fn render_sidebar_html(detail: EntityDetails) -> Html
  -- Render entity or edge details as sidebar HTML.
```

- [ ] **Step 8: Write `src/fukan/web/views/breadcrumb.allium`**

```
-- allium: 2
-- web/views/breadcrumb.allium
--
-- Scope: Breadcrumb rendering — navigation trail from smart-root
--   to the current view
-- Includes: render_breadcrumb signature (in breadcrumb.boundary);
--   breadcrumb display invariants
-- Excludes:
--   - Other view rendering (see shell.allium, graph.allium,
--     sidebar.allium)

------------------------------------------------------------
-- Invariants
------------------------------------------------------------

invariant BreadcrumbShortLabels {
    -- Namespace names show only the last dotted segment.
}

invariant BreadcrumbCurrentItem {
    -- The last breadcrumb item is non-clickable and styled as current.
}

invariant BreadcrumbClickableItems {
    -- All non-last breadcrumb items dispatch navigation events.
}
```

- [ ] **Step 9: Write `src/fukan/web/views/breadcrumb.boundary`**

```
-- boundary: 1

-- Breadcrumb rendering — navigation trail.

fn render_breadcrumb(path: EntityPath) -> Html
  -- Render the navigation breadcrumb trail.
```

- [ ] **Step 10: Delete the old views/spec pair**

```bash
rm src/fukan/web/views/spec.allium src/fukan/web/views/spec.boundary
```

- [ ] **Step 11: Update comment ref in `src/fukan/web/views/projection.allium` line 11**

Find: `-- web/views/spec.allium (e.g.`
Replace with: `-- web/views/graph.allium (e.g.`

(GraphViewer's `projection.nodes.find(...)` annotation now lives in graph.allium.)

- [ ] **Step 11a: Update `test/fukan/vocabulary/allium/pipeline_test.clj` set assertion**

In `pipeline-loads-fukan-corpus`:

1. Update the docstring count `7` → `11`.
2. Update the comment count `7` → `11` and replace `web/views` in the list with `web/views/{shell,graph,sidebar,cytoscape,breadcrumb}`.
3. Update `(is (= 7 (count module-tag-apps)))` → `(is (= 11 (count module-tag-apps)))`.
4. Replace `"fukan/web/views/spec"` in the expected `#{...}` with the five new module ids. Final set after this task:

```clojure
#{"fukan/infra/model"
  "fukan/infra/server"
  "fukan/web/handler"
  "fukan/web/views/shell"
  "fukan/web/views/graph"
  "fukan/web/views/sidebar"
  "fukan/web/views/cytoscape"
  "fukan/web/views/breadcrumb"
  "fukan/web/views/projection"
  "fukan/model/spec"
  "fukan/model/pipeline"}
```

- [ ] **Step 12: Update corpus refs in `test/fukan/libs/allium/parser_test.clj`**

Five lines reference `src/fukan/web/views/spec.allium`. Update each to point at the new file containing the content that test exercises:

| Line | Test context | Retarget to |
|---|---|---|
| 435 | multi-file corpus | `src/fukan/web/views/graph.allium` |
| 444 | multi-file corpus | `src/fukan/web/views/graph.allium` |
| 458 | multi-file corpus | `src/fukan/web/views/graph.allium` |
| 481 | multi-file corpus | `src/fukan/web/views/graph.allium` |
| 510 | parse-file single | `src/fukan/web/views/graph.allium` |
| 805 (testing string) + 806 (parse-file) | "real corpus annotation … captures prose" — exercises GraphViewer's `CurrentProblem:` annotation | `src/fukan/web/views/graph.allium` |
| 828 | top-level corpus list | `src/fukan/web/views/graph.allium` (or expand to include all 5 new files; choose graph as the representative single-file replacement) |

Read each test block (the `(testing "..." (let ...))` form around each line) to understand what the assertion checks; if the assertion expects multiple files (a corpus list), include all 5 new files in the replacement. If it expects a single file's content, use `graph.allium`.

- [ ] **Step 13: Rename impl `render-shell` → `render-app-shell`**

Edit `src/fukan/web/views/shell.clj`:

Find: `(defn render-shell`
Replace with: `(defn render-app-shell`

Update the docstring header comment line 3 (or wherever the old name appears in a comment) similarly.

- [ ] **Step 14: Rename impl `render-entity` → `render-sidebar-html`**

Edit `src/fukan/web/views/sidebar.clj`:

Find: `(defn render-entity`
Replace with: `(defn render-sidebar-html`

Also update the docstring/comment at line 3 (`render-entity handles primitive and artifact panels.`) → `render-sidebar-html handles primitive and artifact panels.`

- [ ] **Step 15: Update call sites in `src/fukan/web/handler.clj`**

Two call sites to update.

Line ~55 (`(views.sidebar/render-entity primitive)`):
- Find: `views.sidebar/render-entity`
- Replace with: `views.sidebar/render-sidebar-html`

Line ~65 (`(views.shell/render-shell req)`):
- Find: `views.shell/render-shell`
- Replace with: `views.shell/render-app-shell`

- [ ] **Step 16: Confirm no other refs to the old names**

Run: `grep -rn "render-shell\|render-entity" src/ test/`

Expected: only `render-app-shell` (which contains `render-shell` as a substring — grep won't match) and `render-sidebar-html` (no substring overlap with `render-entity`). The pattern `\brender-shell\b` is safer:

Run: `grep -rnE "\brender-shell\b|\brender-entity\b" src/ test/`

Expected: empty.

- [ ] **Step 17: Run tests**

Run: `clj -M:test 2>&1 | tail -40`

Expected: all tests pass. Failures most likely come from corpus tests with assertions on specific spec content (e.g., a test expecting the GraphViewer surface to be in `views/spec.allium`). Read each failure and update the assertion's expected file path.

- [ ] **Step 18: Probe coverage**

Restart daemon (`(reset)`), then:

```bash
./bin/fukan eval '(coverage)'
```

Expected: `:covered` jumps further (+3 from views: render-app-shell, render-graph, render-sidebar-html). Total ~11. `render-breadcrumb` stays absent (legitimately — no impl).

- [ ] **Step 19: Commit**

```bash
jj st
jj desc -m "refactor(spec+impl): split web/views/spec, rename render fns

The 459-line web/views/spec.allium declared four render operations
whose impls span four sibling files (shell.clj, graph.clj, sidebar.clj,
breadcrumb-not-yet). Split into per-component spec pairs:
- shell.{allium,boundary}     — render_app_shell
- graph.{allium,boundary}     — render_graph, ViewState, NavigationState,
                                 GraphViewer surface, 8 interaction rules,
                                 RenderingPurity / AtomicUpdate /
                                 GraphSelectionDefault invariants
- sidebar.{allium,boundary}   — render_sidebar_html + 4 sidebar invariants
- cytoscape.allium            — CytoscapeGraph/Node/Edge value types (open)
- breadcrumb.{allium,boundary}— render_breadcrumb (no impl yet — absent
                                 edge is legitimate) + 3 breadcrumb
                                 invariants

Impl renames (no behavioural change):
- shell.clj:   render-shell → render-app-shell
- sidebar.clj: render-entity → render-sidebar-html
- handler.clj: caller call-sites updated

Corpus tests in parser_test retargeted to graph.allium (the new home of
the GraphViewer annotation prose tested at line 805)."
jj new
jj desc -m "refactor(spec+impl): align model/pipeline op-name with impl"
```

---

## Task 4: Pipeline op-name fix

**Files:**
- Modify: `src/fukan/model/pipeline.boundary` (drop two ops, rename one)
- Modify: `src/fukan/model/pipeline.clj` (rename `load-source` → `build-model`)
- Modify: `src/fukan/infra/model.clj:16` (caller)
- Modify: `test/fukan/target/clojure/analyzer_test.clj:17` (caller)
- Modify: `test/fukan/vocabulary/boundary/pipeline_test.clj:30` (caller)
- Modify: `test/fukan/validation/phase4_test.clj:39` (caller)
- Modify: `test/fukan/constraint/phase5_test.clj:49` (caller)

- [ ] **Step 1: Update `src/fukan/model/pipeline.boundary`**

Replace the entire file with:

```
-- boundary: 1

-- Model construction.

use "./spec.allium" as model

fn build_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model
  -- Build complete model from a source path and analyzer keys.

exports:
    AnalysisResult
    LintViolation
    LintReport
    LintStats
```

(Dropped `fn check_contracts` and `fn format_report` — they had no impl. If/when those land, re-add.)

- [ ] **Step 2: Rename `load-source` → `build-model` in `src/fukan/model/pipeline.clj`**

Open the file. The fn at line ~31 is `(defn load-source ...)`. Rename to `(defn build-model ...)`. The body calls `allium/load-source` and `boundary/load-source` — leave those untouched (different namespaces, different fns).

- [ ] **Step 3: Update caller in `src/fukan/infra/model.clj:16`**

Find: `(let [m (pipeline/load-source src)]`
Replace with: `(let [m (pipeline/build-model src)]`

- [ ] **Step 4: Update test caller `test/fukan/target/clojure/analyzer_test.clj:17`**

Find: `(let [m (model-pipeline/load-source "src")]`
Replace with: `(let [m (model-pipeline/build-model "src")]`

- [ ] **Step 5: Update test caller `test/fukan/vocabulary/boundary/pipeline_test.clj:30`**

Find: `(let [model (pipeline/load-source "src")]`
Replace with: `(let [model (pipeline/build-model "src")]`

(In this file `pipeline` aliases `fukan.model.pipeline` — see the `:require` at the top.)

- [ ] **Step 6: Update test caller `test/fukan/validation/phase4_test.clj:39`**

Find: `(let [model (model-pipeline/load-source "src")]`
Replace with: `(let [model (model-pipeline/build-model "src")]`

- [ ] **Step 7: Update test caller `test/fukan/constraint/phase5_test.clj:49`**

Find: `(let [m (model-pipeline/load-source "src")]`
Replace with: `(let [m (model-pipeline/build-model "src")]`

- [ ] **Step 8: Confirm `vocabulary/allium/pipeline_test.clj` was not affected**

The `pipeline/load-source` calls in that file alias `fukan.vocabulary.allium.pipeline/load-source`, which is a different function in a different namespace and IS NOT being renamed. Verify:

Run: `head -10 test/fukan/vocabulary/allium/pipeline_test.clj`

Expected: `:require [fukan.vocabulary.allium.pipeline :as pipeline]`. If so, leave the test untouched.

- [ ] **Step 9: Confirm no other refs to `fukan.model.pipeline/load-source`**

Run: `grep -rnE "model-pipeline/load-source|fukan\.model\.pipeline/load-source" src/ test/`

Expected: empty.

- [ ] **Step 10: Run tests**

Run: `clj -M:test 2>&1 | tail -40`

Expected: all tests pass.

- [ ] **Step 11: Probe coverage**

Restart daemon (`(reset)`), then:

```bash
./bin/fukan eval '(coverage)'
```

Expected: `:covered` ≥11 (the +7 from infra, +1 from web, +3 from views; pipeline rename may add the build-model op). `:absent-edge-count` substantially reduced from baseline 108.

- [ ] **Step 12: Commit**

```bash
jj st
jj desc -m "refactor(spec+impl): align model/pipeline op-name with impl

pipeline.boundary declared build_model / check_contracts /
format_report; impl had only load-source. Two of the three ops had no
implementation; the third was misnamed.

Rename impl load-source → build-model so the existing 1:1 address
convention resolves fukan/model/pipeline :: build_model →
fukan.model.pipeline/build-model. Drop check_contracts and
format_report from the boundary — they re-land when they earn impl.

Updated 5 call sites (infra/model.clj + 4 test files referencing
fukan.model.pipeline/load-source). Allium/Boundary parser pipelines'
own load-source fns are unrelated and untouched."
```

---

## Task 5: Final verification

**Files:** none — verification only.

- [ ] **Step 1: Run the full test suite once more**

Run: `clj -M:test`

Expected: all tests pass. Capture any unexpected failures and resolve before declaring complete.

- [ ] **Step 2: Restart the daemon cleanly**

In the REPL: `(reset)` (full server restart so the handler is recreated with fresh code).

Or kill `clj -M:run` and re-run.

- [ ] **Step 3: Confirm coverage hits the target**

```bash
./bin/fukan eval '(coverage)'
```

Expected:
- `:covered` ≥ 11
- `:absent-edge-count` substantially below 108 (target: drop by ~25–40, reflecting the operation projections that flipped from absent to valid).
- `:total-public-functions` unchanged (~197).

Also probe per-validity breakdown:

```bash
./bin/fukan eval '(let [es (:rows (relations :kind :relation/projects :limit 1000))]
                    (frequencies (map :validity es)))'
```

Expected: `{:valid <N>, :absent <M>, ...}` where N is ≥ the number of operation impls (8 ops from infra/web + 3 from views + 1 from pipeline = 12 minimum; tests for ops where test files exist add more).

- [ ] **Step 4: Spot-check that previously-absent edges are now valid**

```bash
./bin/fukan eval '(:rows (relations :kind :relation/projects :validity :valid :limit 20))'
```

Expected: includes edges like `fukan.infra.model/load-model`, `fukan.web.handler/create-handler`, `fukan.web.views.graph/render-graph`, `fukan.model.pipeline/build-model`.

- [ ] **Step 5: Confirm no new constraint violations**

```bash
./bin/fukan eval '(count (violations :severity :error))'
```

Expected: same as baseline (likely 0 errors; any warnings unchanged from baseline). If new violations appear, inspect with `(violations :severity :error)` and resolve.

- [ ] **Step 6: Confirm legitimate absents are still absent**

```bash
./bin/fukan eval '(filter #(re-find #"render-breadcrumb|check-contracts|format-report" (str (:to %)))
                          (:rows (relations :kind :relation/projects :validity :absent :limit 1000)))'
```

Expected: edges for `render-breadcrumb` remain `:absent`; nothing for `check-contracts`/`format-report` (those ops were dropped from the boundary in Task 4).

- [ ] **Step 7: Push only if explicitly asked**

This plan does not push. If the user asks to push, use `jj git push` (or the project's standard push command). Otherwise stop after Task 5 Step 6.

---

## Notes for the executor

- **REPL workflow:** Per project CLAUDE.md, prefer `(refresh)` after spec/code edits over restarting. `(reset)` is needed when handler routes change (Task 3 Step 15 modifies handler.clj — use `(reset)`).
- **Jujutsu vs git:** This repo uses jj. Never run `git commit` or other git porcelain. The `jj` commands in this plan are correct. If unsure about a jj command, load the `jujutsu` skill before proceeding.
- **Each task is one commit:** Per project commit-driven-development style. `jj new` + `jj desc -m "..."` before the work, then the actual `jj st` after to confirm. The commit messages are pre-drafted in each task's final step.
- **Don't bypass failing tests:** If a corpus test fails because its assertion expects literal old content, the test exists precisely to detect this kind of drift. Update the assertion to match the new structure; don't comment it out or change to a weaker check.
- **Allium/Boundary syntax cues:** All `.allium` files start with `-- allium: 2`. All `.boundary` files start with `-- boundary: 1`. Comments use `--`. The parser is strict — keep blank lines and column alignment as shown in the existing siblings.
