# .legacy-allium — Archived Specifications

This folder is archival. It contains every `.allium` and `.boundary` file that previously lived under `src/fukan/`, preserved exactly as they were at the moment of retirement. No edits, no comments added, no docstring annotations have been made to these files. This README is the only metadata.

## Retirement date

2026-05-26

## Why these files were retired

Phase 3 Sprint 2 ported every `.allium` and `.boundary` specification to canvas form. The canvas files at `canvas/<subsystem>/<module>.clj` are now the sole authoritative source for the structure and behaviour that these files formerly described.

## Where the authoritative specs now live

`canvas/<subsystem>/<module>.clj` — one file per module, colocated under the `canvas/` tree at the project root.

## Classpath status

These files are NOT on the classpath. They are not loaded at runtime by Fukan or by any test suite. The analyzer subsystem that previously read them (`src/fukan/vocabulary/`) was removed in Phase 3 Sprint 4 Task 11.

## Directory structure

The original `src/fukan/` directory structure is preserved verbatim. For example:

- `src/fukan/infra/server.allium` → `infra/server.allium` (here)
- `src/fukan/model/spec.allium` → `model/spec.allium` (here)
- `src/fukan/web/views/graph.boundary` → `web/views/graph.boundary` (here)
