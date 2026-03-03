# Implementation Analyzer Guide

Implementation analyzers discover **code structure** from source files and produce `AnalysisResult` values for the build pipeline.

## What they discover

- **Modules** (namespaces/scopes) — from static analysis
- **Symbols** (functions/definitions) — from static analysis
- **References** (call relationships) — from static analysis
- **Type metadata** (schemas, signatures) — from runtime or static discovery
- **Contract boundaries** — from contract/manifest files

## Key pattern: complete AnalysisResult

Each analyzer's `analyze` function produces a **complete** `AnalysisResult` containing all nodes and edges it discovers. The build pipeline (`model.build`) assembles results into the graph model.

## Node ID conventions

The build pipeline relies on these ID conventions. All analyzers must follow them:

- **Module node IDs**: String form of the module identifier (e.g., `"fukan.model.build"` for Clojure namespaces). Must be unique across the entire model.
- **Function node IDs**: `"parent-id/label"` — the parent module's ID, a `/`, then the symbol name (e.g., `"fukan.model.build/run-pipeline"`). This convention is required because the pipeline uses it to match schema-defining symbols to their function nodes.
- **Schema node IDs**: `"schema:Name"` — prefixed with `schema:`.
- **Folder node IDs**: The raw directory path (e.g., `"src/fukan/model"`). Built automatically by the pipeline from `:source-files`.

Module nodes must include `:filename` in their `:data` map so the pipeline can assign folder parents. Module `:parent` should be `nil` — the pipeline assigns parents from the folder hierarchy.

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

1. Create a new namespace under `languages/` (e.g., `languages/typescript.clj`)
2. Implement an `analyze` function: `(fn [src-path] -> AnalysisResult)`
3. Follow the node ID conventions above
4. Add the analyzer to the analyzer list in `infra/model.clj`
