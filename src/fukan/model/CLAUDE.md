# Model Implementation Guide

Read `spec.md` in this directory for the full graph model specification.

## Key Invariants

- **Immutable after build**: The model is constructed once from analysis results and never mutated
- **Tree structure**: Each entity has at most one parent; nesting follows strict kind ordering
- **Complete data**: The model stores all nodes and edges, including private ones — filtering is the projection layer's job

## Build Pipeline

```
AnalysisData → folder nodes → namespace nodes → var nodes → type nodes → wire children → prune → attach contracts → Model
```

The pipeline in `build.clj`:
1. `build-folder-nodes` — directories from filenames
2. `build-namespace-nodes` — one per namespace definition
3. `build-var-nodes` — one per var/function definition
4. `type-nodes-fn` — injected by caller for language-specific nodes (e.g., schema nodes)
5. `remove-empty-modules` + `wire-children` — cleanup
6. `prune-to-smart-root` — skip single-child folder chains
7. `build-edges` + `build-ns-edges` — var-level and ns-level edges
8. `attach-contracts` — folder contracts from `contract.edn`, namespace contracts from `:malli/schema`

## Schema Conventions

- `^:schema` metadata marks Malli schema definitions on vars
- Schemas use Malli syntax: `[:map ...]`, `[:enum ...]`, `[:vector ...]`
- Function schemas use `[:=> [:cat input...] output]` and are attached via `:malli/schema` in the var's metadata map
- Schema keys are keywords derived from the var name (e.g., `Model`, `Node`, `Edge`)
- See the root `CLAUDE.md` "Schema Design Guidelines" section for writing conventions

## Core Data Shapes

**Model**: `{:nodes {NodeId -> Node}, :edges [Edge]}`

**Node**: `{:id NodeId, :kind NodeKind, :label String, :parent NodeId?, :children #{NodeId}, :data NodeData?}`
- `:kind` is one of `:module`, `:function`, `:schema`
- `:data` is a discriminated union (`NodeData`) — see schema for variants per kind

**Edge**: `{:from NodeId, :to NodeId}` — directed, at leaf level (var-to-var or ns-to-ns)

**NodeData** (discriminated by `:kind`):
- **module**: `{:doc?, :contract?}` — directory or namespace
- **function**: `{:doc?, :private?, :signature?}` — var definition
- **schema**: `{:schema-key, :schema, :doc?}` — schema definition

**Contract**: `{:description?, :functions [ContractFn]}`

## Index Maps

During build, each node type produces an index for cross-referencing:
- `folder-index`: `{dir-path -> node-id}`
- `ns-index`: `{ns-sym -> node-id}`
- `var-index`: `{[ns-sym var-name] -> node-id}`

These are build-time only — the final Model has no indexes.
