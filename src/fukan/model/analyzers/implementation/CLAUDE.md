# Implementation Analyzer Guide

Implementation analyzers discover **code structure** from source files and produce `AnalysisResult` values for the build pipeline.

## What they discover

- **Modules** (namespaces/scopes) — from static analysis
- **Symbols** (functions/vars) — from static analysis
- **References** (call relationships) — from static analysis
- **Type metadata** (schemas, signatures) — from runtime reflection

## Key pattern: raw data only

Analyzers produce raw `CodeAnalysis` data. The build pipeline (`model.build`) assembles it into the graph model. Do not construct node hierarchies or compute parent-child relationships in analyzer code.

## Clojure analyzer (`languages/clojure.clj`)

The reference implementation. Three phases:

1. **Static analysis** (`run-kondo`): Invokes clj-kondo, returns raw kondo output
2. **Normalization** (`normalize-kondo-output`): Maps kondo field names to generic `CodeAnalysis` names
3. **Result construction** (`analysis->result`): Builds module nodes, symbol nodes, and reference edges

Schema discovery and contract resolution are separate operations called from the pipeline, not from the analyzer itself.

## Adding a new implementation language

Provide:
1. An analysis function returning `CodeAnalysis` (module-defs, symbol-defs, symbol-refs, module-imports)
2. Optionally, a type-nodes function for language-specific node kinds
3. Register in `pipeline.clj`
