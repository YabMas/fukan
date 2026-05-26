# Canvas + Substrate Implementation Plan — Phase 5

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make fukan's canvas the powerful thinking-enhancing surface for human-LLM design collaboration. Maximum feedback on design integrity and strength as the author works. **No web/browser UI in this phase** — deferred. **No code-generation or implementation-diff work in this phase** — also deferred. The product surface is the REPL, `bin/fukan` CLI, system-prompt artifacts, and Clojure helpers that turn canvas authoring into a thinking-augmented activity.

**Phase 5's strategic frame:** Phases 1-4 produced a canvas-native analysis substrate with rich queryability. The substrate works; the vocabulary is principled; the examples library exists. What's still flat is the **author's experience of using it**. A human or LLM editing canvas files gets test-pass-or-fail and a graph viewer. They don't get: "this Affordance has no incoming references," "these 4 records have nearly identical shapes," "your new lift pattern appears 5 times — consider promoting to vocab.*," "your changes since last refresh broke 2 invariants." Phase 5 builds the feedback signals that make canvas authoring feel like a conversation with the substrate.

The user reframe: *canvas is the intersection between LLM and human; maximum feedback on the integrity and strength of design; the best thinking-enhancing tool we can make.*

**Architecture:** All Phase 5 work happens in `src/fukan/canvas/` (mostly new namespaces for feedback signals) plus `bin/fukan` (CLI surface for invoking them) plus `doc/` (system prompts and authoring workflows). The substrate, vocabulary libraries, projection pipeline are unchanged unless a feedback signal needs new substrate queryability — in which case a small additive change like Sprint 1's `type-names` extraction is acceptable.

**Tech stack:** Same. Clojure 1.11, Datascript. New: probably some clojure.spec or malli for cross-reference checking; possibly clj-async-profiler-style instrumentation if a signal needs it. nREPL for the live workflow.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-26-phase-4-verification.md` — Phase 4 outcomes; what's already in place to build on
- `doc/plans/2026-05-25-architect-explorer-system-prompt.md` — the Phase 2 Sprint 2 system prompt that activated layered-language thinking in LLMs; Phase 5 promotes this to permanent reusable status
- `doc/plans/2026-05-26-stress-test-findings.md` — Sprint 3 stress-test, including the notification module finding (substrate disappearing into pure design vocabulary)
- `doc/plans/2026-05-26-phase-4-sprint-1-notes.md` — the 31 intra-module duplicate names design question
- `src/fukan/canvas/core/check.clj` — Phase 4 Sprint 4's enriched diagnostics
- `src/fukan/canvas/core/defquery.clj` — the `(this :module/name ?var)` resolution form
- `src/fukan/canvas/identity.clj` — stable-id contract

**Scope of this plan:** Phase 5 only.

**Subsequent phases (out of scope here):**
- Phase 6 — Web/browser UI for canvas (the viewer + editing surfaces the user explicitly deferred)
- Phase 7 — Diff detection between canvas design and code implementation
- Phase 8 — Generating implementation instructions from design-vs-code diffs
- Per-phase vocab expansion (`vocab.cqrs`, `vocab.actor`, etc.) is ongoing across phases as use evidence warrants

---

## What "thinking-enhancing tool" means concretely

A human or LLM authoring canvas content should, while they work, receive these kinds of feedback (Phase 5 builds the ones that matter most):

1. **Structural integrity** — Does this entity reference resolve? Are there orphan entities? Do my invariants name things that exist?
2. **Pattern recurrence** — Am I writing the same shape repeatedly? Should I lift it?
3. **Methodology coherence** — Does this module's vocabulary match the methodology I implicitly chose? Or am I drifting?
4. **Consistency** — Are my entity names following the conventions sister modules use? Are field types consistent across records that share a concept?
5. **Behavioral coverage** — Do declared rules have corresponding invariants? Are all events handled?
6. **Delta awareness** — What did I just change? Did it break anything elsewhere? Did it strengthen or weaken the design?

Phase 5 ships infrastructure for these signals + integrates them into a workflow the LLM can invoke. Not all six become first-class — Sprint 1's design conversation picks which to prioritize.

### Two tiers: canvas-level helpers vs LLM-side tools

A second axis cuts across the six signals: **does the signal produce facts that can be acted on by code, or observations that require interpretation?**

| Signal | Output | Tier | Lives |
|---|---|---|---|
| Structural integrity | "this reference doesn't resolve" — broken refs are errors | **canvas-level helper** | `src/fukan/canvas/inspect/` |
| Behavioral coverage | "this entity has no incoming refs; this rule has no trigger" — severity varies | **canvas-level helper** | `src/fukan/canvas/inspect/` |
| Delta awareness | "X removed since snapshot; these refs now break" — mechanical impact | **canvas-level helper** | `src/fukan/canvas/inspect/` |
| Pattern recurrence | "these 5 affordances share a shape" — interpretation needed | **LLM-side tool** | `src/fukan/canvas/architect/` |
| Consistency | "naming style varies in this module" — intentional? worth normalizing? | **LLM-side tool** | `src/fukan/canvas/architect/` |
| Methodology coherence | "vocabulary fingerprint doesn't match the dominant paradigm" — drift? mixed methodology by design? | **LLM-side tool** | `src/fukan/canvas/architect/` |

**Canvas-level helpers** are pure functions over the canvas db. Decision-ready output. Callable from REPL, from constraints (`fc/check`), from CLI, from the LLM workflow — same fn, multiple consumers. They strengthen the **integrity** of the substrate.

**LLM-side tools** wrap interpretive logic. Output is structured for an LLM to read and reason about, not for code to act on directly. Invoked via `bin/fukan architect <signal>`. They strengthen the **design judgment** the LLM brings.

Phase 5 ships both tiers but keeps them architecturally separate.

**Constraint integration deferred.** Canvas-level helpers COULD be wired as built-in `fc/check` constraints (every `:references` Relation must resolve, etc.) but Phase 5 ships them as plain pure fns. Constraint wiring is a follow-on if use evidence demands automatic enforcement — requires `fc/check` to grow severity-awareness, which today's binary model doesn't have.

---

## File structure (Phase 5)

**Likely new namespaces:**

```
src/fukan/canvas/inspect/             ; tier 1 — canvas-level helpers (pure fns over the canvas db)
  integrity.clj                  ; cross-reference resolution + trigger/emit coherence
  coverage.clj                   ; orphans, unreachable entities, dead exports, invariant target checks
  delta.clj                      ; canvas-to-canvas diff + impact analysis

src/fukan/canvas/architect/           ; tier 2 — LLM-side tools (interpretive output)
  patterns.clj                   ; recurring-shape detection; lift candidates
  consistency.clj                ; naming + structural consistency observations
  methodology.clj                ; vocabulary fingerprint + drift signal
  workflow.clj                   ; the architect-explorer pattern as a reusable workflow

doc/plans/
  2026-05-26-feedback-signals-design.md   ; Sprint 1 output
  2026-05-26-coauthor-workflow-design.md  ; Sprint 1 output
  2026-05-26-phase-5-verification.md      ; Sprint 5 output
```

**Likely modified files:**

```
bin/fukan                              ; new sub-commands for feedback signals
dev/user.clj                           ; REPL convenience for invoking signals
src/fukan/canvas/construction.clj      ; Sprint 2 — emits form
src/fukan/canvas/projection/canvas_source.clj  ; Sprint 2 — duplicate-name fix (TBD)
CLAUDE.md, AGENTS.md                   ; Sprint 5 — authoring workflow guidance
```

**Files NOT touched:**

- Substrate primitives (`substrate.clj`, `substrate/store.clj`) — Phase 4 finalized them
- The canvas vocab libraries — Phase 5 may notice pattern-detection candidates but doesn't ship vocab unless rule-of-three within actual Phase 5 authoring evidence
- `web/` and `web/views/*` — UI work explicitly deferred
- `.legacy-allium/` — archive stays archived
- `/demo/` — stress-test artifacts frozen

---

# Sprint 1 — Feedback inventory + workflow design (Tasks 1–2)

The opening sprint settles two design questions before any signals are built. Both produce docs reviewed by the user before downstream sprints begin.

---

## Phase 5, Task 1: Feedback signals — design doc

**Files:**
- Create: `doc/plans/2026-05-26-feedback-signals-design.md`

### What the design doc must cover

For each of the six signal categories listed in the "thinking-enhancing tool" section above:

- **What it computes.** Concretely. Datalog query, walk over the canvas db, set comparison, etc.
- **What it surfaces.** A list of findings, structured for both LLM and human consumption.
- **Tier.** Canvas-level helper (decision-ready output, pure fn under `inspect/`) or LLM-side tool (interpretive output under `architect/`). Verify the partition holds — surface anything that resists the split.
- **How it integrates with workflow.** REPL fn? `bin/fukan` subcommand? Both?
- **Priority for Phase 5.** Ship in Phase 5 vs defer to Phase 6 or beyond.

Specifically address:

**Category 1: Structural integrity**
- Cross-reference checker: every `:references` Relation's `:to` resolves to an entity that exists
- Orphan checker: entities with no incoming references (might be dead canvas)
- Reachability: every entity is reachable from at least one Module's `:module/child` graph
- Triggers/emits coherence: every `:triggers` Relation points to an actual rule; every `:emits` Relation points to an actual event

**Category 2: Pattern recurrence**
- Shape clustering: find Affordances with structurally similar arrow shapes
- Lift candidate detection: when a manual pattern (e.g. ad-hoc invariants with similar formal expressions) recurs 3+ times, flag as a vocab candidate
- Naming patterns: detect Affordances with similar name suffixes/prefixes (e.g. `*_id`, `make_*`, `*_is_pure`)

**Category 3: Methodology coherence**
- Vocabulary fingerprint: which vocab.* lifts does this module use? Does the mix make sense for a coherent methodology?
- Inconsistency detection: e.g. a module mixing `vocab.event/handler` with `vocab.validation/checker` might be doing two things; flag for review

**Category 4: Consistency**
- Naming style consistency within a module
- Field-type consistency across records that share concept names (e.g. all records with a `:status` field — are they all `:String`?)
- Sister-module symmetry (e.g. `rules_4a..g` all checker-shaped; if one has invariants the others don't, flag it)

**Category 5: Behavioral coverage**
- Every rule with a `when:` clause has a corresponding `function` that emits the trigger (or is otherwise reachable)
- Every event has at least one handler somewhere in the corpus
- Every guarantee has at least one structural mechanism enforcing it (heuristic)

**Category 6: Delta awareness**
- Diff between two canvas db states: what entities added/removed/modified
- Cross-reference impact analysis: when you remove entity X, what references break?
- Test-impact analysis: which tests exercise the entities you changed?

### Steps

- [ ] **Step 1: For each category, sketch concrete computational shape.** Write enough that an implementer could start.

- [ ] **Step 2: Rank by usefulness for the LLM authoring loop.** Some signals matter more than others when you're 30 seconds into a co-authoring session.

- [ ] **Step 3: Pick the 4-5 highest-priority signals for Phase 5.** Defer the others to follow-on phases.

- [ ] **Step 4: Pause for user review.** This is the load-bearing design conversation of Phase 5.

- [ ] **Step 5: Commit.**

```bash
jj desc -m "doc(canvas): Phase 5 feedback-signals design"
jj new
```

---

## Phase 5, Task 2: LLM co-author workflow design

**Files:**
- Create: `doc/plans/2026-05-26-coauthor-workflow-design.md`

### Context

The Phase 2 Sprint 2 architect-explorer system prompt activated layered-language thinking in LLMs during the emergence experiment. It worked. It's currently a one-shot artifact frozen in `doc/plans/2026-05-25-architect-explorer-system-prompt.md`. Phase 5 promotes it to a permanent reusable workflow tool.

The workflow needs to integrate with:
- The architect-explorer system prompt (or its successor)
- The feedback signals from Task 1
- The author's iterative loop (write canvas → invoke feedback → adjust → repeat)

### What the design doc must cover

- **The minimum viable loop.** When the LLM partner is asked to author or extend canvas content, what context does it get? Which feedback signals does it invoke? What does its turn-by-turn output look like?
- **The integration point.** `bin/fukan` CLI extension? An nREPL middleware? A reusable Clojure function callable from the LLM's tool surface?
- **The architect-explorer prompt evolution.** Phase 2's prompt is for one-shot vocab discovery; Phase 5's authoring loop is iterative co-authoring. What changes?
- **The discipline.** How does the LLM avoid:
  - Inventing vocab that doesn't recur (the Phase 2 source-priming failure mode)
  - Hallucinating entity references that don't exist (the feedback signals should catch this)
  - Drifting from the chosen methodology mid-module
- **The trial-run target.** Phase 5 must run the loop against one real authoring task in Sprint 4. The design doc picks the task.

### Steps

- [ ] **Step 1: Reread the architect-explorer system prompt.** Note which parts are one-shot-specific and which generalize.

- [ ] **Step 2: Draft the loop shape.** Per-turn input/output spec. Feedback-signal invocation timing.

- [ ] **Step 3: Pick the Phase 5 trial-run target.** Suggestions:
  - Port one un-touched fukan subsystem (none remain — all 62 modules are ported; would need a synthetic exercise)
  - Refactor one existing canvas module for clarity (real value)
  - Author a small extension to one demo (e.g. add `vocab.cqrs` lifts to a new demo subsystem)

- [ ] **Step 4: Pause for user review.**

- [ ] **Step 5: Commit.**

```bash
jj desc -m "doc(canvas): Phase 5 co-author workflow design"
jj new
```

---

# Sprint 2 — Pre-implementation hardening (Tasks 3–4)

Two items Phase 4 verification flagged as Phase 5 conversations. Both block the feedback signals from being accurate against real canvas content.

---

## Phase 5, Task 3: Resolve the 31 intra-module duplicate names

**Files:** TBD per chosen resolution

Three options from `doc/plans/2026-05-26-phase-4-sprint-1-notes.md`:

1. **Disallow** — rename the 31 colliding entities in canvas/validation/* to disambiguate
2. **Allow + scope resolution by name+role** — substrate stays; resolution gets richer
3. **Promote the convention** — document explicitly as canvas idiom

- [ ] **Step 1: Pick an option with the user.**
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Re-enable strict throw-on-duplicate** in `canvas-source/build-canvas-db` if applicable.
- [ ] **Step 4: Per-commit logical changes.**

---

## Phase 5, Task 4: `emits` form on function

**Files:**
- Modify: `src/fukan/canvas/construction.clj`
- Modify: `src/fukan/canvas/core/substrate/store.clj`
- Test: `test/fukan/canvas/construction_test.clj`
- Backfill: `demo/event-driven/*`

Sprint 3 stress-test surfaced: command functions emitting events have no first-class form. Symmetric with `triggers`:

- `(triggers RuleName)` — function couples to a rule
- `(emits EventName)` — function emits an event (new in Phase 5)

Implementation pattern mirrors Phase 4 Sprint 2 Task 5.

- [ ] **Step 1: Schema** — `:emits {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}`.
- [ ] **Step 2: Test + Implement** — extend `function`'s form grammar.
- [ ] **Step 3: Backfill demo content.**
- [ ] **Step 4: Commit.**

```bash
jj desc -m "feat(canvas): emits form on function — command-to-event coupling first-class"
jj new
```

---

# Sprint 3 — Build the feedback signals (Tasks 5–9)

The substantive sprint. Each task implements one of Sprint 1 Task 1's selected signals. The exact list depends on Sprint 1's prioritization; the placeholders below are the most likely picks based on the design space described above.

Sprint 3 splits into two groups corresponding to the two tiers:

- **Tier 1 (canvas-level helpers)** — Tasks 5–7. Land under `src/fukan/canvas/inspect/`. Pure fns over the canvas db. Decision-ready output. `bin/fukan inspect <signal>` invokes them; REPL helpers expose them; constraints can wrap them.
- **Tier 2 (LLM-side tools)** — Tasks 8–9. Land under `src/fukan/canvas/architect/`. Output formatted for an LLM to reason about. `bin/fukan architect <signal>` invokes them.

Each signal lands as:
- One namespace under the tier's directory
- Tests
- A `bin/fukan` subcommand invoking it (under the tier's subcommand)
- A REPL helper in `dev/user.clj` for canvas-level helpers
- A short EXAMPLES.md showing typical use

---

## Phase 5, Task 5: Cross-reference integrity — canvas-level helper

**Files:**
- Create: `src/fukan/canvas/inspect/integrity.clj`
- Test: `test/fukan/canvas/inspect/integrity_test.clj`
- Update: `bin/fukan` + `dev/user.clj`

What it computes:
- For every `:references` Relation: does the `:to` target resolve to an actual entity in the canvas db?
- For every `:triggers` Relation: does the rule named in the trigger exist?
- For every `:emits` Relation: does the event named in the trigger exist?
- For every cross-module reference keyword in a shape: does the target module + entity exist?

What it returns: a structured report of unresolved references with source-locations and entity stable-ids.

What it computes:
- For every `:references` Relation: does the `:to` target resolve to an actual entity in the canvas db?
- For every `:triggers` Relation: does the rule named in the trigger exist?
- For every `:emits` Relation: does the event named exist?
- For every cross-module reference keyword in a shape: does the target module + entity exist?

What it returns: a structured report of unresolved references with source-locations and entity stable-ids. **Decision-ready** — a broken reference is an error.

- [ ] **Step 1: Test the failure cases first.** Construct a canvas with deliberately broken references; assert the signal catches them.
- [ ] **Step 2: Implement** using Datascript queries against `(canvas-source/build-canvas-db)`.
- [ ] **Step 3: bin/fukan subcommand** — `bin/fukan inspect integrity` produces the report.
- [ ] **Step 4: REPL helper** — `(dev/inspect-integrity)` returns structured data; pretty-prints by default.
- [ ] **Step 5: Run against fukan-itself.** What does the live canvas surface? Document findings in the test or notes.
- [ ] **Step 6: Commit.**

---

## Phase 5, Task 6: Coverage analysis — canvas-level helper

**Files:**
- Create: `src/fukan/canvas/inspect/coverage.clj`
- Test: `test/fukan/canvas/inspect/coverage_test.clj`

What it computes:
- Orphan entities (no incoming references; possibly dead)
- Unreached entities (not reachable from any Module's `:module/child` graph; substrate-floating)
- Exported entities never referenced externally
- Modules without `(exports …)` declarations
- Rules with a `when:` clause and no function triggering them (behavioral coverage gap)
- Events with no handler anywhere in the corpus

Each finding has a severity (error / warning / info) callers can filter on. Output is **decision-ready** — the call to act is structural, not interpretive.

- [ ] **Step 1: Define each coverage check** as a Datalog query.
- [ ] **Step 2: Tests.**
- [ ] **Step 3: Run against fukan-itself.** Expect to find some real issues (dead exports, etc.).
- [ ] **Step 4: bin/fukan subcommand** — `bin/fukan inspect coverage`.
- [ ] **Step 5: Commit.**

---

## Phase 5, Task 7: Delta inspection — canvas-level helper

**Files:**
- Create: `src/fukan/canvas/inspect/delta.clj`
- Test: `test/fukan/canvas/inspect/delta_test.clj`

What it computes (given two canvas db states):
- Entities added
- Entities removed
- Entities whose attributes changed (e.g. docstring updated, shape changed)
- Cross-reference impact: when you remove entity X, which references now break?

Usage pattern: a snapshot is taken (manually or automatically on `(refresh)`); subsequent changes are compared against the snapshot. Output is **decision-ready** — impact is structural.

- [ ] **Step 1: Snapshot representation.** Probably: serialize the canvas db to edn at a known location (e.g. `.fukan/canvas-snapshot.edn`).
- [ ] **Step 2: Diff algorithm.** Walk both dbs; compare by `:entity/id`.
- [ ] **Step 3: Format output** to highlight impact.
- [ ] **Step 4: REPL helper** — `(dev/canvas-delta)` shows changes since last snapshot.
- [ ] **Step 5: bin/fukan subcommand** — `bin/fukan inspect delta`.
- [ ] **Step 6: Commit.**

---

## Phase 5, Task 8: Pattern recurrence — LLM-side tool

**Files:**
- Create: `src/fukan/canvas/architect/patterns.clj`
- Test: `test/fukan/canvas/architect/patterns_test.clj`

What it computes:
- Cluster Affordances by structural shape similarity (input/output type sets, role, presence/absence of formal-expression)
- Flag clusters of 3+ as candidate lift patterns
- For each cluster: show the entities, the shared structure, the suggestion ("consider extracting a lift named X")

This is potentially the highest-value LLM-side signal — the rule-of-three discipline Phase 2 used can be automated here. When canvas authoring surfaces a 3rd instance of a recurring pattern, the LLM gets told. **Interpretation lives with the LLM** — the tool surfaces the cluster; the LLM decides whether a lift is warranted.

- [ ] **Step 1: Define "structural similarity"** rigorously. Probably: same `:affordance/role`; same set-of-type-names in input + output; same presence of formal-expression.
- [ ] **Step 2: Implement clustering.** Group entities by their similarity signature; emit clusters >= 3.
- [ ] **Step 3: Format the output** so it's actionable to an LLM. E.g. "Three Affordances with shape `(Model) -> [Violation]`: rules_4a/check, rules_4b/check, rules_4c/check. Consider `vocab.validation/checker` (already exists). Apply at: …"
- [ ] **Step 4: bin/fukan subcommand** — `bin/fukan architect patterns`.
- [ ] **Step 5: Run against fukan-itself.** The existing `checker` lift should surface for the validation rules. If new patterns surface, document them as Phase 6 vocab candidates.
- [ ] **Step 6: Commit.**

---

## Phase 5, Task 9: Consistency analysis — LLM-side tool

**Files:**
- Create: `src/fukan/canvas/architect/consistency.clj`
- Test: `test/fukan/canvas/architect/consistency_test.clj`

What it computes:
- Naming-style consistency within a module (entities named `snake_case` vs `camelCase` vs `PascalCase`)
- Field-name consistency across records (e.g. all records with `:id` — is it always `:String`?)
- Sister-module structural symmetry (e.g. `rules_4a..g` should all look alike)

Output is **observational** — the LLM (or human) interprets whether each inconsistency is intentional or worth normalizing.

- [ ] **Step 1: Define each consistency check.**
- [ ] **Step 2: Make checks optional / configurable** so authoring projects can opt in/out of specific style rules.
- [ ] **Step 3: Format output** as annotated suggestions (interpretive framing, not error framing).
- [ ] **Step 4: bin/fukan subcommand** — `bin/fukan architect consistency`.
- [ ] **Step 5: Run against fukan-itself.** Expect surprises.
- [ ] **Step 6: Commit.**

---

**Note on methodology coherence (signal 3 from the six-category list).** It was a candidate LLM-side tool, but Phase 5 defers it. Methodology fingerprint computation is genuinely hard to design without a corpus of multi-paradigm projects to test against — fukan's canvas is too uniformly fukan-shaped. Revisit in Phase 6+ once more demos exist.

---

# Sprint 4 — LLM workflow integration + trial run (Tasks 10–11)

The signals from Sprint 3 are useful in isolation. Sprint 4 integrates them into a workflow the LLM can use to author canvas content with feedback.

---

## Phase 5, Task 10: Promote architect-explorer pattern to reusable workflow

**Files:**
- Create: `src/fukan/canvas/architect/workflow.clj` — the architect workflow
- Create: `doc/canvas-authoring-system-prompt.md` — the permanent system prompt
- Update: `bin/fukan` — `bin/fukan architect <task>` invocation

The Phase 2 architect-explorer system prompt activated layered-language thinking. Phase 5 promotes it from one-shot to permanent. Key additions vs the original:

- References the now-existing vocab libraries (behavioral, validation, lifecycle, event)
- References the now-existing EXAMPLES.md files
- Integrates both tiers from Sprint 3:
  - Canvas-level helpers (`inspect/*`) as **trusted** signals — if integrity says X is broken, X is broken
  - LLM-side tools (`architect/*`) as **interpretive** signals — pattern clusters and consistency observations the LLM weighs and decides on
- The architect-explorer pattern's principles (compose first, vocabulary justified by use, etc.) carry forward

- [ ] **Step 1: Reread the original prompt.** Identify generalizable principles vs experiment-specific framing.
- [ ] **Step 2: Draft the canvas-authoring system prompt.** Permanent artifact. Versioned. Includes explicit framing of the two-tier signal model (trust canvas-level helpers; weigh LLM-side tools).
- [ ] **Step 3: Implement `bin/fukan architect <task>`** that wraps the workflow: takes a task description; loads canvas; invokes integrity + coverage first (trusted baseline); produces a structured prompt + context for an LLM to author against; offers `architect/*` tool subcommands as in-loop reasoning aids.
- [ ] **Step 4: Commit.**

---

## Phase 5, Task 11: Trial run

**Files:** dependent on the trial-run target chosen in Sprint 1 Task 2.

Execute the loop end-to-end on a real authoring task. Document what worked, what didn't.

- [ ] **Step 1: Dispatch an LLM subagent** with the canvas-authoring system prompt + the chosen task + access to feedback signals.
- [ ] **Step 2: Observe the loop.** What did the LLM invoke? Did the feedback signals catch the right things? Where did the LLM go astray?
- [ ] **Step 3: Document findings** in `doc/plans/2026-05-26-trial-run-findings.md`.
- [ ] **Step 4: Identify gaps.** What feedback would have made the loop tighter? What signals are missing?
- [ ] **Step 5: Commit.**

---

# Sprint 5 — Verification (Task 12)

## Phase 5, Task 12: Phase 5 verification report

**Files:**
- Create: `doc/plans/2026-05-26-phase-5-verification.md`

Standard verification template per Phases 1, 3, 4:

- [ ] **Section 1: What was attempted vs. built.** Recap Sprints 1-4.
- [ ] **Section 2: Did the feedback signals produce useful output against fukan's own canvas?** What did they find? What surprised?
- [ ] **Section 3: Did the LLM co-author trial run succeed?** Was the loop tight? Did the feedback genuinely shape the LLM's authoring?
- [ ] **Section 4: What did the trial run reveal about gaps?** Specific signals or workflow improvements that would help.
- [ ] **Section 5: Decision.** Three outcomes:
  1. The thinking-enhancing tool works → Phase 6 (browser UI) can begin.
  2. Works with caveats → Phase 5.5 to close caveats first.
  3. The loop didn't produce a noticeable thinking improvement → reset; rethink approach.
- [ ] **Section 6: Phase 6+ implications.** Browser UI as the next phase. Plus: diff detection + impl generation as the eventual product surface (per the user's strategic frame).

```bash
jj desc -m "doc(canvas): phase-5 verification + phase-6 brief"
jj new
```

---

## Subsequent phases (sketches; each gets its own plan after Phase 5)

**Phase 6 — Browser UI for canvas authoring**: the editing surface deferred from Phase 5. Embedded editor, text-mode UI, or chat-driven authoring — decided per Phase 5's evidence.

**Phase 7 — Diff detection between canvas design and code implementation**: the substrate already supports queries; Phase 7 adds the canvas-to-code-tree diff inspector. "What did the implementation drift from what was designed?"

**Phase 8 — Implementation instruction generation from design diffs**: given a design diff (Phase 7's output), generate the precise implementation steps to bring code into alignment with design. This is fukan's eventual primary product surface.

---

## Self-review notes

- **Sprint 1's design docs (Tasks 1 + 2) are the load-bearing artifacts of Phase 5.** Both have pause points before downstream work begins.
- **Don't speculate signals.** Each one in Sprint 3 should be selected because Sprint 1 evidence justified it.
- **Resist scope creep on feedback signals.** "More feedback" isn't the goal; "the RIGHT feedback at the right moment" is.
- **The architect-explorer prompt is reusable infrastructure now.** Treat it carefully.
- **Phase 5 is about FEELING the canvas as a thinking tool.** Hard to verify without authoring something real (Sprint 4's trial run). Plan for that to be subjective evidence.

---

## Open questions for the user (before Sprint 1 begins)

These don't block dispatch but help shape Sprint 1's design docs:

1. **Feedback-signal priority preference.** Which 2-3 categories matter most to you for the LLM authoring experience? Integrity (refs resolve, etc.) is foundational. Pattern recurrence is high-leverage. Coverage may surface dead canvas. Consistency is style-driven. Delta is feedback-during-edit. Methodology is bigger-picture. Pick 2-3 you'd bet on for early evidence.

2. **Trial-run target.** All 62 fukan modules are already ported. Possible trial-run tasks: (a) refactor one existing canvas module for clarity using the loop; (b) extend one demo (e.g. add `vocab.cqrs` to demo/event-driven/); (c) author a NEW small subsystem hand-in-hand (e.g. a /demo/distributed/ port). Which has the most evidence value?

3. **31 duplicate names — preference?** Phase 4 deferred this; Phase 5 needs to settle it.

4. **`bin/fukan` shape.** Current sketch follows the two-tier split: `bin/fukan inspect <integrity|coverage|delta>` for the canvas-level helpers, `bin/fukan architect <patterns|consistency|<task>>` for the LLM-side tools and the architect workflow. The tier-as-top-level-verb makes the trust distinction visible at the CLI surface itself. Any preference to push back?

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–2 | Feedback signals design + co-author workflow design (two pause points) |
| 2 | 3–4 | Pre-implementation hardening: 31 duplicates + emits form |
| 3 | 5–9 | Build 4-5 feedback signals (per Sprint 1 prioritization) |
| 4 | 10–11 | Promote architect-explorer prompt + trial run |
| 5 | 12 | Phase 5 verification + Phase 6 brief |

**Estimated calendar:** Sprint 1 ≈ 1-2 sessions (design + pause). Sprint 2 ≈ 1 session. Sprint 3 ≈ 4-5 sessions (one per signal). Sprint 4 ≈ 2 sessions (workflow integration + trial run). Sprint 5 ≈ 1 session. **Total: 9-11 working sessions.**
