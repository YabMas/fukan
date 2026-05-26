# Canvas + Substrate Implementation Plan — Phase 3

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make fukan-the-analysis-substrate canvas-native end to end. Port every existing `.allium`/`.boundary` file to canvas form using the Phase 2 vocabulary libraries; project the canvas substrate's datoms into the existing fukan graph viewer so the system is observable; settle the legacy-spec retirement strategy and execute it. Phase 3 closes when the canvas substrate is the authoritative model for fukan-itself and the analyzers in `src/fukan/vocabulary/` either retire or are demoted to read-only legacy.

**Phase 3's strategic frame:** *Analysis substrate before authoring tools* — confirmed strategic direction. The canvas exists primarily to be reasoned over, and the graph viewer is fukan's current product surface. Phase 3 makes the canvas observable through that surface; authoring (collaborative editing, LLM-assisted design conversations) is a later phase.

**Architecture:**

- Canvas core, construction, and three vocab libraries are in place (Phase 2 outcomes). Phase 3 adds two more substrate-completeness fixes (`:entity/doc` persistence; maybe a `refresher` distinction inside `vocab.lifecycle` if porting evidence forces it) but does NOT add new lift libraries unless porting surfaces a genuine gap.
- Existing analyzers in `src/fukan/vocabulary/` continue to read `.allium`/`.boundary` files throughout most of Phase 3. They're demoted (canvas is authoritative) but not deleted until Sprint 4.
- The graph viewer projects from the canvas datoms. The existing model-build pipeline (`src/fukan/model/build.clj`) either gets a new path that reads canvas, or the canvas store projects into the existing graph format. Sprint 3 makes that call.

**Tech stack:** Same. Clojure 1.11, Datascript, http-kit, reitit. nREPL for REPL workflow.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-25-emergence-comparison.md` — the substantive synthesis from Phase 2 Sprint 2a + Sprint 2b. The recommendations there shaped the vocab libraries; Phase 3 builds on those libraries.
- `doc/plans/2026-05-25-pilot-port-findings.md` and `doc/plans/2026-05-25-emergence-corpus-readme.md` — what Phase 2 found by porting (the gaps and the patterns).
- `doc/plans/2026-05-25-canvas-substrate-redesign.md` — substrate vision (untouched).
- `doc/plans/2026-05-25-canvas-substrate-phase-2.md` and `2026-05-25-canvas-substrate-phase-2-emergence.md` — Phase 2 plans, for execution patterns and conventions Phase 3 inherits.

**Scope of this plan:** Phase 3 only. Phase 4 (authoring loop, collaborative canvas editing, LLM-assisted design conversations) gets its own plan informed by Phase 3's verification.

**Resolved decisions (from the open-questions section, settled 2026-05-26):**

1. **Sprint 3 graph integration: approach B** — new pipeline reading directly from canvas store. Long-term shape; aligned with Phase 4 authoring requirements.
2. **Sprint 4 retirement strategy: α-modified** — remove `.allium`/`.boundary` from `src/`; move all originals into a `.legacy-allium/` folder at project root (preserving directory structure) for archival reference; delete the analyzer subsystem (`src/fukan/vocabulary/`); update `src/fukan/model/build.clj` to drop analyzer dispatch.
3. **Broader porting scope: full** — port every existing `.allium`/`.boundary` pair; no exceptions.
4. **Test rigor: thin first, harden later** — Phase 1 pattern (assert "non-empty store + key entity names present"); stronger assertions are a follow-up.
5. **Vocab expansion mid-Phase-3: tackle hand-in-hand** — if Sprint 2 surfaces a clear lift gap, ship the fix inline; escalate to the user only if the gap raises a fundamental question.

**New structural decision (2026-05-26):**

- **Move from co-located specs to a dedicated `canvas/` folder at project root.** Currently `.allium`/`.boundary` files live alongside `.clj` implementation files in `src/fukan/<subsystem>/`. Going forward, canvas specs live in a sibling tree:
  - **Folder:** `canvas/` at project root (added to `deps.edn` `:paths`).
  - **Namespace convention:** `canvas.<subsystem>.<module>`. Example: `canvas/infra/server.clj` → ns `canvas.infra.server`.
  - **Rationale:** `fukan.canvas.*` (under `src/`) is canvas *machinery* (core, construction, vocab libraries). `canvas.*` (under top-level `canvas/`) is canvas *content* (the design surface for this project). The two prefixes make the distinction visible.
  - **Phase 1 pilots migrate** from `src/fukan/canvas/pilot/<module>.clj` to `canvas/<subsystem>/<module>.clj` in Sprint 1; broader porting (Sprint 2) writes to the same destination.

---

## File structure (Phase 3)

**New top-level folder:**

```
canvas/                                                  ; new — canvas specs (the design surface)
  infra/server.clj                                       ; ns: canvas.infra.server
  infra/model.clj                                        ; ns: canvas.infra.model
  constraint/<module>.clj                                ; ns: canvas.constraint.<module>
  validation/<module>.clj                                ; ns: canvas.validation.<module>
  vocabulary/<module>.clj                                ; ns: canvas.vocabulary.<module>
  web/<module>.clj                                       ; ns: canvas.web.<module>
  model/<module>.clj                                     ; ns: canvas.model.<module>
  ; ~25–35 files total, one per ported module
```

**New files:**

```
test/canvas/<subsystem>/<module>_test.clj               ; one test per port; ns: canvas.<subsystem>.<module>-test
src/fukan/canvas/projection/canvas_source.clj           ; Sprint 3 — pipeline reading canvas store directly (approach B)

doc/plans/
  2026-05-26-folder-restructure-notes.md                ; Sprint 1 — captures the deps.edn + ns convention shifts
  2026-05-26-broader-porting-notes.md                   ; Sprint 2 — gaps surfaced during broader porting
  2026-05-26-graph-integration-design.md                ; Sprint 3 — approach-B design + canvas-store query layer
  2026-05-26-legacy-retirement-execution.md             ; Sprint 4 — α-modified retirement execution log
  2026-05-26-phase-3-verification.md                    ; Sprint 6 output
```

**Files to modify:**

```
deps.edn                                                ; Sprint 1 — :paths adds "canvas" and "test/canvas"
src/fukan/canvas/core/substrate.clj                     ; Sprint 1 — add doc-of accessor + :doc field on records
src/fukan/canvas/core/substrate/store.clj               ; Sprint 1 — schema + ->datoms wiring for :doc
src/fukan/canvas/core/helpers.clj                       ; Sprint 1 — declare-affordance passes :doc through
src/fukan/canvas/construction.clj                       ; Sprint 1 — lifts pass doc to declare-affordance
src/fukan/canvas/vocab/behavioral.clj                   ; Sprint 1 — invariant passes doc
src/fukan/canvas/vocab/validation.clj                   ; Sprint 1 — checker passes doc
src/fukan/canvas/vocab/lifecycle.clj                    ; Sprint 1 — getter passes doc (rename _doc → doc)
src/fukan/model/build.clj                               ; Sprint 3 — drops analyzer dispatch, adds canvas-source path
src/fukan/infra/model.clj                               ; Sprint 3 — model lifecycle reads canvas store
VISION.md, MODEL.md, DESIGN.md, README.md, CLAUDE.md, AGENTS.md  ; Sprint 5 — vision-doc updates
```

**Files to move (Sprint 1 + Sprint 4):**

```
Sprint 1 — relocate Phase 1 pilots:
  src/fukan/canvas/pilot/server.clj                → canvas/infra/server.clj (ns: canvas.infra.server)
  src/fukan/canvas/pilot/constraint_evaluator.clj  → canvas/constraint/evaluator.clj
  src/fukan/canvas/pilot/vocabulary_analyzer.clj   → canvas/vocabulary/allium/analyzer.clj
  src/fukan/canvas/pilot/validation_phase4.clj     → canvas/validation/phase4.clj
  test/fukan/canvas/pilot_test.clj                 → test/canvas/<each>/_test.clj per port

Sprint 4 — relocate legacy specs:
  src/fukan/**/*.allium                             → .legacy-allium/<mirrored path>/*.allium
  src/fukan/**/*.boundary                           → .legacy-allium/<mirrored path>/*.boundary
```

**Files to delete (Sprint 4):**

```
src/fukan/vocabulary/                                    ; analyzer subsystem — no longer reads any source
src/fukan/canvas/pilot/                                  ; empty after Sprint 1 migration; remove the directory
```

---

# Sprint 1 — Foundation completion + folder restructure (Tasks 1–4)

Small, well-specified cleanup. Closes the doc-string persistence gap surfaced by Phase 2 Sprint 2b (same pattern as the `formal-expression` bug). Sets up the dedicated `canvas/` top-level folder and migrates the four Phase 1 pilots into it. Refactors the migrated pilots against the new vocab libraries. Surfaces any remaining Phase 2 carryover before broader porting begins.

---

## Phase 3, Task 1: `:entity/doc` substrate persistence

**Files:**
- Modify: `src/fukan/canvas/core/substrate.clj` — add `doc-of` accessor
- Modify: `src/fukan/canvas/core/substrate/store.clj` — schema + `->datoms :Affordance` wiring
- Modify: `src/fukan/canvas/core/helpers.clj` — `declare-affordance` accepts `:doc` and threads it
- Modify: `src/fukan/canvas/construction.clj` — `function`, `record`, `value` pass `doc` to `declare-affordance`
- Modify: `src/fukan/canvas/vocab/behavioral.clj` — `invariant` passes `doc`
- Modify: `src/fukan/canvas/vocab/validation.clj` — `checker` passes `doc`
- Modify: `src/fukan/canvas/vocab/lifecycle.clj` — `getter` passes `doc` (rename `_doc` back to `doc`)
- Test: extend existing tests with doc-persistence assertions; add one focused doc-persistence test in `test/fukan/canvas/core/substrate_test.clj`

The exact same pattern as the Phase 2 `formal-expression` fix: the field exists on the `Affordance` record (the lift signature already accepts `doc`) but the store's `->datoms :Affordance` cond-> chain silently drops it.

- [ ] **Step 1: Test** — write a failing test asserting that a lift's docstring is queryable from the store after construction.

- [ ] **Step 2: Add `doc-of` accessor** to `substrate.clj`. The `Affordance` record doesn't yet have a `:doc` field; add it: `(defrecord Affordance [id name module shape role formal-expression doc tags])`. Update the `affordance` constructor function to accept `:doc` via `:keys` destructuring. Add `(defn doc-of [e] (:doc e))`.

- [ ] **Step 3: Schema entry** — in `store.clj`, add `:affordance/doc {:db/index true}` to the schema map.

- [ ] **Step 4: `->datoms :Affordance`** — add the cond-> clause `(sub/doc-of a) (assoc :affordance/doc (sub/doc-of a))`.

- [ ] **Step 5: Lift integration** — in each of the four lifts (`function`, `record`, `value`, `invariant`, `checker`, `getter`), the existing `doc` parameter is now passed to `declare-affordance` as `:doc doc`. Note: `record` and `value` produce Types, not Affordances. Decision: also add `:type/doc` to the schema and persist Type docstrings? **Recommendation: yes — Types are first-class entities; their docstrings deserve persistence.** Mirror the same change in `->datoms :Type`.

- [ ] **Step 6: Verify** — full canvas suite passes; queries for the docs land. Expect 54 + 1 (new dedicated test) + maybe 4-5 extended assertions in existing tests = ~55 tests passing.

- [ ] **Step 7: Commit**

```bash
jj desc -m "feat(canvas/substrate): persist :affordance/doc and :type/doc to store (closes the Phase 2 doc-string gap)"
jj new
```

---

## Phase 3, Task 2: Set up the top-level `canvas/` folder

**Files:**
- Modify: `deps.edn` — add `"canvas"` and `"test/canvas"` to `:paths`
- Create: `canvas/` directory at project root (empty for now; populated in Task 3)
- Create: `test/canvas/` directory at project root

This task is mostly mechanical and small.

- [ ] **Step 1: Update `deps.edn`.** Add `"canvas"` to the top-level `:paths` vector. Add `"test/canvas"` to the `:extra-paths` of the `:test` alias.

- [ ] **Step 2: Create empty directories.** `mkdir -p canvas test/canvas`. Add a placeholder `.keep` or readme so the directories aren't dropped on commit.

- [ ] **Step 3: Smoke test.** Run `clj -P` to confirm deps resolve and the new paths are picked up. Run `clj -M:test -r 'fukan\.canvas\..*-test'` to confirm existing tests still pass.

- [ ] **Step 4: Commit**

```bash
jj desc -m "build(canvas): add top-level canvas/ folder for system specs (separates design surface from impl)"
jj new
```

---

## Phase 3, Task 3: Migrate the four Phase 1 pilots into the new folder

**Files:**
- Create: `canvas/infra/server.clj` (ns `canvas.infra.server`)
- Create: `canvas/constraint/evaluator.clj` (ns `canvas.constraint.evaluator`)
- Create: `canvas/vocabulary/allium/analyzer.clj` (ns `canvas.vocabulary.allium.analyzer`)
- Create: `canvas/validation/phase4.clj` (ns `canvas.validation.phase4`)
- Create: corresponding test files in `test/canvas/<subsystem>/<module>_test.clj`
- Delete: `src/fukan/canvas/pilot/server.clj`, `constraint_evaluator.clj`, `vocabulary_analyzer.clj`, `validation_phase4.clj`
- Delete: `src/fukan/canvas/pilot/` directory once empty
- Delete: `test/fukan/canvas/pilot_test.clj` (replaced by per-port test files)

During the migration, also refactor the pilots against the new vocab libraries — the Phase 1 pilots used old `library.monolith` patterns plus escape hatches to `h/declare-affordance` for unsupported concepts. With Phase 2's vocab libraries, the escape hatches go away.

Each pilot:
- `canvas/infra/server.clj` — `vocab.lifecycle/getter` for the getters; `construction/function` for stop_server; `construction/record` for ServerOpts/ServerInfo; `vocab.behavioral/invariant` for SingleServerInstance
- `canvas/constraint/evaluator.clj` — `construction/value` for Stratum/Binding (escape hatch removed); `vocab.behavioral/invariant` for the seven invariants; `construction/function` for evaluate_rules and query
- `canvas/vocabulary/allium/analyzer.clj` — `vocab.behavioral/invariant` for the eight invariants; `construction/record` for ExposesIssue and EventShapeMismatch (using shape-expression grammar for List/Optional fields now possible after Phase 2 Sprint 1); `construction/function` for the two functions; the `rule AnalyzeFile` declaration stays as an inline TODO with structural intent noted
- `canvas/validation/phase4.clj` — `vocab.validation/checker` for the rules_4X family; `vocab.behavioral/invariant` for the six invariants; `construction/function` for run and gate_g2; the `rule RunPhase4` declaration stays as an inline TODO

Each ported file's test asserts the build produces a non-empty store with the expected key entity names present (thin assertions per the agreed test-rigor decision).

- [ ] **Step 1: Per-pilot migrate-and-refactor.** One file at a time. For each:
  - Create the new file at the new location with the new ns.
  - Refactor to use vocab libraries (drop escape hatches).
  - Create the parallel test file at the new test location.
  - Delete the old source file and old test entries.

- [ ] **Step 2: Confirm tests pass at each step.** `clj -M:test -r '(fukan\.canvas|canvas)\..*-test'` (extending the test regex to cover both ns prefixes).

- [ ] **Step 3: Drop the empty `src/fukan/canvas/pilot/` directory** after the last migration.

- [ ] **Step 4: One commit per pilot migration:**

```bash
jj desc -m "feat(canvas): migrate infra/server pilot to canvas/infra/server using vocab.lifecycle + vocab.behavioral"
jj new
# repeat per pilot
```

Four migration commits.

---

## Phase 3, Task 4: Verify Sprint 1 — full suite + manual sanity check

- [ ] **Step 1: Full canvas suite passes.** `clj -M:test -r 'fukan\.canvas\..*-test'`. Expect ~55 tests.

- [ ] **Step 2: Read each refactored pilot aloud.** Does the canvas form read at module-design altitude using the vocab libraries, vs the Phase 1 pilots' "all generic functions plus escape hatches"? Note any awkwardness that suggests a vocab library is still missing a critical lift.

- [ ] **Step 3: Commit a brief Sprint 1 notes doc** if anything notable surfaced. (Optional; skip if everything was mechanical.)

---

# Sprint 2 — Broader porting (Tasks 5–6)

Port every existing `.allium`/`.boundary` pair in fukan to canvas form. The four Phase 1 pilots + the three Sprint 2a session ports + the four-of-eight Sprint 2b corpus modules cover ~12 modules; fukan has roughly 30-35 module pairs total (count by glob: `find src/fukan -name '*.allium' | wc -l`). The remaining 18-23 are this sprint's work.

The Sprint 1 vocab libraries should cover the majority. Anything that doesn't fit is a Phase 4 or follow-up signal.

**Module list** — enumerate all `.allium` files in `src/fukan/**` not already ported:

- `src/fukan/constraint/ast.allium`
- `src/fukan/constraint/derivations.allium`
- `src/fukan/constraint/derivations_extra.allium`
- `src/fukan/constraint/phase5.allium`
- `src/fukan/constraint/sort.allium`
- `src/fukan/constraint/well_known.allium`
- `src/fukan/validation/rules_4b.allium` through `rules_4g.allium` (six files)
- `src/fukan/vocabulary/allium/effect_canonicalise.allium`
- `src/fukan/vocabulary/allium/expression.allium`
- `src/fukan/vocabulary/allium/renderers.allium`
- `src/fukan/vocabulary/allium/tags.allium`
- `src/fukan/vocabulary/boundary/analyzer.allium`
- `src/fukan/vocabulary/boundary/pipeline.allium`
- `src/fukan/vocabulary/boundary/tags.allium`
- `src/fukan/web/handler.allium` (if not done)
- `src/fukan/web/views/*.allium`
- `src/fukan/model/*.allium` (likely already covered indirectly; verify)

(Exact count to enumerate at execution time.)

---

## Phase 3, Task 5: Broader porting — execution

**Files:** one canvas port per module, in `canvas/<subsystem>/<module>.clj` (ns `canvas.<subsystem>.<module>`). One test per port, in `test/canvas/<subsystem>/<module>_test.clj`.

- [ ] **Step 1: Enumerate** — list all `.allium` files not yet ported. Confirm the count and the subsystem distribution.

- [ ] **Step 2: Port per subsystem, sequentially.** Suggested order:
  1. `infra/` (small, mechanical)
  2. `vocabulary/` (largest, most behavioral content)
  3. `constraint/` (rich invariants)
  4. `validation/` (lots of rules_4X family work)
  5. `web/` (different shape — transport, routing)
  6. `model/` (the kernel; port last because everything else references it)

  One commit per port per the standing per-port commit rule.

- [ ] **Step 3: Catalog gaps as they emerge.** Document each in `doc/plans/2026-05-26-broader-porting-notes.md`. Per the resolved decision: if a gap clearly demands a small targeted lift (e.g. broader porting surfaces 5+ instances of a pattern the current vocab libraries don't cover), ship the lift inline as a Sprint-2.5 addition. Escalate to the user only if the gap raises a fundamental question about the layering principle. Use the bare-substrate escape (`h/declare-affordance` etc.) with TODOs only when the gap genuinely warrants thought rather than direct action.

- [ ] **Step 4: Tests verify each port produces a non-empty store** — the same level of rigor as Phase 1 Task 9. Stronger semantic assertions can come in a follow-up; broader porting is about coverage.

- [ ] **Step 5: After all ports complete, run the full suite.** Expect ~55 + (3 assertions × N ports) tests. All passing.

- [ ] **Step 6: Compile the broader-porting findings doc** at `doc/plans/2026-05-26-broader-porting-notes.md`. Capture: gaps encountered, vocab-library-coverage assessment, any inline lift additions shipped during Sprint 2, recommendations for Phase 4.

---

## Phase 3, Task 6: Sprint 2 review and gap synthesis

- [ ] **Step 1: Read the broader-porting findings doc aloud.** Note any pattern where multiple ports needed the same escape hatch — that's evidence for a follow-up vocab lift, deferred to Phase 4 unless critical.

- [ ] **Step 2: Decision point.** Three possible outcomes:
  1. **Vocab libraries covered everything cleanly.** Proceed directly to Sprint 3.
  2. **One or two gaps emerged that need addressing before graph integration.** Ship those lifts as targeted additions; then Sprint 3.
  3. **Many gaps — the vocab vocabulary is significantly incomplete.** Pause for a brainstorming round on what's missing; the gap may indicate the layering principle needs revision.

  Most likely: (1) or (2). The Sprint 2 vocab libraries were calibrated on 12+ modules; the remaining 20 are within the same fukan-self domain.

---

# Sprint 3 — Graph integration via approach B (Tasks 7–9)

The canvas substrate's datoms must drive the fukan graph viewer end-to-end. Per the resolved decision, Phase 3 takes **approach B**: a new pipeline reading directly from the canvas store. This is the architecturally clean shape (canvas IS the model, not a sibling of the model) and the right long-term foundation — Phase 4 authoring work will need real-time canvas→graph reflection, which is much closer to free under approach B.

The cost: this sprint is larger than approach A would have been. The model build pipeline in `src/fukan/model/build.clj` and the lifecycle in `src/fukan/infra/model.clj` both need significant rework. The handler in `src/fukan/web/handler.clj` continues to fetch model per-request; what changes is the model SOURCE.

---

## Phase 3, Task 7: Graph integration design (approach B)

**Files:**
- Create: `doc/plans/2026-05-26-graph-integration-design.md`

Non-code task. Output is a design doc that specifies:

- **Canvas store as the authoritative model source.** Define a `canvas-source` namespace that exposes the canvas datoms in the shape the graph viewer needs. The graph viewer continues to consume a single model value; only the source changes.
- **Datascript-query layer.** Specify the queries used to project canvas entities into graph nodes/edges. Module → node; Affordance → node with optional shape annotation; Relation → edge; Tag → node attribute; State → node with shape annotation. The query layer lives at `src/fukan/canvas/projection/canvas_source.clj`.
- **Build pipeline restructure.** `src/fukan/model/build.clj` currently dispatches to language analyzers (Clojure, Allium, Boundary). Sprint 4 will retire the Allium/Boundary analyzers entirely. For Phase 3 Sprint 3, build.clj keeps the Clojure analyzer dispatch AND adds the canvas-source path as a peer input. Sprint 4 simplifies further.
- **Lifecycle integration.** `src/fukan/infra/model.clj` currently loads source code + reads `.allium`/`.boundary` files. Sprint 3 adds: also load canvas files (require them, build a fresh canvas store, project to the graph format). `(infra-model/get-model)` returns the merged shape.
- **Per-request guarantees.** Handler fetches model per-request as today; the canvas-source path is no slower than the analyzer path (Datascript query against an in-memory db is fast).
- **REPL workflow.** `(refresh)` in `dev/user.clj` continues to work: clj-reload picks up canvas file changes the same way it picks up source-code changes, then the model rebuild includes the new canvas store.

- [ ] **Step 1: Walk the existing pipeline.** `src/fukan/model/build.clj`, `src/fukan/infra/model.clj`, `src/fukan/web/handler.clj`. Note where dispatch happens, what the model value's shape is, where caching lives.

- [ ] **Step 2: Walk the canvas store API.** `src/fukan/canvas/core/substrate/store.clj`. Confirm what queries are available; identify gaps where the graph viewer needs queries the store doesn't yet expose.

- [ ] **Step 3: Write the design doc.** Approach B fully sketched. The Sprint 4 retirement path noted as a follow-on simplification.

- [ ] **Step 4: Pause for user review.** Major architectural change; confirm before implementation.

- [ ] **Step 5: Commit**

```bash
jj desc -m "doc(canvas): graph integration design — approach B (canvas-as-model-source)"
jj new
```

---

## Phase 3, Task 8: Canvas-source pipeline implementation

**Files:**
- Create: `src/fukan/canvas/projection/canvas_source.clj` — the projection from canvas datoms to graph model shape
- Modify: `src/fukan/model/build.clj` — add canvas-source dispatch alongside existing analyzers
- Modify: `src/fukan/infra/model.clj` — lifecycle loads canvas files, projects them, includes in model

Content tracks Task 7's design. TDD throughout.

- [ ] **Step 1: Tests for `canvas-source/project`** — assert that a known canvas build (e.g. `canvas.infra.server`) produces the expected graph-node-and-edge shape.

- [ ] **Step 2: Implement `canvas-source/project`.** Datascript queries against the canvas store; outputs in the graph format expected by `model/build.clj`.

- [ ] **Step 3: Integrate into `model/build.clj`.** Add the canvas-source path as a peer to existing analyzer dispatch.

- [ ] **Step 4: Integrate into `infra/model.clj`.** On load/refresh, also process canvas files; merge results.

- [ ] **Step 5: REPL sanity check.** From `dev/user.clj`: `(start)` → server up; `(refresh)` → reload + model rebuild includes canvas content.

- [ ] **Step 6: Browser sanity check.** Open the graph viewer, see canvas-authored entities appearing. Confirm legacy `.allium`/`.boundary` content also still appears (will retire in Sprint 4).

- [ ] **Step 7: Commit**

```bash
jj desc -m "feat(canvas/projection): canvas store as model source for fukan graph viewer"
jj new
```

---

## Phase 3, Task 9: Sprint 3 verification

- [ ] **Step 1: Full test suite passes** including graph projection tests.

- [ ] **Step 2: Browser sanity check** — confirm the graph viewer renders canvas-authored content correctly. Confirm that legacy `.allium`-authored content still appears alongside (until Sprint 4 retirement decision).

---

# Sprint 4 — Legacy retirement (Tasks 10–12)

Per the resolved decision: **α-modified**. Remove all `.allium`/`.boundary` files from `src/`; move the originals into a `.legacy-allium/` folder at project root (preserving the directory structure for archival reference); delete the analyzer subsystem at `src/fukan/vocabulary/`; remove analyzer dispatch from the build pipeline. After Sprint 4, the canvas in `canvas/<subsystem>/<module>.clj` is the sole authoritative model source for fukan-itself.

The `.legacy-allium/` folder is not on the classpath. It exists purely as a write-once archive — if anyone needs to read what fukan's spec looked like in the Allium era, the files are there.

---

## Phase 3, Task 10: Move legacy specs into `.legacy-allium/`

**Files:**
- Create: `.legacy-allium/` directory at project root
- Move: `src/fukan/**/*.allium` → `.legacy-allium/<mirrored path>/*.allium`
- Move: `src/fukan/**/*.boundary` → `.legacy-allium/<mirrored path>/*.boundary`
- Add: `.legacy-allium/README.md` noting that the folder is archival; canvas at `canvas/<subsystem>/<module>.clj` is now authoritative
- Modify: `.gitignore` if needed to ensure `.legacy-allium/` is committed (the leading dot makes some tools assume it's machine state)

- [ ] **Step 1: Enumerate all `.allium` and `.boundary` files** under `src/fukan/`. Confirm count.

- [ ] **Step 2: Move them** preserving directory structure. E.g. `src/fukan/infra/server.allium` → `.legacy-allium/infra/server.allium`.

- [ ] **Step 3: Write `.legacy-allium/README.md`.** One paragraph explaining the archive: canvas-first, these are the pre-canvas specs, the dating of when they were retired, and a pointer to `canvas/<subsystem>/<module>.clj` for each one.

- [ ] **Step 4: Confirm `.gitignore` allows committing `.legacy-allium/`.** The leading dot might cause issues with some tools; verify.

- [ ] **Step 5: Run the full canvas test suite.** All tests should still pass (no canvas code references `.allium`/`.boundary` files; only the analyzer subsystem does, and that's still in place at this step).

- [ ] **Step 6: Commit**

```bash
jj desc -m "refactor: move legacy .allium and .boundary specs to .legacy-allium/ archive (canvas-first migration)"
jj new
```

---

## Phase 3, Task 11: Delete the analyzer subsystem

**Files:**
- Delete: `src/fukan/vocabulary/` (recursive — entire subsystem)
- Modify: `src/fukan/model/build.clj` — remove all dispatch paths to Allium/Boundary analyzers; keep only Clojure analyzer + canvas-source
- Modify: `dev/user.clj` — remove any references to retired analyzer namespaces
- Modify: `CLAUDE.md` — remove guidance about `.allium`/`.boundary` editing (Sprint 5 does the full vision-doc updates; this is the targeted fix to keep the project working day-to-day)

The analyzer subsystem in `src/fukan/vocabulary/` reads `.allium`/`.boundary` files. With those files moved to `.legacy-allium/` (off the classpath), the analyzers have nothing to read. They're now dead code.

- [ ] **Step 1: Identify all references** to `fukan.vocabulary.*` namespaces in the codebase. Grep across `src/`, `test/`, `dev/`, `canvas/`. Confirm what calls into the analyzer subsystem.

- [ ] **Step 2: Update `src/fukan/model/build.clj`** to drop analyzer dispatch. The build now uses: Clojure analyzer (for `.clj` source code) + canvas-source (from Sprint 3).

- [ ] **Step 3: Update `dev/user.clj`** if it requires analyzer namespaces.

- [ ] **Step 4: Delete `src/fukan/vocabulary/` recursively.** Also delete `test/fukan/vocabulary/` if it exists.

- [ ] **Step 5: Verify** the full test suite still passes. The canvas suite and canvas-source pipeline are unaffected; only the analyzer-driven path is gone.

- [ ] **Step 6: Browser sanity check.** Graph viewer renders correctly with ONLY canvas-authored entities visible.

- [ ] **Step 7: Commit**

```bash
jj desc -m "refactor: delete fukan.vocabulary analyzer subsystem (canvas is now sole spec source)"
jj new
```

---

## Phase 3, Task 12: Sprint 4 verification + execution log

**Files:**
- Create: `doc/plans/2026-05-26-legacy-retirement-execution.md` — brief log of what happened (counts moved, refs updated, anything that broke)

- [ ] **Step 1: Full suite passes** in the post-retirement state.

- [ ] **Step 2: Browser sanity check** — graph viewer shows only canvas-authored content; nothing missing vs the pre-retirement state.

- [ ] **Step 3: Write the execution log.** Capture the file counts, the analyzer-related code removed, anything surprising. The doc is for future-you understanding why the retirement looked the way it did.

- [ ] **Step 4: Commit**

```bash
jj desc -m "doc(canvas): Sprint 4 legacy retirement execution log"
jj new
```

---

# Sprint 5 — Vision documentation update (Task 13)

## Phase 3, Task 13: Update vision docs to reflect canvas-first state

**Files:**
- Modify: `VISION.md`, `MODEL.md`, `DESIGN.md`
- Possibly modify: `README.md`, `CLAUDE.md`, `AGENTS.md`

The vision docs were written when `.allium`/`.boundary` were the primary design surface. Phase 3 makes them legacy (β) or extinct (α). The docs need to reflect canvas-first state.

Updates per doc:
- **VISION.md** — overall direction, what fukan is. Add the canvas-first state.
- **MODEL.md** — the substrate model description. Update to describe canvas datoms as the authoritative model; analyzers as historical/legacy.
- **DESIGN.md** — design principles. Add the three-tier layering (`core` / `construction` / `vocab.*`) as a documented principle.
- **README.md** — what fukan is for an external reader. Brief mention of canvas as the design surface.
- **CLAUDE.md** — project conventions for AI work. Add canvas conventions; demote `.allium`/`.boundary` references to "legacy."
- **AGENTS.md** — agent primer. Update so future agents reaching for `.allium`/`.boundary` instead reach for canvas.

- [ ] **Step 1: Read each vision doc** and identify the sections needing canvas-first updates.
- [ ] **Step 2: Update each doc** preserving the design philosophy but reflecting the new state.
- [ ] **Step 3: Commit** per doc, or as a single "vision docs canvas-first update" commit. Latter is acceptable here because the updates are coordinated.

```bash
jj desc -m "doc: vision docs reflect canvas-first state (Phase 3 closeout)"
jj new
```

---

# Sprint 6 — Verification (Task 14)

## Phase 3, Task 14: Phase 3 verification report + Phase 4 brief

**Files:**
- Create: `doc/plans/2026-05-26-phase-3-verification.md`

Mandatory content per the verification template Phase 1 and Phase 2 established:

- [ ] **Section 1: What was attempted vs. built.** Recap each sprint.

- [ ] **Section 2: Did the canvas substrate become observable?** The most consequential Phase 3 question. The success criterion: a fukan user (or LLM-architect) authoring a module in canvas sees that module in the graph viewer with no separate analyzer pass. Is that working end-to-end?

- [ ] **Section 3: Did broader porting reveal new structural gaps?** Reference the Sprint 2 findings doc. Anything significant carries forward as Phase 4 work.

- [ ] **Section 4: Was retirement strategy execution clean?** Reflect on whether α or β was the right call and what feedback the codebase gave during execution.

- [ ] **Section 5: Decision.** Three outcomes:
  1. **Analysis substrate is canvas-native end-to-end** → Phase 4 (authoring loop) can begin.
  2. **Substrate is canvas-native with caveats** → Phase 3.5 to close caveats, then Phase 4.
  3. **Something broke** → reset, examine evidence.

- [ ] **Section 6: Phase 4 implications.** Sketch what authoring-loop work needs.

- [ ] **Step Final: Commit**

```bash
jj desc -m "doc(canvas): phase-3 verification + phase-4 brief"
jj new
```

---

## Subsequent phases (sketches)

**Phase 4 — Authoring loop**: Build the human-LLM collaboration surface at canvas altitude. Canvas editing (where? embedded in graph viewer? a separate text-mode interface? an LLM-chat-driven authoring conversation?). LLM as a co-author: suggests completions, identifies inconsistencies, proposes alternative paradigm choices.

**Phase 5 — Vocab library expansion**: Build additional methodology vocabularies as use cases warrant — `vocab.event`, `vocab.cqrs`, `vocab.actor`, etc. Each gets its own evidence-grounded justification before shipping.

**Phase 6 — LLM ergonomics deepening**: Constraint diagnostics, named-entity resolution in queries, examples library shipped with each vocab. Driven by usage evidence.

---

## Self-review notes

- **Substrate untouched in spirit, slightly extended in fact.** Task 1 adds `:entity/doc` and `:type/doc` persistence. These are additive — the same shape as Sprint 2b's `formal-expression` fix. Phase 3 should not need primitive changes beyond Task 1; if a task tempts the implementer to add a primitive, escalate.
- **Sprint 2's per-port commit rule is non-negotiable.** Phase 1 collapsed pilot ports into one commit and was rightly criticized. Phase 3 broader porting has ~20+ commits if done right; that's the expected texture.
- **Sprint 3's design decision (A vs B for graph integration) is load-bearing.** Pause for user review before implementing.
- **Sprint 4's retirement decision (α vs β) is equally load-bearing.** Pause for user review before executing.
- **Sprint 5 (vision docs) comes AFTER the technical state stabilizes.** Don't update the vision docs ahead of the implementation — they should reflect what shipped, not what was planned.
- **Phase 3 is the largest phase by commit count.** Phase 1 was 10 tasks; Phase 2 was 9 tasks + Sprint 2b's 3 tasks. Phase 3 is 13 tasks but Sprint 2's "Broader porting — execution" task covers ~20 commits internally. Total commit count for Phase 3: probably 35-45 commits.
- **Authoring is explicitly Phase 4.** Resist the temptation to mix in authoring-loop work during Phase 3; the strategic decision was analysis-first.

---

## Decisions made (2026-05-26)

All five open questions from the original Phase 3 draft were settled in conversation:

1. **Sprint 3 graph integration: approach B** — new pipeline reading from canvas store directly. Long-term-correct shape; supports Phase 4 authoring better than the bridge approach.
2. **Sprint 4 retirement: α-modified** — remove `.allium`/`.boundary` from `src/`; move to `.legacy-allium/` archive folder; delete the analyzer subsystem.
3. **Broader porting scope: full** — every existing `.allium`/`.boundary` pair gets ported.
4. **Test rigor: thin first, harden later** — Phase 1 pattern for Sprint 2; follow-up sprint can strengthen.
5. **Vocab expansion mid-Phase-3: tackle hand-in-hand** — if Sprint 2 surfaces a clear lift gap, ship inline; escalate to user only for fundamental questions.

Plus one structural decision:

6. **Dedicated `canvas/` top-level folder** for system specs, separate from `src/fukan/` implementation code. Namespace `canvas.<subsystem>.<module>`. Added to `deps.edn` `:paths`. Phase 1 pilots migrate from `src/fukan/canvas/pilot/` to `canvas/<subsystem>/` in Sprint 1.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–4 | Substrate doc-string persistence; top-level `canvas/` folder created; Phase 1 pilots migrated + refactored |
| 2 | 5–6 | Every existing `.allium`/`.boundary` ported to `canvas/`; gap catalog; inline vocab additions if surfaced |
| 3 | 7–9 | Canvas store drives the graph viewer directly (approach B) |
| 4 | 10–12 | `.allium`/`.boundary` files moved to `.legacy-allium/`; analyzer subsystem deleted |
| 5 | 13 | Vision docs reflect canvas-first state |
| 6 | 14 | Phase 3 verification + Phase 4 brief |

**Estimated calendar:** Sprint 1 ≈ 1–2 sessions (folder setup + pilot migration + doc fix). Sprint 2 ≈ 3–4 sessions (sustained broader porting; subagent-driven; many small commits). Sprint 3 ≈ 1 design + 2 implementation sessions (approach B is larger than A would have been; pause point after design). Sprint 4 ≈ 1 session (mechanical bulk; verify after). Sprint 5 ≈ 1 documentation session. Sprint 6 ≈ 1 verification session. **Total: 9–11 working sessions.**
