# Phase 9 Verification Report — Hygiene + carried-forward closure

**Date:** 2026-05-28
**Status:** Complete
**Decision:** (1) Works clean — Phase 9 ships as-is; Phase 10 starts cleanly

**Phase scope:** Close the Phase 8 carry-forward list. Phase 8 shipped the
close-drift loop end-to-end (`close-drift-plan` / `close-drift-verify` +
the synthetic property-test projection) but landed outcome 2 — the
architect-driven dispatch hypothesis empirically failed against the
harness, three doc surfaces still described the wrong seat configuration,
two workflow patterns were implicit, and the trial-fidelity gap (Phase 8
Sprint 2's instruction-quality survey rather than real-Agent closure-rate
data) remained open. Phase 9 was scoped as hygiene — no new substrate
mechanism — to metabolise those findings.

The phase ran three working sprints plus this verification sprint.
Sprint 1 formalised the 2-seat architect↔canvas-author handoff protocol
(replacing the original "architect retirement" framing per an in-phase
amendment). Sprint 2 made two implicit workflow patterns explicit and
fixed a cosmetic prose-indentation defect. Sprint 3 exercised the
handoff protocol against four previously-untrialled projection kinds and
backfilled unit-test coverage for the three reserved escalation triggers.

---

## Strategic frame

Phase 6 closed *detection*. Phase 7 closed *instruction*. Phase 8 closed
*dispatch* — and discovered dispatch lives at the canvas-author seat
(top-level main session), not the architect sub-agent. Phase 9
metabolises that finding into protocol rather than retreat: the
architect keeps its substantive role (planning, rendering per-finding
instructions, weighing verify outcomes at canvas altitude) while the
canvas-author owns dispatch. Phase 9 doesn't add new mechanism; it makes
the collaboration accurate and exercises what hasn't been exercised.

The product surface stays the REPL, `bin/fukan eval`, `(help)`, and the
`fukan-architect` sub-agent. No browser UI work (indefinitely deferred).

---

## 1. What was attempted vs. built

Twelve commits clustered between the Phase 9 plan landing (`oynnwrtp`,
`80b50a28`) and the Sprint 3 findings doc (`lkrosnms`, `1393970a`).

### Sprint 1 — Architect↔canvas-author handoff protocol

| Planned | Delivered | Status |
|---|---|---|
| T1/T2/T3: Handoff package + planning flow + verify flow in `fukan-architect.md` | `.claude/agents/fukan-architect.md` Phase D rewritten as a 2-seat protocol; handoff package template + planning flow (steps 1–5) + verify flow with canvas-altitude interpretation (`a4e11b0a`) | Done |
| T4: Closure-controller design doc amendment | New "two-seat collaboration" section added to `doc/plans/2026-05-28-closure-controller-design.md` (`89a74bc5`) | Done |
| T5: Canvas-authoring system prompt amendment | `doc/canvas-authoring-system-prompt.md` § Phase D rewritten — canvas-author learns to delegate planning, own dispatch, choose verify flow (`8a77374f`) | Done |
| T6: Architect tool grant decision | Option (a) — `Agent` removed from declared tool list; front-matter `description` and Phase D context-block note the harness-block reason (`bc80a173`) | Done |

A mid-sprint plan amendment landed first (`62021342`, `svmmklrr`)
reframing Sprint 1 as "handoff protocol" rather than the original
"architect retirement" — the architect keeps its planning + verifying
role while losing only the dispatch step. The four T1–T6 commits then
landed the protocol consistently across the three doc surfaces.

### Sprint 2 — Workflow patterns formalised

| Planned | Delivered | Status |
|---|---|---|
| T4: Same-file re-render convention | Documented in the canvas-authoring system prompt Phase D § dispatch discipline; also lives in the architect-emitted handoff package's dispatch-instructions block | Verified-already in Sprint 1 |
| T5: Plan-input dependency surfaced via docstring + stale-plan error | `close-drift-verify`'s `:agent/doc` calls out the snapshot requirement; explicit `:stale-plan` `ex-info` fires when post-dispatch verify is invoked with a fresh plan whose `:scope` produces zero current findings but `:reports` is non-empty (`8f8260c8`, `src/fukan/agent/api.clj` ~lines 1545–1625) | Done |
| T6: Cosmetic prose indentation fix | `src/fukan/canvas/project/clojure/invariant_to_property_test.clj` normalised; unit-test regression added (`d0b9bcd4`) | Done |

T4 was a scope shift. Sprint 1's architect-handoff package already
embeds the re-render guidance as canvas-author-facing dispatch
instructions; the canvas-authoring system prompt picked up the same
convention as part of T5's Phase D rewrite. The standalone Sprint 2 T4
doc-only delta collapsed into Sprint 1's broader prompt revision.

### Sprint 3 — Trial-fidelity probe + reserved-escalation coverage

| Planned | Delivered | Status |
|---|---|---|
| T7: Real-Agent closure trial across six unexercised projection kinds | Four closed via the Sprint 1 handoff protocol (event, handler, atomic-type, rule); two scoped out (checker — no live drift; function — Phase 7 iter 3 covered it) (`dcdf05ff`) | Done — 4/4 iter-1 closure |
| T8.1: `:scenario-not-found` unit test | Added — drives plan→verify through synthetic non-registered check (`ae491b63`) | Done |
| T8.2: `:dispatch-error` unit test | Added — pins `(true? :error)` report branch (`ae491b63`) | Done |
| T8.3: `:projection-emits-warning` classification | Decision: leave reserved; negative test added pinning the reserved state from two angles (docstring advertises reserved, synthetic `:warnings` field falls through to `:attempts-exhausted`) (`ae491b63`) | Done |
| T9: Findings doc | `doc/plans/2026-05-28-projection-kinds-trial-findings.md` (`1393970a`) | Done |

Sprint 3 was the load-bearing empirical sprint. The Sprint 1 protocol
worked without amendments under its first production exercise: a single
architect dispatch returned a self-contained four-finding handoff
package; four implementing-LLM subagents dispatched in parallel
(distinct files); all four closed iter-1; `(canvas-drift)` ground-truth
confirmed closure for each target. Latency 65–90s per dispatch (median
~71s), comparable to Phase 8 Sprint 6's 53–75s band.

---

## 2. Architect handoff protocol quality

Sprint 1 shipped the 2-seat protocol across three doc surfaces; Sprint 3
exercised it end-to-end against four findings under cold context.
Empirical verdict per design axis:

**Handoff package format.** A single markdown document — summary +
dispatch-instructions + per-finding blocks + verify recommendation — let
the canvas-author paste blocks verbatim into Agent prompts without
re-wrapping. The per-finding blocks already embed a cold-context
preamble inside the fenced code. Sprint 3 used the package as
specified; no friction surfaced. No amendments needed.

**Planning flow.** Architect's job: call `(close-drift-plan …)` via
Bash, inspect plan structure, compose handoff markdown, emit verify-flow
recommendation. Sprint 3's single architect dispatch covered four
findings in one round-trip; the architect's `Read` tool grant let it
sanity-check renders against neighbor file context before emission. No
friction surfaced.

**Verify flow.** The protocol offers two flows: (a) main-session-direct
`close-drift-verify` call from canvas-author seat, suitable for ≤2-
finding or familiar scopes; (b) re-dispatch architect with plan +
reports for canvas-altitude interpretation, suitable for broader or
unfamiliar scopes. Sprint 3 followed the architect's (a) recommendation
for the four-finding scope and used direct ground-truth `(canvas-drift)`
queries per finding — the full structured verify call wasn't strictly
necessary since ground-truth is authoritative, but the structured path
remains available when classification + counts are wanted.

**Protocol vocabulary.** The "planning" / "verifying" sub-mode split,
the explicit "Agent tool intentionally absent from grant" note in the
architect's front-matter, and the "harness-blocked nested dispatch"
context block (`.claude/agents/fukan-architect.md` lines 3, 19, 102) all
match the empirical reality. Sprint 1 T6's decision to *remove* the
`Agent` grant rather than retain it speculatively was correct — leaving
it would have created surface area for future canvas-authors to retry
the failed orchestration path.

The protocol's first production exercise (Sprint 3) needed no
amendments. The handoff format, planning flow, verify flow, and
vocabulary all held under cold load.

---

## 3. Workflow patterns formalised

Three workflow concerns Phase 8 carried forward.

**Same-file batched re-render convention (Sprint 2 T4).** Verified-already
in Sprint 1's prompt work. The canvas-authoring system prompt's Phase D
section documents the convention ("call `close-drift-plan :stable-id`
between dispatches when targeting the same file"); the architect's
handoff package emits the same guidance inside the dispatch-instructions
block. Sprint 3's four-finding scope didn't exercise the same-file
serial path (all four findings targeted distinct files), so the
convention remains workflow-documented but not Sprint-3-exercised.
Acceptable: Phase 8 Sprint 6's three same-substrate invariant closures
already validated the serial-re-render approach empirically.

**Plan-input dependency surfaced (Sprint 2 T5).** Two-pronged closure:

- `close-drift-verify`'s `:agent/doc` (`src/fukan/agent/api.clj`
  lines 1539–1547) calls out that the `:plan` parameter must be the
  snapshot returned by `close-drift-plan` *before* dispatches landed.
- A `:stale-plan` `ex-info` throw fires when the plan's `:scope`
  produces zero current findings but `:reports` is non-empty — the
  classic violation pattern where verify is called with a fresh plan
  after dispatches closed the findings.

The companion test (`test/fukan/agent/close_drift_test.clj`, +73 lines
in `8f8260c8`) pins both the throw shape and the doc-string content.

**Cosmetic prose indentation (Sprint 2 T6).** Layer A's
`invariant-to-property-test` template emitted holds-that comments with
mixed 2-space/4-space indentation when `doc` carried embedded newlines.
Fixed in `src/fukan/canvas/project/clojure/invariant_to_property_test.clj`;
a unit test in
`test/fukan/canvas/project/clojure/invariant_to_property_test_test.clj`
locks the normalised shape. Pre-fix output already lived in
`test/fukan/distributed/cluster_test.clj` (the Phase 8 Sprint 6
closures) — that file's existing comments are immutable artifacts of the
prior template and remain readable; future invariant closures land with
the corrected indentation.

All three patterns are now either documented, surfaced via error, or
fixed at template level.

---

## 4. Trial-fidelity gap closure

This is the load-bearing Phase 9 outcome. Phase 8 verification §6
carried forward: "Sprint 2's six unexercised projection kinds —
instruction quality surveyed but never end-to-end closed via real-Agent
dispatch." Phase 9 Sprint 3 closed four of six via the new handoff
protocol; the remaining two were scoped out with documented rationale.

| Projection kind | Target stable-id | Outcome | Latency |
|---|---|---|---|
| event (`event-to-schema`) | `distributed.election/HeartbeatReceived` | closed iter-1 | ~90s |
| handler (`handler-to-defn`) | `distributed.log/on_append_entries_requested` | closed iter-1 | ~71s |
| atomic-type (`value-to-def`) | `agent.query/type/QueryRow` | closed iter-1 | ~65s |
| rule (`rule-to-predicate`) | `validation.rules-4b/ExposesPathsResolve` | closed iter-1 | ~71s |
| checker (`checker-to-defn`) | — | scoped-out (no live drift) | — |
| function (`function-to-defn`) | — | scoped-out (Phase 7 iter 3 covered it) | — |

**Iter-1 closure rate (Sprint 3): 4/4 = 100%.** Aggregate across the
three real-Agent trials shipped to date:

- Phase 7 iter 3 (`get_entry`, function-to-defn): 1/1
- Phase 8 Sprint 6 (three invariants → property-test): 3/3
- Phase 9 Sprint 3 (four kinds): 4/4
- **Total: 8/8 iter-1 closures.** No iter-2 dispatch has been
  exercised against live drift in any real-Agent trial.

This is small-sample data, and the trial selected straightforward
findings per kind; harder cases (multi-line holds-that prose, complex
handler payloads, recursive type refs) may produce different rates. The
honest read: substrate is operationally validated, not statistically
calibrated. Phase 10+ trials against organic canvas-author drift-close
sessions will accumulate the calibration data the closure controller's
provisional retry/concurrency thresholds need.

**Reserved escalation trigger coverage.** Phase 8 Sprint 4 shipped six
escalation triggers; three were unit-tested at ship time, three were
reserved. Sprint 3 T8 closed the reserved trio with unit-test coverage:

- `:scenario-not-found` — synthetic non-registered check drives
  full plan→verify; structured `:escalation-reason` lifted correctly.
- `:dispatch-error` — pins the `(true? :error)` report-shape branch
  alongside the existing string-shape coverage.
- `:projection-emits-warning` — classification deferred; negative
  test pins reserved state. Decision rationale: no Layer A projection
  emits warnings today, so synthesising a surface to fire the trigger
  would add noise. Reopen if a future Layer A warning source surfaces.

All six escalation triggers now carry unit-test coverage. The
Phase 8 §6 concern that three triggers had "no live-model exercise"
is closed at the unit-test level. Real-Agent exercise awaits trials
where dispatches actually fail — a calibration concern, not a
substrate concern.

A bonus structural observation surfaced during Sprint 3's rule-kind
dispatch: the name+role disambiguation convention
(CLAUDE.md § Canvas conventions) creates implicit dual drift findings
when a `validation/*` module declares both an `(invariant …)` and a
`(rule …)` under the same `:entity/name`. The rule projects to
`src/.../<module>.clj`; the invariant projects to
`test/.../<module>_test.clj/<name>-property` (Phase 8 Sprint 5's
property-test path). Sprint 3 closed the rule-half of
`ExposesPathsResolve`; the invariant-half remains as a separate drift
finding. This is correct behaviour — the closure controller treats
them as independent findings — but canvas-authors working on
name-pair declarations should know to close both halves. Phase 10
candidate: a one-line note in the canvas-authoring system prompt.

---

## 5. Defects surfaced but NOT closed

Phase 9's hygiene focus produced a short carry-forward list. Each entry
maps to a Phase 10 opener candidate.

1. **Name+role disambiguation creates implicit dual drift findings.**
   Sprint 3 bonus finding (above). Doc-only fix: add a one-line note
   to the canvas-authoring system prompt about closing rule+invariant
   pairs together. Trivial Phase 10 carryover.

2. **`(projects-to :predicate)` producer-side opt-out still deferred.**
   Plumbed through canvas-source and dispatch (Phase 8 Sprint 5); no
   live canvas declaration uses it. Phase 9's optional sprint —
   touching `src/fukan/canvas/vocab/behavioral.clj` to wire the
   producer side — stayed deferred because Sprint 3 didn't surface an
   invariant case where property-test projection is wrong and
   predicate projection would be right. Defer further; reopen when a
   real generator-unfriendly invariant emerges.

3. **Handler payload shape limitation didn't manifest as closure
   failure.** Phase 7.5 documented that handlers project with
   `:cat <event-ref> :any` rather than the dereferenced payload
   structural shape. Sprint 3's handler closure
   (`on_append_entries_requested`) preserved the limitation verbatim
   without complaint. The stub is correct as a closure marker; whether
   real handler implementations eventually need the dereferenced shape
   is a Phase 10+ canvas-db-resolution concern.

4. **Canvas-side hint heuristic (c) — cross-module reference recency —
   still deferred.** Requires `git log` introspection over the canvas
   tree. Pairs naturally with Phase 10's bidirectional-drift substrate
   (canvas-side scenarios that act on the signal these heuristics
   surface); implementing the heuristic standalone in Phase 9 would
   land code with no consumer until bidirectional scenarios fire.

5. **`:projection-emits-warning` trigger reserved.** Sprint 3 Task 8.3
   decision documented above. Reopen when a Layer A warning source
   surfaces — likely paired with the handler-payload-shape resolution
   above.

The cross-module + multi-language lens trial concern (Phase 8 §6 item 7,
Phase 9 plan's out-of-scope item) is unaddressed by Phase 9 and remains
a Phase 10 candidate. Sprint 3's trial closed only within the
fukan-on-fukan Clojure lens.

---

## 6. Decision

**Outcome (1): Works clean — Phase 9 ships as-is; Phase 10 starts cleanly.**

The Phase 9 plan named three outcomes:

1. Phase 9 ships as-is; Phase 10 starts cleanly.
2. Phase 9 ships; concerns carry forward.
3. Phase 9 doesn't ship; gaps need closure first.

The closure controller's 2-seat protocol works empirically. The Sprint 1
handoff package format, planning flow, verify flow, and protocol
vocabulary survived their first production exercise (Sprint 3) without
amendments. Three doc surfaces now match the harness's actual `Agent`
tool semantics. Two workflow patterns are explicit (the re-render
convention via prompt revision; the plan-input dependency via docstring
+ stale-plan error). The cosmetic prose-indentation defect is fixed at
template level with a regression test. The trial-fidelity gap closed
with 4/4 iter-1 closure across four unexercised projection kinds,
bringing the aggregate to 8/8 across all real-Agent trials. The three
reserved escalation triggers now have unit-test coverage.

The §5 carry-forward list is short and contains no items that block
Phase 10 from opening. Three entries (predicate opt-out producer side,
canvas-side heuristic c, `:projection-emits-warning` source) are
deferred-by-design — pairing them with Phase 10's bidirectional-drift
scope is the right structural placement. One entry (name-pair note in
canvas-authoring prompt) is a trivial doc carryover. One entry
(handler payload shape) is a Phase 7.5 known limitation that didn't
manifest as a closure failure in Sprint 3's trial.

Outcome 2 would be honest if Sprint 3 had surfaced substantive new
defects; it didn't. Outcome 3 isn't warranted — Phase 9's scope closed
cleanly.

---

## 7. Phase 10 implications

The trajectory holds: canvas-author writes design at canvas altitude;
fukan tells the implementing-LLM what to write; canvas is the
load-bearing intersection between LLM and human. Phase 9 made the
2-seat collaboration accurate; Phase 10 extends the loop's reach without
changing the trajectory.

Phase 10 candidate framings — enumerated, not picked:

a. **Bidirectional drift — `:canvas-side/*` scenarios.** Phase 8's
   canvas-side hint heuristics tell the canvas-author "this drift might
   be canvas-side"; Phase 10 scenarios would let the close-drift loop
   *act* on that signal — propose canvas edits, not just code edits. The
   hints accumulated through Phases 8+9 make the data ready; bidirectional
   scenarios make the action ready. Pairs naturally with deferred §5
   items 4 (heuristic c) and 5 (`:projection-emits-warning`).

b. **Cross-module + multi-language lens trial.** The Clojure lens is
   the only lens shipped; Sprint 3's trial closed within
   `distributed.cluster`, `distributed.log`, `agent.query`, and
   `validation.rules-4b` but didn't exercise cross-module canvas
   references. A second project-lens (Python? TypeScript?) and a
   cross-module close-drift trial would validate the substrate's
   generality.

c. **Richer canvas-author dialogue surface.** The closure controller
   ships a `:rendered` markdown summary; the canvas-author reads it.
   The reverse direction — letting the canvas-author talk back at the
   loop, querying iter-1 reports, asking "why did this finding
   escalate?" — is unbuilt. Phase 10 closes this if the substrate-driven
   loop starts feeling one-directional in real use.

Each is independently shippable and complementary. The choice depends
on which axis Phase 10 wants to push first.

Standing constraint: no UI work (indefinitely deferred).

---

## 8. Test + lint state

**Test state.** `clojure -M:test`: 1068 tests, 2801 assertions, 0
failures, 3 errors. The three errors are the audit-trail throws from
Phase 8 Sprint 6's property-test closures (`:not yet implemented`
ex-info from each `defspec` in
`test/fukan/distributed/cluster_test.clj`). They are expected closure
markers, not test failures. Test delta from Phase 8 close: +8 tests
(+3 Sprint 3 T8 escalation triggers, +3 Sprint 2 T5 stale-plan
coverage, +2 Sprint 2 T6 indentation regression).

**Lint state.** `clj-kondo --lint src test`: 7 errors, 23 warnings.
The 7 errors are intentional test fixtures in
`test/fukan/fixtures/agent/agent-views-*` — they test error-handling
paths in `fukan.agent.api`. The 23 warnings are pre-existing fixture
noise (legacy `canvas/` mirror tests with unused requires,
`test/fixtures/clojure-projects/dup/a.clj`'s intentional redefinition,
one redundant-let in `defquery_test.clj`). No new lint findings
introduced by Phase 9.

**Daemon smoke test.** Started daemon; verified:

- `(canvas-projections)` returns 10 (the nine Phase 7+7.5 + the
  synthetic-dispatch `:canvas/invariant+property-test`)
- `(canvas-scenarios)` returns 2 (`drift-close`, `cold-write`)
- `(canvas-drift)` returns 422 findings; the four Sprint 3 closure
  targets (`HeartbeatReceived`, `on_append_entries_requested`,
  `QueryRow`, `ExposesPathsResolve`) are absent from drift output —
  confirming closure end-to-end.

---

## 9. Phase 9 artifact inventory

| Artifact | Phase 9 sprint | Description |
|---|---|---|
| `doc/plans/2026-05-28-canvas-substrate-phase-9.md` | Plan | The Phase 9 plan + amendment (architect handoff, not retirement) |
| `doc/plans/2026-05-28-projection-kinds-trial-findings.md` | S3 | Sprint 3 findings — 4/4 iter-1 closures + escalation-trigger coverage |
| `doc/plans/2026-05-28-phase-9-verification.md` | S4 | This document |
| `.claude/agents/fukan-architect.md` | S1 | Phase D rewritten as 2-seat protocol; `Agent` removed from declared tool grant |
| `doc/canvas-authoring-system-prompt.md` | S1 | Phase D rewritten — canvas-author owns dispatch; same-file re-render convention documented |
| `doc/plans/2026-05-28-closure-controller-design.md` | S1 | Two-seat-collaboration section added |
| `src/fukan/agent/api.clj` | S2 | `close-drift-verify` docstring + `:stale-plan` ex-info |
| `src/fukan/canvas/project/clojure/invariant_to_property_test.clj` | S2 | Holds-that comment indentation normalised |
| `test/fukan/agent/close_drift_test.clj` | S2 + S3 | Stale-plan tests + three escalation-trigger tests |
| `test/fukan/canvas/project/clojure/invariant_to_property_test_test.clj` | S2 | Indentation regression test |

**Phase 9 commit count:** 12 commits — 1 plan + 1 plan amendment + 4
Sprint 1 doc revisions + 2 Sprint 2 substrate edits + 2 Sprint 3
substantive commits (trial + escalation triggers) + 1 Sprint 3 findings
doc + this verification doc.

**Substrate state at Phase 9 close.** Layer A: 10 Clojure-lens
projections (unchanged from Phase 8). Layer B: 2 scenarios
(unchanged). Agent API: `close-drift-plan` / `close-drift-verify` /
`close-drift` unchanged in surface; `close-drift-verify` gained a
docstring + stale-plan guard. `fukan-architect`: Phase D rewritten as
2-seat handoff protocol; `Agent` tool grant removed. Closure loop:
8/8 iter-1 closure across all real-Agent trials to date; iter-2 path
remains substrate-built but trial-untested against live drift.
