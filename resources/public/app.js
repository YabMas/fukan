// Fukan - Interactive Clojure codebase visualizer
// Simplified version using Datastar for state management

// -----------------------------------------------------------------------------
// Expanded Modules State (for private visibility toggle)

let expandedModules = new Set();
let showPrivate = new Set();
let visibleEdgeTypes = new Set(['code-flow', 'schema-reference']);
let pendingAction = 'navigate'; // 'navigate' | 'expand-toggle'
let toggleTargetId = null;       // node ID being expanded/collapsed

// Get expanded modules as a comma-separated string for URL params
window.getExpandedParam = function() {
  return Array.from(expandedModules).join(',');
};

// Get show-private modules as a comma-separated string for URL params
window.getShowPrivateParam = function() {
  return Array.from(showPrivate).join(',');
};

// Get visible edge types as a comma-separated string for URL params
window.getVisibleEdgeTypesParam = function() {
  return Array.from(visibleEdgeTypes).join(',');
};

// -----------------------------------------------------------------------------
// URL Sync for Deep Linking
//
// URL structure: /?id=<view>&select=<selected>&schema=<schema>
// - id: The current view (module being displayed in graph)
// - select: The selected node (shown in sidebar)
// - schema: The schema being viewed (e.g., "fukan.model/Model")

let skipNextUrlUpdate = false;
let currentViewId = '';
let currentSelectId = '';
let currentSchemaId = '';

// Build URL from current state
function buildUrl() {
  const params = new URLSearchParams();
  if (currentViewId) params.set('id', currentViewId);
  if (currentSelectId) params.set('select', currentSelectId);
  if (currentSchemaId) params.set('schema', currentSchemaId);
  const query = params.toString();
  return query ? '/?' + query : '/';
}

// Update browser URL without triggering navigation
window.updateUrl = function(viewId, selectId, schemaId) {
  if (skipNextUrlUpdate) {
    skipNextUrlUpdate = false;
    return;
  }
  // Update current state
  if (viewId !== undefined) currentViewId = viewId || '';
  if (selectId !== undefined) currentSelectId = selectId || '';
  if (schemaId !== undefined) currentSchemaId = schemaId || '';

  const url = buildUrl();
  if (window.location.pathname + window.location.search !== url) {
    history.pushState({ viewId: currentViewId, selectId: currentSelectId, schemaId: currentSchemaId }, '', url);
  }
};

// Update just the view ID (called from SSE after navigation)
window.updateViewUrl = function(viewId) {
  window.updateUrl(viewId, viewId, ''); // Navigation clears schema, sets select to view
};

// Update just the selection (called from SSE after sidebar update)
window.updateSelectUrl = function(selectId) {
  window.updateUrl(undefined, selectId, ''); // Selection clears schema
};

// Update to show schema (called from SSE when viewing schema)
window.updateSchemaUrl = function(schemaId) {
  window.updateUrl(undefined, undefined, schemaId);
};

// Handle browser back/forward
window.addEventListener('popstate', (event) => {
  const params = new URLSearchParams(window.location.search);
  const viewId = event.state?.viewId || params.get('id') || '';
  const selectId = event.state?.selectId || params.get('select') || '';
  const schemaId = event.state?.schemaId || params.get('schema') || '';

  // Update local state
  currentViewId = viewId;
  currentSelectId = selectId;
  currentSchemaId = schemaId;

  skipNextUrlUpdate = true; // Don't push to history when navigating via back/forward

  const graphPanel = document.getElementById('graph-panel');

  if (schemaId) {
    // Restore schema view
    graphPanel.dispatchEvent(new CustomEvent('cy-schema', {
      bubbles: true,
      detail: { id: schemaId }
    }));
  } else {
    // Restore main view with selection
    graphPanel.dispatchEvent(new CustomEvent('cy-navigate', {
      bubbles: true,
      detail: { id: viewId, select: selectId }
    }));
  }
});

// -----------------------------------------------------------------------------
// Cytoscape Initialization

const cy = cytoscape({
  container: document.getElementById('cy'),
  elements: [],

  style: [
    // Module node (folders and namespaces)
    {
      selector: 'node[kind="module"]',
      style: {
        'shape': 'roundrectangle',
        'background-color': '#e8f4f8',
        'border-color': '#2980b9',
        'border-width': 2,
        'border-style': 'solid',
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'text-max-width': '160px',
        'padding': '20px',
        'font-weight': 'bold',
        'font-size': '13px',
        'color': '#2c3e50'
      }
    },
    // Function node
    {
      selector: 'node[kind="function"]',
      style: {
        'shape': 'roundrectangle',
        'background-color': '#e8f4f8',
        'border-color': '#2980b9',
        'border-width': 2,
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'text-max-width': '140px',
        'padding': '15px',
        'font-size': '12px',
        'color': '#2c3e50'
      }
    },
    // External folder container (compound node)
    {
      selector: 'node[kind="external-folder"]',
      style: {
        'shape': 'roundrectangle',
        'background-color': '#f8f9fa',
        'border-color': '#bdc3c7',
        'border-width': 1,
        'border-style': 'solid',
        'label': 'data(label)',
        'text-valign': 'top',
        'text-halign': 'center',
        'text-margin-y': '10px',
        'padding': '30px',
        'font-size': '11px',
        'color': '#95a5a6',
        'font-weight': 'bold'
      }
    },
    // Hidden nodes
    {
      selector: 'node.hidden',
      style: {
        'display': 'none'
      }
    },
    // Edges - code-flow: blue to match namespace modules
    {
      selector: 'edge',
      style: {
        'width': 2,
        'line-color': '#2980b9',
        'target-arrow-color': '#2980b9',
        'target-arrow-shape': 'triangle',
        'curve-style': 'bezier',
        'arrow-scale': 0.8,
        'label': 'data(label)',
        'font-size': '10px',
        'text-background-color': '#fff',
        'text-background-opacity': 1,
        'text-background-padding': '2px'
      }
    },
    // Schema-reference edges - dashed green lines
    {
      selector: 'edge[edgeType="schema-reference"]',
      style: {
        'line-style': 'dashed',
        'line-color': '#27ae60',
        'target-arrow-color': '#27ae60',
        'target-arrow-shape': 'triangle',
        'line-dash-pattern': [6, 3],
        'width': 2,
        'curve-style': 'bezier',
        'arrow-scale': 0.8
      }
    },
    // Selected node
    {
      selector: 'node:selected',
      style: {
        'border-color': '#e74c3c',
        'border-width': 3
      }
    },
    // Module as compound parent
    {
      selector: 'node[kind="module"]:parent',
      style: {
        'text-valign': 'top',
        'text-halign': 'center',
        'text-margin-y': '18px',
        'padding': '40px',
        'padding-top': '55px'
      }
    },
    // Top-level module (no parent) - grey (context module)
    {
      selector: 'node[kind="module"]:orphan',
      style: {
        'background-color': '#ecf0f1',
        'border-color': '#bdc3c7'
      }
    },
    // Highlighted edges
    {
      selector: 'edge.highlighted',
      style: {
        'line-color': '#e74c3c',
        'target-arrow-color': '#e74c3c',
        'width': 3,
        'z-index': 1000
      }
    },
    // Containers with hidden private children (collapsed)
    {
      selector: 'node[hasPrivateChildren][!isExpanded]',
      style: {
        'border-style': 'double',
        'border-width': 4
      }
    },
    // Containers with private children expanded
    {
      selector: 'node[hasPrivateChildren][isExpanded]',
      style: {
        'border-style': 'solid',
        'border-width': 3
      }
    },
    // Private functions (shown when parent is expanded) - dashed blue border
    {
      selector: 'node[kind="function"][private]',
      style: {
        'border-style': 'dashed',
        'background-color': '#f5f5f5'
      }
    },
    // Schema node - diamond shape, green theme
    {
      selector: 'node[kind="schema"]',
      style: {
        'shape': 'diamond',
        'background-color': '#e8f5e9',
        'border-color': '#27ae60',
        'border-width': 2,
        'border-style': 'solid',
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'width': '85px',
        'height': '85px',
        'font-weight': 'bold',
        'font-size': '11px',
        'color': '#1b5e20'
      }
    },
    // Selected schema node - red border on diamond (must come after base schema style)
    {
      selector: 'node[kind="schema"]:selected',
      style: {
        'border-color': '#e74c3c',
        'border-width': 3,
        'background-color': '#ffebee'
      }
    }
  ],

  layout: { name: 'preset' },
  userZoomingEnabled: true,
  userPanningEnabled: true,
  boxSelectionEnabled: false
});

// -----------------------------------------------------------------------------
// Edge Type Toggle UI

function createEdgeTypeToggle() {
  const toolbar = document.createElement('div');
  toolbar.id = 'edge-type-toggle';
  toolbar.style.cssText = 'position:absolute;top:8px;right:8px;z-index:10;display:flex;gap:4px;';

  function makeBtn(label, type) {
    const btn = document.createElement('button');
    btn.textContent = label;
    btn.dataset.edgeType = type;
    btn.style.cssText = 'padding:4px 10px;font-size:11px;border:1px solid #bdc3c7;border-radius:4px;cursor:pointer;background:#fff;';
    if (visibleEdgeTypes.has(type)) {
      btn.style.background = type === 'code-flow' ? '#dbeaf5' : '#d5f5e3';
      btn.style.borderColor = type === 'code-flow' ? '#2980b9' : '#27ae60';
    }
    btn.addEventListener('click', () => {
      if (visibleEdgeTypes.has(type)) {
        // Don't allow removing the last type
        if (visibleEdgeTypes.size > 1) {
          visibleEdgeTypes.delete(type);
        }
      } else {
        visibleEdgeTypes.add(type);
      }
      // Update button appearance
      toolbar.querySelectorAll('button').forEach(b => {
        const t = b.dataset.edgeType;
        if (visibleEdgeTypes.has(t)) {
          b.style.background = t === 'code-flow' ? '#dbeaf5' : '#d5f5e3';
          b.style.borderColor = t === 'code-flow' ? '#2980b9' : '#27ae60';
        } else {
          b.style.background = '#fff';
          b.style.borderColor = '#bdc3c7';
        }
      });
      // Re-fetch with updated edge types
      pendingAction = 'navigate';
      const graphPanel = document.getElementById('graph-panel');
      graphPanel.dispatchEvent(new CustomEvent('cy-navigate', {
        bubbles: true,
        detail: { id: currentViewId, select: currentSelectId }
      }));
    });
    return btn;
  }

  toolbar.appendChild(makeBtn('Code Flow', 'code-flow'));
  toolbar.appendChild(makeBtn('Schema Refs', 'schema-reference'));

  const container = document.getElementById('cy').parentElement;
  container.style.position = 'relative';
  container.appendChild(toolbar);
}

createEdgeTypeToggle();

// -----------------------------------------------------------------------------
// Graph Rendering (called by backend via script tag)

// Shared dagre layout options
function dagreOptions(extraOpts) {
  return Object.assign({
    name: 'dagre',
    rankDir: 'TB',
    ranker: 'network-simplex',
    nodeSep: 80,
    rankSep: 100,
    edgeSep: 30,
    animate: false,
    fit: false,
    padding: 50,
    nodeDimensionsIncludeLabels: true,
    spacingFactor: 1.2
  }, extraOpts);
}

// Prepare incoming data: sync expand state, append indicator glyphs
function prepareGraphData(data) {
  expandedModules.clear();
  showPrivate.clear();
  data.nodes.forEach(n => {
    if (n.kind === 'module' && n.isExpanded) {
      expandedModules.add(n.id);
    }
    if (n.showingPrivate) {
      showPrivate.add(n.id);
    }
  });
  data.nodes.forEach(n => {
    if (n.kind === 'module' && n.expandable) {
      n.label = n.isExpanded ? n.label + ' \u25BC' : n.label + ' \u25B6';
    }
  });
}

// Apply highlights and selection from backend data
function applyHighlightsAndSelection(data) {
  cy.edges().removeClass('highlighted');
  if (data.highlightedEdges) {
    data.highlightedEdges.forEach(id => {
      const edge = cy.getElementById(id);
      if (edge.length) edge.addClass('highlighted');
    });
  }
  cy.nodes().unselect();
  if (data.selectedId) {
    const node = cy.getElementById(data.selectedId);
    if (node.length) node.select();
  }
}

// Full teardown + rebuild (navigation, initial load, back/forward)
function renderNavigate(data) {
  cy.elements().remove();

  const parentIds = new Set(data.nodes.map(n => n.parent).filter(Boolean));
  const parents = data.nodes.filter(n => parentIds.has(n.id));
  cy.add(parents.map(n => ({ group: 'nodes', data: n })));
  const children = data.nodes.filter(n => !parentIds.has(n.id));
  cy.add(children.map(n => ({ group: 'nodes', data: n })));
  cy.add(data.edges.map(e => ({ group: 'edges', data: e })));

  applyHighlightsAndSelection(data);

  cy.layout(dagreOptions()).run();
  cy.fit(50);

  if (data.selectedId) {
    setTimeout(() => {
      const node = cy.getElementById(data.selectedId);
      if (node.length) {
        cy.animate({
          center: { eles: node },
          zoom: cy.zoom(),
          duration: 300
        });
      }
    }, 100);
  }
}

// Incremental diff + animated transition (expand/collapse)
function renderExpandToggle(data) {
  const targetId = toggleTargetId;

  // 1. Snapshot old positions for all existing nodes
  const oldPositions = {};
  cy.nodes().forEach(n => {
    oldPositions[n.id()] = { x: n.position('x'), y: n.position('y') };
  });

  // 2. Compute diff
  const newNodeIds = new Set(data.nodes.map(n => n.id));
  const oldNodeIds = new Set(cy.nodes().map(n => n.id()));

  // Data fields to sync on retained nodes
  const syncFields = [
    'label', 'isExpanded', 'showingPrivate', 'hasPrivateChildren',
    'selected', 'expandable', 'childCount'
  ];

  const newNodeMap = {};
  data.nodes.forEach(n => { newNodeMap[n.id] = n; });

  // 3. Remove departed nodes (and their edges go with them)
  cy.nodes().forEach(n => {
    if (!newNodeIds.has(n.id())) {
      n.remove();
    }
  });

  // 4. Update data on retained nodes
  cy.nodes().forEach(n => {
    const incoming = newNodeMap[n.id()];
    if (incoming) {
      syncFields.forEach(field => {
        if (incoming[field] !== undefined) {
          n.data(field, incoming[field]);
        } else {
          n.removeData(field);
        }
      });
    }
  });

  // 5. Add new nodes, positioned at their parent's center
  const parentPos = targetId && oldPositions[targetId]
    ? oldPositions[targetId]
    : { x: 0, y: 0 };

  // Add parents first, then children (compound node ordering)
  const parentIds = new Set(data.nodes.map(n => n.parent).filter(Boolean));
  const toAddParents = data.nodes.filter(n => !oldNodeIds.has(n.id) && parentIds.has(n.id));
  const toAddChildren = data.nodes.filter(n => !oldNodeIds.has(n.id) && !parentIds.has(n.id));

  toAddParents.forEach(n => {
    cy.add({ group: 'nodes', data: n });
    const added = cy.getElementById(n.id);
    added.position(parentPos);
    oldPositions[n.id] = { ...parentPos };
  });
  toAddChildren.forEach(n => {
    cy.add({ group: 'nodes', data: n });
    const added = cy.getElementById(n.id);
    added.position(parentPos);
    oldPositions[n.id] = { ...parentPos };
  });

  // 6. Replace all edges (cheap, avoids complex diff with aggregation changes)
  cy.edges().remove();
  cy.add(data.edges.map(e => ({ group: 'edges', data: e })));

  // 7. Apply highlights and selection
  applyHighlightsAndSelection(data);

  // 8. Run dagre silently to compute final positions
  cy.layout(dagreOptions()).run();

  // 9. Capture new (final) positions and compute viewport target
  const newPositions = {};
  cy.nodes().forEach(n => {
    newPositions[n.id()] = { x: n.position('x'), y: n.position('y') };
  });

  // Compute final center of the root (orphan) module for stable viewport
  const orphanNode = cy.nodes().filter(n =>
    !n.data('parent') && n.data('kind') === 'module'
  );
  let targetPan = null;
  if (orphanNode.length) {
    const bb = orphanNode.first().boundingBox();
    const cx = (bb.x1 + bb.x2) / 2;
    const cy_ = (bb.y1 + bb.y2) / 2;
    const zoom = cy.zoom();
    const w = cy.width();
    const h = cy.height();
    targetPan = { x: w / 2 - cx * zoom, y: h / 2 - cy_ * zoom };
  }

  // 10. Reset all nodes to their old positions
  cy.nodes().forEach(n => {
    const old = oldPositions[n.id()];
    if (old) {
      n.position(old);
    }
  });

  // 11. Animate nodes and viewport in sync
  const duration = 400;
  cy.nodes().forEach(n => {
    const target = newPositions[n.id()];
    if (target) {
      n.animate({
        position: target,
        duration: duration,
        easing: 'ease-in-out-quad'
      });
    }
  });

  if (targetPan) {
    cy.animate({
      pan: targetPan,
      zoom: cy.zoom(),
      duration: duration,
      easing: 'ease-in-out-quad'
    });
  }
}

window.renderGraph = function(data) {
  if (!data || !data.nodes) return;
  prepareGraphData(data);

  const action = pendingAction;
  pendingAction = 'navigate'; // Reset for next call

  if (action === 'expand-toggle' && cy.nodes().length > 0) {
    renderExpandToggle(data);
  } else {
    renderNavigate(data);
  }
};

// -----------------------------------------------------------------------------
// Event Bridge: Cytoscape events -> Datastar (via CustomEvents)

const graphPanel = document.getElementById('graph-panel');

// Single click - select node and show info
cy.on('tap', 'node', function(evt) {
  const node = evt.target;
  const nodeId = node.id();
  const nodeKind = node.data('kind');

  // Visual feedback: select and highlight edges
  cy.nodes().unselect();
  cy.edges().removeClass('highlighted');
  node.select();

  cy.edges().forEach(edge => {
    if (edge.source().id() === nodeId || edge.target().id() === nodeId) {
      edge.addClass('highlighted');
    }
  });

  // For schema nodes, dispatch schema event to view schema details
  if (nodeKind === 'schema') {
    const schemaKey = node.data('schemaKey');
    if (schemaKey) {
      graphPanel.dispatchEvent(new CustomEvent('cy-schema', {
        bubbles: true,
        detail: { id: schemaKey }
      }));
      return;
    }
  }

  // Dispatch custom event for Datastar to handle
  graphPanel.dispatchEvent(new CustomEvent('cy-select', {
    bubbles: true,
    detail: { id: nodeId }
  }));
});

// Single click on edge - select edge and show info
cy.on('tap', 'edge', function(evt) {
  const edge = evt.target;
  const source = edge.data('source');
  const target = edge.data('target');
  const edgeType = edge.data('edgeType') || 'code-flow';
  // Use ~ as delimiter since it's URL-safe and won't appear in UUIDs
  const edgeId = `edge~${source}~${target}~${edgeType}`;

  // Visual feedback: highlight edge, deselect nodes
  cy.nodes().unselect();
  cy.edges().removeClass('highlighted');
  edge.addClass('highlighted');

  // Use same event as nodes - unified selection
  graphPanel.dispatchEvent(new CustomEvent('cy-select', {
    bubbles: true,
    detail: { id: edgeId }
  }));
});

// Double click - navigate into module
cy.on('dbltap', 'node', function(evt) {
  const node = evt.target;
  const childCount = node.data('childCount');

  // Only navigate if it's a module with children
  if ((childCount && childCount > 0) || node.isParent()) {
    // NavigateToNode resets both expanded and showPrivate to empty
    expandedModules.clear();
    showPrivate.clear();
    graphPanel.dispatchEvent(new CustomEvent('cy-navigate', {
      bubbles: true,
      detail: { id: node.id() }
    }));
  }
});

// Right click - toggle expand/collapse for modules
cy.on('cxttap', 'node', function(evt) {
  if (evt.originalEvent.shiftKey) return;
  const node = evt.target;
  if (node.data('kind') !== 'module' || !node.data('expandable')) return;

  evt.preventDefault();
  const moduleId = node.id();

  if (expandedModules.has(moduleId)) {
    expandedModules.delete(moduleId);
    showPrivate.delete(moduleId);
  } else {
    expandedModules.add(moduleId);
  }

  pendingAction = 'expand-toggle';
  toggleTargetId = moduleId;
  graphPanel.dispatchEvent(new CustomEvent('cy-toggle-private', {
    bubbles: true,
    detail: { id: moduleId }
  }));
});

// Shift+right click - toggle private visibility on expanded modules
cy.on('cxttap', 'node', function(evt) {
  if (!evt.originalEvent.shiftKey) return;

  const node = evt.target;
  if (node.data('kind') !== 'module') return;
  if (!expandedModules.has(node.id())) return;
  if (!node.data('hasPrivateChildren')) return;

  evt.preventDefault();
  const moduleId = node.id();

  if (showPrivate.has(moduleId)) {
    showPrivate.delete(moduleId);
  } else {
    showPrivate.add(moduleId);
  }

  pendingAction = 'expand-toggle';
  toggleTargetId = moduleId;
  graphPanel.dispatchEvent(new CustomEvent('cy-toggle-private', {
    bubbles: true,
    detail: { id: moduleId }
  }));
});

// Click background - deselect
cy.on('tap', function(evt) {
  if (evt.target === cy) {
    cy.nodes().unselect();
    cy.edges().removeClass('highlighted');

    // Clear sidebar by fetching empty node info
    graphPanel.dispatchEvent(new CustomEvent('cy-select', {
      bubbles: true,
      detail: { id: '' }
    }));
  }
});

// -----------------------------------------------------------------------------
// Initial Page Load
//
// Wait for Datastar to process the page before triggering initial load

function initPage() {
  const params = new URLSearchParams(window.location.search);
  const viewId = params.get('id') || '';
  const selectId = params.get('select') || '';
  const schemaId = params.get('schema') || '';

  // Initialize local state from URL
  currentViewId = viewId;
  currentSelectId = selectId || viewId;
  currentSchemaId = schemaId;

  // Don't add history entry on initial load
  skipNextUrlUpdate = true;

  // Trigger initial load via Datastar custom events
  if (schemaId) {
    // If schema param present, load both graph and schema sidebar
    graphPanel.dispatchEvent(new CustomEvent('cy-navigate', {
      bubbles: true,
      detail: { id: viewId, select: selectId }
    }));
    // Then load schema view (overwrites sidebar)
    setTimeout(() => {
      skipNextUrlUpdate = true;
      graphPanel.dispatchEvent(new CustomEvent('cy-schema', {
        bubbles: true,
        detail: { id: schemaId }
      }));
    }, 100);
  } else {
    // Normal load - view with optional selection
    graphPanel.dispatchEvent(new CustomEvent('cy-navigate', {
      bubbles: true,
      detail: { id: viewId, select: selectId }
    }));
  }
}

// Run after Datastar has initialized (it adds data-* attributes)
// Use a small delay to ensure Datastar has processed the DOM
setTimeout(initPage, 50);
