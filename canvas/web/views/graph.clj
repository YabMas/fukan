(ns canvas.web.views.graph
  "Canvas port of web/views/graph.allium + graph.boundary.

   Coverage:
     - value ViewState          → construction/record (3 fields)
     - value NavigationState    → construction/record (2 fields)
     - surface GraphViewer      → 5 invariants (DataInEventsOut, ViewStateOwnership,
                                   RenderModeDetection, AnimatedTransition, NodeVisibility)
     - 8 interaction rules      → vocab.behavioral/rule each
     - invariant RenderingPurity  → vocab.behavioral/invariant
     - invariant AtomicUpdate     → vocab.behavioral/invariant
     - invariant GraphSelectionDefault → vocab.behavioral/invariant
     - fn render_graph          → construction/function (boundary)
     - exports: ViewState, NavigationState

   Notes:
     - Cross-module refs: :cytoscape/CytoscapeGraph, :projection/Projection.
     - EditorState is an opaque local type."
  (:require [fukan.canvas.core.helpers :as h]
            [fukan.canvas.construction :refer [record function exports]]
            [fukan.canvas.vocab.behavioral :refer [invariant rule]]))

(defn ^:export build-canvas []
  (h/with-canvas
    (h/within-module "web.views.graph"

      ;; ── Value Types ──────────────────────────────────────────────────────

      (record "ViewState"
        "Client-owned graph state. Lives inside the GraphViewer component
         and is included in every outbound event so the server can use it
         for projection computation. The server never writes these values
         back — the component is the sole owner."
        (field expanded           (set-of :String))
        (field show_private       (set-of :String))
        (field visible_edge_types (set-of :String)))

      (record "NavigationState"
        "Server-owned navigation state. Determines what entity is being
         viewed and what is selected. Updated by SSE responses."
        (field view_id     (optional :String))
        (field selected_id (optional :String)))

      ;; ── GraphViewer surface (guarantees) ─────────────────────────────────

      (invariant "DataInEventsOut"
        "The component never initiates network requests. All data arrives
         through the graph-data attribute. All interactions are emitted as
         CustomEvents. The parent context translates events into SSE
         requests and SSE responses into signal updates."
        (holds-that "component-never-initiates-network-requests"))

      (invariant "ViewStateOwnership"
        "The component owns expanded, show_private, and visible_edge_types.
         It reads initial values from the graph data and maintains them
         across interactions. Every outbound event includes the current
         ViewState via evt.detail.viewState."
        (holds-that "component-owns-view-state"))

      (invariant "RenderModeDetection"
        "The component determines render mode internally by comparing
         incoming graph data to current state: structural change → full
         rebuild; same structure → incremental. For structural changes:
         root node changed → navigate; root unchanged → expand/collapse."
        (holds-that "component-determines-render-mode"))

      (invariant "AnimatedTransition"
        "Expand/collapse uses incremental diff rendering
         (old positions → dagre → animate to new positions) for spatial
         continuity."
        (holds-that "expand-collapse-animated"))

      (invariant "NodeVisibility"
        "The active edge mode controls which leaf node kinds are visible:
         code_flow → functions visible, schemas hidden;
         schema_reference → schemas visible, functions hidden.
         Module nodes are always visible. The sidebar is unaffected."
        (holds-that "edge-mode-controls-node-visibility"))

      ;; ── Interaction Rules ─────────────────────────────────────────────────

      (rule "SelectNode"
        "Single click on graph node or sidebar link. Sets nav.selected_id
         to the effective selection: node_id ?? nav.view_id ?? first root
         node in projection. Sidebar shows details; edges involving the
         selection are highlighted."
        (when SelectNode (node_id :String)))

      (rule "NavigateToNode"
        "Double click on an expandable graph node. Only nodes with children
         navigate. Sets nav.view_id = node_id and clears nav.selected_id.
         GraphViewer resets its expanded and show_private sets to empty."
        (when NavigateToNode (node_id :String)))

      (rule "NavigateToAncestor"
        "Breadcrumb click. Navigates to an ancestor module, setting
         nav.view_id = ancestor_id and clearing nav.selected_id."
        (when NavigateToAncestor (ancestor_id (optional :String))))

      (rule "ExpandToggle"
        "Right click on a module node. The GraphViewer component toggles the
         module in its expanded set before emitting. The server re-projects
         with the updated expanded set."
        (when ExpandToggle (module_id :String)))

      (rule "TogglePrivateVisibility"
        "Shift+right click on an expanded module. The component toggles the
         module in its show_private set. The server re-projects with the
         updated show_private set."
        (when TogglePrivateVisibility (module_id :String)))

      (rule "SelectEdgeMode"
        "Edge mode selector click. Single-select: sets visible_edge_types to
         {edge_type}, replacing the previous mode. The server re-projects
         with the filtered node set."
        (when SelectEdgeMode (edge_type :String)))

      (rule "SelectEdge"
        "Click on an edge. The sidebar shows edge details; the edge is
         highlighted. Edge identity is derived from source, target, and type."
        (when SelectEdge
          (source    :String)
          (target    :String)
          (edge_type :String)))

      (rule "Deselect"
        "Click on background. Clears nav.selected_id. Sidebar returns to
         empty state; no edges are highlighted."
        (when Deselect))

      ;; ── Rendering Invariants ──────────────────────────────────────────────

      (invariant "RenderingPurity"
        "Views receive pre-computed projection data and editor state,
         produce HTML/JSON output, and never fetch data or compute
         projections."
        (holds-that "views-are-pure-renderers"))

      (invariant "AtomicUpdate"
        "Each SSE response delivers breadcrumb HTML, sidebar HTML, and
         graph data signal as a single stream. All three update together
         — no partial states."
        (holds-that "sse-response-is-atomic"))

      (invariant "GraphSelectionDefault"
        "When render_graph receives no explicit selected_id, the effective
         selection falls back first to nav.view_id, then to the first root
         node in the projection. selected flag propagated per-node;
         highlighted_edges computed from the effective selection."
        (holds-that "selection-default-fallback-chain"))

      ;; ── Boundary Functions ────────────────────────────────────────────────

      (function "render_graph"
        "Transform projection data into CytoscapeGraph output."
        (takes [projection :projection/Projection]
               [editor_state :EditorState])
        (gives :cytoscape/CytoscapeGraph))

      ;; ── Exports ───────────────────────────────────────────────────────────

      (exports ViewState NavigationState))))
