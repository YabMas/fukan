# Model Implementation Guide

Read `spec.allium` in this directory for the full graph model specification.

## Key Invariants

- **Immutable after build**: The model is constructed once from analysis results and never mutated
- **Tree structure**: Each entity has at most one parent; nesting follows strict kind ordering
- **Complete data**: The model stores all nodes and edges, including private ones — filtering is the projection layer's job

## Build Pipeline

```
AnalysisResult → folder nodes → parent modules under folders → merge →
remove empty → wire children → prune → materialize surfaces →
collapse to boundary → remove schema-defining vars → filter edges →
attach boundaries → Model
```

The pipeline in `build.clj`:
1. `build-folder-nodes` — directories from filenames
2. Parent module nodes under folder nodes using `:filename` in `:data`
3. `merge-node-maps` — deep merge folders + analysis result nodes
4. `remove-empty-modules` + `wire-children` — cleanup
5. `prune-to-smart-root` — skip single-child folder chains
6. `materialize-surface-functions` — spec provides become Function children
7. `collapse-surface-to-boundary` — remaining surface data (guarantees, description) merges into `:boundary`
8. `remove-schema-defining-vars` — schema nodes replace their defining vars
9. `attach-boundaries` — namespace boundaries inferred from child function signatures, merged into existing boundary

## Module Structure

```
schema.clj  — all Malli schema definitions (pure data, no logic)
nodes.clj   — construction helpers for language analyzers
build.clj   — orchestration + language-agnostic build pipeline
lint.clj    — cross-module contract compliance linting
```

Dependency graph:
```
schema  ←  nodes  ←  clojure.clj  ←  build
                  ←  allium.clj   ←
```

## Analyzer Structure

Analyzers live under `analyzers/`, split by category:
- `analyzers/implementation/` — discovers code structure (Clojure via clj-kondo)
- `analyzers/specification/` — discovers design structure (Allium via instaparse)

Both produce `AnalysisResult` values that are merged by `build.clj` before `run-pipeline`.

The Clojure analyzer produces a **complete** AnalysisResult including:
- Module and symbol nodes from static analysis
- Schema nodes from runtime ^:schema var discovery
- Runtime metadata enrichment (function signatures)
- Contract boundary nodes from contract.edn files

The Allium parser is a shared library at `libs/allium/parser.clj`.

## Schema Conventions

- `^:schema` metadata marks Malli schema definitions on vars
- All schema definitions live in `schema.clj` (model schemas) or in their respective modules (e.g., `SchemaDiscoveryEntry` in `clojure.clj`)
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
