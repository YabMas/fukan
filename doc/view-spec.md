# Fukan View Specification

## Core Concepts

- **Entity**: Any item in the graph (folder, namespace, or var)
- **Children**: Entities whose parent is this entity
- **Relationships**: Edges connecting entities (dependencies in both directions)
- **Parent/Container**: The containing entity (used for grouping in compound nodes)

## Unified View Behavior

Every entity behaves the same way. The view is determined by whether the entity has children or not.

### When viewing a container (entity with children)

1. **Show all children** of the selected container
2. **Show edges between children** (sibling relationships)
3. **If a child has a relationship with an entity inside a sibling container**:
   - Show that related entity explicitly
   - Group it inside its container (the sibling)
   - Only show related entities inside that container, not all its children
   - The sibling container serves dual purpose: child AND grouping container

### When viewing a leaf (entity without children)

1. **Show the selected entity itself**
2. **Show all entities it has relationships with** (both directions)
3. **Group related entities by their parent container**
4. **Show edges involving the selected entity only**

### The Formula

For containers:
```
children = direct_children(selected)
cross_container_relations = relationships_of(children) that target entities inside sibling containers
visible_entities = children ∪ cross_container_relations
visible_edges = edges_between(children) ∪ edges_from(children, cross_container_relations)
```

For leaves:
```
visible_entities = {selected} ∪ relationships(selected)
visible_edges = edges_involving(selected)
```

### Key Principles

1. **No redundant container edges**: When viewing a container, we don't show edges between the container and its nested children's containers. The relationship is shown at the actual entity level.

2. **Containers expand on demand**: A child container (like `web` inside `fukan`) appears as a simple node unless something inside it is related to a sibling. In that case, it expands to show the specific related entities.

3. **Equal treatment**: All entities follow the same rules. The only distinction is whether they have children or not.

## Edge Aggregation

Edges are aggregated at the appropriate level:
- When viewing folders: edges between namespace-level children
- When viewing namespaces: edges between var-level children
- When viewing vars: direct var-to-var edges

Container-to-container edges (e.g., folder-to-folder) are NOT shown when viewing the parent container - they're redundant since we show the actual entity-level relationships.

## Interaction Model

### Selection vs Navigation

The UI distinguishes between **selection** (highlighting within current view) and **navigation** (changing the focused container).

| Action | Behavior |
|--------|----------|
| **Single click** | Select node, highlight its direct edges. Focus stays on current container. |
| **Double click** | Navigate into the node (shift focus) - only if node has children. |
| **Breadcrumb click** | Navigate to that ancestor container. |

### Single Click: Selection

When a node is single-clicked:
1. The node is visually selected (highlighted border)
2. All edges involving that node are highlighted
3. The sidebar shows details for that node
4. **The focused container does not change**

This allows exploring relationships without losing context. External entities and their edges are already visible in the view (from the container view computation), so highlighting simply reveals which edges belong to the selected node.

### Double Click: Navigation

When a node is double-clicked:
- **If the node has children** (folder or namespace): Navigate to show that container's internal structure
- **If the node has no children** (var/leaf): Treated as single click (no navigation)

After navigation, the new container becomes the focused entity:
- Its children are shown as nodes
- Sibling edges between children are visible
- External entities that children relate to are shown in their own containers
- No node is selected until the user clicks one

### Breadcrumb

The breadcrumb shows the path from smart-root to the focused container. Clicking any breadcrumb item navigates to that container.

### Summary

- **Single click** = "peek" (see relationships without changing context)
- **Double click** = "dive in" (change focus to explore deeper)
- **Breadcrumb** = "zoom out" (navigate back up the hierarchy)

## Examples

### Example 1: Viewing `fukan` folder

**Setup**:
- `fukan` has children: `fukan.core`, `fukan.analysis`, `fukan.cytoscape`, `fukan.model`, `web`
- `web` has children: `fukan.web.handler`, `fukan.web.views`
- `fukan.core` depends on `fukan.web.handler`

**Result**:
- Show: `fukan.core`, `fukan.analysis`, `fukan.cytoscape`, `fukan.model` as nodes
- Show: `web` as a compound container with `fukan.web.handler` inside
- Do NOT show: `fukan.web.views` (not related to any sibling)
- Edges: `fukan.core` → `fukan.web.handler`, plus sibling edges between the 4 namespaces
- Do NOT show: `fukan` ↔ `web` folder-level edges (redundant)

### Example 2: Viewing `fukan` folder (no cross-container relations)

**Setup**:
- Same as above, but `fukan.core` does NOT depend on anything in `web`

**Result**:
- Show: `fukan.core`, `fukan.analysis`, `fukan.cytoscape`, `fukan.model`, `web` as simple nodes
- `web` appears as a regular child node (not expanded)
- Edges: only sibling edges between the 4 namespaces

### Example 3: Viewing `fukan.web.handler` namespace

**Setup**:
- `fukan.web.handler` has children: `create-handler`, `compute-view`
- `compute-view` depends on `node->cytoscape` (in `fukan.cytoscape`) and `entity-kind` (in `fukan.model`)

**Result**:
- Show: `create-handler`, `compute-view` as nodes
- Show: `fukan.cytoscape` container with `node->cytoscape` inside
- Show: `fukan.model` container with `entity-kind` inside
- Edges: `create-handler` → `compute-view`, `compute-view` → `node->cytoscape`, `compute-view` → `entity-kind`

### Example 4: Viewing `compute-view` var (leaf)

**Setup**:
- `compute-view` has no children
- `compute-view` depends on `node->cytoscape`, `entity-kind`
- `create-handler` depends on `compute-view`

**Result**:
- Show: `compute-view` (selected), `node->cytoscape`, `entity-kind`, `create-handler`
- Group: `compute-view` and `create-handler` in `fukan.web.handler`, `node->cytoscape` in `fukan.cytoscape`, `entity-kind` in `fukan.model`
- Edges: only those involving `compute-view`

## Data Model

### Nodes

Each node has:
- `id`: Unique identifier (e.g., `folder:src/fukan`, `ns:fukan.core`, `var:fukan.core/-main`)
- `kind`: Entity type (`folder`, `namespace`, `var`)
- `label`: Display name
- `parent`: ID of parent entity (for hierarchy)
- `children`: Set of child entity IDs

### Edges

Edges exist at three levels, each aggregated from the level below:
- **Var edges**: Direct var-to-var dependencies (source of truth from clj-kondo)
- **Namespace edges**: Aggregated from var edges (ns-A → ns-B if any var in A depends on any var in B)
- **Folder edges**: Aggregated from namespace edges (folder-A → folder-B if any ns in A depends on any ns in B)

Self-edges are excluded at all levels.
