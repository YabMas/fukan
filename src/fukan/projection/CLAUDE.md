# Projection Implementation Guide

Read `spec.allium` in this directory for the full projection specification.

## Key Principles

- **Pure functions**: `(model, opts) -> visible subgraph` with no side effects
- **No UI state**: Projections produce domain data only — no `selected?`, `highlighted?`, or other rendering concerns
- **On-demand aggregation**: Edges are aggregated to the visible ancestor level at query time
- **Same-type enforcement**: Cross-module drill-down only shows children matching the source entity's type

## Module Structure

```
graph.clj    — main projection: entity-graph (module + leaf views)
details.clj  — entity detail data for sidebar
path.clj     — breadcrumb path computation
schema.clj   — schema registry lookups and reference extraction
```

## Core Function Signatures

```clojure
;; graph.clj — main entry point
(entity-graph model opts)
;; opts: {:view-id NodeId?, :expanded-modules #{NodeId}}
;; returns: {:nodes [ProjectionNode], :edges [ProjectionEdge], :io {:inputs #{kw} :outputs #{kw}}}

;; details.clj — normalized entity details
(entity-details model entity-id)
;; For nodes, returns:
;;   {:label :kind :description :interface :schemas :dataflow :deps :dependents}
;; For edges, returns:
;;   {:label :kind :called-fns [{:name :schema :id}]}
;; :interface is {:type (:fn-list | :fn-inline | :schema-def | :name-list) :items [...]}
;; :schemas is [{:key :Qualified/Key}]
;; :dataflow is {:inputs [{:key k}] :outputs [{:key k}]} or nil

;; path.clj
(entity-path model entity-id)
;; returns breadcrumb items from root to entity
```

## Relationship to View Layer

The projection returns **domain data** that the view layer enriches with UI state:

```
Projection output         View layer adds
─────────────────         ───────────────
:nodes [ProjectionNode]   + :selected?, :highlighted?
:edges [ProjectionEdge]   + :highlighted?
:io {:inputs :outputs}    (unchanged)
```

Views never call back into projection — data flows one way.

## Edge Types

- `:code-flow` — aggregated from raw var/ns edges in the model
- `:schema-flow` — var-to-schema and schema-to-var based on `:malli/schema` metadata
- `:data-flow` — IO schema nodes to/from visible nodes (boundary-derived)
- `:schema-composition` — schema-to-schema based on `:schema-refs` in node data

## IO Schema Pattern

Module views generate synthetic IO nodes from boundaries:

1. `compute-io-schemas` reads the module's `:data :boundary`
2. Extracts input/output schema keys from function signatures
3. `create-io-nodes` builds `:io-container` and `:io-schema` projection nodes
4. `compute-io-flow-edges` connects them to visible code nodes via `:data-flow` edges

IO containers are orphans (no parent) — positioned by the JS layout engine.

## Key Helper: `compute-io-schemas`

```clojure
(compute-io-schemas model module-id)
;; -> {:inputs #{:SchemaKey ...}, :outputs #{:SchemaKey ...}}
```

Reads the module's boundary, extracts function schemas via `extract-fn-schema-flow`, and collects all referenced schema keys into input/output sets.

## Root Detection

Root functions (boundary entry points with no external callers) are detected at projection time, not stored in the model. `find-boundary-roots` reads boundary functions and checks edges for external callers. This keeps the model pure and moves the "who calls what" logic to where edges are already available.
