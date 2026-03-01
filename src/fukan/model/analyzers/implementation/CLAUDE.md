# Implementation Analyzer Guide

Implementation analyzers discover **code structure** from source files and produce `AnalysisResult` values for the build pipeline.

## What they discover

- **Modules** (namespaces/scopes) — from static analysis
- **Symbols** (functions/vars) — from static analysis
- **References** (call relationships) — from static analysis
- **Type metadata** (schemas, signatures) — from runtime reflection
- **Contract boundaries** — from contract.edn files

## Key pattern: complete AnalysisResult

Each analyzer's `analyze` function produces a **complete** `AnalysisResult` containing all nodes and edges it discovers. The build pipeline (`model.build`) assembles results into the graph model.

## Clojure analyzer (`languages/clojure.clj`)

The reference implementation. Entry point: `analyze`.

1. **Static analysis** (`run-kondo`): Invokes clj-kondo, returns raw kondo output
2. **Normalization** (`normalize-kondo-output`): Maps kondo field names to generic `CodeAnalysis` names
3. **Result construction** (`analysis->result`): Builds module nodes, symbol nodes, and reference edges
4. **Schema discovery** (`discover-schema-data`): Scans runtime for `^:schema` vars
5. **Runtime enrichment** (`enrich-with-runtime-metadata`): Attaches `:malli/schema` function signatures
6. **Schema node building** (`build-schema-nodes`): Creates schema nodes from discovered schemas
7. **Contract resolution** (`discover-contract-nodes`): Finds contract.edn files, produces boundary module nodes

All seven steps happen within `analyze`, producing a single complete AnalysisResult.

## Adding a new implementation language

Provide an `analyze` function that takes a `src-path` and returns an `AnalysisResult` containing:
1. Module nodes, symbol nodes, and reference edges from static analysis
2. Optionally, type nodes (e.g., schema nodes) from runtime or static discovery
3. Optionally, boundary module nodes from contract/manifest files

Register the new analyzer in `pipeline.clj`.
