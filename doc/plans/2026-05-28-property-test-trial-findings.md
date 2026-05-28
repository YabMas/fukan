# Phase 8 Sprint 6 — Property-test trial run findings

**Date:** 2026-05-28
**Loop tested:** drift-close × 3 iterations (one per invariant)
**Scope:** `canvas/distributed/cluster.clj`'s three invariants —
`MajorityRequiredForLeadership`, `AtMostOneLeaderPerTerm`,
`TermMonotonicity` — closed via the Sprint 5 property-test projection
substrate through the architect-driven dispatch loop.

## Preamble — the harness-gap pivot

Phase 8 Sprint 1's closure-controller design was explicit that the
SCI sandbox can't invoke the `Agent` tool, and that the architect's
Phase D close-drift mode would drive dispatch via the architect's
own `Agent` tool grant. Sprint 6 was the first trial of that flow.

**That flow doesn't work.** When `fukan-architect` was dispatched as a
subagent from the main session, its `Agent` tool returned
`"No such tool available: Agent. Agent is not available inside
subagents."` — uniform, hard error. The Sprint 1 hypothesis that
purpose-built agents with declared `Agent` tool grants inherit
working nested dispatch is empirically false. The harness blocks
`Agent` invocation at the subagent boundary regardless of agent type
or declared grants.

**Only the top-level main session has a working `Agent` tool.** So
Task 21 ran as a top-level-driven loop: the canvas-author called
`(close-drift-plan)`, rendered the three instructions, dispatched
three implementing-LLM subagents serially (same-file constraint),
ran `bin/fukan reset`, and confirmed closure via `(canvas-drift)`.
The `fukan-architect` role retires from "orchestrator" to "planner +
verifier" — the architect can call `close-drift-plan`/`close-drift-verify`
via Bash but cannot dispatch the implementing-LLM step.

Despite the pivot, the empirical questions Sprint 6 was scoped to
answer all got answered:

- Did the property-test projection substrate work end-to-end? **Yes.**
- Did the implementing-LLM produce a usable defspec from the rendered
  instruction? **Yes, all three did, first attempt.**
- Did drift recognize the test-side closure? **Yes, drift dropped
  from 4 missing-impl findings to 1 (the non-invariant getter that
  was out of scope).**

## Defects surfaced pre-dispatch (closed before Task 21 ran)

The architect's planning step surfaced two defects in the Sprint 5
substrate before any dispatch attempt. Both were fixed mid-Sprint 6:

**Defect 1 — Plan-entry `:expected-code-path` field for invariants.**
The architect noted that `close-drift-plan`'s structured plan entries
had `:expected-code-path nil` for invariants while the rendered
markdown correctly named `test/fukan/distributed/cluster_test.clj`.
Investigation found Sprint 5's projection-kind-branched
`expected-path-for` was already correctly populating both (the field
was `:context.expected-code-path`, not a top-level field — easy to
miss). Added two regression tests in `close_drift_test.clj` that lock
the contract — fail-fast if a future regression points invariant
findings back at `src/`.

**Defect 2 — Layer A invariant template encouraged premature
generator authorship.** The rendered prose read "The implementing-LLM
should replace the placeholder generator with a real generator and
property body" — but Sprint 6's goal was audit-trail skeleton landing,
not actual Raft property authorship. The implementing-LLM would
plausibly spend cycles trying to author a real generator when the
intended action is "land the skeleton verbatim, leave the placeholder
and throw intact." Reworded the template's `prose-envelope` plus the
inline comment to frame the `gen/return ::placeholder` + `throw` body
as the **audit-trail closure marker** — explicitly forbidding
generator authorship in the body.

Both defects fixed in commits `02e7bdf6` (regression tests for
defect 1) and `8183ce8e` (template rewording for defect 2) before
Task 21 dispatches ran.

## Iteration 1 — `MajorityRequiredForLeadership`

- **Target file state:** absent. `test/fukan/distributed/cluster_test.clj`
  didn't exist on disk.
- **Instruction:** the rendered drift-close instruction correctly named
  the missing target file, included the full defspec skeleton, the
  holds-that prose carried as comment + ex-info payload, and the
  audit-trail closure-marker discipline. Neighbor-section said
  "the target file does not yet exist; create it with an appropriate
  `(ns ...)` form."
- **Implementing-LLM dispatch (cold context):** created the test file
  with `ns` form following sibling-convention (mirrored
  `fukan.target.clojure.address-test`'s require set), added the
  `defspec majority-required-for-leadership-property` verbatim from
  the instruction, including the placeholder generator + throw body.
- **Verification:** `clj -M:test -n fukan.distributed.cluster-test`
  ran cleanly; the audit-trail throw fired with `:canvas-id
  "distributed.cluster/MajorityRequiredForLeadership"` matching the
  canvas stable-id. 1 test, 1 error, 0 failures — the error being
  the expected audit-trail closure marker.
- **Dispatch duration:** ~53s wall-clock.
- **Loop quality:** clean. The instruction was self-contained; the
  implementing-LLM made one reasonable judgment beyond the
  instruction (sibling-convention ns form) and otherwise followed the
  template verbatim.

## Iteration 2 — `AtMostOneLeaderPerTerm`

- **Target file state:** present (created by iter-1). Same file.
- **Instruction re-rendered after iter-1 commit.** The
  `Neighbor context` section now correctly surfaced the existing
  `majority-required-for-leadership-property` as a sibling-def
  sample, named the ns + requires from the live file, and read
  "Insertion point: end-of-file." This **same-file-batched
  re-render** pattern is load-bearing — without it, iter-2's
  instruction would have falsely told the implementing-LLM the file
  doesn't exist, risking a duplicate ns form.
- **Implementing-LLM dispatch (cold context):** read the file,
  appended the second `defspec` at end-of-file matching the sibling
  spacing convention (one blank line between defspecs). Preserved
  the placeholder + throw body verbatim.
- **Verification:** `clj -M:test`. 1059 tests, 0 failures, 2 errors —
  both errors the expected audit-trail throws.
- **Dispatch duration:** ~75s wall-clock.
- **Loop quality:** clean. The re-rendered instruction's neighbor
  context successfully prevented the duplicate-ns-form failure mode
  that would have arisen from a stale render.

## Iteration 3 — `TermMonotonicity`

- **Target file state:** present (two existing defspecs).
- **Instruction re-rendered.** Neighbor section listed both existing
  defspecs as sibling-def samples.
- **Implementing-LLM dispatch (cold context):** appended the third
  `defspec` at end-of-file, matching the sibling style verbatim.
- **Verification:** `clj -M:test`. 1060 tests, 0 failures, 3 errors —
  all expected audit-trail throws.
- **Dispatch duration:** ~68s wall-clock.
- **Loop quality:** clean. Indistinguishable from iter-2.

## Closure-rate summary

| Iteration | Invariant | Outcome | Latency |
|---|---|---|---|
| 1 | `MajorityRequiredForLeadership` | closed iter-1 | ~53s |
| 2 | `AtMostOneLeaderPerTerm` | closed iter-1 | ~75s |
| 3 | `TermMonotonicity` | closed iter-1 | ~68s |

**Iter-1 closure rate: 3/3 = 100%.** No iter-2 dispatches needed.
Per-dispatch latency median: ~68s; range 53–75s.

`(canvas-drift :module-coord "distributed.cluster")` after all three
closures: 2 findings remain — `get_self_role` (a getter, out of
Sprint 6 scope) and a shape-drift on the `Node` record (also out of
scope). The three invariants are absent from drift output. Loop
closed end-to-end.

## Loop quality

The closure controller's two-entry-point architecture worked exactly
as Sprint 1 designed:

- **`close-drift-plan` is pure** and idempotent — calling it before
  each dispatch to re-render with the latest on-disk state is the
  load-bearing pattern. The architect-driven loop (had it been
  available) would have called plan once and held the result;
  same-file batching means re-render is essential when sibling
  context changes between dispatches.
- **The drift-close scenario's neighbor-section** correctly surfaced
  the file's evolving state across dispatches — iter-2 and iter-3
  saw their predecessors as siblings, which prevented duplicate-ns
  failure modes.
- **Implementing-LLM cold-context discipline** held: each subagent
  produced the audit-trail skeleton verbatim, made one or two
  reasonable judgments beyond the instruction (ns form for iter-1,
  spacing convention for iter-2 and iter-3), and reported a
  structured outcome.

## Post-dispatch cleanup — the migrate path

Per Sprint 1's invariant-projection design, the `migrate` path called
for deleting the orphan predicate stubs in `src/fukan/distributed/cluster.clj`
after the property-test closures landed. Only one orphan existed
(`majority-required-for-leadership`, from Phase 7.5 Sprint 2's trial);
the other two invariants never had predicate stubs. Deleted in commit
`ac494505` along with the surrounding section header comment, replaced
with a brief note pointing at the test file. Drift state after deletion
is unchanged: the three cluster invariants stay closed; the two
unrelated findings (get_self_role, Node shape-drift) persist as
expected.

## Lint-config hygiene

The implementing-LLMs' defspec output triggered clj-kondo's
`unresolved-symbol` errors (defspec wasn't taught to clj-kondo) and
clojure-lsp's `unused-public-var` info (defspec-registered vars are
discovered by the test runner, not statically called). Both closed
via `.clj-kondo/config.edn` + `.lsp/config.edn` updates in commit
`92603ef3`:

- `:lint-as {clojure.test.check.clojure-test/defspec clojure.core/def}`
  for clj-kondo
- `fukan.distributed.cluster-test` added to both files' namespace
  exemption lists for the unused-* lints (the property bindings, the
  `clojure.test` require, and the defspec-registered vars are all
  intentional per the audit-trail skeleton convention)

## Defects surfaced during the trial — Phase 9 carry-forwards

1. **`fukan-architect`'s `Agent` tool grant is harness-blocked.** The
   architect can plan and verify via Bash; it cannot dispatch. The
   architect's role retires to "planner + verifier" and the
   closure-controller-design doc + `.claude/agents/fukan-architect.md`
   + `doc/canvas-authoring-system-prompt.md` need amending to reflect
   this. Phase 9 substrate-doc work.

2. **Same-file-batched re-render pattern is implicit.** Iter-2 and
   iter-3 only worked because the canvas-author re-rendered the
   instruction before each dispatch (picking up the updated on-disk
   state). The architect-driven loop's Sprint 4 design assumed plan
   was captured once and reused; that pattern would have failed for
   same-file batches. Phase 9 should formalize the re-render
   convention either in the architect's prompt or in a controller-
   level wrapper.

3. **Verify path's plan-input dependency.** `close-drift-verify`
   requires the plan from `close-drift-plan` to classify outcomes.
   If the canvas-author calls verify after dispatch without holding
   the original plan, a fresh `close-drift-plan` won't include
   closed findings — verify can't see them. Sprint 4's design
   anticipated this (the architect holds the plan), but the
   convenience-wrapper `close-drift` and any future
   non-architect-driven flow need to surface the plan-persistence
   requirement clearly. Phase 9 doc work.

4. **Indentation of holds-that prose comment.** The template renders
   the holds-that block with mixed 2-space / 4-space indentation
   when `doc` carries embedded newlines. Cosmetic; the
   implementing-LLM preserved it verbatim and the file remains
   readable. Worth polishing in Phase 9.

5. **Task 23 (second module trial) deferred.** The Phase 8 plan
   suggested a second canvas module with invariants would broaden
   the trial's fidelity. After Sprint 6 confirmed the loop works
   structurally on the canonical case, this widening can wait for
   Phase 9's broader exercise — likely opportunistically as
   canvas-author drives close-drift against other modules.

## Closing assessment

Phase 8's automation loop closes drift end-to-end. The Sprint 5
property-test substrate produces usable instructions; the
implementing-LLM seat with cold context produces clean code; the
drift comparator recognizes the test-side closure. Three iterations,
three closures, no retries needed.

The only fidelity caveat is that `fukan-architect` cannot drive the
dispatch step — that lives in the main session. This is a structural
harness constraint, not a Phase 8 substrate defect; the architect's
documented role gets revised accordingly. The closure controller's
two-entry-point split was the right design even though the architect-
driven-orchestration claim retires — the same split works equally
well for top-level-driven dispatch.

Phase 8 ships. Phase 9 opens with the Phase D doc revisions and the
broader-canvas exercises Task 23 deferred.
