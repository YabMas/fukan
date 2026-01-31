# Graph Model Specification

## Core Concepts

- **Entity**: Any item in the graph
- **Container**: An entity that has children (a current state, not a fixed property)
- **Leaf**: An entity that has no children
- **Children**: Entities whose parent is this entity
- **Relationships**: Edges connecting entities (dependencies in both directions)
- **Parent**: The containing entity (used for grouping in compound nodes)

## Node Structure

Each node has:
- `id`: Unique identifier
- `kind`: Entity type (determines hierarchy level)
- `label`: Display name
- `parent`: ID of parent entity (for hierarchy)
- `children`: Set of child entity IDs
- `data`: Kind-specific properties (including `private?` for leaf nodes)

## Edge Structure

Edges are directed: `{from, to}`.

- **Leaf edges** are the source of truth (direct dependencies)
- **Container edges** are derived from child relationships (container-A -> container-B if any child in A depends on any child in B)
- **Self-edges** are excluded at all levels

## Hierarchy Invariants

- The graph forms a **tree structure** (each entity has at most one parent)
- Nesting follows **strict kind ordering** (container kinds nest in a fixed hierarchy)
- **Smart-root pruning**: single-child root chains are collapsed to the first meaningful branching point

## Private Nodes

`private?` is a **model-level property** stored in the node's data. The model stores the complete graph including all private nodes and their edges. Filtering private nodes out of views is the projection layer's responsibility.

## Contract Structure

- **Folder contracts**: Defined explicitly via `contract.edn` files
- **Leaf contracts**: Inferred from `:malli/schema` metadata on public functions
- Contracts describe the module's API boundary (function signatures with input/output schemas)
