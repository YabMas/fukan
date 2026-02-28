# Specification Analyzer Guide

Specification analyzers discover **design structure** from spec files and produce `AnalysisResult` values for the build pipeline.

## What they discover

- **Module descriptions** — from entity/value declarations
- **Surface declarations** — provides operations, guarantees, facing direction
- **Type relationships** — from type references in fields (currently unused for edges)

## Key constraint: module-level only

Specification analyzers produce **only module-level nodes**. Individual declarations (entities, values, rules) are not represented as graph nodes — they exist as data enrichments on the module node. Leaf nodes from `provides` operations are materialized by the build pipeline.

## Allium analyzer (`languages/allium.clj`)

The reference implementation:

1. **Discovery** (`discover-allium-files`): Finds `.allium` files recursively
2. **Parsing**: Uses `fukan.libs.allium.parser` (instaparse-based) to produce ASTs
3. **Node construction** (`build-allium-nodes`): Each file enriches its parent directory's module node with spec data (description, surface)
4. **Edge construction**: Currently returns no edges — spec imports are not code dependencies

## Parser location

The Allium parser lives in `src/fukan/libs/allium/parser.clj`, not in this module. It's a general-purpose library used by both the analyzer and potentially other consumers.
