# Phase 8 Verification Report — Closing the dispatch loop

**Date:** 2026-05-28
**Status:** Complete
**Decision:** (2) Works with caveats — Phase 8 ships; concerns carry forward to Phase 9

**Phase scope:** Close the dispatch loop between drift detection (Phase 6) and
instruction generation (Phase 7). Phase 8 ships a closure controller —
`(close-drift-plan)`, `(close-drift-verify)`, and a thin `(close-drift)`
convenience wrapper — plus a second invariant projection that targets test
files rather than `src/`. Together the two moves let a canvas-author scope a
set of drift findings, hand a top-level driver a rendered plan, and verify
closure via a structured report. The substrate Phase 7 + 7.5 produced is the
input to the loop; Phase 8 adds the orchestration around it.

The phase ran six working sprints and one verification sprint. Sprint 2
pivoted mid-flight from a real-Agent dispatch probe to an instruction-quality
survey when the harness denied subagent dispatch. Sprint 6's trial run
confirmed the harness denial at a deeper level — `fukan-architect` cannot
invoke the `Agent` tool from a subagent seat — and the planned
architect-driven orchestration retired in favour of top-level-driven
dispatch. The structural mechanism the design called for (two pure entry
points, dispatch in between) survived the pivot intact.

---

## Strategic frame

Phase 6 closed *detection*. Phase 7 closed *instruction*. Phase 8 closes
*dispatch* — the last manual step between "drift surfaced" and "drift
closed". The canvas-author no longer copy-pastes per-finding instructions
into a subagent prompt; the controller renders a plan, the dispatching seat
fans out implementing-LLM dispatches grouped by target file, and the
controller's verify entry point re-runs drift to classify outcomes with a
structured escalation taxonomy.

Two complementary moves carry the phase. The closure controller is the
orchestration substrate — pure orchestration in SCI-callable Clojure, no
new model primitives. The invariant→property-test projection is the
hardest case that controller closes — it gains a new artifact-kind, a new
projection-kind, a synthetic dispatch key (Option β from Sprint 1's
design), and a test-side awareness path in the drift comparator.

The product surface stays the REPL, `bin/fukan eval`, `(help)`, and
`fukan-architect`. The architect's role evolves mid-phase — from
orchestrator (the original Sprint 1 design) to planner+verifier (the
Sprint 6 finding) — but the user-facing surface doesn't change.

---

## 1. What was attempted vs. built

Six working sprints across ~40 commits between the Phase 8 plan landing
(`trnmxkpv`) and the Sprint 6 findings doc (`smxypoqk`).

### Sprint 1 — Closure controller + invariant-projection designs

| Planned | Delivered | Status |
|---|---|---|
| Task 1: Closure-controller design | `doc/plans/2026-05-28-closure-controller-design.md` (629 lines) | Done |
| Task 2: Invariant property-test projection design | `doc/plans/2026-05-28-invariant-projection-design.md` (834 lines) | Done |

Both paused for user review before downstream sprints began. Task 1
settled the two-entry-point split (`close-drift-plan` + `close-drift-verify`)
under the SCI-can't-invoke-`Agent` constraint and the six escalation
trigger taxonomy. Task 2 settled the migrate path (property-test default,
predicate opt-in via `(projects-to :predicate)`) and the Option β
synthetic dispatch key.

Two design amendments to the Phase 8 plan itself landed alongside —
`e93528f1` (two-entry-point split + Option β + migrate path) and
`66bb3fb6` (Sprint 2 outcome + pivot rationale). Both are recorded on
the master plan, not split into separate docs.

### Sprint 2 — Trial-fidelity probe (pivoted to instruction-quality survey)

| Planned | Delivered | Status |
|---|---|---|
| Task 3: Real-Agent dispatch probe across 6 unexercised projection kinds + cold-write | Instruction-quality survey across the same 6 kinds + cold-write; no end-to-end closure-rate data | Pivoted |
| Task 4: Defect triage | Two blockers fixed (`c714335e` event payload drop, `305a9231` cold-write entry point); four defects deferred to Phase 9 with documented rationale | Done |

Sprint 2's pivot is on the record: the harness denied subagent dispatch
to the dispatched-LLM running the trial. The survey produced actionable
Task 4 fixes but no empirical closure-rate calibration for the controller's
retry/concurrency thresholds. Sprint 3 shipped the MVP with Sprint 1's
proposed thresholds tagged provisional; calibration awaits later trial-runs.

### Sprint 3 — Closure controller MVP

| Planned | Delivered | Status |
|---|---|---|
| Task 5: `(close-drift-plan {…})` agent-api fn | Registered under `:trust` `:severity :info`; SCI-callable | Done (`559e4bba`) |
| Task 6: `(close-drift-verify {…})` agent-api fn | Same layer; per-finding `:closed` / `:failed` / `:no-report` classification | Done (`559e4bba`) |
| Task 7: `fukan-architect` Phase D extension | Phase D close-drift mode prompt in `.claude/agents/fukan-architect.md` + `doc/canvas-authoring-system-prompt.md` | Done (`f196a01e`) |
| Task 8: Convenience wrapper `(close-drift {…})` | Thin SCI helper composing the two entry points; default `:dispatch-fn` returns "manual dispatch required" | Done (`559e4bba`) |
| Task 9: Smoke test on `canvas/distributed/*` | Unit + smoke tests against the live model | Done (`e9839523`) |

The two entry points landed in a single commit (the controller MVP);
the architect-prompt extension landed separately to keep the doc edits
self-contained.

A mid-Sprint workflow fix landed alongside Sprint 3: `(reset)` in
`src/fukan/agent/system.clj` previously reloaded the dynamic-load
loaders (for `canvas.project.clojure`, `canvas.lens.registry`,
`canvas.instruct.registry`) but did **not** rebuild the SCI sandbox
context — newly-added agent-api fns required a daemon restart to
appear. Commit `2093e2c7` adds an explicit `agent.api` reload step plus
a SCI context rebuild so `close-drift-plan` / `close-drift-verify` /
`close-drift` were callable without bouncing the daemon. See §5.

### Sprint 4 — Retry + escalation + canvas-side hints

| Planned | Delivered | Status |
|---|---|---|
| Task 10: Iter-2 instruction rendering | `close-drift-plan` accepts `:retry-of` / `:iter-1-report` / `:iter-1-drift`; renders reconciliation-prose preamble + iter-1 context + original instruction | Done (`9441c8c5`) |
| Task 11: Architect's iter-2 dispatch loop | Phase D prompt extended with the six-trigger escalation surface and the iter-2 loop | Done (`40bf60d1`) |
| Task 12: Escalation classification | Six escalation triggers landed in `close-drift-verify`; three exercised in the smoke test, three reserved | Done (`20edc031`) |
| Task 13: Canvas-side hint heuristics | Two heuristics implemented — stubbed-invariant + shape-drift recency — one (cross-module reference recency) deferred | Done (`20edc031`) |
| Task 14: Observability | Per-attempt timing + per-iter report excerpts surface in the structured report and rendered markdown | Done (`20edc031`) |

The six escalation triggers (`:attempts-exhausted`,
`:no-projection-registered`, `:projection-emits-warning`,
`:canvas-side-hint`, `:scenario-not-found`, `:dispatch-error`) all carry
structured `{:trigger :detail :hint-kind}` maps in the per-finding
report entry. Sprint 6's trial exercised `:attempts-exhausted` (zero
firings since all three closed on iter-1) and the canvas-side-hint
heuristics (none fired, since all three were straightforwardly
substrate-driven closures); `:dispatch-error`, `:scenario-not-found`,
and `:projection-emits-warning` remain reserved-but-untested in the
live model.

### Sprint 5 — Property-test projection substrate

| Planned | Delivered | Status |
|---|---|---|
| Task 15: Layer A `invariant-to-property-test` projection | `src/fukan/canvas/project/clojure/invariant_to_property_test.clj` — `defmethod project [:clojure :canvas/invariant+property-test]` | Done (`9612232f`) |
| Task 16: Canvas-source flag plumbing | `:invariant/projects-to` defaults `:property-test`; canvas-source reads `(projects-to :predicate)` opt-out (no canvas currently uses it) | Done (`2c74fda3`) |
| Task 17: Drift comparator test-side awareness | `expected-path-for` branches on `:projection-kind`; `ns->test-file-path` mirrors `ns->file-path` with `test/` prefix | Done (`9b813d38`) |
| Task 18: Address registry update | `:projection-kind/property-test` address shape (ns `<base>-test`, symbol `<kebab>-property`, file `test/<path>_test.clj`) | Done (`1d43dfbb`) |
| Task 19: Layer B `drift-close` test-side branch | Property-test artifacts route through a test-aware neighbor branch | Done (`6ffa931c`) |
| Task 20: Tests + coverage regression | `test/fukan/canvas/project/clojure/invariant_to_property_test_test.clj`; coverage regression accepts the synthetic dispatch key | Done (`67317140`) |

Six commits land Sprint 5 atomically in a single block — the substrate
was designed top-down and the implementation followed the design's
section order. Sprint 5 also added a new `:projection-kind/property-test`
keyword to the model enum (`6d161b86`) and recognised `defspec` in the
Clojure analyzer (`2c74fda3`).

The migrate path's producer side — letting canvas authors opt **back** to
predicates via `(projects-to :predicate)` — is plumbed through
canvas-source but no current canvas declaration uses it. The opt-in
remains structurally available for any future invariant that lacks a
useful generator; in practice every invariant in the canvas defaults to
property-test. Producer-side validation that the keyword actually flows
through to dispatch is deferred to Phase 9.

### Sprint 6 — Property-test trial run via the controller

| Planned | Delivered | Status |
|---|---|---|
| Task 21: Trial-run on `canvas/distributed/*` | Three invariants closed via property-test through top-level-driven dispatch; 3/3 iter-1 closure | Done (`c425bdde`) |
| Task 22: Migrate path cleanup | Deleted the one orphan predicate stub (`majority-required-for-leadership`); the other two never had stubs | Done (`ac494505`) |
| Task 23: Second canvas module trial | Deferred — Sprint 6 confirmed the loop structurally; broader exercise can wait for organic canvas-author use | Deferred |
| Task 24: Findings doc | `doc/plans/2026-05-28-property-test-trial-findings.md` | Done (`ec97f9d2`) |

Two defect-fix prep commits landed before Task 21 dispatched any
implementing-LLMs: `02e7bdf6` (regression tests locking
`:expected-code-path` for invariants pointing at `test/`) and `8183ce8e`
(template prose rewording around the audit-trail skeleton). Both fell
out of the architect's planning step finding gaps in the Sprint 5
substrate before any dispatch attempted. See §5.

A post-Sprint-6 lint config commit (`92603ef3`) closed clj-kondo +
clojure-lsp noise from the new `defspec` forms in
`fukan.distributed.cluster-test` — clj-kondo learned `defspec` via
`:lint-as`; both tools added the namespace to their respective
unused-* exemption lists.

---

## 2. Closure controller behavior

The controller's two-entry-point architecture worked end-to-end in
Sprint 6's trial. The three invariant dispatches all closed on iter-1:

| Iteration | Invariant | Outcome | Latency |
|---|---|---|---|
| 1 | `MajorityRequiredForLeadership` | closed iter-1 | ~53s |
| 2 | `AtMostOneLeaderPerTerm` | closed iter-1 | ~75s |
| 3 | `TermMonotonicity` | closed iter-1 | ~68s |

Iter-1 closure rate: 3/3 = 100%. No iter-2 dispatches needed in the
trial. Per-dispatch median latency ~68s; range 53–75s. The iter-2
machinery (retry-context rendering + Phase D iter-2 prompt) is built
and unit-tested but not exercised against live drift in this phase.

`(canvas-drift :module-coord "distributed.cluster")` after the three
closures: 2 remaining findings (`get_self_role` getter and a
`Node` shape-drift, both out of Sprint 6 scope). The three invariants
disappeared from drift output as the test-side artifacts landed —
verifying the comparator's `expected-path-for` branch routes correctly.

### Escalation triggers

Six triggers are implemented (`src/fukan/agent/api.clj`, around line 1337
`structured-escalation`):

| Trigger | Implemented? | Exercised in Sprint 6? |
|---|---|---|
| `:attempts-exhausted` | Yes | No (all closed iter-1) |
| `:no-projection-registered` | Yes | No |
| `:projection-emits-warning` | Yes | No (no Layer A warnings surface today) |
| `:canvas-side-hint` | Yes | No (hint heuristics didn't fire) |
| `:scenario-not-found` | Yes | No |
| `:dispatch-error` | Yes | No |

Three are structurally tested via unit tests; three remain reserved
in code with no live-model exercise. None blocked Sprint 6's closure,
but the escalation surface stays load-bearing for future phases where
dispatches fail.

### Canvas-side hint heuristics

Two of the three heuristics from Sprint 1's design landed
(`canvas-side-hint-stubbed-invariant` + `canvas-side-hint-shape-drift`,
lines 1282–1334 of `agent/api.clj`). The third — cross-module reference
recency, requiring `git log` introspection — is deferred. Neither
implemented heuristic fired during Sprint 6's trial, which is expected
behavior: the three invariants were straightforwardly substrate-driven
closures with no retry signal and no recent shape-drift activity.

### Dispatch model — the architect retirement

The closure-controller-design doc (Sprint 1, Task 1) specified that
`fukan-architect`'s Phase D prompt would drive the dispatch loop —
the architect calls `close-drift-plan`, dispatches per-entry via its
own `Agent` tool, calls `close-drift-verify`. Sprint 6 empirically
falsified this: **`Agent` is not available inside subagents, including
purpose-built agents with declared `Agent` tool grants.** The error
is uniform: `"No such tool available: Agent. Agent is not available
inside subagents."`

Only the top-level main session has a working `Agent` tool. Sprint 6
pivoted to top-level-driven dispatch: the canvas-author calls
`close-drift-plan`, the top-level agent fans out implementing-LLM
dispatches via its native `Agent` tool, the canvas-author calls
`close-drift-verify`. The architect's role retires from "orchestrator"
to "planner + verifier" — the architect can call the two pure entry
points via Bash but cannot do the dispatch step itself.

This is a structural finding, not a Phase 8 defect: the two-entry-point
split survives intact (it was designed under the assumption that the
SCI sandbox couldn't dispatch; it works equally well when the
dispatching seat shifts from architect to top-level). What needs
updating is the doc surface: see §4 and §6.

---

## 3. Property-test projection quality

Ten projections are now registered under `[:clojure …]` — confirmed via
daemon smoke test:

```
$ bin/fukan eval '(count (canvas-projections))' → 10
$ bin/fukan eval '(canvas-projections)' →
  [{:lens-id :clojure :dispatch-key :Type/atomic}
   {:lens-id :clojure :dispatch-key :Type/record}
   {:lens-id :clojure :dispatch-key :canvas/checker}
   {:lens-id :clojure :dispatch-key :canvas/event}
   {:lens-id :clojure :dispatch-key :canvas/getter}
   {:lens-id :clojure :dispatch-key :canvas/handler}
   {:lens-id :clojure :dispatch-key :canvas/invariant}
   {:lens-id :clojure :dispatch-key :canvas/invariant+property-test}
   {:lens-id :clojure :dispatch-key :canvas/rule}
   {:lens-id :clojure :dispatch-key :fukan.canvas.monolith/exposed-call}]
```

The synthetic `:canvas/invariant+property-test` dispatch key
(Option β) joins the nine Phase 7+7.5 registrations without
changing any of them. `dispatch-key-of` returns the synthetic key
when the affordance's `:canvas-projection-kind` is `:property-test`
(the default for canvas invariants); when a canvas declaration opts
back to `(projects-to :predicate)`, dispatch falls through to the
existing `:canvas/invariant` registration.

### Address shape

`src/fukan/target/clojure/address.clj`'s `canonical` extension
produces `{:ns "<base>-test" :name "<kebab>-property"}` for the new
projection-kind, with files at `test/<path>_test.clj`. The three
trial closures verify the convention end-to-end:

- ns: `fukan.distributed.cluster-test`
- file: `test/fukan/distributed/cluster_test.clj`
- symbols: `majority-required-for-leadership-property`,
  `at-most-one-leader-per-term-property`, `term-monotonicity-property`

### Template idiom

The `invariant-to-property-test` projection emits a `clojure.test.check`
`defspec` with a placeholder generator (`gen/return ::placeholder`)
and an audit-trail `throw` body carrying `:canvas-id`, `:invariant-name`,
`:holds-that`, and `:iteration-count` in the ex-info data. The
template prose was reworded mid-Sprint-6 (commit `8183ce8e`) to frame
the placeholder + throw as an **audit-trail closure marker** — the
implementing-LLM is explicitly forbidden from authoring a generator or
filling in the property body at this stage. The skeleton lands as the
substrate's "this invariant is canvas-declared, the file is correctly
positioned, the property semantics await a future iteration" signal.

All three Sprint 6 implementing-LLMs produced legal Clojure verbatim
from the template, made one reasonable judgment beyond the instruction
(the iter-1 LLM matched a sibling test ns convention for the require
block), and reported structured outcomes.

### Drift comparator's test-side awareness

`src/fukan/canvas/inspect/drift.clj` gained an `expected-path-for`
function that branches on `:projection-kind`:

```clojure
:projection-kind/property-test (ns->test-file-path ns-str)
;; else: ns->file-path  (the existing src/ prefix)
```

The comparator recognized the test-side artifacts after they landed
on disk: each `(canvas-drift)` re-run between iterations correctly
showed the closed invariants dropping out of the missing-impl set.

### The migrate path

Only one orphan predicate stub existed before Sprint 6 — Phase 7.5
Sprint 2's trial-commit of `majority-required-for-leadership` in
`src/fukan/distributed/cluster.clj`. The other two invariants
(`TermMonotonicity`, `AtMostOneLeaderPerTerm`) never had predicate
stubs. After Sprint 6's three closures landed, the orphan was deleted
(`ac494505`); drift state after deletion is unchanged.

The producer-side opt-out (`(projects-to :predicate)`) is plumbed
through canvas-source and dispatch but no canvas declaration uses it
today. Phase 9 picks this up if a future invariant proves
generator-unfriendly enough to want the predicate path.

---

## 4. Trial-fidelity gap — the architect-role retirement

**This is the load-bearing structural finding of Phase 8.**

Sprint 1's closure-controller design hypothesised that `fukan-architect`
could drive the dispatch loop via its declared `Agent` tool grant.
Sprint 2 ran into the harness denying `Agent` dispatch to its trial-
running subagent — the second consecutive Phase to flag the same gap.
Sprint 6 went one level deeper: it dispatched a `fukan-architect`
subagent purpose-built with the `Agent` tool declared in its tool grants
and observed the **exact same error message**: `"No such tool available:
Agent. Agent is not available inside subagents."`

The hypothesis that purpose-built agents inherit working nested
dispatch is empirically false. The harness blocks `Agent` invocation
at the subagent boundary uniformly, regardless of declared tool
grants. Only the top-level main session has a working `Agent` tool.

### What this means structurally

The closure-controller-design doc's recommended resolution — Sketch B,
where the architect calls `close-drift-plan`, dispatches per-entry,
calls `close-drift-verify` — does not work as written.

What does work: top-level-driven dispatch. The canvas-author (or the
top-level agent in the user's main session) calls `close-drift-plan`,
fans out implementing-LLM dispatches via the top-level `Agent` tool,
calls `close-drift-verify`. The architect can still be invited as a
planner/verifier — it can call both entry points via Bash and reason
about the structured outputs — but it cannot do the dispatch step.

### What survives intact

The two-entry-point split is the right design *regardless* of which
seat drives dispatch. The split was designed under the
SCI-can't-call-`Agent` constraint, which holds for any seat that
can't invoke `Agent` — including, it turns out, every seat except
the top-level. The architect-driven hypothesis was wrong; the
two-entry-point split was right.

The closure controller's MVP, retry context, escalation taxonomy,
and observability features all work identically under top-level-driven
dispatch as they would have under architect-driven dispatch. Sprint 6's
3/3 iter-1 closure verifies the loop end-to-end in the alternative
seat configuration.

### What needs doc revision (Phase 9 cleanup)

Three doc surfaces still describe architect-driven orchestration:

1. **`doc/plans/2026-05-28-closure-controller-design.md`** (§ Interaction
   with `fukan-architect`) — sketches A/B/C and recommends Sketch B with
   the architect as the dispatching seat.
2. **`.claude/agents/fukan-architect.md`** (§ Phase D close-drift mode)
   — the prompt teaches the architect to invoke `Agent` per plan entry.
3. **`doc/canvas-authoring-system-prompt.md`** (§ Phase D — Instruct +
   Dispatch) — same.

None of these doc surfaces match the empirical reality of Sprint 6's
trial run. They need amending to teach the architect that its role is
planner + verifier; the top-level seat is the dispatching seat. The
amendment is doc-only — the substrate code path doesn't change.

The Sprint 6 findings doc records this as a Phase 9 doc-revision task.
This verification doc records the same: it's a real carry-forward,
classified as substrate-doc-work, not substrate-code-work.

---

## 5. Mid-phase fixes

Three workflow improvements landed mid-Phase 8 that aren't sprint-scope
but are substrate-improving.

### SCI reload gap (`2093e2c7`)

Post-Sprint-3, mid-Sprint-4 development surfaced a stale-context bug:
adding `close-drift-plan` to `src/fukan/agent/api.clj` and running
`bin/fukan reset` did **not** make the new fn appear in the SCI
sandbox — the daemon's SCI context was built once at startup, and
`(reset)` reloaded the canvas + lens loaders but did not rebuild the
SCI context.

Fix: `src/fukan/agent/system.clj`'s `reset` now explicitly reloads
`fukan.agent.api` and rebuilds the SCI context as part of the reset
cycle. Verified by inspection: new agent-api fns appear without
daemon restart.

A `^:export` annotation was added to `reset-ctx!` (commit `1eb26cf5`)
so clojure-lsp's `unused-public-var` info exempts it via the existing
`:exclude-when-meta` rule — keeping the LSP exemption surface
consistent with clj-kondo's.

The same `defmulti` caveat from the Phase 7+7.5 verification still
applies: `(reset)` adds new `defmethod` registrations but cannot
unregister deleted ones until JVM restart. Documented in CLAUDE.md.

### Sprint 6 pre-dispatch substrate fixes (`02e7bdf6`, `8183ce8e`)

The architect's planning step before Task 21's dispatches surfaced two
defects in the Sprint 5 substrate:

1. **`:expected-code-path` regression coverage.** The plan entries
   carried the correct test-side path in `:context.expected-code-path`
   (Sprint 5's branched `expected-path-for` worked), but no test locked
   the contract. Commit `02e7bdf6` adds two regression tests in
   `close_drift_test.clj` that fail if a future regression points
   invariant findings back at `src/`.

2. **Layer A template prose encouraged premature generator authorship.**
   The pre-fix prose read "The implementing-LLM should replace the
   placeholder generator with a real generator and property body." The
   implementing-LLM, reading this with cold context, would plausibly
   try to author a real generator on iter-1 — when the intended action
   was "land the skeleton verbatim, leave the audit-trail closure
   marker intact." Commit `8183ce8e` reworded the template's
   prose-envelope and the inline comment to explicitly frame the
   placeholder + throw as the **audit-trail closure marker** and to
   explicitly forbid generator authorship in the body.

Both fixes landed before any dispatch attempted in Task 21, and all
three Sprint 6 implementing-LLMs honoured the new prose verbatim. The
audit-trail throws appear in test runs as expected errors (3 of them
in the current test count — see §7), not as failures.

### Lint config for `defspec` (`92603ef3`)

The implementing-LLMs' `defspec` output triggered two lint signals:
clj-kondo's `unresolved-symbol` (defspec wasn't taught to clj-kondo)
and clojure-lsp's `unused-public-var` (defspec-registered vars are
test-runner-discovered, not statically called). Both closed:

- `.clj-kondo/config.edn` gains `:lint-as
  {clojure.test.check.clojure-test/defspec clojure.core/def}`
- `fukan.distributed.cluster-test` joins the namespace exemption lists
  in both `.clj-kondo/config.edn` and `.lsp/config.edn` for the
  unused-* lints (the property bindings, the `clojure.test` require,
  and the defspec-registered vars are all intentional per the
  audit-trail skeleton convention)

---

## 6. Defects surfaced but NOT closed

Compiled from the Sprint 2 findings doc, Sprint 6 findings doc, and
each sprint's surprises section. None block Phase 8 ship; all map to
Phase 9 opener candidates.

1. **Architect-role retirement requires doc revision across three
   surfaces.** The closure-controller-design doc, the
   `fukan-architect` agent doc, and the canvas-authoring system prompt
   all describe architect-driven orchestration that the harness
   doesn't permit. Phase 9 Sprint 1 doc-work candidate.

2. **Same-file-batched re-render pattern is implicit.** Sprint 6's
   iter-2 and iter-3 only worked because the canvas-author re-rendered
   `close-drift-plan` between dispatches (picking up the updated
   on-disk neighbor context). The architect-driven loop would have
   needed the same pattern, but the original Sprint 4 design assumed
   plan was captured once and reused. Phase 9 should formalize the
   re-render convention — either in the dispatching seat's prompt or
   via a controller-level wrapper that handles the re-render
   automatically.

3. **Verify path's plan-input dependency.** `close-drift-verify`
   requires the plan from `close-drift-plan` to classify outcomes. If
   the dispatching seat calls verify after dispatch without holding
   the original plan, a fresh `close-drift-plan` call won't include
   findings already closed — verify can't classify them as `:closed`.
   Sprint 4's architect-driven design anticipated this (the architect
   would hold the plan in working memory); the top-level-driven
   workflow now in effect has the same need, and the doc surface
   should call it out explicitly.

4. **Holds-that prose indentation is mixed.** The Sprint 6 trial-run
   output shows the audit-trail comments with mixed 2-space / 4-space
   indentation when `doc` carries embedded newlines (e.g. lines 10–11
   of `test/fukan/distributed/cluster_test.clj`). The implementing-LLM
   preserved the template's indentation verbatim — the cosmetic issue
   is in the Layer A template, not the dispatch step. Cosmetic only;
   the file is readable and the assertions still throw as intended.

5. **`(projects-to :predicate)` producer-side deferred to Phase 9.**
   Canvas-source recognises the keyword and dispatch routes correctly
   when an invariant carries it, but no live canvas declaration uses
   the opt-out. The producer-side validation pipeline — what the
   canvas-author sees when they add `(projects-to :predicate)` to a
   declaration that genuinely doesn't generate well — is structurally
   plumbed but never exercised in trial.

6. **Task 23 (second canvas module trial) deferred.** Sprint 6
   confirmed the loop closes drift end-to-end on `canvas/distributed/*`;
   a second module's invariants would broaden the trial's fidelity but
   wasn't necessary to verify the structural mechanism. Phase 9
   picks this up opportunistically as canvas-authors close drift in
   other modules.

7. **Sprint 2's six unexercised projection kinds — instruction quality
   surveyed but never end-to-end closed via real-Agent dispatch.** The
   instruction-quality survey verified the projections render legal
   Clojure for `event-to-schema` (after the payload-drop fix),
   `rule-to-predicate`, `checker-to-defn`, `handler-to-defn`,
   `value-to-def`, and `function-to-defn`'s predicate case. None went
   through a full real-Agent dispatch cycle. Sprint 6's trial closed
   only the property-test path; the other six remain trial-untested
   end-to-end. Phase 9 can expand the trial surface as canvas-author
   use surfaces real drift in those projection kinds.

8. **`:projection-emits-warning` escalation trigger reserved but never
   fired.** No Layer A projection currently emits warnings to its
   `:context`; the escalation surface is built but unexercised. If
   Phase 9 fixes the handler-payload-shape Layer A limitation (which
   was the original motivating use case), the warning surface will
   start firing.

9. **Canvas-side hint heuristic (c) — cross-module reference recency —
   deferred.** Two of Sprint 1's three canvas-side hint heuristics
   landed (stubbed-invariant and shape-drift); the third requires
   `git log` introspection over the canvas tree, which Sprint 4
   deferred to keep the heuristic surface MVP. Phase 9 if the two
   landed heuristics prove undermatched in practice.

10. **Same defmulti-defonce caveat for fn-remove via `(reset)`.**
    `(reset)` correctly registers new `defmethod` projections but
    cannot unregister a deleted one until the daemon restarts.
    Documented in CLAUDE.md; no functional impact during Phase 8;
    flagged here as a known limit for the future-canvas-deletion case.

---

## 7. Decision

**Outcome (2): Phase 8 ships; concerns carry forward to Phase 9.**

The Phase 8 plan named three outcomes:

1. Phase 8 ships as-is; Phase 9 starts cleanly.
2. Phase 8 ships; concerns carry forward to Phase 9.
3. Phase 8 doesn't ship; specific gaps need closure first.

The closure loop empirically works. Sprint 6's three-of-three iter-1
closure on `canvas/distributed/*` invariants demonstrates the substrate
end-to-end: `close-drift-plan` renders correct plans, the dispatching
seat fans out implementing-LLMs that produce legal Clojure verbatim
from the property-test template, `close-drift-verify` recognises the
test-side closures and produces a structured report. Ten projections
register correctly; two scenarios route correctly; the synthetic
dispatch key works.

The architect-role retirement is the load-bearing structural finding
that pushes Phase 8 out of outcome 1. The design hypothesis that
`fukan-architect` would drive the dispatch loop turned out to contradict
the harness's actual `Agent` tool semantics. The structural mechanism
the closure controller relies on (the two-entry-point split, the pure
plan/verify entry points, the six escalation triggers, the canvas-side
hint heuristics) all survive intact — but three doc surfaces still
describe the wrong orchestrator seat, and that needs Phase 9 cleanup.

The other carry-forwards are smaller — three exercised escalation
triggers vs three reserved, the same-file re-render pattern's implicit
status, the plan-input dependency in verify, the cosmetic
holds-that indentation, the producer-side `(projects-to :predicate)`
not in use, the second-module trial deferral. None block Phase 8's ship;
all map to specific Phase 9 opener candidates.

Outcome 1 would be honest if the architect-driven orchestration had
worked as designed. It didn't; the doc surfaces need amending; that's
outcome 2.

---

## 8. Phase 9 implications

The trajectory has been consistent across Phases 6–8: canvas-author
writes design at canvas altitude; fukan tells the implementing-LLM
exactly what code to write; the canvas becomes the load-bearing
intersection between LLM and human. The Phase 8 close-drift loop
operationalises that trajectory — the canvas-author no longer drafts
per-finding instructions, the substrate does. Phase 9 builds on the
same trajectory: extending where the canvas-author seat reaches, not
adding new surfaces for the LLM.

The standing constraint holds: no UI work (indefinitely deferred).

Phase 9 candidate framings — enumerated, not picked:

a. **Doc revisions + harness-aware loop polish.** Close the three
   architect-role-retirement doc surfaces, formalise the same-file
   re-render pattern, document the plan-input dependency, fix the
   cosmetic prose indentation. The substrate stays the same; the
   surface around it gets accurate. Likely a small, fast sprint.

b. **Bidirectional drift — `:canvas-side/*` scenarios.** The Phase 8
   canvas-side hint heuristics tell the canvas-author "this drift
   might be canvas-side"; bidirectional scenarios would let the
   close-drift loop *act* on that signal — propose canvas edits, not
   just code edits. Substantial new substrate work. The hints
   accumulated this phase make the data ready; the scenarios make the
   action ready.

c. **Cross-module + multi-language lens trial.** The Clojure lens is
   the only lens shipped; the property-test trial closed within
   `distributed.cluster` and didn't exercise cross-module references.
   A second project-lens (Python? TypeScript?) and a cross-module
   close-drift trial would validate the substrate's generality. The
   substrate is ready for this; what's needed is the trial.

d. **Richer canvas-author dialogue surface.** The closure controller
   ships a `:rendered` markdown summary; the dispatching seat reads
   it. The reverse direction — letting the canvas-author talk back at
   the dispatching seat from inside the loop, querying iter-1 reports,
   asking "why did this finding escalate?" — is unbuilt. Phase 9
   could close this if the substrate-driven loop starts feeling
   one-directional in real use.

Each of the four is independently shippable and complementary. The
choice depends on which axis Phase 9 wants to push first.

---

## Appendix: Phase 8 artifact inventory

| Artifact | Phase 8 sprint | Description |
|---|---|---|
| `doc/plans/2026-05-28-canvas-substrate-phase-8.md` | Plan | The Phase 8 plan + amendments |
| `doc/plans/2026-05-28-closure-controller-design.md` | S1 T1 | Closure controller design |
| `doc/plans/2026-05-28-invariant-projection-design.md` | S1 T2 | Property-test projection design |
| `doc/plans/2026-05-28-trial-run-real-agent-findings.md` | S2 | Instruction-quality survey + Task 4 defect classification |
| `doc/plans/2026-05-28-property-test-trial-findings.md` | S6 | Property-test trial findings + harness pivot |
| `doc/plans/2026-05-28-phase-8-verification.md` | S7 | This document |
| `src/fukan/agent/api.clj` | S3 + S4 | `close-drift-plan`, `close-drift-verify`, `close-drift` wrapper + escalation + hints |
| `src/fukan/agent/system.clj` | mid-S3 | SCI context rebuild on `(reset)` |
| `src/fukan/canvas/project/clojure/invariant_to_property_test.clj` | S5 | Layer A property-test projection |
| `src/fukan/canvas/project/core.clj` | S5 | Option β synthetic dispatch key |
| `src/fukan/canvas/inspect/drift.clj` | S5 | Test-side `expected-path-for` |
| `src/fukan/canvas/instruct/drift_close.clj` | S5 | Property-test-aware neighbor branch |
| `src/fukan/canvas/projection/canvas_source.clj` | S5 | `:invariant/projects-to` canvas-source plumbing |
| `src/fukan/target/clojure/address.clj` | S5 | `:projection-kind/property-test` address shape |
| `src/fukan/target/clojure/source.clj` | S5 | `defspec` recognition + `:code/property-test` artifacts |
| `src/fukan/model/*` | S5 | `:projection-kind/property-test` + `:code/property-test` enums |
| `test/fukan/distributed/cluster_test.clj` | S6 | Three property-test closures + audit-trail throws |
| `test/fukan/canvas/project/clojure/invariant_to_property_test_test.clj` | S5 | Projection unit tests |
| `test/fukan/canvas/project/coverage_test.clj` | S5 | Coverage regression accepts synthetic key |
| `test/fukan/agent/close_drift_test.clj` | S3 + S6 | Controller unit tests + Sprint 6 prep regression tests |
| `.claude/agents/fukan-architect.md` | S3 + S4 | Phase D close-drift mode prompt (pre-retirement) |
| `doc/canvas-authoring-system-prompt.md` | S3 + S4 | Phase D close-drift discipline (pre-retirement) |
| `.clj-kondo/config.edn` + `.lsp/config.edn` | post-S6 | `defspec` lint config + test-ns exemptions |

**Phase 8 commit count:** ~40 commits — 1 Phase 8 plan + 2 plan amendments
+ 2 Sprint 1 design docs + Sprint 2 trial doc + Task 4 defect-fix commits
+ Sprint 3 controller MVP + Sprint 3 architect Phase D + Sprint 3
convenience wrapper + Sprint 3 smoke tests + SCI reload fix + Sprint 4
iter-2 retry-context + Sprint 4 escalation + hints + observability +
Sprint 4 architect Phase D update + Sprint 5 substrate (6 commits in
one block) + Sprint 6 trial pre-dispatch substrate fixes + Sprint 6
three closures + Sprint 6 migrate-path cleanup + Sprint 6 lint config
+ Sprint 6 findings doc + this verification doc.

**Substrate state at Phase 8 close.** Layer A: 10 Clojure-lens
projections (the 9 from Phase 7+7.5 plus the synthetic-dispatch
`:canvas/invariant+property-test`). Layer B: 2 scenarios, both
kind-polymorphic per Phase 7.5. Agent API: `(close-drift-plan)`,
`(close-drift-verify)`, `(close-drift)` registered under `:trust`
with `:severity :info`. `fukan-architect`: Phase D close-drift mode
shipped, role retiring to planner+verifier (doc revision in Phase 9).
Workflow: `(reset)` now rebuilds the SCI context so new agent-api fns
appear without daemon restart. Closure loop: 3/3 iter-1 closure
verified on `canvas/distributed/*` invariants via top-level-driven
dispatch; median ~68s per dispatch.

**Test state.** 1060 tests, 2764 assertions, 0 failures, 3 errors —
the three errors are the audit-trail throws in Sprint 6's
property-test closures (`:not yet implemented` ex-info from each
`defspec`). They are expected closure markers, not test failures.

**Lint state.** `clj-kondo --lint src test`: 7 errors, 20 warnings.
The 7 errors are intentional test fixtures
(`test/fukan/fixtures/agent/agent-views-*`) — they test error-handling
paths. The 20 warnings are pre-existing noise (mostly `dup/a.clj`
fixture redefinitions and one `cluster-test.clj` line 18 reflective
match the audit-trail prose triggers). No new lint findings introduced
by Phase 8.
