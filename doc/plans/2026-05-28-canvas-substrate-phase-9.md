# Phase 9 — Hygiene + carried-forward closure

**Date:** 2026-05-28
**Status:** Drafted

Phase 8's verification (`doc/plans/2026-05-28-phase-8-verification.md`)
landed outcome 2 — works with caveats — with a substantial
carried-forward list. The load-bearing item is that three doc surfaces
still describe `fukan-architect` as the dispatch orchestrator, which
Sprint 6 empirically falsified. Phase 9 is a deliberate hygiene phase:
close the carried-forward items, give the substrate breathing room
between Phase 8's seven sprints and whatever the next push is.

The trajectory hasn't changed — canvas-author writes design; fukan
tells the implementing-LLM what to write; canvas as the intersection
between LLM and human. Phase 9 doesn't add new mechanism; it makes
the existing one accurate and exercises what hasn't been exercised yet.

## Strategic frame

Phase 6 closed *detection*. Phase 7 closed *instruction*. Phase 8
closed *dispatch* — and discovered along the way that the dispatching
seat is the top-level main session, not a sub-agent. Phase 9
metabolises that discovery: revising the docs that name the wrong seat,
formalising the workflow patterns Sprint 6 exercised in passing
(same-file re-render, plan-input persistence), exercising the surfaces
that shipped without end-to-end fidelity (Sprint 2's six unexercised
projection kinds, three reserved escalation triggers), and closing the
small substrate carry-forwards (template prose indentation, optional
`(projects-to :predicate)` producer-side).

Nothing here is new mechanism. Phase 9 is what Phase 8 didn't have
time to clean up.

## Out of scope

- **Bidirectional drift / `:canvas-side/*` scenarios.** Phase 8 canvas-side hints surface the *signal*; Phase 10 (or later) builds the action. Phase 9 stays on the code-side dispatch.
- **Multi-language lens trial.** A second project-lens (Python/TypeScript) waits until cleanup completes.
- **Richer canvas-author dialogue surface.** The "talk back to the loop" capability waits. Phase 9 just makes the existing loop accurate.
- **Browser UI / explorer.** Indefinitely deferred per standing instruction.
- **New scenarios beyond drift-close + cold-write.** Refactor and similar wait until carried-forward items close.

## Sprint plan

Four sprints. Three working + verification.

### Sprint 1 — Doc revisions for architect role retirement

The largest single carry-forward item. Three doc surfaces describe
`fukan-architect` as the dispatch orchestrator; Sprint 6 confirmed
this isn't the case. Revise all three to retire the architect's
"orchestrator" role to "planner + verifier" — the architect can call
`close-drift-plan`/`close-drift-verify` via Bash but cannot dispatch;
the canvas-author (top-level main session) drives the dispatch loop.

**Task 1 — Closure controller design doc.**
`doc/plans/2026-05-28-closure-controller-design.md` § "Where the
controller lives" + the dispatch sketches (Sketch B section). Update
to reflect that the canvas-author (top-level) is the only context
with working `Agent`. The two-entry-point split stays the right design
even though its motivating constraint (architect-driven orchestration)
doesn't hold — the split works equally well for top-level-driven
dispatch. Add a brief "harness constraint" section explaining the
empirical finding (Sprint 6).

**Task 2 — `fukan-architect` agent prompt.**
`.claude/agents/fukan-architect.md`. The architect retains: `Bash`
for `fukan eval`/`fukan status`/`fukan primer`, `Read` for source
inspection. The architect LOSES: `Agent` tool grant (it never worked;
keep the declared grant or remove it — Phase 9 design decides).
Phase D close-drift mode revises from "dispatch the loop" to "plan
the loop + render instructions + verify after canvas-author
dispatches". The architect becomes a planner+verifier; the
canvas-author dispatches.

Decision needed in Sprint 1: keep `Agent` in the architect's declared
tool grant (despite the harness blocking it) or remove. Removing is
honest about what actually works; keeping anticipates harness changes
that might expose nested Agent later. Recommendation: remove + add a
comment explaining the empirical limitation.

**Task 3 — Canvas-authoring system prompt.**
`doc/canvas-authoring-system-prompt.md` § Phase D close-drift mode.
Revise the 5-step orchestration loop to name the canvas-author as
the dispatcher. The architect's role in this prompt becomes:
canvas-author asks the architect to plan; architect renders
instructions per finding; canvas-author dispatches each; canvas-author
holds the plan + reports; canvas-author calls verify. The architect
can advise + verify but doesn't dispatch.

Sprint 1 is doc-only — no code changes.

### Sprint 2 — Workflow patterns formalised in docs

Two implicit Sprint 6 patterns that need to be made explicit.

**Task 4 — Same-file-batched re-render pattern.** Sprint 6 iter-2
and iter-3 only worked because the canvas-author re-rendered the
instruction between dispatches — picking up the previous dispatch's
on-disk changes in the neighbor-section. Without this, iter-2's
instruction would have falsely told the implementing-LLM to "create
the file" when it already existed.

Two ways to formalise:
- (a) Document the convention in the canvas-authoring prompt: "when
  dispatching multiple findings against the same target file, call
  `(close-drift-plan :stable-id <next-id>)` between each dispatch to
  re-render with updated sibling state."
- (b) Add a controller-level helper `(close-drift-plan-next plan
  done-ids)` that takes the original plan + the already-completed
  stable-ids and re-renders only the next pending finding.

Recommendation: (a) — documentation. The canvas-author already calls
`close-drift-plan` per finding in practice; the convention just needs
naming. (b) is a future Phase 10 candidate if the workflow gets used
heavily enough that the wrapping is worth substrate complexity.

**Task 5 — Plan-input dependency surfacing.** `close-drift-verify`
needs the plan from `close-drift-plan` to classify outcomes. If the
canvas-author calls verify after dispatch without holding the
original plan, a fresh `close-drift-plan` won't include closed
findings — verify can't classify them. Sprint 6 hit this when the
canvas-author tested the verify path ad-hoc after dispatches landed.

Two ways to handle:
- (a) Document the dependency in `close-drift-verify`'s `:agent/doc`
  + the canvas-authoring prompt. Make explicit that the plan
  parameter must be the snapshot from before dispatches.
- (b) Add an explicit error in `close-drift-verify` when the plan's
  scope produces zero current findings but `:reports` are non-empty
  — surface "plan appears stale; pass the original plan from
  pre-dispatch".

Recommendation: (a) + (b) together. Documentation makes the convention
clear; the explicit error catches the convention being violated.

**Task 6 — Cosmetic prose indentation fix.** Layer A
`invariant-to-property-test` template's holds-that comment renders
with mixed 2-space/4-space indentation when `doc` carries embedded
newlines. Sprint 6 implementing-LLMs preserved the uneven indentation
verbatim. Cosmetic fix in
`src/fukan/canvas/project/clojure/invariant_to_property_test.clj` —
normalise to 2-space throughout.

Sprint 2 is small code + medium docs.

### Sprint 3 — Trial-fidelity probe via top-level dispatch

Sprint 2 of Phase 8 surveyed instruction quality for the six
unexercised projection kinds but couldn't produce real closure-rate
data due to the harness gap. Sprint 6 confirmed that top-level
dispatch DOES work. Sprint 3 of Phase 9 closes the trial-fidelity gap
by running the survey-then-pivot dispatches from the main session.

**Task 7 — Real-Agent closure trial across the six kinds.** Top-level
dispatch (canvas-author, main session) against six findings — one per
projection kind — to capture iter-1 closure rate. Kinds to exercise:
- `:Type/atomic` — atomic types in `distributed.log` (e.g. `Command`)
- `:canvas/event` — events in `distributed.log` (post-blocker-1 fix,
  payload now renders)
- `:canvas/rule` — rules in `canvas/validation/rules-*`
- `:canvas/checker` — checkers in `canvas/validation/violation.clj` or
  similar
- `:canvas/handler` — handlers in `distributed.log` (e.g.
  `on-append-entries-requested`)
- `:fukan.canvas.monolith/exposed-call` — already exercised in
  Phase 7 iter 3 (`get_entry`); re-exercise or skip if the data is
  considered current

Per-kind: render the instruction, dispatch an implementing-LLM,
verify closure via `(canvas-drift)`, capture wall-clock latency.
Iter-2 if iter-1 fails.

Output: `doc/plans/2026-05-28-projection-kinds-trial-findings.md`
mirroring Sprint 6's findings doc. Closure-rate table per kind +
instruction-quality observations + any defects surfaced.

**Task 8 — Exercise the three reserved escalation triggers.**
Sprint 4 of Phase 8 implemented six escalation triggers; three were
codepath-tested but not empirically exercised:
- `:scenario-not-found` — fire by synthesising a drift kind with no
  registered scenario (drift comparator extension or unit-test
  fixture)
- `:dispatch-error` — fire by injecting a failed report (`:error
  true` flag or missing report)
- `:projection-emits-warning` — currently has no source (Layer A
  emits no warnings). Decide: leave reserved with a documented
  future-Phase-N trigger, OR add a Layer A warning surface that
  fires when the projection skips/falls back.

Recommendation: leave `:projection-emits-warning` reserved; add the
warning surface only when Layer A's behaviour gives it real cause.
Exercise the other two via unit tests + Sprint 7 documentation.

Sprint 3 is the empirical-data sprint Phase 8 was scoped to deliver
but couldn't complete in-harness.

### Sprint 4 — Verification

Mirrors prior verification doc patterns.
`doc/plans/2026-05-28-phase-9-verification.md`. Covers:

- What was attempted vs. built across Sprints 1–3
- Doc revision quality (architect role retirement landed cleanly?)
- Workflow pattern formalisation (re-render convention + plan-input
  dependency surfaced in prompts and at the verify error path?)
- Trial-fidelity gap closed (six projection kinds with real-Agent
  closure-rate data + three escalation triggers exercised or
  explicitly deferred?)
- Defects surfaced + carried-forward (likely small, given the
  hygiene focus)
- Decision (outcome 1/2/3)
- Phase 10 implications

Likely outcome 1 — works clean. Phase 9's scope is closing
known gaps, not opening new ones. If Sprint 3's trial-fidelity probe
surfaces substantive new defects, those bump the outcome to 2.

## Optional Sprint — `(projects-to :predicate)` producer side

Deferred from Phase 8 Sprint 5. Implementing the canvas-side
`(projects-to :predicate)` form on `(invariant …)` lifts means
touching `src/fukan/canvas/vocab/behavioral.clj` (substrate primitive)
and plumbing the canvas-source `:affordance/projects-to` datom +
element-map `:canvas-projection-kind` field.

Two considerations:
- No canvas declaration in the codebase needs the opt-out today
- The consumer side (`dispatch-key-of`) is already wired and tested

The opt-out can stay deferred until a canvas-author actually needs
it. If Sprint 3's trial-fidelity probe surfaces an invariant case
where property-test projection is wrong and predicate projection
would be right, this optional sprint becomes load-bearing. Otherwise
defer further.

Recommendation: defer. Reopen if Sprint 3 demands.

## Definition of done

- The three architect-role-retirement doc surfaces accurately
  describe the canvas-author-driven dispatch flow. No
  "architect dispatches" claims remain.
- Same-file-batched re-render pattern is documented as canvas-author
  convention. Plan-input dependency for `close-drift-verify` is
  documented + surfaced via explicit error.
- Cosmetic prose indentation in the invariant template normalised.
- Six unexercised projection kinds have empirical iter-1 closure-rate
  data from real-Agent dispatch.
- Two reserved escalation triggers exercised via unit tests; the
  third (`:projection-emits-warning`) explicitly classified
  (deferred or implemented).
- Sprint 4 verification doc lands; decision is outcome 1 or 2 with
  named Phase 10 implications.

## Carried-forward concerns from Phase 8 that Phase 9 addresses

| Concern | Phase 9 sprint |
|---|---|
| Architect role retirement requires doc revision | Sprint 1 |
| Same-file-batched re-render pattern not formalised | Sprint 2 Task 4 |
| Verify path's plan-input dependency | Sprint 2 Task 5 |
| Cosmetic prose indentation | Sprint 2 Task 6 |
| Sprint 2's six unexercised projection kinds | Sprint 3 Task 7 |
| Three reserved escalation triggers | Sprint 3 Task 8 |
| `(projects-to :predicate)` producer-side | Optional sprint |
| Task 23 (second canvas module trial) | Folded into Sprint 3's broader probe |
| Canvas-side hint heuristic (c) — cross-module reference recency | NOT addressed — carry to Phase 10 (bidirectional drift) |
| `:projection-emits-warning` trigger reserved | Sprint 3 Task 8 (classify) |

The cross-module reference recency heuristic (c) stays deferred
because it pairs naturally with Phase 10's bidirectional-drift
substrate — implementing it standalone in Phase 9 would land code
that has no consumer until Phase 10's scenarios fire.
