# Canvas + Substrate Implementation Plan — Phase 6

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect, structure, and surface design-vs-implementation drift. Phase 5 made the canvas a thinking-enhancing tool for *design*; Phase 6 closes the loop with *code* by extending fukan's existing code-side analyzer so canvas content drives what fukan expects to find in `src/`, and aggregating the resulting drift signal into the feedback tiers Phase 5 built (trust + weigh).

**Phase 6's strategic frame:** Canvas is design intent; `src/` is implementation. The two diverge over time — a canvas function declared but never written, a record's fields evolving in code without the canvas catching up, an invariant declared with no code enforcing it. Today those drifts are silent. Phase 6 makes them queryable: integrity-style findings the LLM author can read between authoring rounds and decide whether the canvas, the code, or both should move.

The previous Phase 5 plan's deferred-phases sketch named this Phase 7 ("diff detection between canvas design and code implementation"). With the browser-UI work indefinitely deferred, this slot opens to the drift-detection work directly.

**Architecture:** Phase 6 builds on existing machinery — `src/fukan/target/clojure/` already walks Clojure source, identifies fn + def-shaped forms, emits Code.* Artifacts, and tags each projection edge with `:validity {:valid | :absent}`. That's drift detection at the substrate level today. What's missing: (a) canvas content driving the *expected* side of the projection (currently the analyzer projects from older MODEL/spec primitives), (b) an LLM-facing surface that aggregates `:validity` into structured findings, (c) the feedback-loop integration with `fukan-architect`.

**Tech stack:** Same. Clojure 1.11, Datascript. The Clojure target analyzer is already in place; Phase 6 adapts it. nREPL for the live workflow.

**Naming note — two senses of "Phase":** The build pipeline has internally-numbered phases (Phase 0 canvas ingestion → Phase 4 structural validation → Phase 5 constraint evaluation → Phase 6 Clojure analyzer). The PROJECT trajectory has its own phases (Phases 1-5 already shipped; this is Project Phase 6). They're independent numbering schemes that happen to collide at 6. Throughout this doc, **"Phase 6"** means the project phase (this plan); the build-pipeline Phase 6 is referred to explicitly as **"the Clojure analyzer"** or **"build-Phase-6"** to avoid confusion.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-26-phase-5-verification.md` — Phase 5 outcomes; the substrate Phase 6 builds on
- `doc/plans/2026-05-27-trial-run-findings.md` — what the loop felt like in practice; informs what drift output should feel like
- `doc/canvas-authoring-system-prompt.md` — the LLM-facing activation Phase 6 extends
- `src/fukan/target/clojure/analyzer.clj` — the existing code-side analyzer; the load-bearing inheritance for Phase 6
- `src/fukan/target/clojure/{source,projector,blueprint,address}.clj` — supporting machinery
- `src/fukan/canvas/inspect/integrity.clj` — Phase 5's trust-tier shape; Phase 6's drift helper will mirror it
- `src/fukan/canvas/lens/*` — Phase 5's lens substrate; drift may also surface as a lens

**Scope of this plan:** Phase 6 only.

**Subsequent phases (out of scope here):**
- Phase 7 — Implementation-instruction generation from drift findings (the eventual product surface: given drift, produce the precise code edits to align)
- Continued lens additions (methodology coherence, coupling, FCIS, DDD, etc.) as drop-a-file enhancements
- Phase 5.5+ carryover items (survey scoping, coverage warning filtering) — pulled into Phase 6 sprints where they're prerequisite

---

## What "drift detection" means concretely

Phase 6 ships infrastructure for the following kinds of finding. Sprint 1 Task 1 settles which of these ship vs which defer.

1. **Missing implementation.** A canvas `function` exists; no fn of that name in the expected `src/` location. The canvas declared intent; code didn't follow.
2. **Missing canvas.** A public fn exists in `src/`; no canvas `function` declares it. The code grew beyond what the canvas described. (This is the symmetric direction; whether to surface it is a Sprint 1 question — it could be noise in projects where not every fn deserves canvas presence.)
3. **Shape drift on records.** A canvas `record` declares fields `[a b c]`; the implementation defrecord/schema has `[a b d]`. Field-name and field-type mismatches both qualify.
4. **Signature drift on functions.** Canvas says `(takes [:String :Integer]) (gives :Boolean)`; the fn's reflected arity or spec says otherwise. Detection depends on what the analyzer can actually inspect (clj-kondo analysis, spec hints, etc.).
5. **Invariant without enforcement.** Canvas declares an invariant ("AtMostOneLeaderPerTerm"); no code mechanism enforces it (no constraint, no schema check, no test). The author can decide: write the enforcement, or relax the invariant to documentation.
6. **Rule without trigger in code.** Canvas declares `(rule "X" (when X (model :model/Model)))` but no code path emits the trigger. Cross-cuts coverage tier from Phase 5 but with code-side evidence rather than canvas-side.
7. **Event without emit/handler in code.** Canvas declares `(event "PaymentSucceeded")` but no code calls anything that emits it.

Categories 1, 3, 5, 6, 7 produce **decision-ready facts** (something is or isn't there). Category 2 (missing canvas) is more interpretive — depends on whether every fn must be canvas-described. Category 4 (signature drift) depends on whether the analyzer can extract enough structure to compare.

The trust/weigh partition from Phase 5 likely applies again, with severity in trust-tier (some are errors, some are warnings).

---

## File structure (Phase 6)

**Likely new namespaces:**

```
src/fukan/canvas/inspect/
  drift.clj                          ; trust-tier helper aggregating drift findings from the analyzer

src/fukan/canvas/lens/                ; if interpretive framing also adds value
  drift.clj                          ; weigh-tier lens — patterns in the drift (which modules drift most, which categories cluster)

src/fukan/target/clojure/
  (modifications, not new files)     ; adapt analyzer to read canvas content as expected-side

doc/plans/
  2026-05-27-drift-signals-design.md      ; Sprint 1 output
  2026-05-27-canvas-to-code-projection-design.md  ; Sprint 1 output
  2026-05-27-phase-6-verification.md      ; Sprint 5 output
```

**Likely modified files:**

```
src/fukan/target/clojure/analyzer.clj         ; canvas-as-source-of-truth adaptation
src/fukan/target/clojure/projector.clj        ; project canvas content → code-side expectations
src/fukan/target/clojure/blueprint.clj        ; possibly — depending on how the projection model lands
src/fukan/agent/api.clj                       ; expose drift in `(help)` under :trust (and possibly :weigh)
src/fukan/canvas/projection/canvas_source.clj ; possible auto-discover canvas files (Phase 5.5 carryover)
src/fukan/canvas/lens/survey.clj              ; possible `(survey :scope <prefix>)` (Phase 5.5 carryover)
doc/canvas-authoring-system-prompt.md         ; add drift to the tier model section
AGENTS.md                                     ; same
```

**Files NOT touched:**

- Canvas substrate primitives (`core/substrate.clj`, `core/substrate/store.clj`) — settled in Phase 4
- The canvas vocab libraries — Phase 6 may notice candidates but doesn't ship vocab unless rule-of-three within authoring evidence
- The Phase 5 inspect/integrity, inspect/coverage helpers — they may inform drift but stay independent
- The Phase 5 lenses (patterns, consistency, tar-pit) — untouched; drift may join them as a fourth lens
- `.legacy-allium/` — archive stays archived

---

# Sprint 1 — Drift signals + canvas→code projection design (Tasks 1–2)

The opening sprint settles two design questions before any signals are built. Both produce docs reviewed by the user before downstream sprints begin. Mirrors Phase 5 Sprint 1's shape.

---

## Phase 6, Task 1: Drift signals — design doc

**Files:**
- Create: `doc/plans/2026-05-27-drift-signals-design.md`

### What the design doc must cover

For each of the seven drift categories listed in the "What drift detection means concretely" section above:

- **What it computes.** Concretely. Canvas datom query + code-side artifact query + comparison rule.
- **What it surfaces.** Structured finding shape, mirroring the Phase 5 violation-diagnostics pattern.
- **Tier.** Trust (decision-ready) or weigh (interpretive). Each category gets a tier verdict.
- **Severity (if trust-tier).** `:error` / `:warning` / `:info` ladder.
- **Priority for Phase 6.** Ship in Sprint 3 vs defer.
- **Dependence on the analyzer's capability.** Some categories need only fn-name extraction (already in the analyzer); others need richer signal (arity reflection, schema introspection). Note what each category needs.

### Critical design question to settle

**Which direction does drift flow?** Three philosophical stances:

1. **Canvas is intent; code follows.** Drift findings recommend code changes. The canvas is the source of truth; gaps mean implementation hasn't caught up.
2. **Code is reality; canvas describes.** Drift findings recommend canvas changes. The canvas should mirror what's been built.
3. **Neither — both directions are findings.** Drift surfaces gaps; the LLM (and human) decide per-finding whether the canvas, the code, or both should move.

Sprint 1 Task 1 picks. **Default recommendation: (3).** It matches the trust/weigh discipline (drift is decision-ready evidence; what to do about it is judgment). And it avoids declaring one side authoritative when fukan's whole stance is that canvas is design and code is implementation, both legitimate, both subject to drift.

### Steps

- [ ] **Step 1: For each of the 7 categories, sketch concrete computational shape.** What canvas query + what code-side query + what comparison.
- [ ] **Step 2: Verify the trust/weigh partition.** Flag any category that resists.
- [ ] **Step 3: Settle the directional question** (canvas-as-intent / code-as-reality / both).
- [ ] **Step 4: Pick the 4-5 highest-priority signals for Phase 6.** Defer the others.
- [ ] **Step 5: Pause for user review.** Load-bearing design conversation.
- [ ] **Step 6: Commit.**

```bash
jj desc -m "doc(canvas): Phase 6 drift-signals design"
jj new
```

---

## Phase 6, Task 2: Canvas → code projection design

**Files:**
- Create: `doc/plans/2026-05-27-canvas-to-code-projection-design.md`

### Context

The existing `src/fukan/target/clojure/analyzer.clj` projects from MODEL/spec primitives (a pre-Phase-3 path that predates the canvas-as-sole-spec-source switch). Phase 6 adapts it: canvas datoms become the projection's expected-side, replacing the older spec primitives.

The projector already understands the projection mechanic — every canvas-declared affordance becomes an *expectation* of a code-side artifact at a canonical `src/` address; the analyzer walks code and tags each projection edge `:valid` (artifact exists at expected address) or `:absent` (artifact missing). Phase 6's work is to **wire the canvas as the expectation source**, not to redesign the projection mechanic.

### What the design doc must cover

- **The canonical-address mapping.** Given a canvas entity (e.g. `function "build_canvas" in module "infra.server"`), what's its expected code-side address (e.g. `src/fukan/infra/server.clj`, fn name `build-canvas`)? Module-name → file-path rules; entity-name → identifier-case rules. The existing `src/fukan/target/clojure/address.clj` does some of this; Sprint 1 reviews and confirms.
- **What's projected vs what's not.** Canvas declares Affordances (functions, getters, checkers), Types (records, values), Modules. Which of these have meaningful code-side counterparts? (Functions → fns; records → defrecords/schemas; values → opaque types maybe surfaced as defs/specs; modules → namespaces; rules/invariants → ?? — likely not direct code counterparts.)
- **How the analyzer reads canvas content.** Two options:
  - (a) Build a new code path that reads canvas datoms directly from the canvas db
  - (b) Keep the analyzer's existing input shape (spec primitives) and have canvas content project INTO that shape first. The analyzer doesn't know it's reading canvas-derived input.
  
  Option (b) is less invasive; option (a) is cleaner architecturally. Pick.
- **What changes in the analyzer surface.** The analyzer currently emits Code.* Artifacts into the model. Phase 6's drift helper reads those artifacts. Does the analyzer's output shape need any change to make drift queries ergonomic? (E.g. tagging artifacts with the canvas entity they were projected from, so a "missing implementation" finding can name the canvas-side originator.)

### Steps

- [ ] **Step 1: Read the existing target/clojure machinery thoroughly.** Especially `analyzer.clj`, `projector.clj`, `address.clj`, `blueprint.clj`.
- [ ] **Step 2: Decide canvas→code input path** (option a or b above).
- [ ] **Step 3: Specify the canonical-address mapping** for each canvas-entity-type that has a code counterpart.
- [ ] **Step 4: Specify what's projected vs not** (rules, invariants likely not; events/handlers maybe via emit-detection in code).
- [ ] **Step 5: Pause for user review.**
- [ ] **Step 6: Commit.**

```bash
jj desc -m "doc(canvas): Phase 6 canvas→code projection design"
jj new
```

---

# Sprint 2 — Pre-implementation hardening (Tasks 3–5)

> **Amended 2026-05-27 after Sprint 1 design review.** Sprint 2 grew from 2 tasks to 3 to absorb the wiring gaps Sprint 1 Task 2's design surfaced. The projection layer is "structured correctly but wired incorrectly" today — gaps 1-7 below must land before Sprint 3's drift helper can produce trustworthy output.

---

## Phase 6, Task 3: Projection-layer wiring fixes (gaps 1-4 from Sprint 1 Task 2)

**Files:**
- Modify: `src/fukan/target/clojure/analyzer.clj` (gap 1: `module-coord-of-primitive` `::` → `/`)
- Modify: `src/fukan/project_layer/defaults.clj` (gap 2: `fukan-on-fukan` `:root-prefix` `""` → `"fukan"`)
- Modify: `src/fukan/target/clojure/analyzer.clj` selectors (gap 3: type selector tag-based → kind-based)
- Modify: `src/fukan/canvas/projection/canvas_source.clj` `affordance-kind` (gap 4: add `:canvas/event` routing)
- Tests for each gap fix

Each fix is ~one-liner to ~10 LoC; per-commit hygiene means one commit per gap (4 commits total in this task) so the wiring change → behavioral change correspondence stays clean.

The Sprint 1 Task 2 design doc named these gaps as the load-bearing issues. Today: against fukan-itself, almost every function projects as `:absent` (false negative); every record/event is silent. After Task 4: projection edges accurately reflect canvas↔code reality.

- [ ] **Step 1: Gap 1 — `::` → `/` in `module-coord-of-primitive`.** TDD against a synthetic canvas-derived primitive; assert the module-coord is the dot-separated module name, not the polluted full id.
- [ ] **Step 2: Gap 2 — `:root-prefix` fix.** Update `fukan-on-fukan` registry; verify against the live canvas.
- [ ] **Step 3: Gap 3 — Type selector kind-based.** Loosen the selector to read `:primitive/container` kinds directly rather than filtering on the absent `Allium::Entity` tag.
- [ ] **Step 4: Gap 4 — Event routing.** Add the `:canvas/event` case to `affordance-kind`'s dispatch.
- [ ] **Step 5: Verify against fukan-itself.** Build canvas + run analyzer; expect `:valid` edges across functions/records/events that previously silenced or false-negatived.

---

## Phase 6, Task 4: Invariant + rule projection (gaps 5-7 from Sprint 1 Task 2)

**Files:**
- Modify: `src/fukan/canvas/projection/canvas_source.clj` — emit a `:primitive/rule` (or a new dedicated kind) per `(invariant X (holds-that "Y"))` and per `(rule X (when X …))`
- Modify: `src/fukan/target/clojure/address.clj` — canonical-address derivation for invariants/rules
- Modify (canvas content): `canvas/validation/rules_4a.clj` through `rules_4g.clj` — rename labels `check_4X` → `check` to align with code (gap 7)
- Tests covering invariant projection + rule projection + rules-4* alignment

The user resolved this question during Sprint 1 review: **canvas invariants and rules ARE projected into code**, with canonical names taken from `(holds-that "Y")` for invariants and from the rule's own name (kebab-cased) for rules. This is "option (a)" in the conversation — symmetric, mechanical, simple.

- [ ] **Step 1: TDD against synthetic canvas + src/.** Construct a canvas with one invariant `(invariant "A" (holds-that "b"))` and one rule `(rule "C" ...)`; assert the projection emits primitives whose addresses are `<module>/b` and `<module>/c` respectively.
- [ ] **Step 2: Implement projection-layer emission** for invariants + rules.
- [ ] **Step 3: Implement address derivation** for the new kinds.
- [ ] **Step 4: Rename canvas labels in rules-4*** from `check_4X` to `check` so canvas + code align. ~7 files. Run integrity after to confirm no broken refs.
- [ ] **Step 5: Verify against fukan-itself.** Expect new `:valid` edges for the ~270 invariants + ~47 rules that previously had no projection.

---

## Phase 6, Task 5: Auto-discover canvas files (Phase 5.5 carryover)

**Files:**
- Modify: `src/fukan/canvas/projection/canvas_source.clj` — replace the explicit `canvas-namespaces` registry with classpath scanning of `canvas/**/*.clj`
- Test: `test/fukan/canvas/projection/canvas_source_test.clj` — assert all canvas modules are picked up
- Update: `CLAUDE.md` — remove the "add a require entry" step from the REPL workflow section

The trial-run findings' #3-ranked friction item. Each new canvas file currently requires two manual edits; auto-discovery from `canvas/**/*.clj` eliminates both.

Sequenced after Task 4 because the projection layer is the same file; landing the wiring fixes first lets Task 5 focus purely on the discovery mechanism.

- [ ] **Step 1: Implement classpath scanning** that finds all `canvas/**/*.clj` namespaces and harvests their `build-canvas` fns.
- [ ] **Step 2: Tests.**
- [ ] **Step 3: Document** in CLAUDE.md.
- [ ] **Step 4: Update `bin/fukan reset`** if needed so the reset command picks up new files via the scanner.
- [ ] **Step 5: Commit.**

---

# Sprint 3 — Build drift detection (Tasks 6–7)

> **Amended 2026-05-27 after Sprint 1 design review.** Sprint 3 contracted from 4-5 task slots to 2 after the design conversation collapsed drift "categories" into "presence vs shape" — broader projection coverage (Sprint 2's gaps 5-7) means the missing-implementation signal uniformly catches functions, events, invariants, and rules. Shape-drift on records is the only structurally distinct signal.

Each task lands as:
- Code in `src/fukan/canvas/inspect/drift.clj` (helper) and `src/fukan/target/clojure/` (analyzer extension for Task 7)
- Tests
- Registration in agent API `(help)` under `:trust`
- A short EXAMPLES.md showing the finding shape

---

## Phase 6, Task 6: Missing-implementation drift helper (umbrella across all projected entity types)

**Files:**
- Create: `src/fukan/canvas/inspect/drift.clj`
- Test: `test/fukan/canvas/inspect/drift_test.clj`
- Update: `src/fukan/agent/api.clj` — register `drift` under `:trust`

The umbrella signal — uniformly catches "canvas X declared, no code counterpart for X" across all projected entity types (functions, events, invariants, rules, getters, checkers). Reads `:validity :absent` directly from the analyzer's output; no per-category branching needed.

Helper namespace mirrors `inspect/integrity` and `inspect/coverage` from Phase 5. Every finding is `:severity :warning` (drift is fact-of-discrepancy; resolution is judgment).

The canonical finding shape:

```clojure
{:check     :inspect.drift/missing-implementation
 :severity  :warning
 :message   "Canvas declares X at <module>; no matching code-side artifact at <expected-path>"
 :offenders [{:stable-id "<module>/<entity-name>"
              :expected-code-path "<src/fukan/.../<file>.clj>"
              :expected-symbol "<symbol-name>"
              :canvas-kind :function|:event|:invariant|:rule|...}]
 :detail    {:canvas-side-id "<stable-id>"
             :code-side-expected "<full address>"}}
```

The detail.canvas-side-id + offenders[].expected-code-path name both sides per the user-confirmed bidirectional framing — the LLM weighs whether canvas or code (or both) should move.

- [ ] **Step 1: Tests first.** Synthetic canvas + src/ pair with one missing fn → 1 finding. Same for one missing invariant (canvas declares `(invariant "A" (holds-that "b"))` but code has no fn `b`). Clean pair → `[]`.
- [ ] **Step 2: Implement.** Walk the analyzer's projection edges; filter by `:validity :absent`; emit a finding per `:absent` edge, naming both sides via the projection metadata.
- [ ] **Step 3: Register in `(help)`** under `:trust`. Mark fn with `^:export`.
- [ ] **Step 4: Run against fukan-itself.** Document the count by canvas-kind (fns missing vs invariants missing vs rules missing). Surface a sample of each.
- [ ] **Step 5: Commit.**

---

## Phase 6, Task 7: Shape-drift on records + analyzer extension

**Files:**
- Modify: `src/fukan/target/clojure/source.clj` — extend symbol extraction to capture `def` body shapes (Malli `[:map …]`, defrecord field-list, etc.)
- Modify: `src/fukan/target/clojure/analyzer.clj` — attach parsed `:fields` to `Code.DataStructure` artifacts
- Modify: `src/fukan/canvas/inspect/drift.clj` — add `check-shape-drift` per-check fn
- Test: extended `drift_test.clj` + `source_test.clj`

The half-session analyzer extension Sprint 1 Task 1's design doc estimated: ~30-50 LOC reading `def` bodies + a small (~10-15 entry) `:Integer ↔ :int` alias table for Clojure-vs-canvas type-name mapping (likely already lives in `canvas.model.vocabulary`).

The drift check compares each canvas record's `:type/fields` (already populated by Phase 5 Sprint 2 Task 5) against the matching code-side `Code.DataStructure` artifact's `:fields`. Finding shape:

```clojure
{:check     :inspect.drift/shape-drift-on-record
 :severity  :warning
 :message   "Canvas record X has field set <A>; code-side record at <path> has <B>"
 :offenders [{:stable-id "<module>/<record>"
              :code-side-path "<path>"
              :canvas-fields {<name> <type> ...}
              :code-fields    {<name> <type> ...}
              :delta {:only-in-canvas {...} :only-in-code {...} :type-mismatch {...}}}]}
```

- [ ] **Step 1: TDD against the analyzer extension first.** Synthetic src/ file with one `(defrecord Order [id customer])`; assert `source/extract-symbols` returns a symbol with `:fields [[:id :Any] [:customer :Any]]` (or however field shapes land).
- [ ] **Step 2: TDD the drift check.** Canvas record `(record "Order" (field id :String) (field customer :Customer))` + code `(defrecord Order [id customer total])` → 1 finding with `:only-in-code #{:total}` in the delta.
- [ ] **Step 3: Implement the analyzer extension** + the `check-shape-drift` per-check fn.
- [ ] **Step 4: Run against fukan-itself.** Expect interesting evidence on the Model record types (ServerOpts, ServerInfo, etc.) — surface any real drift.
- [ ] **Step 5: Commit.**

---

# Sprint 4 — Workflow integration + trial run (Tasks 8–9)

Same shape as Phase 5 Sprint 4. The drift helper from Sprint 3 plugs into the architect agent's authoring loop.

---

## Phase 6, Task 8: Extend the canvas-authoring system prompt + `fukan-architect` for drift

**Files:**
- Update: `doc/canvas-authoring-system-prompt.md` — add drift to the tier model section; add a discipline point about when to invoke drift
- Update: `.claude/agents/fukan-architect.md` — survey-style invocation extended to optionally include drift
- Update: `AGENTS.md` — add drift to the trust/weigh primer

When does the LLM invoke drift? Probably less often than integrity/coverage — drift only matters when src/ is in scope. A canvas-only authoring session doesn't need it. A canvas-edit-followed-by-implementation session benefits from it.

- [ ] **Step 1: Draft the prompt extension.** Keep it tight (~150 words on drift; don't bloat).
- [ ] **Step 2: Update the agent definition.**
- [ ] **Step 3: Update AGENTS.md.**
- [ ] **Step 4: Commit per artifact.**

---

## Phase 6, Task 9: Trial run — author canvas content + matching code + verify drift catches the gap

**Files:**
- Create: a small canvas content + matching src/ implementation (subject TBD; chosen during the trial run)
- Create: `doc/plans/2026-05-27-drift-trial-run-findings.md`

The trial run for Phase 6 differs from Phase 5's: it should exercise the **canvas↔code loop**, not just the canvas loop. The dispatched agent (general-purpose with the system prompt) should:

1. Pick a small feature to author at both layers (canvas + matching src/).
2. Author the canvas first.
3. Author SOME of the code (deliberately leave a gap).
4. Invoke drift; verify it catches the gap.
5. Close the gap (write the missing code).
6. Invoke drift again; verify it's clean.
7. Document the loop's feel.

Subjective evidence shape mirrors Phase 5's trial run.

- [ ] **Step 1: Dispatch the trial.**
- [ ] **Step 2: Observe — what did drift catch? what did it miss? was the LLM's experience improved by the new tier?**
- [ ] **Step 3: Document findings.**
- [ ] **Step 4: Commit.**

---

# Sprint 5 — Verification (Task 10)

## Phase 6, Task 10: Phase 6 verification report

**Files:**
- Create: `doc/plans/2026-05-27-phase-6-verification.md`

Standard verification template, mirroring Phase 5's verification doc:

- [ ] **Section 1: What was attempted vs. built.** Recap Sprints 1-4.
- [ ] **Section 2: Did the drift tier produce useful output against fukan's own canvas+code?** What did each category find?
- [ ] **Section 3: Did the canvas-as-projection-input adaptation work?** Reference the analyzer's behavior pre- and post-Sprint-3.
- [ ] **Section 4: Did the trial run succeed?** Was the canvas↔code loop tight?
- [ ] **Section 5: What did the trial run reveal about gaps?** Carry into Phase 7+.
- [ ] **Section 6: Decision.** Three outcomes:
  1. Drift works → Phase 7 (implementation-instruction generation) can begin.
  2. Works with caveats → Phase 6.5 to close them.
  3. Drift didn't produce useful output → reset; rethink approach.
- [ ] **Section 7: Phase 7+ implications.** Implementation-instruction generation as the next phase. Other carry-forward concerns.
- [ ] **Section 8: Carried-forward concerns from prior phases not yet addressed.**

```bash
jj desc -m "doc(canvas): phase-6 verification + phase-7 brief"
jj new
```

---

## Subsequent phases (sketches; each gets its own plan after Phase 6)

**Phase 7 — Implementation-instruction generation from drift findings.** Given drift output, produce the precise code edits to bring implementation into alignment with canvas (or vice versa). The eventual product surface: the LLM author writes the canvas; fukan tells the implementing LLM exactly what to write in src/.

**Phase 7+** — additional lenses (methodology coherence now that the canvas has demos as multi-paradigm corpus; coupling/dependency lens; FCIS, DDD, CQRS-as-perspective), survey scoping, other refinements.

---

## Self-review notes

- **Sprint 1's design docs (Tasks 1 + 2) are the load-bearing artifacts of Phase 6.** Both have pause points before downstream sprints begin. Sprint 1 Task 2 in particular needs careful design — the canvas-as-projection-input adaptation has architectural consequences.
- **The existing target/clojure machinery is the substrate Phase 6 inherits.** Don't redesign it; adapt it.
- **Resist scope creep.** "More drift categories" isn't the goal; "the right drift categories with high signal-to-noise" is. Phase 5 oversteered on coverage's orphan check before refining; Phase 6 should pick fewer categories deliberately.
- **Trust/weigh discipline carries forward.** Same partition; same in-band severity for trust-tier findings; same lens substrate available if interpretive output is also useful.
- **Don't fix existing canvas content via drift findings unless evidence demands.** The 18-finding fixup cycle in Phase 5 Sprint 3 happened because canvas declarations were genuinely missing types; if drift findings reveal real gaps in fukan-itself, they may or may not be worth fixing in this phase. Default: document; defer the fixup.
- **Phase 6 is about CLOSING THE LOOP between design and implementation.** Hard to verify without a real authoring exercise (Sprint 4's trial run). Plan for subjective evidence.

---

## Open-question status (settled 2026-05-27 unless noted)

The user resolved most open questions during Sprint 1 review. Recorded here for reference.

1. **Drift direction** — Settled: option (3) — both directions; LLM decides per-finding. Each finding names both sides (canvas-side stable-id + code-side expected address).
2. **Drift categories priority** — Reframed: the original 7-category enumeration collapsed into **2 signals** (missing-implementation umbrella + shape-drift-on-records). Broader projection coverage (Sprint 2's gaps 5-7 — invariants + rules) means missing-implementation uniformly catches functions, events, invariants, and rules. Event-without-handler-in-code stays deferred.
3. **Canvas-as-projection-input adaptation path** — Settled by Sprint 1 Task 2's design: option (b) — canvas content already projects into the analyzer's existing `:primitives` input shape via the model pipeline; **no architectural rework needed**, only the 7 wiring gaps in Sprint 2.
4. **Demos in scope** — Settled: no. Drift checks `src/` against `canvas/`. Demos remain stress-test artifacts.
5. **Trial-run target** — Settled: `canvas/distributed/*` (exists from Phase 5's trial run) paired with new `src/fukan/distributed/*` (to be authored). The asymmetry is a ready-made test corpus.
6. **Invariant + rule projection** (raised during Sprint 1 review) — Settled: yes. Invariants project to predicate fn named by their `holds-that "Y"` clause; rules project to predicate fn named after the rule itself (kebab-cased). Sprint 2 Task 4 implements.
7. **Rules-4* naming convention** (raised by Sprint 1 Task 2's design) — Settled: rename canvas labels (`check_4a` → `check`) so canvas + code align. Sprint 2 Task 4 includes the rename.

All Sprint 1 questions are resolved. Sprint 2 begins with settled design.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–2 | ✅ Drift signals design + canvas→code projection design (both committed; both produced substantive pushback that reshaped Sprint 2/3) |
| 2 | 3–5 | Projection-layer wiring fixes (gaps 1-4) + invariant/rule projection + rules-4* rename (gaps 5-7) + auto-discover canvas files |
| 3 | 6–7 | Missing-implementation drift helper (umbrella) + shape-drift-on-records + analyzer `def`-body extension |
| 4 | 8–9 | Architect agent extension + trial run (canvas↔code loop on canvas/distributed/* + new src/fukan/distributed/*) |
| 5 | 10 | Phase 6 verification + Phase 7 brief |

**Estimated calendar:** Sprint 1 ≈ done. Sprint 2 ≈ 2-3 sessions (7 wiring fixes + new projection + auto-discover; per-commit hygiene per gap). Sprint 3 ≈ 2 sessions (umbrella signal is mechanical reading of existing output; shape-drift wants the half-session extension). Sprint 4 ≈ 2 sessions. Sprint 5 ≈ 1 session. **Total remaining: 7-9 working sessions.**
