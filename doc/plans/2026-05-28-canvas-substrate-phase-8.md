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

- **Closure controller.** A new agent-api fn `(close-drift {…})` plus a
  Phase D extension on `fukan-architect`. Takes a finding scope, dispatches
  instructions, verifies, retries, escalates, reports. Lives on top of
  `(canvas-drift)` + `(spec)` + `(instruct)` + the `Agent` tool grant —
  pure orchestration, no new substrate primitives.
- **Property-test projection.** A second invariant projection targeting
  `test/<ns>/properties.clj` instead of `src/<ns>.clj`. Drift comparator
  gains a test-side awareness path. The `defn` predicate projection stays
  as a fallback shape, but property-tests become the primary code-side
  counterpart for invariants.

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

### Sprint 1 — Closure controller + invariant-projection designs

Two design docs paused for user review before any code lands.

**Task 1 — Closure controller design.** Output: `doc/plans/2026-05-28-closure-controller-design.md`. Covers:

- **Where the controller lives.** Agent-api fn vs architect-agent extension vs new CLI subcommand. Recommendation: agent-api fn `(close-drift {…})` callable from `bin/fukan eval` AND from `fukan-architect`'s Phase D. Pure orchestration on top of existing surface.
- **Scope shape.** `:module-coord`, `:check` (drift kind), `:stable-id` (single finding), `:all`. Mirrors `(canvas-drift)`'s filter surface.
- **Concurrency model.** Sequential vs parallel dispatch. Recommendation: sequential per-file (avoids edit conflicts when multiple findings land in the same target), parallel across files. The architect already has this constraint.
- **Retry policy.** Per-finding attempt count (recommend max 2). On attempt 2, the verification failure from attempt 1 is fed into the instruction. Persistent failure → escalation.
- **Escalation triggers.** What surfaces as "needs human review": (a) >N failed attempts, (b) drift kind not handled by any scenario, (c) projection emits warnings or skips, (d) Sprint 4's canvas-side hint heuristics.
- **Report shape.** Structured map per finding: `{:stable-id :outcome :attempts :elapsed :final-state}` plus a markdown summary. Returns to caller; the architect can fold into a Phase D report.
- **State + observability.** Where the controller's progress lives (in-memory only? `.fukan/closure-log.edn`?). Recommendation: in-memory return value first; persistence only if Sprint 3 trial-run reveals need.

**Task 2 — Invariant projection design.** Output: `doc/plans/2026-05-28-invariant-projection-design.md`. Covers:

- **Why property-tests, not predicate `defn`s.** The trial doc's recommendation #4 framing. Invariants are timeless behavioral commitments — generative property tests express them naturally; `defn` stubs that throw express them as deferred work.
- **Projection-kind addition.** New `:projection-kind/property-test` alongside the existing `:projection-kind/invariant`. The Clojure-lens registers BOTH for `:canvas/invariant`; selection happens at controller level (canvas-author preference) or projection-level (a `:fukan/projects-to-tests` flag on the canvas declaration).
- **Artifact kind addition.** New `Code.PropertyTest` artifact kind alongside `Code.Function`. The analyzer's projection edges from invariants now optionally land on the test-side artifact.
- **Address convention for test artifacts.** `addr/canonical` extension: invariants project to `test/<root>/<module>-test.clj` namespace, symbol `<invariant-kebab>-property` or similar. Mirror `:projection-kind/test`'s existing path but distinguish from generic tests.
- **Template shape.** A `defspec`-style or `clojure.test.check` `defproperty` template skeleton — generative inputs + property body with the holds-that prose as the property name + an `is`-style assertion against the invariant.
- **Drift comparator updates.** A property-test artifact at the conventional path closes the drift finding. A bare `defn` stub at the conventional `src/` path also closes (legacy compatibility). Either-or: a single canvas-side invariant has at most one closing artifact at a time, and the comparator chooses the matching projection-kind.
- **Coexistence with predicate projection.** Keep `invariant-to-predicate` registered. The two projections produce different `:target.path` (src vs test) and different `:projection-kind` so they don't collide. Canvas-author picks; future canvas-decoration can choose per invariant.

Both docs pause for user review before Sprints 2–6 begin. The user
amendment cycle has been load-bearing across Phases 5–7; preserve it.

### Sprint 2 — Trial-fidelity probe across unexercised projections

Goal: get honest end-to-end data on how often a *real subagent* (with
zero prior context) successfully implements a Phase 7 instruction. This
data is the empirical input to Sprint 1's retry-policy design.

Sprint 2 sits BEFORE Sprint 3 deliberately. The Phase 7 trial-run used
in-session implementing-LLM substitution (canvas-author and
implementing-LLM were the same context); the controller's retry +
escalation thresholds need to be calibrated against real cold-subagent
behavior, not pretend.

**Task 3 — Real-Agent dispatch probe.** `fukan-architect` dispatches
implementing-LLMs against the 6 unexercised projection kinds:
`event-to-schema`, `rule-to-predicate`, `checker-to-defn`,
`handler-to-defn`, `value-to-def`, plus the `cold-write` scenario.
Each probe runs at least 2 iterations with fresh subagents. Output:
`doc/plans/2026-05-28-trial-run-real-agent-findings.md` — structured
per-projection-kind, with closure rate, instruction-quality observations,
and any defects surfaced.

**Task 4 — Defect triage.** Fixes for surfaced defects land between
Task 3 and Sprint 3. Defects worth a Phase 8 fix vs deferred to
Phase 9 get explicitly classified in the trial doc. The classification
criterion: does the defect block automation? If yes, fix now; if no,
log and continue.

Sprint 2 must complete before Sprint 3 begins. The Sprint 1 design's
retry-policy section gets amended post-Sprint 2 if the empirical data
contradicts the proposed thresholds.

### Sprint 3 — Closure controller MVP

Implement the controller per Sprint 1 + Sprint 2 amendments. MVP scope:
single-pass dispatch, no retries yet. The MVP exercises the orchestration
shape (scope filtering, instruction generation, dispatch, verification,
report) end-to-end against the easy cases (function-kind drift, getter
drift) before retry logic adds complexity.

**Task 5 — `(close-drift {…})` agent-api fn.** Under `:trust` layer
with `:severity :info`. Same SCI sandbox surface as `(canvas-drift)`
and `(instruct)`. Accepts the scope shape Sprint 1 specified. Returns
the structured-report map.

**Task 6 — Dispatch backbone.** For each finding in scope:
`(instruct finding scenario-id)` → render instruction → `Agent`
dispatch → wait for completion → `(canvas-drift)` against same scope →
finding present? failure : success. Sequential per-file, parallel
across files (per Sprint 1's concurrency model).

**Task 7 — `fukan-architect` Phase D extension.** Architect gains
"close-drift mode": canvas-author asks "close drift in module X", the
architect calls `(close-drift :module-coord "X")`, watches the
controller's progress, and renders a Phase D close-drift report. The
architect remains the *only* code-side actor — it never edits source
itself; it only orchestrates.

**Task 8 — Smoke test on canvas/distributed/*.** End-to-end run against
the Phase 7 trial-run's remaining drift findings in `distributed/*`.
Verifies the controller closes the cases Phase 7 closed manually.

### Sprint 4 — Retry + escalation + canvas-side hints

The controller learns to be less brittle.

**Task 9 — Retry on failure.** Attempt 2 receives the attempt-1
verification failure as additional instruction context. The
verification-error format is structured — drift kind, comparator
output (e.g. expected-symbol vs actual-symbol, shape divergence
detail). Max 2 attempts per finding by default; configurable via
`:max-attempts` opt.

**Task 10 — Escalation report.** Findings that fail after max attempts
land in an escalation section of the controller's report: per-finding,
why it escalated, what the attempted instructions were, what the
verification said. The canvas-author reads this section and decides
manually.

**Task 11 — Canvas-side hint heuristics.** Pattern-match on drift
findings to flag "canvas might be wrong here": (a) invariants whose
projected predicate has been stubbed-and-failed twice across iterations,
(b) record shape-drift where the canvas-side adds fields but `src/` has
been touched recently (`git log` heuristic) — code-side may be the
ground truth, (c) cross-module references where the *referenced* canvas
declaration has been edited recently. Hints surface in the controller's
report as advisory; canvas-author decides. NO `:canvas-side/*`
scenarios in Phase 8 — only the hint.

**Task 12 — Observability.** Per-attempt timing + dispatch log surfaces
in the controller's report. Useful for Sprint 7 verification + future
Phase 9 trial-run framing.

### Sprint 5 — Property-test projection substrate

Land the Sprint 1 design.

**Task 13 — Layer A `invariant-to-property-test` projection.**
`src/fukan/canvas/project/clojure/invariant_to_property_test.clj`.
Registers `defmethod project [:clojure :canvas/invariant]` — wait, the
key collides with the existing `invariant-to-predicate`. Resolution:
add a `:projection-kind` discriminator at dispatch level, OR retire
the predicate projection. The Sprint 1 design picks; if predicate stays,
the dispatch key extends to `[lens-id :canvas/invariant :projection-kind]`
and the existing 2-tuple form delegates to the default (predicate).

**Task 14 — Drift comparator's test-side awareness.**
`src/fukan/canvas/inspect/drift.clj` learns to recognize a
`Code.PropertyTest` artifact at the conventional test path as the
closer for an invariant drift finding. The canvas-side projection-kind
selection (predicate vs property-test) becomes part of finding context;
the comparator uses it to pick the right path.

**Task 15 — Address registry update.**
`src/fukan/target/clojure/address.clj` gains the `:projection-kind/property-test`
address shape per Sprint 1's address-convention design.

**Task 16 — Layer B `drift-close` extends drift-kind dispatch.** A
property-test artifact lives in a test file with imports and
`defproperty`-shaped neighbors, not in the impl-side defn neighborhood.
The drift-close scenario's neighbor-section needs a property-test-aware
branch (new `defmethod` keyed on the projection-kind or on a property-test
sentinel in the finding).

**Task 17 — Tests.** Unit tests for the new projection. Coverage
regression test updates: the projection-coverage assertion now
expects two registrations under `:canvas/invariant` (predicate +
property-test).

### Sprint 6 — Property-test trial run via the controller

Use Sprint 3's controller to close invariant drift via the new
property-test projection. The harder, end-to-end test of the
automation loop.

**Task 18 — Trial-run on canvas/distributed/*.** Invariants in
`distributed.cluster` (`AtMostOneLeaderPerTerm`, `TermMonotonicity`,
`MajorityRequiredForLeadership`) close via property-test projection
through the controller. Verifies that (a) the controller routes
correctly, (b) the implementing-LLM produces working property tests
from the template, (c) drift recognizes the test-side closure.

**Task 19 — Trial-run on one other canvas module with invariants.**
Pick a canvas module that's stable enough that real property tests
make sense (e.g. `canvas/model/build.clj`'s invariants if they exist,
or `canvas/validation/*`). Closes the same loop on richer canvas
material than `distributed/*`'s trial-run scaffolding.

**Task 20 — Findings doc.** `doc/plans/2026-05-28-property-test-trial-findings.md`.
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

- `(close-drift {…opts…})` is a `:trust`-layer agent-api fn callable from
  `bin/fukan eval` and from `fukan-architect`'s Phase D.
- The controller closes a Phase 6-style drift finding-set end-to-end with
  no human edits between scope-selection and report-review.
- Retry on first failure works; escalation on persistent failure surfaces
  a decision-ready report.
- Invariant drift findings close via property-test projection through the
  controller, at least for the canvas/distributed/* canonical set.
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
