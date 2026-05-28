# Phase 9 Sprint 3 — Projection-kinds trial findings

**Date:** 2026-05-28
**Loop tested:** drift-close × 4 iterations (one per unexercised projection kind)
**Scope:** four findings, one per Phase 7+8 unexercised projection kind, dispatched via the Sprint 1 handoff protocol's first end-to-end exercise.

## Preamble — first exercise of the Sprint 1 handoff protocol

Phase 8 Sprint 6 worked by accident: the architect planned, the canvas-author dispatched, but the protocol was implicit. Sprint 1 of Phase 9 formalised the 2-seat collaboration (handoff package, planning flow, verify flow, protocol vocabulary). Sprint 3 is its first production exercise.

The protocol worked. The architect produced a single self-contained handoff package covering all four findings; the canvas-author dispatched four implementing-LLM subagents in parallel (distinct files, no batching constraint); each landed iter-1; ground-truth `(canvas-drift)` confirmed closure. The flow needs no protocol amendments — what shipped in Sprint 1 was sufficient.

## Targets — one finding per unexercised kind

Phase 7+7.5 shipped 9 Clojure-lens projections; Phase 8 Sprint 5 added the 10th (`invariant-to-property-test`). Sprint 6 exercised the property-test path end-to-end. Phase 7 iter 3 exercised `function-to-defn` (via `:fukan.canvas.monolith/exposed-call`). Phase 8 Sprint 2's survey + blocker fixes verified instruction quality on the other six but couldn't dispatch.

Sprint 3's trial covered four of the six:

| Kind | Projection | Target stable-id | Target file |
|---|---|---|---|
| event | `clojure/event-to-schema` | `distributed.election/HeartbeatReceived` | `src/fukan/distributed/election.clj` |
| handler | `clojure/handler-to-defn` | `distributed.log/on_append_entries_requested` | `src/fukan/distributed/log.clj` |
| atomic type | `clojure/value-to-def` | `agent.query/type/QueryRow` | `src/fukan/agent/query.clj` |
| rule | `clojure/rule-to-predicate` | `validation.rules-4b/ExposesPathsResolve` | `src/fukan/validation/rules_4b.clj` |

Two kinds were NOT exercised:

- **`:canvas/checker`** — no `:checker` drift exists in current canvas. All canvas checkers are covered. Sprint 3 documents the gap rather than synthesizing one.
- **`:fukan.canvas.monolith/exposed-call` (function)** — Phase 9 plan said skip if data current; Phase 7 iter 3's `get_entry` closure is current data.

## Methodology — the Sprint 1 protocol in action

1. **Survey drift.** `(canvas-drift)` aggregated 425 findings; filtered by `:canvas-kind` to identify samples per target kind.
2. **Snapshot plans.** Before dispatch, captured each finding's `close-drift-plan` return to `/tmp/plan-<slug>.json`. Sprint 2's stale-plan heuristic enforces this discipline — calling verify with a fresh plan after dispatches landed would have errored.
3. **Architect plan dispatch.** Single Agent call with `subagent_type: fukan-architect`, prompt: "Plan close-drift for the following four stable-ids …". The architect returned a self-contained markdown handoff package with summary, dispatch instructions (including the parallel-fanout-cap-3 + serial-same-file guidance), four per-finding instruction blocks (each pre-wrapped with the cold-context preamble), an unhandled section (empty), and a verify recommendation (a — main-session-direct).
4. **Implementing-LLM dispatch.** Four parallel Agent calls with `subagent_type: general-purpose`, each receiving its per-finding block verbatim from the handoff. All four landed iter-1.
5. **Reset + verify.** `bin/fukan reset` rebuilt the model; per-finding ground-truth check confirmed closure for the four targets.

## Per-iteration results

### Iter 1 — Event (`HeartbeatReceived` → `event-to-schema`)

- **Outcome:** closed iter-1.
- **Duration:** ~90s.
- **Implementing-LLM deviation:** swapped the literal spec's `^:event` keyword-ref form (`:cluster/Term`) for the file's existing `cluster/Term` symbol-alias form, matching sibling defs. Also updated the ns docstring inventory to reflect the new event. Both reasonable judgments beyond the instruction.
- **Loop quality:** clean. The instruction's payload-fields fix from Phase 8 Sprint 2 Task 4 (event projection rendering empty `:map`) held — fields rendered correctly.

### Iter 2 — Handler (`on_append_entries_requested` → `handler-to-defn`)

- **Outcome:** closed iter-1.
- **Duration:** ~71s.
- **Implementing-LLM deviation:** none. Wrote the function body, malli schema, ex-info payload, and docstring verbatim from the instruction. Anchored the Edit on `get-entry` (the prior EOF def) to land cleanly above the file's trailing comments block.
- **Loop quality:** clean. The handler's input shape was the documented `:cat <event-ref> :any` (Phase 7.5 known limitation — handler payload shape carries event reference not payload struct); the implementing-LLM preserved it. Sprint 7 verification confirms the limitation didn't manifest as closure failure.

### Iter 3 — Atomic type (`QueryRow` → `value-to-def`)

- **Outcome:** closed iter-1.
- **Duration:** ~65s.
- **Implementing-LLM deviation:** none. Pasted the `(def ^:schema QueryRow ...)` form verbatim.
- **Loop quality:** clean. Shortest iteration of the four — atomic types are the simplest projection shape.

### Iter 4 — Rule (`ExposesPathsResolve` → `rule-to-predicate`)

- **Outcome:** closed iter-1 (the rule projection).
- **Duration:** ~71s.
- **Implementing-LLM deviation:** none. Stub `(defn exposes-paths-resolve …)` landed at end-of-file with full ex-info payload (canvas-id, rule-name, `:when` trigger).
- **Bonus finding:** the canvas declares `ExposesPathsResolve` as BOTH a rule AND an invariant — the name+role disambiguation convention from CLAUDE.md (`canvas/validation/rules-*` declares each behavioural commitment from two angles). Sprint 3's trial closed the rule-half; the invariant-half remains as a separate drift finding, projecting to `test/fukan/validation/rules_4b_test.clj/exposes-paths-resolve-property`. Sprint 3 didn't scope to invariants in `validation/*`; the bonus drift is documented but not closed.

## Closure-rate summary

| Iteration | Kind | Outcome | Latency |
|---|---|---|---|
| 1 | event | closed iter-1 | ~90s |
| 2 | handler | closed iter-1 | ~71s |
| 3 | atomic type | closed iter-1 | ~65s |
| 4 | rule | closed iter-1 | ~71s |

**Iter-1 closure rate: 4/4 = 100%.** No iter-2 dispatches needed. Per-dispatch latency median ~71s; range 65–90s. Comparable to Phase 8 Sprint 6's 53–75s range — both trials sit in the same wall-clock band.

Aggregate across Phase 7+8+9 real-Agent trials:
- Phase 7 iter 3 (`get_entry`, function): 1/1
- Phase 8 Sprint 6 (3 invariants → property-test): 3/3
- Phase 9 Sprint 3 (4 kinds): 4/4
- **Total: 8/8 iter-1 closures.** No iter-2 dispatch yet exercised in any real-Agent trial.

This is small-sample data and overstates the closure rate — the trial selected straightforward findings in each kind; harder cases (multi-line holds-that, complex handler payloads, recursive type refs) may produce different rates. But the *substrate* is clearly working — instructions are clear enough for a cold implementing-LLM to land them on first attempt.

## Sprint 1 handoff protocol observations

The protocol's first production exercise validated the design:

- **Single architect dispatch covered four findings cleanly.** The handoff package's structure — per-finding block with cold-context wrapper inside the fenced code, no canvas-author wrapping needed — let the canvas-author paste blocks verbatim into Agent prompts. Reduced mental load to "copy block N into prompt N."
- **Parallel dispatch worked.** Four distinct files, no batching contention. Fanout-3 was a moot upper bound (only 4 dispatches total).
- **Plan-snapshot discipline held.** I captured all four `close-drift-plan` returns to `/tmp/plan-*.json` BEFORE dispatching. If I'd skipped this and called `close-drift-verify` with fresh plans afterward, Sprint 2's stale-plan heuristic would have caught me — the post-dispatch plan returns are empty (drift closed) but the reports carry the closed stable-ids.
- **Verify recommendation was correct.** The architect recommended (a) main-session-direct for the small scope; I followed it via ground-truth `(canvas-drift)` queries per finding. The full `close-drift-verify` call wasn't strictly necessary — ground-truth is the authoritative test — but the structured verify call would have given counts + classifications if I'd wanted them.

## Task 8 — escalation triggers exercised

Phase 8 Sprint 4 shipped six escalation triggers; three were untested empirically pre-Sprint 3:

- **`:scenario-not-found`** — unit test added (`test/fukan/agent/close_drift_test.clj`). Drives full plan→verify chain through a synthetic `:check :inspect.drift/this-kind-is-not-registered`. Confirms `:unhandled` extraction lifts into per-finding outcomes with structured `:escalation-reason`.
- **`:dispatch-error`** — unit test added. Pins the `(true? :error)` branch of `dispatch-error-report?` (existing tests covered only the string-shape branch). Uses the canonical `{:stable-id "..." :error true :error-reason "..." :attempt 1}` shape.
- **`:projection-emits-warning`** — **decision: leave reserved.** No Layer A projection emits warnings today; synthesising a surface to fire the trigger creates noise. Added a negative test that pins the reserved state from two angles: (1) `:agent/doc` advertises the trigger as reserved, (2) a plan entry carrying a synthetic `:warnings` field does NOT fire the trigger today (falls through to `:attempts-exhausted`). The trigger's classification codepath in `classify-outcome` is genuinely absent (not stubbed-out); the reserved state is honest. The Phase 9 Sprint 3 Task 8.3 deferral is documented in the docstring near the `structured-escalation` helper.

Test count delta: 1065 → 1068 (+3 close-drift tests, all passing).

All six Sprint 4 escalation triggers now have unit-test coverage:
- `:attempts-exhausted` (Sprint 4)
- `:scenario-not-found` (Sprint 4 + Sprint 3 Task 8.1 expansion)
- `:no-projection-registered` (Sprint 4)
- `:dispatch-error` (Sprint 4 + Sprint 3 Task 8.2 expansion)
- `:canvas-side-hint` (Sprint 4)
- `:projection-emits-warning` (Sprint 3 Task 8.3 — reserved-state pinned)

## Loop quality assessment

The closure loop works end-to-end across the canvas surface Sprint 3 covered. Four projection kinds, four distinct files, four cold implementing-LLM subagents, four iter-1 closures. The Sprint 1 handoff protocol made the 2-seat collaboration deliberate rather than accidental. Sprint 2's plan-snapshot discipline + stale-plan heuristic backstopped the verify path.

Two substrate observations worth carrying to Sprint 4 verification:

1. **Name+role disambiguation creates implicit dual drift findings.** The `validation.rules-*` modules declare each behavioural commitment as both a rule (reactive) and an invariant (timeless). After Phase 8 Sprint 5's migrate path, the rule projects to `src/.../rules_4b.clj` and the invariant projects to `test/.../rules_4b_test.clj`. Closing one doesn't close the other. The closure controller treats them as independent findings (correct); the canvas-author should know to close BOTH halves when working on a name-pair declaration. Worth a one-line note in the canvas-authoring system prompt.

2. **Handler payload shape limitation didn't manifest.** Phase 7.5 documented that handlers project with `:cat <event-ref> :any` rather than the dereferenced payload structural shape. The implementing-LLM preserved the limitation verbatim without complaint or workaround. The stub is correct as a closure marker; whether the eventual real handler implementation needs the dereferenced shape is a Phase 9+ canvas-db-resolution work item, not a Phase 8 closure concern.

## Carry-forward observations

1. **No `:canvas/checker` drift currently exists.** All canvas checkers in `canvas/validation/*` have code-side counterparts. Sprint 3 documents this rather than synthesising a checker drift to exercise the projection. If a future canvas-author adds a `(checker …)` declaration without immediate code-side counterpart, the projection is ready (Phase 7.5 Sprint 1 shipped it with unit tests + coverage regression assertion).

2. **`:fukan.canvas.monolith/exposed-call` (function) trial is current.** Phase 7 iter 3's `get_entry` closure documented the function path's iter-1 closure. No re-exercise needed.

3. **Name-pair convention awareness for canvas-authors.** Sprint 4 verification candidate: add to the canvas-authoring system prompt a brief note about closing rule+invariant pairs together. Doc-only; trivial to land.

## Closing assessment

Phase 9 Sprint 3 closed the trial-fidelity gap Phase 8 Sprint 2 carried forward. The closure-rate empirics Phase 8 was scoped to deliver but couldn't complete in-harness now exist: 4 kinds × 1 finding × 1 attempt = 4/4. The Sprint 1 handoff protocol's first production exercise worked without amendments. The six Sprint 4 escalation triggers now have unit-test coverage.

The substrate is operationally validated across the projection-kind surface that Phase 7+8 shipped. Phase 9 Sprint 4 verification can now claim the trial-fidelity gap is closed.
