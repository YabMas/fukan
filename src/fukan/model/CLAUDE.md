# Model Implementation Guide

Read `spec.md` in this directory for the full graph model specification.

## Key Invariants

- **Immutable after build**: The model is constructed once from analysis results and never mutated
- **Tree structure**: Each entity has at most one parent; nesting follows strict kind ordering
- **Complete data**: The model stores all nodes and edges, including private ones — filtering is the projection layer's job

## Build Pipeline

```
CodeAnalysis → folder nodes → module nodes → symbol nodes → type nodes → wire children → prune → materialize surfaces → collapse to boundary → attach boundaries → Model
```

The pipeline in `build.clj`:
1. `build-folder-nodes` — directories from filenames
2. `build-module-nodes` — one per module definition
3. `build-symbol-nodes` — one per symbol/function definition
4. `type-nodes-fn` — injected by caller for language-specific nodes (e.g., schema nodes)
5. `remove-empty-modules` + `wire-children` — cleanup
6. `prune-to-smart-root` — skip single-child folder chains
7. `materialize-surface-functions` — spec provides become Function children
8. `collapse-surface-to-boundary` — remaining surface data (guarantees, description) merges into `:boundary`
9. `attach-boundaries` — namespace boundaries inferred from child function signatures, merged into existing boundary
10. Post-build: `resolve-contracts` (in `pipeline.clj`) — contract.edn files resolved on folder nodes

## Analyzer Structure

Analyzers live under `analyzers/`, split by category:
- `analyzers/implementation/` — discovers code structure (Clojure via clj-kondo)
- `analyzers/specification/` — discovers design structure (Allium via instaparse)

Both produce `AnalysisResult` values that are merged by `pipeline.clj` before `build-model`.

The Allium parser is a shared library at `libs/allium/parser.clj`.

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
- **module**: `{:doc?, :boundary?}` — directory or namespace
- **function**: `{:doc?, :private?, :signature?}` — var definition
- **schema**: `{:schema-key, :schema, :doc?}` — schema definition

**Boundary**: `{:description?, :functions [BoundaryFn]?, :schemas [keyword]?, :guarantees [string]?}`

## Index Maps

During build, each node type produces an index for cross-referencing:
- `folder-index`: `{dir-path -> node-id}`
- `ns-index`: `{ns-sym -> node-id}`
- `var-index`: `{[module-sym var-name] -> node-id}`

These are build-time only — the final Model has no indexes.
