# Explorer Rewrite for the New Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Plan 6's frontend gap — make the new Model (76 primitives + 612 artifacts + 158 edges + thirteen relation kinds) visible and navigable in-browser. Rewrite `views/shell.clj` (Plan-1 placeholder), `resources/public/app.js` (OLD `GraphViewer` web component, ~786 lines coupled to `module`/`function`/`schema` kinds), the cytoscape stylesheet, and the sidebar render path. Wire the `/projector` endpoint into a click-to-inspect Blueprint modal. Surface all thirteen kernel relations through an edge-kind filter.

**Architecture:** Server-rendered shell that hosts a slimmer `<graph-viewer>` web component. Component receives graph data via Datastar attribute updates from a server-side handler that calls `projection.core/project-model`. Click events emit CustomEvents that Datastar wires back to server handlers — `/sidebar?id=X` returns hiccup-rendered detail HTML; `/projector?primitive-id=X&projection-kind=Y` returns Blueprint JSON for the modal. Edge-kind filter is a client-only mask that hides edges by `:kind` attribute — no server round-trip needed. Drift-marker click runs through the same `/projector` flow but mounts the Blueprint markdown into a sidebar panel.

**Tech Stack:** Existing Clojure 1.11. Existing http-kit + reitit + hiccup + datastar SDK + cheshire. Existing Cytoscape.js + dagre layout. No new dependencies. Markdown rendering in-browser via a small JS helper (no library — Blueprint markdown is simple enough that 30 lines of regex-driven HTML conversion handles it; full markdown features wait).

---

## Plan-of-plans context

This is **Plan 7 of N** in the next-chapter overhaul.

The original sequence (Plans 1–6) is closed. Plan 6 shipped Projector + Explorer substrate but the **frontend half landed in degraded MVP** because the existing GraphViewer JS is tightly coupled to the OLD code-graph model (`module`/`function`/`schema` kinds, `code-flow`/`schema-reference` edge types, module-expansion semantics that don't apply to the new Model). The `/projector` endpoint was proven end-to-end against fukan-on-fukan, but the only client-side affordance was a `console.log` of the Blueprint URL.

Plan 7 closes this gap. Specifically:

1. **Server-side**: Plan 6's `handler.clj` already has `/`, `/graph`, `/projector`, `/sidebar`. Plan 7 expands `/sidebar` from a minimal hiccup render to a full primitive-or-artifact panel with related-edge rendering.
2. **Client-side**: Replace `app.js` GraphViewer's OLD-model wiring with new-model handlers. Cytoscape stylesheet picks up all primitive kinds (container, rule, operation, event, behaviour, boundary, intent, clause, actor) plus artifact kinds (code/function, code/data-structure). Drift edges render red-dashed and clickable into a Blueprint modal.
3. **Shell**: `views/shell.clj` is currently 18 lines of Plan-1 placeholder. Plan 7 expands it into the proper Datastar-driven SPA frame — `<graph-viewer>` element on the left, sidebar panel on the right, optional Blueprint modal on top.

Authoritative refs:
- [DESIGN.md "Explorer"](../DESIGN.md) line 738 — thirteen relation kinds + tag-applied projection scoping (the latter deferred to a future plan).
- [DESIGN.md "Generation UX details"](../DESIGN.md) line 796 — "the concrete affordance shape (button placement, batch operations, diff UI, accept/reject mechanics) lands at implementation."
- [VISION.md](../VISION.md) lines 96-99 — "clicking a red `absent` drift marker summons the Projector". Plan 7 finally realises the click-handler half.
- [Plan 6](2026-05-19-projector-explorer.md) — Task 11's degraded MVP scope note. The console-log affordance from there is REPLACED by the Plan 7 modal.

What Plan 7 does NOT cover (per the lean scope decision):

- LLM API wiring (Anthropic API client + `/generate` endpoint + diff preview + write-back).
- Default-hide of unbound artifacts (Lean ships all 612 nodes; performance tuning waits for forcing examples).
- Cmd+K finder / search.
- Container drill-in navigation (descend into a module subset).
- Sidebar backlinks (incoming edges in the sidebar; outgoing edges are in scope).

Existing surfaces Plan 7 builds on:
- `src/fukan/web/handler.clj` — routes already in place; `/sidebar` will get a meaningful payload (currently minimal).
- `src/fukan/web/views/sidebar.clj` — Plan 6 added a minimal `render-entity`; Plan 7 expands.
- `src/fukan/projection/core.clj` — `project-model` already emits the new-model graph shape; reused as-is.
- `src/fukan/web/views/cytoscape.clj` — Plan 6's transformer; reused as-is.
- `src/fukan/web/views/graph.clj` — Plan 6's `render-graph`; reused as-is.
- `resources/public/app.js` — Plan 7 strips the OLD GraphViewer down and rebuilds.
- `src/fukan/web/views/shell.clj` — Plan 7 rewrites entirely.

Existing surfaces Plan 7 RETIRES:
- `src/fukan/web/views/breadcrumb.clj` (39 lines) — OLD navigation crumb tied to module hierarchy. No equivalent in the new Model for MVP.
- `src/fukan/web/views/schema.clj` (176 lines) — OLD schema-detail renderer. Replaced by the new sidebar artifact render path.
- Large parts of `src/fukan/web/views/sidebar.clj` (existing ~340 lines of OLD-model entity-details). Keep the `render-entity` helper from Plan 6; delete the rest.

---

## Repository conventions (jj over git)

Identical to prior plans. **NEVER `jj squash -m "..."`** (silently collapses commits). Use `jj desc -m "..."` + `jj new` after each task commit.

---

## Conventions used throughout this plan

- **Manual browser smoke at every UI task.** There is no JS test runner in fukan. Each UI task ends with a manual smoke checklist (REPL start → browser action → expected behaviour). For Clojure-side changes (shell.clj, sidebar.clj, handler.clj), use Ring-invocation tests as established in `test/fukan/web/handler_test.clj`.
- **Server-side rendering wherever possible.** The sidebar is hiccup-rendered HTML returned by `/sidebar?id=X`. The Blueprint modal contents are JSON from `/projector?...`; the markdown rendering happens client-side via a small helper.
- **No new JS dependencies.** Existing Cytoscape.js + dagre + Datastar. Markdown helper is ~30 lines of regex-driven conversion (sufficient for Blueprint output: headers, code blocks, lists, inline code, paragraphs).
- **Datastar attribute-driven data flow.** The `<graph-viewer>` element observes `graph-data` attribute changes; the server updates the attribute via `patch-attributes` SSE pushes. Selection sync uses `patch-elements` for the sidebar panel.
- **Edge-kind filter is client-only.** No server round-trip — cytoscape selectors hide edges with non-visible `:edgeType` values. State held in component instance.

---

## File Structure

### Files to create

- (none — Plan 7 rewrites existing surfaces rather than adding new ones)

### Files to modify (substantial rewrites)

- `src/fukan/web/views/shell.clj` — Plan-1 placeholder → SPA frame.
- `resources/public/app.js` — strip OLD GraphViewer (~786 lines) → new GraphViewer (~300 lines projected).
- `src/fukan/web/views/sidebar.clj` — strip OLD entity-details (~340 lines) → primitive + artifact render path (~120 lines projected).
- `src/fukan/web/handler.clj` — extend `/sidebar` payload; ensure `/graph` returns the new projection.

### Files to delete

- `src/fukan/web/views/breadcrumb.clj` — OLD navigation crumb.
- `src/fukan/web/views/schema.clj` — OLD schema renderer.

### Files to leave untouched

- All `src/fukan/{model,target,projection,project_layer,constraint,validation,vocabulary,infra,libs}/*` — unchanged.
- `src/fukan/web/views/{cytoscape,graph}.clj` — Plan 6 already adapted these to the new Model.
- Plans 1–6 fixtures + test infrastructure — untouched.

---

## Reading the canonical reference

The new Model's node shape per `fukan.projection.core/project-model`:

```clojure
;; Primitive node:
{:id          "<primitive-id>"
 :kind        :primitive/container | :primitive/rule | :primitive/operation | ...
 :label       "<human-readable>"
 :allium-kind :entity | :surface | :rule | ... | nil
 :parent      <host-primitive-id-or-nil>}

;; Artifact node:
{:id              "artifact:code/function:clojure:<qualified-name>"
 :kind            :artifact/code-function | :artifact/code-data-structure
 :label           "<qualified-name>"
 :source-location {:file "<path>" :line <int>}}
```

Edge shape:

```clojure
;; Generic kernel edge:
{:id   "e<n>"
 :from "<node-id>"
 :to   "<node-id>"
 :kind :relation/triggers | :relation/emits | ... | :relation/projects}

;; Projects edge (extra fields):
{:projection-kind :projection-kind/rule | :projection-kind/operation | ...
 :validity        :valid | :absent}
```

After `cytoscape/graph->cytoscape` (Plan 6): the same data with camelCase'd keys, `:kind` rendered as `"primitive/container"` etc., and `:drift "absent"` attached to absent-validity edges.

The thirteen kernel relation kinds (per MODEL.md §4):
`:relation/triggers :relation/observes :relation/reads :relation/writes :relation/creates :relation/destroys :relation/emits :relation/realises :relation/specialises :relation/uses :relation/exposes :relation/provides :relation/projects`.

---

## Task 0: Scaffold — delete OLD UI files + retire OLD JS

**Files:**
- Delete: `src/fukan/web/views/breadcrumb.clj`
- Delete: `src/fukan/web/views/schema.clj`
- Modify: `src/fukan/web/views/sidebar.clj` (strip OLD entity-details, preserve `render-entity` helper from Plan 6)
- Modify: `resources/public/app.js` (strip everything below the `<graph-viewer>` definition; Task 2 rebuilds)

Hard reset of the OLD UI so subsequent tasks rebuild from a known state.

- [ ] **Step 0.1: Identify the keep-list in `sidebar.clj`**

Read the file. Plan 6 added `render-entity` (a small MVP helper for primitive-or-artifact panels). Everything else is OLD-model code paths: `render-entity-detail`, `render-edge-detail`, `render-dep-list`, `fn-signature-str`, `render-sidebar-html`, etc.

Keep `render-entity`. Delete the rest. Adjust the ns docstring to reflect the new scope.

- [ ] **Step 0.2: Strip `sidebar.clj`**

After this step, `sidebar.clj` should be ~30 lines. Just the ns form + `render-entity`. (Task 6 expands it.)

- [ ] **Step 0.3: Delete `breadcrumb.clj` + `schema.clj`**

```bash
rm src/fukan/web/views/breadcrumb.clj src/fukan/web/views/schema.clj
```

- [ ] **Step 0.4: Strip `app.js`**

Replace `resources/public/app.js` with:

```js
// Fukan Explorer — Plan 7 rewrite for the new Model.
// GraphViewer web component (Task 2 rebuilds below) +
// markdown rendering helper (Task 8) +
// /projector + /sidebar fetch helpers.
// This stub is intentional — Tasks 2-9 populate it.

class GraphViewer extends HTMLElement {
  static observedAttributes = ['graph-data'];

  connectedCallback() {
    // Task 2 implements.
    const placeholder = document.createElement('div');
    placeholder.textContent = 'graph-viewer placeholder (Plan 7 Tasks 2+ pending)';
    this.appendChild(placeholder);
  }
}

customElements.define('graph-viewer', GraphViewer);
```

- [ ] **Step 0.5: Run tests, expect pass**

```
clj -M:test
```

Expected: 423/0/0 (Plan 6 baseline). Some tests may reference deleted files — fix any imports. Most likely: `test/fukan/web/handler_test.clj` doesn't import schema.clj or breadcrumb.clj, so it stays clean.

If imports break, update the broken file inline.

- [ ] **Step 0.6: Commit**

```bash
jj desc -m "chore(web): retire OLD-model UI surface (Plan 7 Task 0)

Deletes breadcrumb.clj + schema.clj (OLD-model only — module hierarchy
crumb + schema detail renderer; neither has a new-Model equivalent in
MVP scope).

Strips sidebar.clj down to its render-entity helper (Plan 6's MVP).
Strips app.js down to a placeholder GraphViewer; Task 2 rebuilds.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: Rewrite shell.clj — Datastar SPA frame

**Files:**
- Modify: `src/fukan/web/views/shell.clj`
- Modify: `test/fukan/web/handler_test.clj` (add a `/` smoke test)

Replace the Plan-1 placeholder shell with a proper Datastar-driven SPA frame:
- HTML5 doctype + `<head>` with Cytoscape + Datastar script tags + inline stylesheet.
- Two-column layout: `<graph-viewer>` element (flexible width) + sidebar panel `<aside id="sidebar">` (fixed 360px).
- Datastar `data-on-load` directive that triggers an initial `GET /graph` to populate the GraphViewer's `graph-data` attribute (this is Task 4's wiring — Task 1 only puts the directive in place).
- Hidden `<dialog id="blueprint-modal">` shell for the Task 8 Blueprint modal.

### Step 1.1: Test

In `test/fukan/web/handler_test.clj`, add:

```clojure
(deftest shell-renders-graph-viewer
  (let [h (handler/create-handler)
        response (h {:request-method :get :uri "/"})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "<graph-viewer"))
    (is (str/includes? (:body response) "<aside id=\"sidebar\""))
    (is (str/includes? (:body response) "<dialog id=\"blueprint-modal\""))
    (is (str/includes? (:body response) "app.js"))
    (is (str/includes? (:body response) "cytoscape"))))
```

### Step 1.2: Run, see fail

The existing shell doesn't have these elements.

### Step 1.3: Implement

Replace `src/fukan/web/views/shell.clj`:

```clojure
(ns fukan.web.views.shell
  "Datastar-driven SPA shell — two-column layout hosting the
   <graph-viewer> web component (left) and the entity sidebar (right),
   plus a hidden <dialog> for the Blueprint modal.

   Plan 7 rewrite from Plan-1 placeholder."
  (:require [hiccup2.core :as h]))

(defn- render-head []
  [:head
   [:meta {:charset "utf-8"}]
   [:title "fukan — explorer"]
   [:script {:src "https://unpkg.com/cytoscape@3.30.0/dist/cytoscape.min.js"}]
   [:script {:src "https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js"}]
   [:script {:src "https://unpkg.com/dagre@0.8.5/dist/dagre.min.js"}]
   [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.0-beta.11/bundles/datastar.js"
             :type "module"}]
   [:script {:src "/app.js" :defer true}]
   [:style "
     html, body { margin: 0; padding: 0; height: 100%; font-family: system-ui, sans-serif; }
     #app { display: flex; height: 100vh; }
     graph-viewer { flex: 1; position: relative; background: #fafafa; }
     #cy { width: 100%; height: 100%; }
     #sidebar { width: 360px; border-left: 1px solid #ccc; padding: 12px;
                overflow-y: auto; background: #fff; font-size: 13px; }
     #sidebar h3 { margin: 0 0 8px 0; font-size: 15px; }
     #sidebar .related-edge { padding: 2px 0; }
     #sidebar .related-edge .kind { color: #888; font-family: monospace; font-size: 11px; }
     #blueprint-modal { width: 80vw; max-width: 900px; padding: 16px; }
     #blueprint-modal pre { background: #f4f4f4; padding: 8px; overflow-x: auto; }
     #blueprint-modal code { font-size: 12px; }
     .filter-toolbar { position: absolute; top: 8px; right: 8px; z-index: 10;
                       background: rgba(255,255,255,0.95); padding: 6px; border-radius: 4px;
                       border: 1px solid #ccc; font-size: 11px; }
     .filter-toolbar label { display: block; cursor: pointer; padding: 1px 4px; }
   "]])

(defn- render-body []
  [:body
   [:div#app
    [:graph-viewer]
    [:aside#sidebar
     [:p {:style "color:#888"} "Select a node or edge to inspect."]]]
   [:dialog#blueprint-modal
    [:button {:onclick "this.closest('dialog').close()"
              :style "float:right"} "Close"]
    [:div#blueprint-modal-body]]])

(defn render-shell
  "Render the SPA shell. Plan 7."
  [_request]
  (str "<!doctype html>\n"
       (h/html (render-head) (render-body))))
```

### Step 1.4: Run, expect pass + commit

Expected: 423 + 1 = **424/0/0**.

```bash
jj desc -m "feat(web/views): rewrite shell as Datastar SPA frame (Plan 7 Task 1)

Replaces Plan-1 placeholder shell with a proper two-column SPA: the
<graph-viewer> web component (left, fills available space) and the
entity sidebar (right, fixed 360px), plus a hidden <dialog> for the
Blueprint modal (Task 8).

Loads Cytoscape + cytoscape-dagre + Datastar from CDN. /app.js is
served as a static resource. Inline stylesheet sets the layout; later
tasks add cytoscape-specific styling via JS.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: Rewrite app.js GraphViewer for new Model

**Files:**
- Modify: `resources/public/app.js`

Rebuild the `GraphViewer` web component for the new Model. Strip module-expansion, private-toggle, view-id navigation — none of those apply. Keep the high-level shape: web component that observes `graph-data` attribute, renders into a `<div id="cy">`, emits CustomEvents on user interactions.

What the new GraphViewer does:
- On `graph-data` attribute change → parse JSON → reset cytoscape with new nodes + edges.
- On node tap → emit `graph-select` CustomEvent with `{nodeId}`. Datastar binding in shell wires this to a `GET /sidebar?id=X` SSE.
- On edge tap (especially `[drift="absent"]`) → emit `graph-edge-select` CustomEvent with `{source, target, projectionKind, drift}`. Datastar binding wires drift edges to `GET /projector?primitive-id=X&projection-kind=Y` SSE.
- On background tap → emit `graph-deselect`.

No expansion, no private toggle, no view-id navigation. The graph shows the FULL Model at all times for MVP (filters land in Task 9).

### Step 2.1: Implement

Replace `resources/public/app.js` (the placeholder from Task 0):

```js
// Fukan Explorer — Plan 7 GraphViewer for the new Model.
// See doc/plans/2026-05-19-explorer-rewrite.md.

class GraphViewer extends HTMLElement {
  cy = null;
  visibleEdgeKinds = new Set([
    'relation/triggers', 'relation/observes', 'relation/reads',
    'relation/writes', 'relation/creates', 'relation/destroys',
    'relation/emits', 'relation/realises', 'relation/specialises',
    'relation/uses', 'relation/exposes', 'relation/provides',
    'relation/projects'
  ]);

  static observedAttributes = ['graph-data'];

  connectedCallback() {
    const cyDiv = document.createElement('div');
    cyDiv.id = 'cy';
    this.appendChild(cyDiv);
    cyDiv.addEventListener('contextmenu', (e) => e.preventDefault());

    this.cy = cytoscape({
      container: cyDiv,
      elements: [],
      style: this._cytoscapeStyles(),  // Task 3 fills in
      layout: { name: 'preset' },
      userZoomingEnabled: true,
      userPanningEnabled: true,
      boxSelectionEnabled: false
    });

    this._bindCytoscapeEvents();
    // Task 9 mounts the filter toolbar; Task 2 leaves a slot.
  }

  disconnectedCallback() {
    if (this.cy) { this.cy.destroy(); this.cy = null; }
  }

  attributeChangedCallback(name, _oldValue, newValue) {
    if (name === 'graph-data' && newValue && newValue !== 'null' && this.cy) {
      try {
        this._renderGraph(JSON.parse(newValue));
      } catch (e) { console.error('[graph-viewer] parse error:', e); }
    }
  }

  _renderGraph(data) {
    if (!data || !data.nodes) return;
    const cy = this.cy;
    cy.elements().remove();
    cy.add(data.nodes.map(n => ({ group: 'nodes', data: n })));
    cy.add(data.edges.map(e => ({ group: 'edges', data: e })));
    this._applyEdgeFilter();
    cy.layout(this._dagreOptions()).run();
    cy.fit(40);
  }

  _dagreOptions() {
    return {
      name: 'dagre', rankDir: 'TB', ranker: 'network-simplex',
      nodeSep: 60, rankSep: 80, edgeSep: 20,
      animate: false, fit: false, padding: 30,
      nodeDimensionsIncludeLabels: true, spacingFactor: 1.1
    };
  }

  _bindCytoscapeEvents() {
    const cy = this.cy;

    cy.on('tap', 'node', (evt) => {
      const node = evt.target;
      cy.nodes().unselect();
      node.select();
      cy.edges().removeClass('highlighted');
      cy.edges().forEach(e => {
        if (e.source().id() === node.id() || e.target().id() === node.id()) {
          e.addClass('highlighted');
        }
      });
      this.dispatchEvent(new CustomEvent('graph-select', {
        bubbles: true, detail: { nodeId: node.id() }
      }));
    });

    cy.on('tap', 'edge', (evt) => {
      const edge = evt.target;
      cy.nodes().unselect();
      cy.edges().removeClass('highlighted');
      edge.addClass('highlighted');
      this.dispatchEvent(new CustomEvent('graph-edge-select', {
        bubbles: true, detail: {
          source: edge.data('source'),
          target: edge.data('target'),
          kind: edge.data('kind'),
          projectionKind: edge.data('projectionKind'),
          drift: edge.data('drift')
        }
      }));
    });

    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        cy.nodes().unselect();
        cy.edges().removeClass('highlighted');
        this.dispatchEvent(new CustomEvent('graph-deselect', { bubbles: true }));
      }
    });
  }

  setEdgeKindVisible(kind, visible) {
    if (visible) this.visibleEdgeKinds.add(kind);
    else this.visibleEdgeKinds.delete(kind);
    this._applyEdgeFilter();
  }

  _applyEdgeFilter() {
    const cy = this.cy;
    cy.edges().forEach(e => {
      const visible = this.visibleEdgeKinds.has(e.data('edgeType'));
      if (visible) e.removeClass('filtered-out');
      else e.addClass('filtered-out');
    });
  }

  _cytoscapeStyles() {
    // Task 3 fills this in.
    return [];
  }
}

customElements.define('graph-viewer', GraphViewer);
```

### Step 2.2: Manual smoke

```
clojure -M:dev
;; In REPL:
(require '[fukan.infra.model :as m])
(m/load-model "src")
(require '[fukan.infra.server :as s])
(s/start-server!)
```

Open `http://localhost:8080/`. Expected: page loads with empty `<graph-viewer>` element (no graph yet — Task 4 wires initial load) + sidebar shell. Open DevTools — no JS errors.

### Step 2.3: Commit

```bash
jj desc -m "feat(web/frontend): rewrite GraphViewer for new Model (Plan 7 Task 2)

Strips OLD-model logic (module expansion, private toggle, view-id
navigation, schema-key highlighting) and rebuilds around the new Model.

GraphViewer responsibilities:
- Observe :graph-data attribute → render via cytoscape + dagre.
- Emit graph-select / graph-edge-select / graph-deselect CustomEvents.
- Public setEdgeKindVisible(kind, visible) for the Task 9 filter UI.

Cytoscape stylesheet (_cytoscapeStyles) is a stub; Task 3 fills it.

No JS tests in fukan; manual browser smoke is the verification path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: Cytoscape stylesheet for primitive + artifact kinds

**Files:**
- Modify: `resources/public/app.js` (`_cytoscapeStyles` method)

Define cytoscape style rules covering:
- **Nine primitive kinds**: container, actor, behaviour, rule, boundary, operation, intent, clause, event.
- **Two artifact kinds**: code-function, code-data-structure.
- **Thirteen relation kinds**: 12 generic + projects (with `:drift "absent"` red-dashed override).
- **Selection / highlight states**.
- **Filtered-out class** (Task 9).

The stylesheet doesn't need to be visually polished for MVP — distinct shapes per kind + readable colours are enough to verify the new Model renders.

### Step 3.1: Implement

Replace `_cytoscapeStyles()` in `resources/public/app.js`:

```js
  _cytoscapeStyles() {
    return [
      // ---- Primitive nodes ----
      { selector: 'node[kind^="primitive/"]', style: {
        'label': 'data(label)', 'text-valign': 'center', 'text-halign': 'center',
        'text-wrap': 'wrap', 'text-max-width': '140px',
        'font-size': '11px', 'color': '#2c3e50', 'padding': '12px'
      }},
      { selector: 'node[kind="primitive/container"]', style: {
        'shape': 'roundrectangle', 'background-color': '#e8f4f8',
        'border-color': '#2980b9', 'border-width': 2, 'font-weight': 'bold'
      }},
      { selector: 'node[kind="primitive/rule"]', style: {
        'shape': 'roundrectangle', 'background-color': '#fff5e6',
        'border-color': '#e67e22', 'border-width': 2
      }},
      { selector: 'node[kind="primitive/operation"]', style: {
        'shape': 'ellipse', 'background-color': '#e8f8f0',
        'border-color': '#16a085', 'border-width': 2
      }},
      { selector: 'node[kind="primitive/event"]', style: {
        'shape': 'tag', 'background-color': '#fef9e7',
        'border-color': '#f39c12', 'border-width': 2
      }},
      { selector: 'node[kind="primitive/behaviour"]', style: {
        'shape': 'roundrectangle', 'background-color': '#f4ecf7',
        'border-color': '#8e44ad', 'border-width': 2
      }},
      { selector: 'node[kind="primitive/boundary"]', style: {
        'shape': 'cut-rectangle', 'background-color': '#fdf2e9',
        'border-color': '#d35400', 'border-width': 2
      }},
      { selector: 'node[kind="primitive/intent"]', style: {
        'shape': 'hexagon', 'background-color': '#eaf2f8',
        'border-color': '#5499c7', 'border-width': 2
      }},
      { selector: 'node[kind="primitive/clause"]', style: {
        'shape': 'octagon', 'background-color': '#f2f4f4',
        'border-color': '#7f8c8d', 'border-width': 1
      }},
      { selector: 'node[kind="primitive/actor"]', style: {
        'shape': 'star', 'background-color': '#ebdef0',
        'border-color': '#7d3c98', 'border-width': 2
      }},

      // ---- Artifact nodes ----
      { selector: 'node[kind^="artifact/"]', style: {
        'shape': 'rectangle', 'background-color': '#f8f9fa',
        'border-color': '#95a5a6', 'border-width': 1, 'border-style': 'dashed',
        'label': 'data(label)', 'text-valign': 'center', 'text-halign': 'center',
        'font-size': '10px', 'font-family': 'monospace', 'color': '#566573',
        'text-wrap': 'wrap', 'text-max-width': '160px', 'padding': '8px'
      }},

      // ---- Generic edges ----
      { selector: 'edge', style: {
        'width': 1.5, 'line-color': '#7f8c8d',
        'target-arrow-color': '#7f8c8d', 'target-arrow-shape': 'triangle',
        'curve-style': 'bezier', 'arrow-scale': 0.7,
        'label': 'data(edgeType)', 'font-size': '8px',
        'text-background-color': '#fff', 'text-background-opacity': 0.8,
        'text-background-padding': '2px', 'color': '#7f8c8d'
      }},

      // ---- Per-relation edge styles ----
      { selector: 'edge[edgeType="relation/triggers"]',    style: { 'line-color': '#2980b9', 'target-arrow-color': '#2980b9' }},
      { selector: 'edge[edgeType="relation/emits"]',       style: { 'line-color': '#f39c12', 'target-arrow-color': '#f39c12' }},
      { selector: 'edge[edgeType="relation/realises"]',    style: { 'line-color': '#16a085', 'target-arrow-color': '#16a085' }},
      { selector: 'edge[edgeType="relation/specialises"]', style: { 'line-color': '#8e44ad', 'target-arrow-color': '#8e44ad', 'line-style': 'dashed' }},
      { selector: 'edge[edgeType="relation/uses"]',        style: { 'line-color': '#34495e', 'target-arrow-color': '#34495e' }},
      { selector: 'edge[edgeType="relation/exposes"]',     style: { 'line-color': '#27ae60', 'target-arrow-color': '#27ae60' }},
      { selector: 'edge[edgeType="relation/provides"]',    style: { 'line-color': '#d35400', 'target-arrow-color': '#d35400' }},
      { selector: 'edge[edgeType="relation/observes"]',    style: { 'line-color': '#7f8c8d', 'line-style': 'dotted' }},
      { selector: 'edge[edgeType="relation/reads"]',       style: { 'line-color': '#5dade2' }},
      { selector: 'edge[edgeType="relation/writes"]',      style: { 'line-color': '#e74c3c' }},
      { selector: 'edge[edgeType="relation/creates"]',     style: { 'line-color': '#52be80' }},
      { selector: 'edge[edgeType="relation/destroys"]',    style: { 'line-color': '#c0392b' }},
      { selector: 'edge[edgeType="relation/projects"]',    style: {
        'line-color': '#95a5a6', 'line-style': 'dotted', 'arrow-scale': 0.5
      }},

      // ---- Drift markers (Plan 6 carry-over, refined) ----
      { selector: 'edge[drift="absent"]', style: {
        'line-color': '#e74c3c', 'target-arrow-color': '#e74c3c',
        'line-style': 'dashed', 'line-dash-pattern': [4, 4], 'width': 2
      }},

      // ---- Selection / highlight ----
      { selector: 'node:selected', style: {
        'border-color': '#e74c3c', 'border-width': 3
      }},
      { selector: 'edge:selected', style: {
        'width': 3, 'z-index': 1000
      }},
      { selector: 'edge.highlighted', style: {
        'line-color': '#e74c3c', 'target-arrow-color': '#e74c3c',
        'width': 2.5, 'z-index': 999
      }},
      { selector: 'edge.filtered-out', style: { 'display': 'none' }}
    ];
  }
```

### Step 3.2: Manual smoke

REPL → load model → open browser. Open DevTools; manually call `document.querySelector('graph-viewer').cy.add(...)` to inject a test node + edge. Verify shape + colour match the spec.

Better smoke: wait for Task 4 to wire initial load; Task 3's stylesheet is verified end-to-end at Task 4's smoke.

### Step 3.3: Commit

```bash
jj desc -m "feat(web/frontend): cytoscape stylesheet for new Model kinds (Plan 7 Task 3)

Adds style rules for:
- Nine primitive kinds (container/rule/operation/event/behaviour/boundary/
  intent/clause/actor) with distinct shapes + colour palette.
- Two artifact kinds (Code.Function / Code.DataStructure) — monospace
  label, dashed-grey border to signal 'projection target'.
- Twelve generic relation kinds with distinct line colours; projects
  edges get a dotted-grey baseline.
- Drift marker override (red dashed) for :validity :absent.
- Selection + highlight + filtered-out classes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: Initial-load wiring — page open → /graph → render

**Files:**
- Modify: `src/fukan/web/views/shell.clj` (add Datastar `data-on-load` directive)
- Modify: `src/fukan/web/handler.clj` (`/graph` returns Datastar SSE patch-attributes, not raw JSON)

The flow:
1. Browser opens `/`. Server renders shell.
2. Shell's `<body>` carries `data-on-load="@get('/graph')"` (or equivalent Datastar directive).
3. Datastar fires `GET /graph` on load.
4. Handler returns SSE response: `patch-attributes` updating `<graph-viewer>`'s `graph-data` attribute with the JSON-stringified cytoscape graph.
5. GraphViewer's `attributeChangedCallback` fires → renders.

Datastar SSE specifics: the response is `Content-Type: text/event-stream` with `datastar-patch-attributes` events. The fukan project uses `dev.data-star.clojure/http-kit` SDK which provides helpers.

### Step 4.1: Survey datastar SDK helpers

Read the datastar SDK to confirm the helper API. Likely something like:

```clojure
(require '[starfederation.datastar.clojure.api :as d*])

(d*/patch-attributes! sse "graph-viewer" {:graph-data json-string})
```

If the SDK isn't already required somewhere in the project, find the right namespace:

```bash
find . -name "*.clj" -exec grep -l "datastar" {} \;
```

Adapt the example to the actual SDK shape.

### Step 4.2: Implement handler change

In `src/fukan/web/handler.clj`, change `handle-graph` from plain JSON to Datastar SSE:

```clojure
(require '[starfederation.datastar.clojure.api :as d*])
(require '[starfederation.datastar.clojure.adapter.http-kit :as d*hk])

(defn- handle-graph [req]
  (if-let [m (infra-model/get-model)]
    (let [graph (views.graph/render-graph (projection/project-model m)
                                          {:selected-id nil})]
      (d*hk/->sse-response req
        {:on-open (fn [sse]
                    (d*/patch-attributes! sse "graph-viewer"
                                          {:graph-data (json/generate-string graph)})
                    (d*/close-sse! sse))}))
    (bad-request "no model loaded")))
```

The SDK API may differ — adapt based on what `starfederation.datastar.clojure.*` actually exposes. If unclear, fall back to raw http-kit SSE: write `data: <event-line>\n\n` to the response output stream.

### Step 4.3: Implement shell directive

In `src/fukan/web/views/shell.clj`'s `render-body`, add:

```clojure
[:body {:data-on-load "@get('/graph')"}
 ...]
```

### Step 4.4: Update handler_test.clj

The previous `/graph` test asserted JSON output. Now `/graph` returns SSE. Either:
- (a) Update the test to assert SSE format (`Content-Type: text/event-stream`, body contains `datastar-patch-attributes`).
- (b) Refactor: extract the graph-data computation into a pure helper `(defn compute-graph-payload [model] ...)` testable in isolation; have the test call the helper directly.

Choose (b) — cleaner separation. Add the helper, test the helper. Don't test SSE plumbing.

### Step 4.5: Manual smoke

REPL → load model → open browser. Expected: page loads, `<graph-viewer>` populates with the graph (76 primitives + 612 artifacts + 158 edges). DAGRE layout runs once. View fits.

Performance check: layout takes <5s for 600+ nodes. If much slower, note in commit message + defer perf tuning to a follow-on plan.

### Step 4.6: Commit

```bash
jj desc -m "feat(web): initial-load /graph SSE → GraphViewer attribute (Plan 7 Task 4)

Body carries data-on-load='@get(/graph)' Datastar directive. Handler's
/graph route returns SSE with a single patch-attributes event updating
the <graph-viewer> element's graph-data attribute with the JSON-stringified
cytoscape graph.

GraphViewer's attributeChangedCallback fires → cytoscape resets and
dagre lays out. Verified end-to-end against fukan-on-fukan: 612 nodes,
158 edges, layout completes in <Xs.

compute-graph-payload helper extracted from handler for testability.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 5: Selection sync — click node → /sidebar?id=X

**Files:**
- Modify: `src/fukan/web/views/shell.clj` (add Datastar event listeners)
- Modify: `src/fukan/web/handler.clj` (`/sidebar` returns Datastar SSE patch-elements)
- Modify: `resources/public/app.js` (no changes — events already emitted)

When a node is clicked, the GraphViewer emits `graph-select` with `{nodeId}`. Datastar wires this to `GET /sidebar?id=X`. The handler returns an SSE patch-elements event that replaces `<aside id="sidebar">`'s contents with the rendered entity HTML.

### Step 5.1: Add Datastar binding in shell

In `render-body`:

```clojure
[:div#app {:data-on-graph-select__window
           "@get(`/sidebar?id=${event.detail.nodeId}`)"}
 ...]
```

Datastar's `data-on-<event>__window` listens on the window for CustomEvents bubbling up from the GraphViewer.

### Step 5.2: Implement handler change

`handle-sidebar` already exists from Plan 6 — but it returns plain HTML. Change to SSE:

```clojure
(defn- handle-sidebar [req]
  (let [id (get-in req [:query-params "id"])
        m (infra-model/get-model)]
    (if (and m id)
      (d*hk/->sse-response req
        {:on-open
         (fn [sse]
           (let [html (views.sidebar/render-entity-panel m id)]
             (d*/patch-elements! sse html)
             (d*/close-sse! sse)))})
      (bad-request "missing id or model"))))
```

`render-entity-panel` is a new helper to add in Task 6. For Task 5, it can stub to `(str "<aside id='sidebar'>id: " id "</aside>")`.

### Step 5.3: Manual smoke

REPL → load model → open browser. Click any node. Sidebar replaces its contents with `id: <node-id>`. Confirm DevTools Network tab shows the `/sidebar?id=X` request firing.

### Step 5.4: Commit

```bash
jj desc -m "feat(web): selection sync — graph-select → /sidebar (Plan 7 Task 5)

When a node is tapped, the GraphViewer emits graph-select via CustomEvent
bubbling. Datastar binding in the shell listens on the window for this
event and fires GET /sidebar?id=<nodeId>.

The handler returns SSE patch-elements replacing the <aside id='sidebar'>
contents. Task 6 fills in render-entity-panel with real primitive +
artifact details; for now it's a stub showing the selected id.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: Sidebar render — primitive details + related edges

**Files:**
- Modify: `src/fukan/web/views/sidebar.clj`
- Create: `test/fukan/web/views/sidebar_test.clj`

Replace the stub `render-entity-panel` with a real renderer for primitives:

```
<aside id="sidebar">
  <h3>{label}</h3>
  <p><strong>Kind:</strong> {kind}</p>
  <p><strong>Allium kind:</strong> {allium-kind}</p>
  <p>{description}</p>
  <section>
    <h4>Intent</h4>
    <ul>
      <li>{clause-1}</li>
      ...
    </ul>
  </section>
  <section>
    <h4>Related (outgoing)</h4>
    <div class="related-edge"><span class="kind">triggers</span> → {target-label}</div>
    ...
  </section>
</aside>
```

For artifacts, see Task 7. The Task 6 function dispatches on whether the id is in `:primitives` or `:artifacts`.

### Step 6.1: Test

Create `test/fukan/web/views/sidebar_test.clj`:

```clojure
(ns fukan.web.views.sidebar-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [fukan.web.views.sidebar :as sidebar]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]))

(deftest render-entity-panel-primitive
  (let [model (-> (build/empty-model)
                  (build/add-primitive
                    (p/make-rule {:id "m::R" :label "R"
                                  :description "checks a thing"})))
        html (sidebar/render-entity-panel model "m::R")]
    (is (str/includes? html "R"))
    (is (str/includes? html "primitive/rule"))
    (is (str/includes? html "checks a thing"))))

(deftest render-entity-panel-with-related-edge
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-primitive
                    (p/make-event {:id "m::E" :label "E" :parameters []}))
                  (build/add-edge (r/make-edge :relation/emits
                                               (r/primitive-ref "m::R")
                                               (r/primitive-ref "m::E"))))
        html (sidebar/render-entity-panel model "m::R")]
    (is (str/includes? html "relation/emits"))
    (is (str/includes? html "E"))))

(deftest render-entity-panel-unknown-id
  (let [model (build/empty-model)
        html (sidebar/render-entity-panel model "nonsense")]
    (is (str/includes? html "not found"))))
```

### Step 6.2: Implement

Replace `src/fukan/web/views/sidebar.clj`:

```clojure
(ns fukan.web.views.sidebar
  "Entity-detail sidebar renderer (Plan 7 rewrite).

   Two paths:
   - Primitive: id/label/kind/description/intent/related-outgoing-edges.
   - Artifact: qualified-name/source-location/incoming projects edges.

   Both render into <aside id='sidebar'> as a hiccup HTML string,
   returned by the /sidebar handler via Datastar patch-elements."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]))

(defn- kw->str [k] (when k (subs (str k) 1)))

(defn- outgoing-edges [model id]
  (filter (fn [e]
            (and (= :endpoint/primitive (-> e :from :case))
                 (= id (-> e :from :id))))
          (:edges model)))

(defn- target-label [model edge]
  (let [to (:to edge)]
    (case (:case to)
      :endpoint/primitive (or (get-in model [:primitives (:id to) :label]) (:id to))
      :endpoint/substrate (:container to)
      :endpoint/artifact  (let [[_ _ qname] (:id to)] qname))))

(defn- render-primitive [model id primitive]
  [:aside#sidebar
   [:h3 (:label primitive)]
   [:p [:strong "ID: "] [:code id]]
   [:p [:strong "Kind: "] (kw->str (:kind primitive))]
   (when-let [desc (:description primitive)]
     [:p desc])
   (when-let [intent (:intent primitive)]
     [:section
      [:h4 "Intent"]
      (when (seq (:clauses intent))
        [:ul (for [c (:clauses intent)] [:li (pr-str c)])])])
   (let [edges (outgoing-edges model id)]
     (when (seq edges)
       [:section
        [:h4 "Related (outgoing)"]
        (for [e edges]
          [:div.related-edge
           [:span.kind (kw->str (:kind e))]
           " → "
           [:span (target-label model e)]
           (when (:validity e)
             [:span {:style "margin-left:6px;color:#888"}
              "(" (kw->str (:validity e)) ")"])])]))])

(defn- render-artifact [model id artifact]
  (let [[case-kw _ qname] id
        ;; incoming projects edges
        incoming (filter (fn [e]
                           (and (= :relation/projects (:kind e))
                                (= :endpoint/artifact (-> e :to :case))
                                (= id (-> e :to :id))))
                         (:edges model))]
    [:aside#sidebar
     [:h3 qname]
     [:p [:strong "Kind: "] (kw->str case-kw)]
     (when-let [loc (get-in artifact [:sub :source-location])]
       [:p [:strong "Source: "] (str (:file loc) (when (:line loc) (str ":" (:line loc))))])
     (when (seq incoming)
       [:section
        [:h4 "Projected by"]
        (for [e incoming
              :let [from-id (-> e :from :id)
                    from-label (or (get-in model [:primitives from-id :label]) from-id)]]
          [:div.related-edge
           [:span from-label]
           " ("
           (kw->str (:projection-kind e))
           ", "
           (kw->str (:validity e))
           ")"])])]))

(defn render-entity-panel
  "Render the sidebar HTML for a primitive or artifact id. id can be:
   - A primitive id (string in :primitives map)
   - An artifact node id ('artifact:<case>:<language>:<qname>' string from
     projection.core), which we parse back to the identity tuple"
  [model id]
  (let [primitive (get-in model [:primitives id])
        artifact-id (when (str/starts-with? id "artifact:")
                      (let [[_ case-name language qname]
                            (str/split id #":" 4)]
                        [(keyword case-name) language qname]))
        artifact (when artifact-id
                   (get-in model [:artifacts artifact-id]))]
    (str (h/html
           (cond
             primitive (render-primitive model id primitive)
             artifact  (render-artifact model artifact-id artifact)
             :else     [:aside#sidebar
                        [:p {:style "color:#888"}
                         (str "Entity " id " not found.")]])))))

;; Plan 6 legacy: render-entity is the unqualified-shape helper used
;; by the Plan-6 handler; preserved for backward compatibility with the
;; old /sidebar payload until Task 5's SSE migration completes.
(defn render-entity [entity]
  (str (h/html [:div.entity-panel
                [:h3 (or (:label entity) (:id entity))]
                [:p [:strong "Kind: "] (str (:kind entity))]
                (when (:description entity) [:p (:description entity)])])))
```

### Step 6.3: Wire handler to call render-entity-panel

In `handler.clj`'s `handle-sidebar`, replace the Task-5 stub with:

```clojure
(let [html (views.sidebar/render-entity-panel m id)]
  (d*/patch-elements! sse html)
  ...)
```

### Step 6.4: Run, expect pass + manual smoke

Expected: 424 + 3 = **427/0/0**.

Manual: REPL → load model → click `fukan/model/spec::Container` in browser. Sidebar should show:
- `Container` heading
- Kind: `primitive/container`
- Outgoing edges section (depends on corpus state)

### Step 6.5: Commit

```bash
jj desc -m "feat(web/views): sidebar render — primitive details + related edges (Plan 7 Task 6)

render-entity-panel dispatches on whether the id is a primitive or an
artifact (parsed from 'artifact:<case>:<language>:<qname>' prefix).
Primitive render: id/label/kind/description/intent clauses/outgoing
edges denormalised with target labels + validity badges where present.
Artifact render: qualified-name/source-location/incoming projects edges
(by which primitive they're bound).

Hiccup-rendered HTML returned from /sidebar via Datastar patch-elements.

Plan 6's render-entity helper is preserved for backward compatibility
but is no longer called by the handler — Task 7 may remove it once we
confirm nothing else depends on it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: Sidebar render — artifact details + source-location link

**Files:**
- Modify: `src/fukan/web/views/sidebar.clj`
- Modify: `test/fukan/web/views/sidebar_test.clj`

Task 6 already includes `render-artifact`. Task 7 polishes it:

1. **Source-location link** — if `:source-location.file` exists, render as `<a>` with a `file://` scheme link (best-effort; some browsers block these, but copy-pasting works).
2. **Unprojected indicator** — when an artifact has NO incoming projects edges (Plan 6 Task 13's case), surface that clearly: "Unbound — no spec primitive projects to this artifact."
3. **Test for artifact path** — add a `render-entity-panel-artifact` test.

### Step 7.1: Test

Add to `test/fukan/web/views/sidebar_test.clj`:

```clojure
(deftest render-entity-panel-artifact-with-projects
  (let [artifact {:case :artifact/code
                  :language "clojure"
                  :sub {:case :code/function
                        :qualified-name "m/foo"
                        :source-location {:file "src/m.clj" :line 42}}}
        model (-> (build/empty-model)
                  (build/add-primitive
                    (p/make-rule {:id "m::Foo" :label "Foo"}))
                  (assoc-in [:artifacts [:code/function "clojure" "m/foo"]] artifact)
                  (build/add-edge
                    (-> (r/make-edge :relation/projects
                                     (r/primitive-ref "m::Foo")
                                     (r/artifact-ref [:code/function "clojure" "m/foo"])
                                     {:projection-kind :projection-kind/rule})
                        (assoc :validity :valid))))
        html (sidebar/render-entity-panel model "artifact:code/function:clojure:m/foo")]
    (is (str/includes? html "m/foo"))
    (is (str/includes? html "src/m.clj:42"))
    (is (str/includes? html "Foo"))))

(deftest render-entity-panel-unbound-artifact
  (let [artifact {:case :artifact/code :language "clojure"
                  :sub {:case :code/function :qualified-name "m/helper"
                        :source-location {:file "src/m.clj"}}}
        model (-> (build/empty-model)
                  (assoc-in [:artifacts [:code/function "clojure" "m/helper"]] artifact))
        html (sidebar/render-entity-panel model "artifact:code/function:clojure:m/helper")]
    (is (str/includes? html "Unbound"))
    (is (str/includes? html "no spec primitive"))))
```

### Step 7.2: Implement source-location + unbound

Update `render-artifact` in `sidebar.clj`:

```clojure
(defn- render-artifact [model id artifact]
  (let [[case-kw _ qname] id
        incoming (filter (fn [e]
                           (and (= :relation/projects (:kind e))
                                (= :endpoint/artifact (-> e :to :case))
                                (= id (-> e :to :id))))
                         (:edges model))
        loc (get-in artifact [:sub :source-location])]
    [:aside#sidebar
     [:h3 qname]
     [:p [:strong "Kind: "] (kw->str case-kw)]
     (when loc
       [:p [:strong "Source: "]
        [:a {:href (str "file://" (:file loc))}
         (str (:file loc) (when (:line loc) (str ":" (:line loc))))]])
     (if (seq incoming)
       [:section
        [:h4 "Projected by"]
        (for [e incoming
              :let [from-id (-> e :from :id)
                    from-label (or (get-in model [:primitives from-id :label]) from-id)]]
          [:div.related-edge
           [:span from-label]
           " ("
           (kw->str (:projection-kind e))
           ", "
           (kw->str (:validity e))
           ")"])]
       [:section
        [:p {:style "color:#888;font-style:italic"}
         "Unbound — no spec primitive projects to this artifact."]])]))
```

### Step 7.3: Run, expect pass + commit

Expected: 427 + 2 = **429/0/0**.

```bash
jj desc -m "feat(web/views): artifact sidebar — source location + unbound marker (Plan 7 Task 7)

render-artifact extends with:
- Source-location rendered as a file:// link (best-effort; browsers may
  block but copy/paste works).
- Unbound indicator when no :relation/projects edge points to the artifact,
  surfacing the Plan-6-Task-13 unprojected case clearly.

Closes the sidebar render path for both node kinds.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 8: Drift-marker click → /projector → Blueprint modal

**Files:**
- Modify: `src/fukan/web/views/shell.clj` (add Datastar listener for graph-edge-select)
- Modify: `src/fukan/web/handler.clj` (`/projector` returns SSE patch-elements for the modal body, or stays JSON and the client fetches + renders)
- Modify: `resources/public/app.js` (markdown helper + modal open)

The flow:
1. User clicks a `[drift="absent"]` edge in cytoscape.
2. GraphViewer emits `graph-edge-select` with `{source, projectionKind, drift}`.
3. Client-side: if `drift === "absent"`, fetch `/projector?primitive-id=<source>&projection-kind=<projectionKind>` (JSON).
4. Parse response; convert `rendered.markdown` to HTML via a small markdown helper.
5. Mount HTML into `<div id="blueprint-modal-body">`; call `dialog.showModal()`.

Server-side SSE flow is overkill here — `/projector` already returns JSON in Plan 6 and works fine for this. Keep it as JSON.

### Step 8.1: Markdown helper in app.js

Add to `app.js` (top-level helper, NOT inside the class):

```js
// Minimal markdown → HTML conversion for Blueprint output.
// Covers headers, code blocks, inline code, lists, paragraphs.
function renderMarkdown(md) {
  if (!md) return '';
  let html = md;
  // Code blocks (must come before inline code)
  html = html.replace(/```([a-z]*)\n([\s\S]*?)```/g, (_, lang, code) =>
    `<pre><code class="lang-${lang}">${escapeHtml(code)}</code></pre>`);
  // Headers
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');
  // List items
  html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
  html = html.replace(/(<li>.+<\/li>\n?)+/g, '<ul>$&</ul>');
  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
  // Bold
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  // Paragraphs (consecutive non-empty lines that didn't get caught above)
  html = html.split(/\n\n+/).map(block => {
    if (block.match(/^<(h\d|pre|ul|li)/)) return block;
    if (block.trim() === '') return '';
    return `<p>${block.trim()}</p>`;
  }).join('\n');
  return html;
}

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
```

### Step 8.2: Modal-open client logic

In `app.js`, after the class definition, add a listener that fires on `graph-edge-select`:

```js
window.addEventListener('graph-edge-select', async (evt) => {
  const { source, projectionKind, drift } = evt.detail;
  if (drift !== 'absent' || !projectionKind) return;

  const url = `/projector?primitive-id=${encodeURIComponent(source)}` +
              `&projection-kind=${encodeURIComponent(projectionKind)}`;
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`projector returned ${res.status}`);
    const bp = await res.json();
    const md = bp?.rendered?.markdown || '(no markdown rendered)';
    document.getElementById('blueprint-modal-body').innerHTML = renderMarkdown(md);
    document.getElementById('blueprint-modal').showModal();
  } catch (e) {
    console.error('[blueprint] fetch failed:', e);
    alert(`Failed to fetch Blueprint: ${e.message}`);
  }
});
```

### Step 8.3: Verify /projector still returns plain JSON

Plan 6 wired `/projector` as plain JSON. No change needed unless you've migrated it to SSE. Confirm:

```
curl -s 'http://localhost:8080/projector?primitive-id=fukan/infra/spec::load_model&projection-kind=projection-kind/operation' | head -c 200
```

Expected: JSON beginning with `{"primitive-id":"fukan/infra/spec::load_model"...`.

### Step 8.4: Manual smoke

REPL → load model → open browser → wait for graph → click any red drift edge. Modal opens with rendered Blueprint markdown.

### Step 8.5: Commit

```bash
jj desc -m "feat(web/frontend): drift-marker click → Blueprint modal (Plan 7 Task 8)

Replaces Plan 6's console.log affordance with a full modal flow:
1. User clicks an absent drift edge.
2. GraphViewer emits graph-edge-select with {source, projectionKind, drift}.
3. Client-side fetches /projector?primitive-id=X&projection-kind=Y (JSON).
4. Blueprint :rendered.markdown converted to HTML via 30-line helper
   (covers headers, code blocks, lists, inline code, paragraphs).
5. Modal opens via dialog.showModal() with the rendered markdown.

Realises VISION.md's 'clicking a red absent drift marker summons the
Projector' — finally, end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 9: Edge-kind filter UI

**Files:**
- Modify: `resources/public/app.js` (add `_createFilterToolbar` method)

Toolbar in the top-right corner of `<graph-viewer>` with 13 checkboxes — one per relation kind. Plus a "drift only" toggle that hides all edges except `[drift="absent"]` ones (handy for triage). State held in component instance.

### Step 9.1: Implement

In `app.js`, add a `_createFilterToolbar` method to the GraphViewer class:

```js
  _createFilterToolbar() {
    const toolbar = document.createElement('div');
    toolbar.className = 'filter-toolbar';

    const driftWrap = document.createElement('label');
    const driftCheckbox = document.createElement('input');
    driftCheckbox.type = 'checkbox';
    driftCheckbox.addEventListener('change', () => {
      this._driftOnly = driftCheckbox.checked;
      this._applyEdgeFilter();
    });
    driftWrap.appendChild(driftCheckbox);
    driftWrap.appendChild(document.createTextNode(' drift only'));
    toolbar.appendChild(driftWrap);

    toolbar.appendChild(document.createElement('hr'));

    const kinds = [
      'relation/triggers', 'relation/observes', 'relation/reads',
      'relation/writes', 'relation/creates', 'relation/destroys',
      'relation/emits', 'relation/realises', 'relation/specialises',
      'relation/uses', 'relation/exposes', 'relation/provides',
      'relation/projects'
    ];
    for (const kind of kinds) {
      const wrap = document.createElement('label');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = true;
      cb.addEventListener('change', () => {
        this.setEdgeKindVisible(kind, cb.checked);
      });
      wrap.appendChild(cb);
      wrap.appendChild(document.createTextNode(' ' + kind.replace('relation/', '')));
      toolbar.appendChild(wrap);
    }

    this.appendChild(toolbar);
  }
```

Call from `connectedCallback`:

```js
    this._bindCytoscapeEvents();
    this._createFilterToolbar();
```

Extend `_applyEdgeFilter` to respect `_driftOnly`:

```js
  _applyEdgeFilter() {
    const cy = this.cy;
    cy.edges().forEach(e => {
      const kindVisible = this.visibleEdgeKinds.has(e.data('edgeType'));
      const driftVisible = !this._driftOnly || e.data('drift') === 'absent';
      const visible = kindVisible && driftVisible;
      if (visible) e.removeClass('filtered-out');
      else e.addClass('filtered-out');
    });
  }
```

### Step 9.2: Manual smoke

REPL → load model → open browser → graph renders. Toolbar appears top-right. Uncheck `:projects` → projects edges disappear (the bulk of edges). Check "drift only" → only red drift edges show.

### Step 9.3: Commit

```bash
jj desc -m "feat(web/frontend): edge-kind filter toolbar (Plan 7 Task 9)

Adds a top-right toolbar in <graph-viewer> with 13 checkboxes (one per
relation kind) + a 'drift only' toggle. Client-only filter via
.filtered-out class; no server round-trip.

drift-only mode masks every edge except [drift='absent'] ones — useful
for triage workflows ('what's missing in this module').

State held in component instance (per-tab). Persistence across reloads
is out of MVP scope.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 10: Browser smoke + UX walk-through

**Files:**
- Create (or update): `doc/SMOKE.md` — a manual smoke-check checklist for end-to-end verification.

No code changes. Document the end-to-end workflow:

1. Start REPL: `clojure -M:dev`.
2. Load model: `(load-model "src")`.
3. Start server: `(start-server!)`.
4. Open `http://localhost:8080/`.
5. Verify:
   - Graph renders within 5–10s.
   - DAGRE layout fits to viewport.
   - All thirteen node-kind shapes are visible (use the filter toolbar to scan).
   - Click a primitive → sidebar populates with details + related edges.
   - Click an artifact → sidebar shows qualified-name + source-location + unbound/projected-by section.
   - Click a red drift edge → modal opens with Blueprint markdown.
   - Toggle "drift only" → only red edges visible.
   - Uncheck `:projects` in toolbar → ~half the edges disappear.

Document any rough edges discovered + recommended follow-ups.

### Step 10.1: Run the full smoke

Walk through the checklist. Note observations.

### Step 10.2: Write `doc/SMOKE.md`

Capture the manual smoke procedure for future re-runs. Include observed performance numbers (layout time, click→sidebar latency) + screenshots if helpful.

### Step 10.3: Final commit

```bash
jj desc -m "doc: end-to-end smoke procedure for the Explorer (Plan 7 Task 10)

Captures the manual verification workflow that closes Plan 7. Future
contributors run through this checklist to confirm the Explorer is
healthy end-to-end against the current corpus.

Closes Plan 7. Plan 5's Projector + Plan 6's Explorer substrate are
now usable through the browser; clicking an absent drift marker summons
the Projector and surfaces the Blueprint per VISION.md.

What's still deferred (per the Lean scope):
- LLM API wiring + diff/accept-reject UI.
- Cmd+K finder + container drill-in + sidebar backlinks.
- Default-hide of unbound artifacts.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing all 10 tasks, verify before declaring Plan 7 done:

1. **Shell rewrite complete** — `views/shell.clj` is the Datastar SPA frame (Task 1).
2. **GraphViewer rebuilt** — `app.js` operates on new-model graph data (Task 2); cytoscape stylesheet covers all primitive + artifact + relation kinds (Task 3).
3. **Initial-load works** — opening `/` loads the corpus graph (Task 4).
4. **Selection sync works** — clicking a node populates the sidebar (Tasks 5–7).
5. **Drift markers actionable** — clicking a red edge opens the Blueprint modal (Task 8).
6. **Filter toolbar works** — 13 relation kinds + "drift only" toggle (Task 9).
7. **End-to-end smoke documented** (Task 10).
8. **Test suite green** — `clj -M:test` zero failures (target around **429/0/0** depending on test deletions/additions).
9. **OLD UI surface retired** — `breadcrumb.clj`, `schema.clj`, OLD `sidebar.clj` body all gone.
10. **No new dependencies** — verify `deps.edn` unchanged.

If any check fails, fix in place — do NOT declare Plan 7 done with open issues.

---

## What this plan does NOT cover

Per the Lean scope decision:

- **LLM API wiring.** Anthropic API client + `/generate` endpoint + diff/accept-reject + write-back. The Blueprint already drives this; the API plumbing is its own design surface. Future plan.
- **Cmd+K / search finder.** Primitive lookup by id/label. Probably a 2-3 task addition. Future plan.
- **Container drill-in navigation.** "Show only this module's children." Requires sidebar navigation buttons + URL state. Future plan.
- **Sidebar backlinks.** Incoming edges in the primitive sidebar. Trivial extension; deferred for now.
- **Default-hide of unbound artifacts.** Lean Plan 7 ships all 612 nodes by default. Performance tuning (default mask + opt-in toggle) waits for a forcing example.
- **Tag-applied projection scoping** (per DESIGN.md "Explorer" line 738). Viewing the graph through one methodology namespace's lens (e.g. "show only Allium-tagged content"). Future plan.
- **Persistent UI state** across page reloads. Filter toolbar state resets per tab.
- **Performance optimisation for very large graphs** (10k+ nodes). The new model is 600+ nodes; if future corpora grow much larger, layout + render will need attention. Not a Plan 7 concern.
- **Server-side render fallback.** Plan 7 requires JS; static rendering is out of scope.
