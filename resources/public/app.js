// Fukan - Interactive Clojure codebase visualizer
// Graph viewer web component with Datastar integration.
// Data in via observed attributes, events out via CustomEvent.

// =============================================================================
// GraphViewer Web Component
// =============================================================================

class GraphViewer extends HTMLElement {

  // ---------------------------------------------------------------------------
  // State (owned by the component, included in every outbound event)

  expandedModules = new Set();
  showPrivate = new Set();
  visibleEdgeTypes = new Set(['code-flow', 'schema-reference']);
  pendingAction = 'navigate'; // 'navigate' | 'expand-toggle'
  toggleTargetId = null;

  // URL sync state
  skipNextUrlUpdate = false;
  currentViewId = '';
  currentSelectId = '';
  currentSchemaId = '';

  // Cytoscape instance
  cy = null;

  // ---------------------------------------------------------------------------
  // Lifecycle

  static observedAttributes = ['graph-data'];

  connectedCallback() {
    // Create the cy container
    const cyDiv = document.createElement('div');
    cyDiv.id = 'cy';
    this.appendChild(cyDiv);

    this.cy = cytoscape({
      container: cyDiv,
      elements: [],
      style: this._cytoscapeStyles(),
      layout: { name: 'preset' },
      userZoomingEnabled: true,
      userPanningEnabled: true,
      boxSelectionEnabled: false
    });

    this._bindCytoscapeEvents();
    this._createEdgeTypeToggle();

    // Handle browser back/forward
    window.addEventListener('popstate', (event) => this._onPopState(event));

    // Initial page load
    setTimeout(() => this._initPage(), 50);
  }

  disconnectedCallback() {
    if (this.cy) {
      this.cy.destroy();
      this.cy = null;
    }
  }

  attributeChangedCallback(name, oldValue, newValue) {
    // Guard: cy may not exist yet if attributeChangedCallback fires before connectedCallback
    if (name === 'graph-data' && newValue && newValue !== 'null' && this.cy) {
      try {
        const data = JSON.parse(newValue);
        this._renderGraph(data);
      } catch (e) {
        console.error('[graph-viewer] Failed to parse graph data:', e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // View state accessors (for Datastar expressions in event handlers)

  get expandedParam() {
    return Array.from(this.expandedModules).join(',');
  }

  get showPrivateParam() {
    return Array.from(this.showPrivate).join(',');
  }

  get visibleEdgeTypesParam() {
    return Array.from(this.visibleEdgeTypes).join(',');
  }

  // Keep window globals for backward compat with sidebar data-on:click expressions
  static _instance = null;

  // ---------------------------------------------------------------------------
  // URL Sync

  _buildUrl() {
    const params = new URLSearchParams();
    if (this.currentViewId) params.set('id', this.currentViewId);
    if (this.currentSelectId) params.set('select', this.currentSelectId);
    if (this.currentSchemaId) params.set('schema', this.currentSchemaId);
    const query = params.toString();
    return query ? '/?' + query : '/';
  }

  updateUrl(viewId, selectId, schemaId) {
    if (this.skipNextUrlUpdate) {
      this.skipNextUrlUpdate = false;
      return;
    }
    if (viewId !== undefined) this.currentViewId = viewId || '';
    if (selectId !== undefined) this.currentSelectId = selectId || '';
    if (schemaId !== undefined) this.currentSchemaId = schemaId || '';

    const url = this._buildUrl();
    if (window.location.pathname + window.location.search !== url) {
      history.pushState(
        { viewId: this.currentViewId, selectId: this.currentSelectId, schemaId: this.currentSchemaId },
        '', url
      );
    }
  }

  _onPopState(event) {
    const params = new URLSearchParams(window.location.search);
    const viewId = event.state?.viewId || params.get('id') || '';
    const selectId = event.state?.selectId || params.get('select') || '';

    this.currentViewId = viewId;
    this.currentSelectId = selectId;
    this.currentSchemaId = '';

    this.skipNextUrlUpdate = true;
    this._emitNavigate(viewId, selectId);
  }

  // ---------------------------------------------------------------------------
  // Graph Rendering

  _dagreOptions(extraOpts) {
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

  _prepareGraphData(data) {
    this.expandedModules.clear();
    this.showPrivate.clear();
    data.nodes.forEach(n => {
      if (n.kind === 'module' && n.isExpanded) {
        this.expandedModules.add(n.id);
      }
      if (n.showingPrivate) {
        this.showPrivate.add(n.id);
      }
    });
    data.nodes.forEach(n => {
      if (n.kind === 'module' && n.expandable) {
        n.label = n.isExpanded ? n.label + ' \u25BC' : n.label + ' \u25B6';
      }
    });
  }

  _applyHighlightsAndSelection(data) {
    const cy = this.cy;
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

  _renderNavigate(data) {
    const cy = this.cy;
    cy.elements().remove();

    const parentIds = new Set(data.nodes.map(n => n.parent).filter(Boolean));
    const parents = data.nodes.filter(n => parentIds.has(n.id));
    cy.add(parents.map(n => ({ group: 'nodes', data: n })));
    const children = data.nodes.filter(n => !parentIds.has(n.id));
    cy.add(children.map(n => ({ group: 'nodes', data: n })));
    cy.add(data.edges.map(e => ({ group: 'edges', data: e })));

    this._applyHighlightsAndSelection(data);

    cy.layout(this._dagreOptions()).run();

    if (data.selectedId) {
      const node = cy.getElementById(data.selectedId);
      if (node.length) {
        // Single viewport operation: fit for zoom level, then center on selection
        cy.fit(50);
        cy.center(node);
        return;
      }
    }
    cy.fit(50);
  }

  _renderExpandToggle(data) {
    const cy = this.cy;
    const targetId = this.toggleTargetId;

    // 1. Snapshot old positions
    const oldPositions = {};
    cy.nodes().forEach(n => {
      oldPositions[n.id()] = { x: n.position('x'), y: n.position('y') };
    });

    // 2. Compute diff
    const newNodeIds = new Set(data.nodes.map(n => n.id));
    const oldNodeIds = new Set(cy.nodes().map(n => n.id()));

    const syncFields = [
      'label', 'isExpanded', 'showingPrivate', 'hasPrivateChildren',
      'selected', 'expandable', 'childCount'
    ];

    const newNodeMap = {};
    data.nodes.forEach(n => { newNodeMap[n.id] = n; });

    // 3. Remove departed nodes
    cy.nodes().forEach(n => {
      if (!newNodeIds.has(n.id())) n.remove();
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

    // 5. Add new nodes at parent's center
    const parentPos = targetId && oldPositions[targetId]
      ? oldPositions[targetId]
      : { x: 0, y: 0 };

    const parentIds = new Set(data.nodes.map(n => n.parent).filter(Boolean));
    const toAddParents = data.nodes.filter(n => !oldNodeIds.has(n.id) && parentIds.has(n.id));
    const toAddChildren = data.nodes.filter(n => !oldNodeIds.has(n.id) && !parentIds.has(n.id));

    toAddParents.forEach(n => {
      cy.add({ group: 'nodes', data: n });
      cy.getElementById(n.id).position(parentPos);
      oldPositions[n.id] = { ...parentPos };
    });
    toAddChildren.forEach(n => {
      cy.add({ group: 'nodes', data: n });
      cy.getElementById(n.id).position(parentPos);
      oldPositions[n.id] = { ...parentPos };
    });

    // 6. Replace all edges
    cy.edges().remove();
    cy.add(data.edges.map(e => ({ group: 'edges', data: e })));

    // 7. Apply highlights and selection
    this._applyHighlightsAndSelection(data);

    // 8. Run dagre to compute final positions
    cy.layout(this._dagreOptions()).run();

    // 9. Capture new positions
    const newPositions = {};
    cy.nodes().forEach(n => {
      newPositions[n.id()] = { x: n.position('x'), y: n.position('y') };
    });

    // Compute viewport target
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

    // 10. Reset to old positions
    cy.nodes().forEach(n => {
      const old = oldPositions[n.id()];
      if (old) n.position(old);
    });

    // 11. Animate
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

  _renderGraph(data) {
    if (!data || !data.nodes) return;
    this._prepareGraphData(data);

    const action = this.pendingAction;
    this.pendingAction = 'navigate';

    if (action === 'expand-toggle' && this.cy.nodes().length > 0) {
      this._renderExpandToggle(data);
    } else {
      this._renderNavigate(data);
    }

    // Update URL after render
    // selectedId tells us what's selected; derive viewId from root module
    if (data.viewId !== undefined) {
      this.updateUrl(data.viewId, data.selectedId || data.viewId, '');
    }
  }

  // ---------------------------------------------------------------------------
  // Event Emission (events out)

  _viewState() {
    return {
      expanded: this.expandedParam,
      showPrivate: this.showPrivateParam,
      visibleEdgeTypes: this.visibleEdgeTypesParam
    };
  }

  _emitSelect(nodeId) {
    // Update URL to reflect selection (without changing view)
    this.updateUrl(undefined, nodeId, '');
    this.dispatchEvent(new CustomEvent('graph-select', {
      bubbles: true,
      detail: { id: nodeId, ...this._viewState() }
    }));
  }

  _emitNavigate(viewId, selectId) {
    this.dispatchEvent(new CustomEvent('graph-navigate', {
      bubbles: true,
      detail: { id: viewId, select: selectId || '', ...this._viewState() }
    }));
  }

  _emitExpandToggle() {
    this.dispatchEvent(new CustomEvent('graph-expand', {
      bubbles: true,
      detail: this._viewState()
    }));
  }

  // ---------------------------------------------------------------------------
  // Cytoscape Event Binding (user interactions → component events)

  _bindCytoscapeEvents() {
    const cy = this.cy;

    // Single click on node
    cy.on('tap', 'node', (evt) => {
      const node = evt.target;
      const nodeId = node.id();
      const nodeKind = node.data('kind');

      // Visual feedback
      cy.nodes().unselect();
      cy.edges().removeClass('highlighted');
      node.select();

      cy.edges().forEach(edge => {
        if (edge.source().id() === nodeId || edge.target().id() === nodeId) {
          edge.addClass('highlighted');
        }
      });

      this._emitSelect(nodeId);
    });

    // Single click on edge
    cy.on('tap', 'edge', (evt) => {
      const edge = evt.target;
      const source = edge.data('source');
      const target = edge.data('target');
      const edgeType = edge.data('edgeType') || 'code-flow';
      const edgeId = `edge~${source}~${target}~${edgeType}`;

      cy.nodes().unselect();
      cy.edges().removeClass('highlighted');
      edge.addClass('highlighted');

      this._emitSelect(edgeId);
    });

    // Double click - navigate into module
    cy.on('dbltap', 'node', (evt) => {
      const node = evt.target;
      const childCount = node.data('childCount');

      if ((childCount && childCount > 0) || node.isParent()) {
        this.expandedModules.clear();
        this.showPrivate.clear();
        this._emitNavigate(node.id());
      }
    });

    // Right click - toggle expand/collapse
    cy.on('cxttap', 'node', (evt) => {
      if (evt.originalEvent.shiftKey) return;
      const node = evt.target;
      if (node.data('kind') !== 'module' || !node.data('expandable')) return;

      evt.preventDefault();
      const moduleId = node.id();

      if (this.expandedModules.has(moduleId)) {
        this.expandedModules.delete(moduleId);
        this.showPrivate.delete(moduleId);
      } else {
        this.expandedModules.add(moduleId);
      }

      this.pendingAction = 'expand-toggle';
      this.toggleTargetId = moduleId;
      this._emitExpandToggle();
    });

    // Shift+right click - toggle private visibility
    cy.on('cxttap', 'node', (evt) => {
      if (!evt.originalEvent.shiftKey) return;
      const node = evt.target;
      if (node.data('kind') !== 'module') return;
      if (!this.expandedModules.has(node.id())) return;
      if (!node.data('hasPrivateChildren')) return;

      evt.preventDefault();
      const moduleId = node.id();

      if (this.showPrivate.has(moduleId)) {
        this.showPrivate.delete(moduleId);
      } else {
        this.showPrivate.add(moduleId);
      }

      this.pendingAction = 'expand-toggle';
      this.toggleTargetId = moduleId;
      this._emitExpandToggle();
    });

    // Click background - deselect
    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        cy.nodes().unselect();
        cy.edges().removeClass('highlighted');
        this._emitSelect('');
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Edge Type Toggle UI

  _createEdgeTypeToggle() {
    const toolbar = document.createElement('div');
    toolbar.id = 'edge-type-toggle';
    toolbar.style.cssText = 'position:absolute;top:8px;right:8px;z-index:10;display:flex;gap:4px;';

    const makeBtn = (label, type) => {
      const btn = document.createElement('button');
      btn.textContent = label;
      btn.dataset.edgeType = type;
      btn.style.cssText = 'padding:4px 10px;font-size:11px;border:1px solid #bdc3c7;border-radius:4px;cursor:pointer;background:#fff;';
      if (this.visibleEdgeTypes.has(type)) {
        btn.style.background = type === 'code-flow' ? '#dbeaf5' : '#d5f5e3';
        btn.style.borderColor = type === 'code-flow' ? '#2980b9' : '#27ae60';
      }
      btn.addEventListener('click', () => {
        if (this.visibleEdgeTypes.has(type)) {
          if (this.visibleEdgeTypes.size > 1) {
            this.visibleEdgeTypes.delete(type);
          }
        } else {
          this.visibleEdgeTypes.add(type);
        }
        toolbar.querySelectorAll('button').forEach(b => {
          const t = b.dataset.edgeType;
          if (this.visibleEdgeTypes.has(t)) {
            b.style.background = t === 'code-flow' ? '#dbeaf5' : '#d5f5e3';
            b.style.borderColor = t === 'code-flow' ? '#2980b9' : '#27ae60';
          } else {
            b.style.background = '#fff';
            b.style.borderColor = '#bdc3c7';
          }
        });
        this.pendingAction = 'navigate';
        this._emitNavigate(this.currentViewId, this.currentSelectId);
      });
      return btn;
    };

    toolbar.appendChild(makeBtn('Code Flow', 'code-flow'));
    toolbar.appendChild(makeBtn('Schema Refs', 'schema-reference'));
    this.appendChild(toolbar);
  }

  // ---------------------------------------------------------------------------
  // Initial Page Load

  _initPage() {
    const params = new URLSearchParams(window.location.search);
    const viewId = params.get('id') || '';
    const selectId = params.get('select') || '';

    this.currentViewId = viewId;
    this.currentSelectId = selectId || viewId;
    this.currentSchemaId = '';

    this.skipNextUrlUpdate = true;
    this._emitNavigate(viewId, selectId);
  }

  // ---------------------------------------------------------------------------
  // Cytoscape Styles

  _cytoscapeStyles() {
    return [
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
      { selector: 'node.hidden', style: { 'display': 'none' } },
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
      {
        selector: 'node:selected',
        style: { 'border-color': '#e74c3c', 'border-width': 3 }
      },
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
      {
        selector: 'node[kind="module"]:orphan',
        style: { 'background-color': '#ecf0f1', 'border-color': '#bdc3c7' }
      },
      {
        selector: 'edge.highlighted',
        style: {
          'line-color': '#e74c3c',
          'target-arrow-color': '#e74c3c',
          'width': 3,
          'z-index': 1000
        }
      },
      {
        selector: 'node[hasPrivateChildren][!isExpanded]',
        style: { 'border-style': 'double', 'border-width': 4 }
      },
      {
        selector: 'node[hasPrivateChildren][isExpanded]',
        style: { 'border-style': 'solid', 'border-width': 3 }
      },
      {
        selector: 'node[kind="function"][private]',
        style: { 'border-style': 'dashed', 'background-color': '#f5f5f5' }
      },
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
      {
        selector: 'node[kind="schema"]:selected',
        style: {
          'border-color': '#e74c3c',
          'border-width': 3,
          'background-color': '#ffebee'
        }
      }
    ];
  }
}

customElements.define('graph-viewer', GraphViewer);

// =============================================================================
// Backward compatibility: expose view state accessors as window globals
// so server-rendered data-on:click expressions in sidebar can read them.
// =============================================================================

// These get resolved lazily from the component instance.
function getGraphViewer() {
  return document.querySelector('graph-viewer');
}

window.getExpandedParam = function() {
  const gv = getGraphViewer();
  return gv ? gv.expandedParam : '';
};

window.getShowPrivateParam = function() {
  const gv = getGraphViewer();
  return gv ? gv.showPrivateParam : '';
};

window.getVisibleEdgeTypesParam = function() {
  const gv = getGraphViewer();
  return gv ? gv.visibleEdgeTypesParam : '';
};
