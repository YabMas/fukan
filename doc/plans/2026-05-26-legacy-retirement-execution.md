# Phase 3 Sprint 4 — Legacy retirement execution log

**Date:** 2026-05-26
**Status:** Complete

## What landed

Three commits across the sprint:

```
urpqksrm  refactor: delete fukan.vocabulary analyzer subsystem (canvas is now sole spec source)
kumzsroy  refactor: move legacy .allium and .boundary specs to .legacy-allium/ (canvas is now sole spec source)
```

Plus inline test cleanup squashed into `urpqksrm` (removed an unused `instaparse.core` require in `parser_test.clj` left over from test removal).

## Task 10 — Move legacy specs

- **62 `.allium` files** moved from `src/fukan/**/*.allium` to `.legacy-allium/<mirrored path>/*.allium`.
- **72 `.boundary` files** moved similarly.
- Total: **134 files** relocated; directory structure preserved.
- `.legacy-allium/README.md` added explaining the archive.
- jj tracked all moves as renames (`R {src/fukan => .legacy-allium}/...`), preserving history.
- `.gitignore`: no change needed.

## Task 11 — Delete analyzer subsystem

**Files deleted:**

- `src/fukan/vocabulary/` (entire directory; 9 source files: `allium/{analyzer,effect_canonicalise,expression,pipeline,renderers,tags}.clj` + `boundary/{analyzer,pipeline,tags}.clj`).
- `test/fukan/vocabulary/` (8 test files mirroring the source).

**Files modified:**

- `src/fukan/model/pipeline.clj` — removed `allium` and `boundary` requires; `build-model` now starts from `build/empty-model` and runs phases 4–6 only.
- `src/fukan/model/effect.clj` — removed `canonicalise` function (a `requiring-resolve` bridge to the retired analyzer).
- `CLAUDE.md` — targeted fix: "Two Spec Languages" + "Spec Locations" + "Spec Authoring Rules" sections replaced with a single pointer to `canvas/<subsystem>/<module>.clj`. Full vision-doc update is Sprint 5; this is the minimal change to keep the project working day-to-day.

**Tests removed (with rationale):**

- `test/fukan/vocabulary/**` (8 files) — testing deleted source code.
- `test/fukan/libs/allium/parser_test.clj`: 5 integration tests opening `.allium` files that are now archived.
- `test/fukan/libs/boundary/parser_test.clj`: 1 integration test asserting `>= 4` `.boundary` files in `src/`.
- `test/fukan/model/effect_test.clj`: 1 test for the deleted `canonicalise` function.
- `test/fukan/smoke_test.clj`: replaced `pipeline-loader-end-to-end` (expected 62 Allium modules) with `pipeline-loads-clean-model` (validates model structure only).
- `test/fukan/target/clojure/analyzer_test.clj`: removed one sub-assertion that depended on spec primitives existing.

## Sprint 4 verification (Task 12)

**Tests:**
- Full project suite: **560 tests / 1578 assertions / 0 failures / 0 errors**
- Canvas suite: **189 tests / 699 assertions / 0 failures / 0 errors**

**Structure:**
- `find src/fukan -name '*.allium' -o -name '*.boundary'` returns empty.
- `src/fukan/vocabulary/` does not exist.
- `.legacy-allium/` contains the archived spec files mirroring the original `src/fukan/` layout.
- `canvas/` (62 ports) is the sole authoritative spec source.

**Grep audit:**
- Zero `fukan.vocabulary` references in `src/`, `dev/`, or `test/`.
- Zero `TODO: rule` comments in `canvas/` (Sprint 2.5b backfill earlier).
- Zero `:Map` placeholder uses (Sprint 2.5 backfill earlier).

## What changed for the day-to-day workflow

**Before Sprint 4:**
- `.allium` and `.boundary` files were co-located with `.clj` implementation files under `src/fukan/**`.
- The Allium and Boundary analyzer subsystems read them at model-build time.
- Specs and code lived in the same directory.

**After Sprint 4:**
- Spec content lives in `canvas/<subsystem>/<module>.clj` (Clojure-data canvas form).
- Implementation lives under `src/fukan/**` (Clojure source).
- The two surfaces are now structurally separated.
- Legacy `.allium`/`.boundary` files are archived in `.legacy-allium/` but not on the classpath.
- The Allium and Boundary analyzers no longer exist; their work has been replaced by the canvas store.

## What's still to do in Phase 3

**Sprint 3 — Graph integration (next).** The canvas substrate's datoms must drive the fukan graph viewer end-to-end via approach B (new pipeline reading canvas store directly). The current `src/fukan/model/pipeline.clj` runs phases 4-6 only after Sprint 4's analyzer removal; Sprint 3 will reattach the canvas store as the model source.

**Sprint 5 — Vision doc updates.** `VISION.md`, `MODEL.md`, `DESIGN.md`, `README.md`, `CLAUDE.md`, `AGENTS.md` rewritten to reflect canvas-first state. The targeted CLAUDE.md fix in Task 11 was the minimum to keep things working; Sprint 5 does the full rewrite.

**Sprint 6 — Phase 3 verification + Phase 4 brief.**
