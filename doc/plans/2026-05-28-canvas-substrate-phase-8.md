# Phase 8 — Close the dispatch loop

**Date:** 2026-05-28
**Status:** Drafted

Phase 7 made drift findings *individually* actionable — one finding produces
one instruction the canvas-author dispatches to an implementing-LLM by hand.
Phase 8 closes that loop *programmatically*. A drift-closure controller
takes a scoped set of findings (per-module, per-kind, or full-canvas),
dispatches per-finding instructions to implementing-LLMs, verifies each
closure via `(canvas-drift)`, retries on failure with the verification
error fed back into the instruction, and surfaces a structured report to
the canvas-author. The human stays at canvas altitude — they pick the
scope and review the closure report; they don't write code or copy-paste
instructions.

The invariant→property-test design question — the open Phase 8 item the
trial findings named load-bearing — lands in this same phase. It is the
hardest case for the automated loop (test-side artifacts, drift comparator
needs a separate path) and Phase 8 is the right place to put it under
real-Agent exercise.

## Strategic frame

Phase 6 closed *detection*. Phase 7 closed *instruction*. Phase 8 closes
*dispatch* — the last manual step between "drift surfaced" and "drift
closed". The canvas-author seat stays human; the implementing-LLM seat
becomes a controlled, batched, retry-aware dispatch driven by the
substrate Phase 7 shipped.

Two complementary moves carry this phase:

- **Closure controller.** Two new agent-api fns — `(close-drift-plan {…})`
  and `(close-drift-verify {…})` — plus a Phase D extension on
  `fukan-architect`. Sprint 1's design surfaced that **the SCI sandbox
  cannot invoke the `Agent` tool** (tool grants are harness-level; the
  sandbox runs in-daemon with no channel to the harness). Resolution:
  split the controller into two pure entry points and put the dispatch
  loop in `fukan-architect`'s Phase D prompt. `close-drift-plan` renders
  per-finding instructions from a scope; the architect invokes `Agent`
  for each plan entry and collects subagent reports; `close-drift-verify`
  re-runs drift against the reported scope to classify outcomes and
  produce the structured report. A thin convenience wrapper
  `(close-drift {…})` exists for terminal callers (with a stub dispatch-fn
  that returns "manual dispatch required") and tests (with an injected
  stub). Pure orchestration; no new substrate primitives.
- **Property-test projection.** A second invariant projection targeting
  `test/<ns>_test.clj` instead of `src/<ns>.clj`. Sprint 1's design picked
  the **migrate** path: property-test becomes the default; the predicate
  projection stays registered for opt-in via a canvas-side
  `(projects-to :predicate)` flag. Existing predicate stubs in
  `distributed.cluster` get deleted in Sprint 6 after property-test
  closures land. Drift comparator gains a test-side awareness path; the
  dispatch tuple stays `[lens-id canvas-role]` via a synthetic
  `:canvas/invariant+property-test` key (Option β from the design — the
  other 9 Phase 7+7.5 registrations stay untouched).

The product surface stays the REPL, `bin/fukan eval`, `(help)`, and
`fukan-architect`. No browser UI work; no canvas-side bidirectional
scenarios (deferred to Phase 9 pending Sprint 4's canvas-side-hint
evidence). The canvas-author never types a code edit; the loop reports
what happened.

## Out of scope

- **Full bidirectional drift scenarios.** Sprint 4 adds *hints* — flagging
  where drift might be canvas-side rather than code-side — but does NOT
  add `:canvas-side/drop-declaration` or `:canvas-side/restructure`
  scenarios. Those wait until the hint evidence accumulates.
- **Browser UI / explorer.** Indefinitely deferred per standing instruction.
- **Refactor scenario.** Deferred from Phase 7; remains deferred until the
  multi-instruction batching shape is exercised in Phase 8's controller.
- **Multi-language lens trial.** The Clojure lens is the only lens Phase 7
  shipped. Adding a second project-lens (e.g. Python, TypeScript) waits
  until Phase 8's dispatch loop is stable enough to verify cross-lens
  behavior cheaply.
- **Cold-write controller.** Phase 8's controller handles drift-close
  exclusively. Cold-write needs its own scoping and concurrency model
  (writing N projections to one empty target file is sequential, not
  parallel); fold into a later phase once drift-close is stable.

## Sprint plan

Seven sprints. Two design + five working + verification.

### Sprint 1 — Closure controller + invariant-projection designs ✅

Two design docs paused for user review before downstream sprints. Both
landed in the working tree as of 2026-05-28.

**Task 1 — Closure controller design.** `doc/plans/2026-05-28-closure-controller-design.md`. Key decisions:

- **Two pure entry points** — `(close-drift-plan {…scope…})` returns a
  rendered-instruction plan per finding; `(close-drift-verify
  {:plan … :reports […]})` consumes subagent reports + re-runs drift to
  classify outcomes. Both run inside the SCI sandbox. A convenience
  `(close-drift)` wrapper exists for terminal/test callers with a
  stub dispatch-fn.
- **`fukan-architect`'s Phase D drives the dispatch loop** between the
  two entry points — the architect calls `close-drift-plan`, iterates
  through `:plan`, invokes its native `Agent` tool with each rendered
  instruction, collects reports, calls `close-drift-verify`, handles
  retries up to `:max-attempts`. The SCI sandbox stays sealed; the
  architect's harness-level `Agent` grant is the only dispatch surface.
- **Scope shape.** `:module-coord`, `:check`, `:stable-id`, `:limit`
  (default 25 with truncation flag), `:dry-run`, `:max-attempts`
  (default 2), `:dispatch-fn` (architect-injected; stub for non-architect
  callers).
- **Concurrency.** Findings batch by `:expected-code-path`; sequential
  within file (avoids edit conflicts), parallel across files at fanout
  cap 3.
- **Retry context.** Iter-2 instructions carry reconciliation-prose
  preamble + iter-1 subagent report + iter-1 drift state + original
  instruction (top-down urgency order).
- **Six escalation triggers.** `:attempts-exhausted`,
  `:no-projection-registered`, `:projection-emits-warning`,
  `:canvas-side-hint`, `:scenario-not-found`, `:dispatch-error` —
  each maps to a specific upstream cause with a hinted resolution path.
- **In-memory state only** for Phase 8; `.fukan/closure-log.edn`
  persistence deferred unless Sprint 3 trial reveals need.

**Task 2 — Invariant projection design.** `doc/plans/2026-05-28-invariant-projection-design.md`. Key decisions:

- **Migrate, not coexist.** Property-test becomes the default invariant
  projection; predicate stays as opt-in via canvas-side
  `(projects-to :predicate)` flag. Sprint 6 closes via property-test
  then deletes the three orphan predicate stubs in
  `distributed.cluster`.
- **New `:projection-kind/property-test` keyword** distinct from the
  existing (mostly-dormant) `:projection-kind/test`.
- **New `:code/property-test` artifact-kind** sibling to
  `:code/function` + `:code/data-structure`.
- **Address convention.** ns `<base>-test`, symbol `<kebab>-property`,
  file `test/<path>_test.clj`.
- **Template idiom.** `clojure.test.check` `defspec` with
  `gen/return ::placeholder` + audit-trail `throw` body. Holds-that
  prose carried as the property's docstring/comment.
- **Dispatch via Option β** — augmented `dispatch-key-of` returns the
  synthetic key `:canvas/invariant+property-test` when the canvas
  declaration opts to the property-test default. The other 9
  Phase 7+7.5 registrations stay on `[lens-id canvas-role]` untouched;
  only invariants pay the discriminator cost.
- **Drift comparator** gets a `ns->test-path` mirror and a
  projection-kind-branched `expected-path-for`.

### Sprint 2 — Trial-fidelity probe across unexercised projections ✅

Goal: get honest end-to-end data on how often a real subagent (with
zero prior context) successfully implements a Phase 7 instruction.

**Sprint 2 pivoted to an instruction-quality survey.** Task 3's subagent
discovered that the harness running the trial does not grant the `Agent`
tool to nested subagents — the same fidelity gap Phase 7 ran into. Rather
than substitute in-session implementing-LLM behavior (which would
reproduce Phase 7 data with no new signal), the subagent ran an
instruction-rendering survey across the 6 unexercised projection kinds +
cold-write scenario, capturing per-kind instruction quality + defects
surfaced. The survey produced actionable Task 4 input even though it
didn't produce closure-rate empirics.

Architectural consequence: Sprint 3's MVP ships with the closure-
controller design's retry/concurrency thresholds as placeholders. Real
closure-rate data accumulates organically through Sprint 3's smoke test
(Task 9) + Sprint 6's property-test trial + canvas-author use, not via a
contrived trial. The two-entry-point split (Sprint 1 design) is even
more load-bearing under the confirmed harness constraint — the only
context with reliable `Agent` tool grant is the human-driven main
session driving `fukan-architect`.

**Task 3 — Real-Agent dispatch probe** (delivered as instruction-quality
survey). Output: `doc/plans/2026-05-28-trial-run-real-agent-findings.md` —
per-projection-kind instruction quality, defects surfaced, Sprint 3
readiness assessment.

**Task 4 — Defect triage** ✅. Two blockers fixed: (a) event projection
dropped payload fields (`affordance-element` had an `:arrow`-shape guard
but events use record-kind shape — one-line fix); (b) cold-write had no
public entry point (`(instruct …)` unconditionally called `(spec …)`
which rejects module-shaped input — extended to detect module-coord
shape and walk the canvas db's module children). Four other defects
deferred to Phase 9 with documented rationale: predicate `?`-suffix
naming, atomic `[:any]` render prose, rule/invariant name collision
artifact, Agent-tool harness gap.

### Sprint 3 — Closure controller MVP

Implement the controller per Sprint 1 + Sprint 2 amendments. MVP scope:
single-pass dispatch (no retry yet, no canvas-side hints yet). The MVP
exercises the two-entry-point orchestration shape end-to-end against the
easy cases (function-kind drift, getter drift) before retry logic adds
complexity. The architect's Phase D extension is load-bearing here — the
dispatch loop lives in the architect's prompt, not in the controller.

**Task 5 — `(close-drift-plan {…})` agent-api fn.** Under `:trust` layer
with `:severity :info`. Same SCI sandbox surface as `(canvas-drift)`
and `(instruct)`. Accepts the scope shape Sprint 1 specified. Returns a
plan map: `{:plan [{:stable-id … :scenario … :rendered "…markdown…"
:context {…}} …] :scope … :counts {:findings-total N}}`. Pure: no
dispatch, no side effects beyond reading the canvas db.

**Task 6 — `(close-drift-verify {…})` agent-api fn.** Same layer.
Accepts `{:plan <plan-from-task-5> :reports [{:stable-id … :report
"…subagent narrative…"} …]}`. Re-runs `(canvas-drift)` against the
plan's scope; per finding, classifies outcome (`:closed`, `:failed`,
`:requires-retry?`) and aggregates into the structured report. Pure
against the live canvas + drift state.

**Task 7 — `fukan-architect` Phase D extension.** Architect gains
"close-drift mode": canvas-author asks "close drift in module X", the
architect's Phase D prompt teaches it the two-step orchestration:

1. Call `(close-drift-plan :module-coord "X")` via Bash; receive `:plan`.
2. For each entry in `:plan`, invoke `Agent` with the rendered instruction;
   collect each subagent's final report into a `:reports` vector.
3. Call `(close-drift-verify :plan … :reports …)`; receive structured outcome.
4. (Sprint 4 lands retry handling here.)
5. Render the markdown summary to the canvas-author; flag any escalations.

The architect remains the only code-side actor — it never edits source
itself; it dispatches implementing-LLM subagents and orchestrates
verification. The Phase D system-prompt addition lives in
`.claude/agents/fukan-architect.md` + `doc/canvas-authoring-system-prompt.md`.

**Task 8 — Convenience wrapper `(close-drift {…})`.** Thin SCI-sandbox
helper for terminal/test callers. Takes `:dispatch-fn` (defaults to a
stub that returns `"manual dispatch required"`). Composes the two
entry points end-to-end when called from a context that CAN dispatch
(future phases; tests with stubbed dispatch). For terminal canvas-author
use, the stub return surfaces "use fukan-architect to close drift" prose
so the canvas-author doesn't misuse the surface.

**Task 9 — Smoke test on canvas/distributed/*.** End-to-end run via
`fukan-architect` against the Phase 7 trial-run's remaining drift
findings in `distributed/*`. Verifies the architect-driven loop closes
the cases Phase 7 closed manually. Captures latency data and per-iter
subagent report shape to feed Sprint 4's retry-context design.

### Sprint 4 — Retry + escalation + canvas-side hints

The loop learns to be less brittle. Retry handling lives across two
seats — `close-drift-plan` learns to render an iter-2 instruction
given an iter-1 outcome; the architect's Phase D loop learns when to
dispatch iter-2 vs surface as escalation.

**Task 10 — Iter-2 instruction rendering.** Extend `close-drift-plan`
with `:retry-of <finding-id>` and `:iter-1-report <subagent-narrative>`
+ `:iter-1-drift <finding-snapshot>` opts. When present, the rendered
instruction carries reconciliation-prose preamble (Sprint 1 spec) + the
iter-1 subagent report + the iter-1 drift state + the original
instruction body. Architecture: the architect's loop calls
`close-drift-plan` a second time with these opts after iter-1 verify
returns `:requires-retry? true` for a finding.

**Task 11 — Architect's iter-2 dispatch loop.** Extend Phase D prompt
to handle the per-finding retry flow:

1. After iter-1 `close-drift-verify`, for each `:per-finding` entry
   where `:requires-retry? true` and `:attempts < :max-attempts`:
2. Call `(close-drift-plan :retry-of <id> :iter-1-report … :iter-1-drift …)`.
3. Invoke `Agent` with the iter-2 rendered instruction.
4. Append iter-2's subagent report to the running `:reports` vector
   (tagged with `:attempt 2`).
5. After all iter-2 dispatches complete, call `close-drift-verify`
   again over the iter-2 reports.
6. Findings still failing after iter-2 land in the escalation report.

**Task 12 — Escalation classification.** `close-drift-verify` learns the
six escalation triggers from Sprint 1: `:attempts-exhausted`,
`:no-projection-registered`, `:projection-emits-warning`,
`:canvas-side-hint`, `:scenario-not-found`, `:dispatch-error`. Each
escalation entry in the report carries the trigger keyword, a
human-readable explanation, and a hinted resolution path. Canvas-author
reads escalations to decide manual follow-up.

**Task 13 — Canvas-side hint heuristics.** Pattern-match on drift
findings to flag "canvas might be wrong here": (a) invariants whose
projected predicate has been stubbed-and-failed twice across iterations
(now possible to detect because Sprint 4 has retry data), (b) record
shape-drift where the canvas-side adds fields but `src/` has been
touched recently (`git log` heuristic), (c) cross-module references
where the *referenced* canvas declaration has been edited recently.
Hints surface in the controller's report as advisory `:canvas-side-hint`
escalations; canvas-author decides. NO `:canvas-side/*` scenarios in
Phase 8 — only the hint.

**Task 14 — Observability.** Per-attempt timing + dispatch log + per-iter
subagent report excerpts surface in the report. Useful for Sprint 7
verification + future Phase 9 trial-run framing.

### Sprint 5 — Property-test projection substrate

Land the Sprint 1 design (Option β dispatch + migrate path).

**Task 15 — Layer A `invariant-to-property-test` projection.**
`src/fukan/canvas/project/clojure/invariant_to_property_test.clj`.
Registers `defmethod project [:clojure :canvas/invariant+property-test]`
(the synthetic key from Option β). `dispatch-key-of` learns to return
the synthetic key when the canvas declaration omits the
`(projects-to :predicate)` opt — that is, when property-test is the
selected projection-kind. Existing `invariant-to-predicate` stays
registered on `[:clojure :canvas/invariant]` for the opt-in predicate
case.

**Task 16 — Canvas-source flag plumbing.** Extend `canvas-source` to
recognize the `(projects-to :predicate)` clause on `(invariant …)`
forms. The primitive's projection-kind selection rides on the
canvas-source-emitted `:invariant/projects-to` (default
`:property-test`).

**Task 17 — Drift comparator's test-side awareness.**
`src/fukan/canvas/inspect/drift.clj` gains a `ns->test-path` mirror
and a projection-kind-branched `expected-path-for`. Given an invariant
finding, the comparator uses the canvas-side `:invariant/projects-to`
to pick `src/<ns>.clj` (predicate) or `test/<ns>_test.clj`
(property-test). A property-test artifact at the conventional path
closes the finding; missing artifact at either path surfaces the
appropriate `:expected-code-path` in the finding's offender.

**Task 18 — Address registry update.**
`src/fukan/target/clojure/address.clj` gains the
`:projection-kind/property-test` address shape: ns `<base>-test`,
symbol `<kebab>-property`, file `test/<path>_test.clj`.

**Task 19 — Layer B `drift-close` extends drift-kind dispatch.** A
property-test artifact lives in a test file with `clojure.test.check`
imports and `defspec` neighbors, not in the impl-side defn neighborhood.
The drift-close scenario's `neighbors-section` gets a property-test-aware
branch (new `defmethod` keyed on a property-test sentinel in the finding).

**Task 20 — Tests + coverage regression update.** Unit tests for the new
projection. `test/fukan/canvas/project/coverage_test.clj` updated: the
projection-coverage assertion now expects the
`:canvas/invariant+property-test` synthetic key alongside the existing
`:canvas/invariant`. Address-shape test for the test-path convention.

### Sprint 6 — Property-test trial run via the controller

Use Sprint 3+4's architect-driven loop to close invariant drift via the
new property-test projection. The harder, end-to-end test of the
automation loop.

**Task 21 — Trial-run on canvas/distributed/*.** Invariants in
`distributed.cluster` (`AtMostOneLeaderPerTerm`, `TermMonotonicity`,
`MajorityRequiredForLeadership`) close via property-test projection
through the architect-driven loop. Verifies that (a) the dispatch
loop routes correctly, (b) the implementing-LLM produces working
property tests from the template, (c) drift recognizes the test-side
closure.

**Task 22 — Migrate path cleanup.** After Task 21 closes the three
invariants via property-tests, the orphan predicate stubs in
`src/fukan/distributed/cluster.clj` get deleted (per Sprint 1's
migrate decision). The deletion is itself drift-closure-shaped — the
canvas now expects the test-side artifact, so the src-side stub is
no longer projected.

**Task 23 — Trial-run on one other canvas module with invariants.**
Pick a canvas module that's stable enough that real property tests
make sense (e.g. `canvas/model/build.clj`'s invariants if they exist,
or `canvas/validation/*`). Closes the same loop on richer canvas
material than `distributed/*`'s trial-run scaffolding.

**Task 24 — Findings doc.** `doc/plans/2026-05-28-property-test-trial-findings.md`.
Same structure as Phase 7's trial doc — per-iteration, instruction
quality, defect/decision separation. Inputs Sprint 7 verification.

### Sprint 7 — Verification

Mirrors Phase 5/6/7 verification doc pattern.
`doc/plans/2026-05-28-phase-8-verification.md`. Covers:

- What was attempted vs. built across Sprints 1–6
- Closure controller behavior (closure rate, retry rate, escalation rate)
- Property-test projection quality (Sprints 5 + 6)
- Trial-fidelity gap (Sprint 2): closed, partially closed, or carry-forward?
- Defects surfaced + carried-forward
- Decision (1/2/3)
- Phase 9+ implications

Likely outcome 2 again — works with caveats, with specific carried-forward
items naming the Phase 9 opener.

## Definition of done

- `(close-drift-plan {…opts…})` and `(close-drift-verify {…opts…})` are
  `:trust`-layer agent-api fns callable from `bin/fukan eval`.
- `fukan-architect`'s Phase D prompt drives the two-step dispatch loop —
  canvas-author asks "close drift in module X", the architect plans,
  dispatches per-finding via `Agent`, verifies, surfaces the report.
- The architect-driven loop closes a Phase 6-style drift finding-set
  end-to-end with no human edits between scope-selection and
  report-review.
- Iter-2 retry on first failure works; escalation on persistent failure
  surfaces a decision-ready report with one of the six escalation
  triggers naming the cause.
- Invariant drift findings close via property-test projection through the
  architect-driven loop, at least for the canvas/distributed/* canonical
  set; the three orphan predicate stubs in `distributed.cluster` get
  cleaned up post-closure (migrate path).
- The Phase 7 trial-fidelity gap (real-Agent dispatch) is empirically
  resolved — closure-rate data exists for at least the 6 projection kinds
  that shipped without end-to-end exercise.
- Sprint 7 verification doc lands; decision is outcome 1 or 2 with named
  Phase 9 implications.

## Carried-forward concerns from Phase 7 + 7.5 that Phase 8 addresses

| Concern | Phase 8 sprint |
|---|---|
| `Agent` tool dispatch fidelity unverified | Sprint 2 |
| Event projection not exercised end-to-end | Sprint 2 |
| Rule projection not exercised end-to-end | Sprint 2 |
| Checker projection not exercised end-to-end | Sprint 2 |
| Handler projection not exercised end-to-end | Sprint 2 |
| Atomic projection not exercised end-to-end | Sprint 2 |
| Cold-write scenario not exercised end-to-end | Sprint 2 (probe only — controller doesn't drive cold-write) |
| Invariants might project to property tests | Sprints 1, 5, 6 |
| Type-mismatch shape-drift not exercised end-to-end | Sprint 3 (the controller smoke-test in distributed/* will exercise it once a type-mismatch finding surfaces) |
| drift-close `discipline-prose` is `if`-branched, not `defmulti`-keyed | Folded into Sprint 5's drift-close extension if it lands a fourth kind |
| Handler payload-shape is generic | NOT addressed in Phase 8 — carry to Phase 9 |

The handler-payload-shape limitation stays a Phase 9 item because it
requires canvas-db resolution work orthogonal to Phase 8's dispatch loop
focus.
