# Phase 9 — Hygiene + carried-forward closure

**Date:** 2026-05-28
**Status:** Drafted

Phase 8's verification (`doc/plans/2026-05-28-phase-8-verification.md`)
landed outcome 2 — works with caveats — with a substantial
carried-forward list. The load-bearing item is the architect's
relationship to dispatch: Phase 8's design assumed the architect
orchestrates end-to-end via its own `Agent` tool grant; Sprint 6
empirically confirmed the harness blocks nested `Agent` invocation.
Sprint 6 worked by accident — the canvas-author (main session) drove
dispatch directly while the architect's plan + instructions remained
the substantive output. Phase 9 metabolises that finding by making
the 2-seat collaboration first-class: architect plans + renders +
returns a handoff package; canvas-author (main session) consumes the
package and dispatches; verify runs from either seat depending on
scope.

The trajectory hasn't changed — canvas-author writes design; fukan
tells the implementing-LLM what to write; canvas as the intersection
between LLM and human. Phase 9 doesn't add new mechanism; it makes
the collaboration protocol accurate and exercises what hasn't been
exercised yet.

## Strategic frame

Phase 6 closed *detection*. Phase 7 closed *instruction*. Phase 8
closed *dispatch* — and discovered that dispatch lives at the
canvas-author seat (top-level main session), not at the architect
sub-agent. The architect keeps its substantive role — planning, rendering
per-finding instructions, weighing verify outcomes at canvas altitude —
but loses the dispatch step. Phase 9 names that collaboration
explicitly: a handoff protocol from architect to canvas-author,
documented in both prompts so it's reproducible rather than accidental.

Phase 9 closes:
- The architect↔canvas-author handoff protocol (Sprint 1)
- The implicit Sprint 6 workflow patterns (Sprint 2)
- Sprint 2 of Phase 8's six unexercised projection kinds + three
  reserved escalation triggers (Sprint 3)

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

### Sprint 1 — Architect↔canvas-author handoff protocol

The substantive sprint. Two seats — architect and canvas-author —
collaborate across the close-drift loop. Sprint 6 demonstrated the
shape worked accidentally; Sprint 1 makes it first-class:

- **Architect's job:** plan + render per-finding instructions + return
  a structured handoff package the canvas-author consumes. Optionally
  verify after the canvas-author reports back.
- **Canvas-author's job:** dispatch each implementing-LLM via the
  main session's `Agent` tool, collect reports, decide whether to
  call verify directly or re-dispatch the architect for canvas-altitude
  verify interpretation.

Two verify flows ship — canvas-author picks based on scope:
- **Light scope (single-finding or familiar module):** canvas-author
  calls `(close-drift-verify)` directly from main session
- **Heavy scope (multi-finding or unfamiliar canvas territory):**
  canvas-author re-dispatches architect with the reports; architect
  calls `(close-drift-verify)` and returns a canvas-altitude
  interpretation + escalation summary

**Task 1 — Handoff package format.** A markdown dispatch package the
architect returns from its planning step. Single self-contained
document the canvas-author reads top-to-bottom. Structure:

```markdown
# close-drift handoff — <scope description>

## Summary
- Scope: <module-coord / check / stable-id>
- Findings in scope: N
- Plan entries: M (excluding K :unhandled)
- Same-file batches: <list of paths each batch lands in>

## Dispatch instructions
[Canvas-author guidance:]
- Dispatch each finding's instruction below as a fresh Agent call
  with subagent_type: general-purpose
- For same-file batches: dispatch serially (one Agent at a time),
  re-rendering each instruction via `(close-drift-plan :stable-id …)`
  before its dispatch to pick up sibling state
- Collect each subagent's report tagged with :attempt 1
- After all reports collected, choose a verify flow (Section "Verify")

## Per-finding instructions

### Finding 1 — <stable-id> (canvas-kind, batch path)
[verbatim rendered instruction]

### Finding 2 — <stable-id> (canvas-kind, batch path)
[verbatim rendered instruction]

...

## Verify
After collecting reports, either:
(a) Call `(close-drift-verify :plan <plan-from-this-handoff> :reports [...])`
    directly from main session. Reads escalations from the structured
    return.
(b) Re-dispatch fukan-architect with the reports + this handoff. The
    architect calls verify and returns a canvas-altitude interpretation.

Recommendation for this scope: <(a) or (b), with one-line reason>.
```

The architect renders this single markdown document; the canvas-author
copies per-finding blocks into Agent prompts; reports come back as
plain text the canvas-author accumulates.

**Task 2 — Architect's render of the handoff package.** New section
in `.claude/agents/fukan-architect.md` Phase D close-drift mode
defining the planning flow:

1. Canvas-author asks architect "plan close-drift for <scope>"
2. Architect calls `(close-drift-plan …)` via Bash
3. Architect inspects the plan: per-finding rendered instructions,
   batches, unhandled
4. Architect composes the handoff markdown package + a
   verify-flow recommendation based on scope size
5. Architect returns the package as its response

Sprint 6's serial-same-file dispatch + re-render pattern gets baked
into the dispatch-instructions section of the handoff (Task 4
formalises the convention generally; here it appears as architect-
emitted guidance for the canvas-author).

**Task 3 — Architect's verify flow.** New section in
`.claude/agents/fukan-architect.md` for verify mode:

1. Canvas-author re-dispatches architect with `:plan <…>` +
   `:reports [<…>]`
2. Architect calls `(close-drift-verify :plan … :reports …)` via Bash
3. Architect reads the structured outcome, weighs escalations at
   canvas altitude (canvas-side hints especially), composes a
   canvas-author-facing summary
4. Architect returns the summary + recommendation (close session,
   dispatch iter-2 on findings X/Y/Z, escalate finding W for
   canvas-side action, etc.)

This is the canvas-altitude interpretation step — architect's value
beyond what verify's raw structured return surfaces.

**Task 4 — Closure controller design doc amendment.**
`doc/plans/2026-05-28-closure-controller-design.md` § "Where the
controller lives" + the dispatch sketches. Add a "two-seat
collaboration" section explaining the empirical Sprint 6 finding +
the new handoff protocol. The two-entry-point split (`close-drift-plan`
/ `close-drift-verify`) stays the right design — it now naturally
supports the architect-plans, canvas-author-dispatches, either-seat-
verifies flow.

**Task 5 — Canvas-authoring system prompt amendment.**
`doc/canvas-authoring-system-prompt.md` § Phase D close-drift mode.
Replace the prior "architect orchestrates" 5-step flow with the new
2-seat protocol. Canvas-author learns: "delegate planning to the
architect, dispatch the implementing-LLMs yourself per the architect's
package, choose verify flow."

**Task 6 — Architect tool grant decision.** The architect's `.md`
front-matter currently declares `Agent` in its tool list. Sprint 6
confirmed the harness blocks nested `Agent` invocation. Decide:

- (a) Remove `Agent` from the declared grant. Honest about what
  works. The architect's prompt no longer mentions dispatch.
- (b) Keep `Agent` in the declared grant. Anticipates a future
  harness change that might expose nested Agent. The architect's
  prompt notes "Agent declared but currently blocked by harness; if
  available, the architect-driven dispatch flow may work — try
  one finding, fall back to handoff if it fails."

Sprint 1's call. Recommendation: (a). Removing matches the documented
flow; (b) creates surface for confusion when the harness behaviour
later changes.

Sprint 1 is doc + prompt work — the substrate stays the same. The
protocol is what the sprint ships.

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

- The architect↔canvas-author handoff protocol is documented in the
  architect's prompt, the canvas-authoring system prompt, and the
  closure-controller-design doc. The architect knows how to render
  the handoff package + verify summary; the canvas-author knows how
  to consume both.
- Same-file-batched re-render pattern lives as canvas-author guidance
  in the handoff package's dispatch-instructions section (Sprint 1)
  AND as documented convention in the canvas-authoring prompt
  (Sprint 2 Task 4).
- Plan-input dependency for `close-drift-verify` is documented +
  surfaced via explicit error.
- Cosmetic prose indentation in the invariant template normalised.
- Six unexercised projection kinds have empirical iter-1 closure-rate
  data from real-Agent dispatch (via the new handoff protocol).
- Two reserved escalation triggers exercised via unit tests; the
  third (`:projection-emits-warning`) explicitly classified
  (deferred or implemented).
- Sprint 4 verification doc lands; decision is outcome 1 or 2 with
  named Phase 10 implications.

## Carried-forward concerns from Phase 8 that Phase 9 addresses

| Concern | Phase 9 sprint |
|---|---|
| Architect↔canvas-author handoff protocol needed | Sprint 1 (replaces "retirement") |
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
