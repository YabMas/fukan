# View & Interaction Specification

## Purpose

Pure renderers: `projection data + editor state -> HTML/JSON`.

Views receive pre-computed projection data and editor state, then produce visual output. They do not fetch data or compute projections.

## Editor State

The editor maintains state that drives both projection and rendering:

**Projection inputs** (determine what's visible):
- `view-id`: The container being viewed (`nil` = root)
- `expanded-containers`: Set of container IDs where private children are visible

**Rendering inputs** (determine how it's displayed):
- `selected-id`: Currently highlighted node (for sidebar details and edge highlighting)
- `schema-id`: Currently selected schema (for IO detail panel)

## Interaction Model

### Selection vs Navigation

The UI distinguishes between **selection** (highlighting within current view) and **navigation** (changing the focused container).

| Action | Behavior |
|--------|----------|
| **Single click** | Select node, highlight its direct edges. Focus stays on current container. |
| **Double click** | Navigate into the node (shift focus) — only if node has children. |
| **Right click** | Toggle private visibility for containers with private children. |
| **Breadcrumb click** | Navigate to that ancestor container. |

### Single Click: Selection

When a node is single-clicked:
1. The node is visually selected (highlighted border)
2. All edges involving that node are highlighted
3. The sidebar shows details for that node
4. The focused container does not change

### Double Click: Navigation

When a node is double-clicked:
- **If the node has children**: Navigate to show that container's internal structure
- **If the node has no children**: Treated as single click (no navigation)

After navigation, the new container becomes the focused entity:
- Its children are shown as nodes
- Sibling edges between children are visible
- No node is selected until the user clicks one

### Breadcrumb

The breadcrumb shows the path from smart-root to the focused container. Clicking any breadcrumb item navigates to that container.

### Summary

- **Single click** = "peek" (see relationships without changing context)
- **Double click** = "dive in" (change focus to explore deeper)
- **Right click** = "reveal/hide" (toggle private children visibility)
- **Breadcrumb** = "zoom out" (navigate back up the hierarchy)

## Visual Indicators

- **Double border**: Container has private children that are currently hidden
- **Dashed border + gray background**: Private leaf node (when visible)
- **Solid border**: Container is expanded (showing all children) or has no private children

## Rendering Pipeline

```
projection domain data -> add UI state (selected?) -> cytoscape transform
```

The view layer adds UI-specific state (`selected?`) to projection nodes, then transforms the result into the visualization format. Edge highlighting is driven entirely by the `highlightedEdges` array in the Cytoscape output, computed from the selected node's edges (with schema-key matching for schema nodes).

## Entity Detail Sidebar

The sidebar renders a **normalized entity detail** structure from the projection layer. One generic renderer handles all non-edge entity kinds; edges have a dedicated renderer.

### Section order (non-edge entities)

1. **Label** — entity name + kind badge
2. **Description** — from doc, contract description, or data description (when available)
3. **Interface** — dispatches by `:type` to sub-renderers (when available). For container entities with `:fn-list` interface type, dataflow IO types (inputs/outputs) are rendered as subsections within the Public API heading, before the functions list.
4. **Schemas** — clickable schema references (when available)
5. **Dependencies** — entities this one depends on (always, shows "None" if empty)
6. **Dependents** — entities that depend on this one (always, shows "None" if empty)

### Edge entities

Edges show: label + called functions list. They use shared components (`render-fn-list`) but have a dedicated layout.

### Shared components

- `render-fn-list` — function list with optional signatures and click handlers
- `render-dep-list` — dependency/dependent section with heading and counts
- `render-description` — description text block
- `render-schema-refs` — clickable schema reference list
- `render-interface` — dispatches to sub-renderers by interface type
