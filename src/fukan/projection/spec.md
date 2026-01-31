# Projection Specification

## Purpose

Pure computation: `(model, opts) -> visible subgraph`.

The projection layer takes the complete model and editor options (`view-id`, `expanded-containers`) and produces the subset of nodes and edges to display. It contains no UI state (no `selected?`, `highlighted?` — those belong to the view layer).

## Container View

When viewing a container (entity with children):

1. Show **visible children** of the selected container (private children hidden unless expanded)
2. Show **edges between visible children** (sibling relationships)
3. If a visible child has a relationship with a **same-type entity inside a sibling container**:
   - Show that related entity explicitly (same-type only)
   - Group it inside its container (the sibling)
   - Only show related entities of the same type, not all children
   - The sibling container serves dual purpose: child AND grouping container

### Formula

```
children = visible_children(selected, expanded-containers)
cross_container_relations = same_type_relationships_of(children) targeting entities inside sibling containers
visible_entities = children ∪ cross_container_relations
visible_edges = edges_between(children) ∪ edges_from(children, cross_container_relations)
```

## Leaf View

When viewing a leaf (entity without children):

1. Show **the selected entity itself**
2. Show **all entities it has relationships with** (both directions)
3. **Group related entities by their parent container**
4. Show **edges involving the selected entity only**

### Formula

```
visible_entities = {selected} ∪ visible_relationships(selected)
visible_edges = edges_involving(selected) where both endpoints visible
```

## Edge Aggregation

Edges are aggregated on demand to the visible ancestor level:
- Duplicate edges after aggregation are removed
- Container-to-container edges are NOT shown when viewing the parent container — they are redundant since the actual entity-level relationships are displayed
- Edges crossing the container bounding box are not rendered (container views only)

## Visibility Rules

A node is visible if:
1. It is not private, OR
2. It is private AND its parent container is in `expanded-containers`

An edge is visible if both its source and target nodes are visible.

### Formula

```
visible_children(container) =
  if container in expanded-containers:
    all children
  else:
    children where not private?

visible_edges(children) =
  edges where both from and to are in visible_children
```

## Key Principles

1. **No redundant container edges**: When viewing a container, edges between the container and its nested children's containers are not shown. The relationship is shown at the actual entity level.

2. **Same-type drill-down**: When expanding a sibling container, only show children of the same type as the source entity. This keeps each level focused on structural relationships at that level.

3. **Strict bounding box**: Container views enforce a strict boundary. ONLY entities inside the viewed container are shown. External entities are NEVER shown. Edges crossing the bounding box are NOT rendered. To see external relationships, navigate to a common ancestor. Leaf views are different — they show all related entities regardless of container.

4. **Contract-driven IO**: Input/output schemas are derived from the container's contract. Containers with no contract show no IO.

## IO Schema Projection

- Synthetic IO nodes are generated from the container's contract
- Data-flow edges connect contract functions to their schema types
- Only schemas referenced by the contract's function signatures are shown

## Entity Details

`entity-details` returns a **normalized, view-agnostic** structure for any entity.

### Node entity detail shape

```clojure
{:label       "display-name"
 :kind        :folder | :namespace | :var | :schema | :interface
 :description "text or nil"
 :interface   {:type :fn-list | :fn-inline | :schema-def | :name-list
               :items [...]}       ; or nil
 :schemas     [{:key :Qualified/Key}]  ; or nil
 :dataflow    {:inputs [{:key :SchemaKey}]    ; or nil when no contract/schema
               :outputs [{:key :SchemaKey}]}
 :deps        {id -> {:count n :label "str"}}
 :dependents  {id -> {:count n :label "str"}}}
```

### Edge entity detail shape

```clojure
{:label       "A -> B"
 :kind        :edge
 :called-fns  [{:name :schema :id}]}
```

### Interface types

- `:fn-list` — list of functions with optional schemas and IDs (folders, namespaces)
- `:fn-inline` — single function schema (vars)
- `:schema-def` — schema definition form (schemas)
- `:name-list` — list of names without schemas (interface nodes)
