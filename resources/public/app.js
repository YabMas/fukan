// Fukan - Interactive Clojure codebase visualizer
// Simplified version using Datastar for state management

// -----------------------------------------------------------------------------
// Expanded Containers State (for private visibility toggle)

let expandedContainers = new Set();

// Get expanded containers as a comma-separated string for URL params
window.getExpandedParam = function() {
  return Array.from(expandedContainers).join(',');
};

// Toggle a container's expanded state and return the new state
window.toggleExpanded = function(containerId) {
  if (expandedContainers.has(containerId)) {
    expandedContainers.delete(containerId);
  } else {
    expandedContainers.add(containerId);
  }
  return window.getExpandedParam();
};

// -----------------------------------------------------------------------------
// URL Sync for Deep Linking
//
// URL structure: /?id=<view>&select=<selected>&schema=<schema>
// - id: The current view (container being displayed in graph)
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
    // Folder node
    {
      selector: 'node[kind="folder"]',
      style: {
        'shape': 'roundrectangle',
        'background-color': '#ecf0f1',
        'border-color': '#bdc3c7',
        'border-width': 2,
        'border-style': 'solid',
        'label': 'data(label)',
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'text-max-width': '150px',
        'padding': '25px',
        'font-weight': 'bold',
        'font-size': '14px',
        'color': '#7f8c8d'
      }
    },
    // Namespace node
    {
      selector: 'node[kind="namespace"]',
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
    // Var node
    {
      selector: 'node[kind="var"]',
      style: {
        'shape': 'roundrectangle',
        'background-color': '#fff',
        'border-color': '#7f8c8d',
        'border-width': 1,
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
    // Edges - blue to match namespace containers
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
    // Selected node
    {
      selector: 'node:selected',
      style: {
        'border-color': '#e74c3c',
        'border-width': 3
      }
    },
    // Folder as compound parent
    {
      selector: 'node[kind="folder"]:parent',
      style: {
        'text-valign': 'top',
        'text-halign': 'center',
        'text-margin-y': '18px',
        'padding': '40px',
        'padding-top': '55px'
      }
    },
    // Namespace as compound parent
    {
      selector: 'node[kind="namespace"]:parent',
      style: {
        'text-valign': 'top',
        'text-halign': 'center',
        'text-margin-y': '18px',
        'padding': '40px',
        'padding-top': '55px'
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
    // Private vars (shown when parent is expanded)
    {
      selector: 'node[private]',
      style: {
        'background-color': '#f5f5f5',
        'border-style': 'dashed',
        'color': '#7f8c8d'
      }
    },
    // Schema node - diamond shape, green theme for data flow
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
        'width': '90px',
        'height': '90px',
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
    },
    // Schema flow edges - dashed green lines for data flow
    {
      selector: 'edge[edgeType="schema-flow"]',
      style: {
        'line-style': 'dashed',
        'line-color': '#27ae60',
        'target-arrow-color': '#27ae60',
        'line-dash-pattern': [6, 3],
        'width': 2,
        'curve-style': 'bezier',
        'arrow-scale': 0.8
      }
    },
    // Highlighted schema flow edges - red like other highlighted edges
    {
      selector: 'edge[edgeType="schema-flow"].highlighted',
      style: {
        'line-color': '#e74c3c',
        'target-arrow-color': '#e74c3c',
        'width': 3,
        'z-index': 1000
      }
    }
  ],
  
  layout: { name: 'preset' },
  userZoomingEnabled: true,
  userPanningEnabled: true,
  boxSelectionEnabled: false
});

// -----------------------------------------------------------------------------
// Graph Rendering (called by backend via script tag)

window.renderGraph = function(data) {
  if (!data || !data.nodes) return;

  // Sync expandedContainers state from the node data
  // (nodes with hasPrivateChildren=true and isExpanded=true are expanded)
  expandedContainers.clear();
  data.nodes.forEach(n => {
    if (n.hasPrivateChildren && n.isExpanded) {
      expandedContainers.add(n.id);
    }
  });

  // Clear existing elements
  cy.elements().remove();
  
  // Identify parent nodes (compound containers)
  const parentIds = new Set(data.nodes.map(n => n.parent).filter(Boolean));
  
  // Add parent nodes first
  const parents = data.nodes.filter(n => parentIds.has(n.id));
  cy.add(parents.map(n => ({ group: 'nodes', data: n })));
  
  // Add child nodes
  const children = data.nodes.filter(n => !parentIds.has(n.id));
  cy.add(children.map(n => ({ group: 'nodes', data: n })));
  
  // Add edges
  cy.add(data.edges.map(e => ({ group: 'edges', data: e })));
  
  // Apply highlights from backend
  cy.edges().removeClass('highlighted');
  if (data.highlightedEdges) {
    data.highlightedEdges.forEach(id => {
      const edge = cy.getElementById(id);
      if (edge.length) edge.addClass('highlighted');
    });
  }
  
  // Select node if specified by backend
  cy.nodes().unselect();
  if (data.selectedId) {
    const node = cy.getElementById(data.selectedId);
    if (node.length) {
      node.select();
    }
  }
  
  // Run layout
  cy.layout({
    name: 'dagre',
    rankDir: 'TB',
    nodeSep: 80,
    rankSep: 100,
    edgeSep: 30,
    animate: true,
    animationDuration: 300,
    fit: true,
    padding: 50,
    nodeDimensionsIncludeLabels: true,
    spacingFactor: 1.2
  }).run();
  
  // Center on selected node after layout completes
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
    }, 350);
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

  if (nodeKind === 'schema') {
    // For schema nodes, highlight all schema-flow edges with the same schemaKey
    const schemaKey = node.data('schemaKey');
    cy.edges().forEach(edge => {
      if (edge.data('schemaKey') === schemaKey) {
        edge.addClass('highlighted');
      }
    });
  } else {
    // For other nodes, highlight only code-flow edges (not schema-flow)
    cy.edges().forEach(edge => {
      const isSchemaFlow = edge.data('edgeType') === 'schema-flow';
      if (!isSchemaFlow && (edge.source().id() === nodeId || edge.target().id() === nodeId)) {
        edge.addClass('highlighted');
      }
    });
  }

  // Dispatch custom event for Datastar to handle
  graphPanel.dispatchEvent(new CustomEvent('cy-select', {
    bubbles: true,
    detail: { id: nodeId }
  }));
});

// Double click - navigate into container
cy.on('dbltap', 'node', function(evt) {
  const node = evt.target;
  const childCount = node.data('childCount');

  // Only navigate if it's a container with children
  if ((childCount && childCount > 0) || node.isParent()) {
    graphPanel.dispatchEvent(new CustomEvent('cy-navigate', {
      bubbles: true,
      detail: { id: node.id() }
    }));
  }
});

// Right click (context tap) - toggle private visibility for containers
cy.on('cxttap', 'node', function(evt) {
  const node = evt.target;
  const hasPrivateChildren = node.data('hasPrivateChildren');

  // Only toggle if this container has private children
  if (hasPrivateChildren) {
    evt.preventDefault();
    const containerId = node.id();
    window.toggleExpanded(containerId);

    // Dispatch event to refresh the view with new expanded state
    graphPanel.dispatchEvent(new CustomEvent('cy-toggle-private', {
      bubbles: true,
      detail: { id: containerId }
    }));
  }
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
