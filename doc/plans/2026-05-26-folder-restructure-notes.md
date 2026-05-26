# Phase 3 Sprint 1 — Folder restructure & migration notes

**Date:** 2026-05-26
**Status:** Complete

## What landed

- **`:entity/doc` persistence fix** (Task 1) — `:doc` field added to `Affordance` and `Type` records; schema entries `:affordance/doc` and `:type/doc`; lifts thread `doc` through. Closes the same latent-bug pattern Sprint 2b found with `formal-expression`. ~9 new tests; 63 total passing afterward.

- **Top-level `canvas/` folder** (Task 2) — `deps.edn` `:paths` includes `"."` (project root) as a classpath root, so files at `canvas/<subsystem>/<module>.clj` resolve to namespace `canvas.<subsystem>.<module>`. The bare `"canvas"` classpath root would have stripped the leading `canvas.` from namespaces, so the path got widened to `.` instead. Side-effect: every file at project root is now technically on the classpath. Not currently a problem; worth keeping an eye on as `.legacy-allium/` lands in Sprint 4.

- **Four Phase 1 pilots migrated** (Task 3) — `src/fukan/canvas/pilot/*.clj` → `canvas/<subsystem>/<module>.clj`. Each refactored to use vocab libraries (`construction`, `vocab.behavioral`, `vocab.validation`, `vocab.lifecycle`) instead of Phase 1's `library.monolith` patterns and `h/declare-affordance` escape hatches.

## Sprint 1 verification (Task 4)

Found shape-grammar regressions in three of four migrated pilots — the migrator fell back to prose docstrings for optionality and collection types even though Phase 2 Sprint 1 shipped the shape grammar that handles exactly these cases. Specifically:

- `canvas/infra/server.clj` — `ServerOpts.port: Integer?` ported as `(field port :Integer)` with prose note. Fixed: `(field port (optional :Integer))`. Same for `start_server`'s `ServerInfo?` return.
- `canvas/constraint/evaluator.clj` — `evaluate_rules`'s `rules: List<ast.ConstraintRule>` ported as bare `:ast/ConstraintRule`. Fixed: `(list-of :ast/ConstraintRule)`. Same for `query`. `query`'s `Set<Binding>` return → `(set-of :Binding)`.
- `canvas/validation/phase4.clj` — `gate_g2`'s `violations: List<agent.Violation>` ported as bare `:agent/Violation`. Fixed: `(list-of :agent/Violation)`.

`canvas/vocabulary/allium/analyzer.clj` correctly used the shape grammar throughout (e.g. `(field arities (optional (list-of :Integer)))`). The pattern of WHEN the regression happened: the migrator used shape grammar when explicitly told the Phase 2 Sprint 1 work resolved a known gap (analyzer was that case), and didn't reach for it otherwise. Worth a note for future broader-porting subagents: shape-grammar usage isn't reserved for "Phase 2 closed this gap"; it's the standard way to express Optional/List/Set/Sum.

One real-but-not-blocking gap: `analyzer`'s `use_aliases: Map<String, String>?` was approximated as plain `:Map`. The shape grammar lacks a `map-of` combinator. Note for Sprint 2: if broader porting surfaces 3+ Map-shaped fields, ship a `map-of` shape addition.

## Counts

- Before Sprint 1: 54 tests / 86 assertions
- After Task 1: 63 / 95 (9 new doc-persistence tests)
- After Tasks 2–4: 67 / 119 (4 new per-port test files, 2 thin tests each)
- No regressions.

## File-tree state

```
canvas/                            ; NEW top-level folder
  infra/server.clj                 ; ns: canvas.infra.server
  constraint/evaluator.clj         ; ns: canvas.constraint.evaluator
  vocabulary/allium/analyzer.clj   ; ns: canvas.vocabulary.allium.analyzer
  validation/phase4.clj            ; ns: canvas.validation.phase4

src/fukan/canvas/                  ; canvas machinery (unchanged)
  core/                            ; mechanism
  construction.clj                 ; non-opt-out vocabulary
  vocab/                           ; opt-in methodology vocabularies
  pilot/                           ; GONE — directory removed after migration

test/canvas/                       ; NEW
  infra/server_test.clj            ; etc — parallel structure
```

Ready for Sprint 2 broader porting.
