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

let skipNextUrlUpdate = false;

// Update browser URL without triggering navigation
window.updateUrl = function(entityId) {
  if (skipNextUrlUpdate) {
    skipNextUrlUpdate = false;
    return;
  }
  const url = entityId ? `/?id=${encodeURIComponent(entityId)}` : '/';
  if (window.location.pathname + window.location.search !== url) {
    history.pushState({ entityId }, '', url);
  }
};

// Handle browser back/forward
window.addEventListener('popstate', (event) => {
  const entityId = event.state?.entityId || new URLSearchParams(window.location.search).get('id') || '';
  skipNextUrlUpdate = true; // Don't push to history when navigating via back/forward
  // Trigger Datastar SSE request via custom event
  document.getElementById('graph-panel').dispatchEvent(new CustomEvent('cy-navigate', {
    bubbles: true,
    detail: { id: entityId }
  }));
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
    // Edges
    {
      selector: 'edge',
      style: {
        'width': 2,
        'line-color': '#95a5a6',
        'target-arrow-color': '#95a5a6',
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
  
  // Skip compound parent containers
  if (node.isParent()) return;
  
  const nodeId = node.id();
  
  // Visual feedback: select and highlight edges
  cy.nodes().unselect();
  cy.edges().removeClass('highlighted');
  node.select();
  
  cy.edges().forEach(edge => {
    if (edge.source().id() === nodeId || edge.target().id() === nodeId) {
      edge.addClass('highlighted');
    }
  });
  
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
