# Phase 8 Sprint 1 — Closure controller design

**Date:** 2026-05-28
**Status:** Draft for user review (pause point before Sprint 2 trial-fidelity probe)
**Companion doc:** `doc/plans/2026-05-28-invariant-projection-design.md` (Sprint 1 Task 2)
**Parent plan:** `doc/plans/2026-05-28-canvas-substrate-phase-8.md`

---

## Strategic frame

Phase 7 made a drift finding *individually* actionable. The canvas-author
reads `(canvas-drift)`, picks one offender, calls `(instruct …)`, reviews
the rendered markdown, dispatches an implementing-LLM by hand via the
`Agent` tool, re-runs drift to verify, and on failure constructs a retry
brief by hand. Every step from "scope chosen" to "drift cleared" is a
distinct manual turn.

This phase closes the *dispatch loop* — not the substrate underneath it.
Phase 6 closed *detection*. Phase 7 closed *instruction*. Phase 8 closes
*dispatch* — the last manual step between "drift surfaced" and "drift
closed". The closure controller is the substrate that runs the
review→dispatch→verify→retry sequence programmatically: scope in,
structured-report out. The canvas-author seat stays human at two
moments — scope selection up front, report review at the end. Everything
between is mechanical.

What hand-dispatch already does *fine* — pick the right scenario per
drift kind (Layer B's `defmulti` does it), render a clean instruction
(Layer A + B already do it), verify via drift (Sprint 2's `:module-coord`
filter already does it). The controller adds nothing per-finding. What
hand-dispatch does *badly*:

- **Batching.** A 6-finding module is six review→dispatch→verify cycles
  the canvas-author tracks manually. Cross-finding state (this finding
  failed; should I retry the whole module or just this one?) lives in
  human working memory.
- **Same-file conflicts.** Two findings landing in the same `src/` file
  must be dispatched sequentially or the second clobbers the first. The
  canvas-author has to notice and order by hand.
- **Retry context.** Phase 7's retry shape — "iteration-1 instruction +
  iteration-1 report + post-iter-1 drift + reconciliation paragraph" —
  is hand-built per retry. Mechanical to produce, tedious to forget.
- **Escalation surfacing.** A persistent failure is invisible until the
  canvas-author manually re-runs drift and notices the finding survived.
  Mixed in with "things I haven't tried yet."

The bound is tight: this is the drift-closure dispatch loop, NOT a
general task runner. The controller doesn't pick scenarios (Layer B
already dispatches on `:check`), doesn't decide *which* drift kind is
worth closing (the canvas-author picked the scope), doesn't edit canvas
(only dispatches code-side closures), doesn't mediate cross-finding
conflicts where iter-1's edit broke iter-2's neighbor context (Phase 9).
It is one specific mechanism: take a scope, run the per-finding sequence
N times in parallel-where-safe, return a decision-ready report.

---

## Where the controller lives

Three candidates, weighed:

**A. Agent-api fn `(close-drift {…})`** — a new `:trust`-layer fn in
`src/fukan/agent/api.clj`, callable from `bin/fukan eval` and from any
agent with the SCI sandbox surface. Pure Clojure orchestration on top of
the existing `(canvas-drift)` + `(spec)` + `(instruct)` surface. No new
substrate primitives.

**B. Architect-agent prompt extension.** Phase D of `fukan-architect`
already does the per-finding loop by hand. Teach the architect to loop
in-prompt: read drift, render instructions, dispatch via `Agent`,
re-verify. No new code surface; pure prompt engineering.

**C. CLI subcommand `bin/fukan close-drift <scope>`.** A separate
process; orchestrates the daemon's evals from outside. Lives alongside
`bin/fukan eval`, `bin/fukan status`.

**Recommendation: A — agent-api fn.** Three reasons converge.

The first is *idiom symmetry*. `(canvas-drift)`, `(spec)`, `(instruct)`,
`(canvas-projections)`, `(canvas-scenarios)` all live as `:trust`-layer
fns inside `fukan.agent.api`. `(close-drift {…})` extends that same
surface — same SCI sandbox, same `:agent/layer`/`:severity`/`:agent/doc`
metadata convention, same return-shape discipline (structured-map with
embedded `:rendered` for human-readable surface). The architect agent
already calls these via Bash; `(close-drift {…})` slots in without
changing the agent's tool-grant or call patterns.

The second is *testability*. A pure-Clojure orchestration fn is
unit-testable with `with-redefs` stubs for the dispatch step; a
prompt-only extension is verifiable only end-to-end through real subagent
runs. Phase 8 needs both, but the controller's *control flow* (scope
filter, file-batching, retry shape, escalation triggers) should be
testable without burning real `Agent` dispatches.

The third is *separability*. Option B couples the loop's control logic
to the architect agent's prompt — every refinement to retry shape or
escalation criteria becomes a prompt edit. Option C separates control
logic into a process that has to re-read the model out-of-band — daemon
roundtrips replace fn calls. Option A keeps the control logic in one
namespace, callable from inside or outside, evolvable as Clojure code.

The critical sub-question Option A raises: **can a SCI sandbox fn call
the `Agent` tool?** Sketched answer: *no, not directly* — the `Agent`
tool is granted to the architect agent's tool surface; SCI evals inside
`bin/fukan eval` run in-daemon and don't have access to harness-level
tool grants. The architectural consequence is that **`(close-drift {…})`
returns a dispatch plan rather than executing dispatches itself**, OR
**the architect agent dispatches and the controller is the orchestrating
loop the architect drives in its Phase D prompt**.

The cleaner split: the controller does *everything except the dispatch
itself*. It accepts a callback (`:dispatch-fn`) defaulting to a no-op
stub for tests; the architect's Phase D prompt is taught to pass an
actual `Agent`-tool-dispatch lambda. From inside the architect's
context, the loop looks like:

```clojure
(close-drift {:module-coord "distributed.cluster"
              :dispatch-fn  <invoke-Agent-tool>})
```

The architect's prompt teaches it that for each per-finding dispatch
the controller requests, it invokes `Agent` with the rendered
instruction + a small targeted context bundle and returns the subagent's
final report. Everything else — scope filter, file-batching, retry
context construction, drift re-verification, escalation classification,
report rendering — happens inside `(close-drift)`.

This split keeps the SCI sandbox honest (no escape hatches), keeps the
control logic testable (pass a stub dispatch-fn), and keeps the
canvas-author seat in the loop at exactly the two human moments
(scope selection, report review). It also leaves a graceful upgrade
path: if a later phase grants the daemon real dispatch capability
(e.g. a sub-process spawn into a `claude` CLI), the dispatch-fn default
gets a non-stub implementation and nothing else changes.

---

## Scope shape

Mirror `(canvas-drift)`'s filter surface; add a small batch of controller-
specific knobs.

```clojure
(close-drift
 {:module-coord  <prefix-string>      ; optional; default nil = all modules
  :check         <drift-kind-keyword> ; optional; default nil = all kinds
  :stable-id     <single-id-string>   ; optional; single-finding scope
  :limit         <int>                ; optional; default 25 — cap per-run dispatch count
  :dry-run       <bool>               ; optional; default false — render+verify-plan, no dispatch
  :max-attempts  <int>                ; optional; default 2 — per-finding retry cap
  :dispatch-fn   <fn>                 ; optional; injected by architect's Phase D
  :registry      <map>})              ; optional; lens/scenario overrides, default fukan-on-fukan
```

**Behaviour with no filters.** Without `:module-coord`, `:check`, or
`:stable-id`, the controller computes the *full canvas-drift surface*
and applies `:limit` (default 25). The conservative default — refuse to
dispatch without an explicit filter — feels paternalistic but reads
poorly to canvas-authors who have eyeballed `(canvas-drift)` and want a
one-shot full-canvas closure. The middle ground: no-filter is allowed
but capped at 25 findings per run, with the report flagging
`{:truncated? true :remaining N}` when more findings exist than the
limit covers. The canvas-author runs `(close-drift)` again to continue.

**`:limit` choice.** 25 is a guess, calibrated against the Phase 7
trial-run's "30 surviving drift findings inside `distributed.*`". A
typical canvas module is 5–15 findings; a full-canvas drift surface is
likely 20–60 today. 25 is large enough to cover a typical module in one
run and small enough to keep wall-clock per-run bounded (Sprint 2 will
give us latency data). Sprint 2 amends.

**`:dry-run`.** Returns the would-dispatch list with rendered
instructions, but skips dispatch + verification. Useful for the
canvas-author to preview the close-drift plan before committing.
Equivalent to "call `(instruct …)` per finding and stop." The
architect's Phase D prompt should reach for `:dry-run true` when the
canvas-author asks "what would close-drift do on this module?".

**Filter composition.** Filters AND together. `:module-coord
"distributed.cluster" :check :inspect.drift/shape-drift-on-record` closes
only the shape-drift findings inside `distributed.cluster`. `:stable-id`
overrides both — a single-finding scope is unambiguous.

**What's deliberately NOT in the scope shape.** No `:severity` filter
(all drift findings are `:warning`; Phase 6 settled this). No
`:scenario-id` override (Layer B already dispatches on `:check`;
overriding from the controller would defeat the polymorphism). No
`:parallel?` knob (concurrency model is fixed-by-file; see next section).
No `:include-related-element-defs?` (that's an `(instruct …)` opt; the
controller doesn't intercept Layer B's options).

---

## Concurrency model

Findings can land in the same target file (two missing functions in
`cluster.clj`, three shape-drifts on one record). Sequential per-file
edits are essential — parallel edits to the same file race.

**The algorithm:**

1. Collect findings (filtered by scope) into a flat vector.
2. For each finding, compute its `:expected-code-path` from the first
   offender. Findings without a code-path (shouldn't happen with
   Sprint 2's filter, but defensively) sort to a "no-path" bucket and
   run sequential.
3. Group findings by `:expected-code-path` — same-file findings go in
   one bucket.
4. Within a bucket, sort findings by stable-id for determinism, then
   run them **sequentially** (one dispatch at a time; the next dispatch
   sees the prior dispatch's edits).
5. Across buckets, run **in parallel**, bounded by a file-fanout cap
   (default 3, configurable via `:max-parallel-files`). A higher cap
   isn't safer — it just means more concurrent subagents, which costs
   API tokens and contention against the daemon for re-verification.

**Why file-fanout 3.** A guess. Sprint 2 will tell us how much each
dispatch actually costs (latency, API-token cost, drift-reverify
latency). 1 is the conservative default; 3 is the "we expect this to be
the common case" default; higher caps invite the question "is the
canvas-author still in the loop?". 3 splits the difference.

**Re-verification is per-finding, not per-file.** After each dispatch,
the controller runs `(canvas-drift {:stable-id <id>})` (or the closest
scope-filter that lands; today it's `:module-coord` + filter-by-id
client-side). The dispatch is closed iff the finding's stable-id is no
longer in the result. Same-file dispatches re-verify in sequence; the
second dispatch sees the post-iter-1 file state and the first dispatch's
finding is already closed (or escalated) by then.

**File-batching is by `:expected-code-path` only, NOT by canvas module.**
The canvas-author may scope to `:module-coord "distributed.cluster"` but
shape-drift findings inside `distributed.cluster` might project to
`src/fukan/distributed/cluster.clj` AND to `test/fukan/distributed/cluster_test.clj`
(post-Sprint-5 with property-test projections). The cross-side
parallelism is fine — different files. The same-side serialization is
load-bearing.

---

## Retry policy

Per-finding, max-attempts capped at 2 by default (configurable).
"Failed" means: after the dispatch returns and `(canvas-drift)`
re-verifies the same scope, the finding's stable-id is still present.

**Attempt 2's instruction shape.** The retry context is structured:

```clojure
{:original-instruction  <iter-1 rendered>
 :iter-1-subagent-report <subagent's terminal message>
 :iter-1-drift-output    <post-iter-1 canvas-drift result for this stable-id>
 :reconciliation-prose
 "Attempt 1 did not close drift. The finding still names a missing
  symbol at the expected path. Reconcile: either the edit didn't land at
  the path the instruction named, the symbol is mismatched, or the canvas
  shape and the implementation shape still diverge. See iter-1-drift
  for the exact mismatch."}
```

The full iter-2 instruction is:

```
<reconciliation-prose preamble>

# Iteration 2 — additional context

[iter-1 subagent report — verbatim]

# Drift state after iteration 1

[iter-1 drift output — the offender entry, formatted]

# Original instruction (unchanged)

[iter-1 rendered]
```

The preamble carries the WHY (drift still names the gap); the iter-1
report carries the WHAT (what the subagent did); the original
instruction carries the contract. The implementing-LLM reads top-down;
the most urgent information lands first.

**Per-finding, not per-scope.** A 5-finding scope with 2 failures
retries the 2 failed findings only; the 3 already-closed findings stay
closed. The scope-level summary aggregates: "5 findings; 3 closed iter
1; 2 closed iter 2; 0 escalated."

**`:max-attempts 2` is the structural default; Sprint 2 calibrates.**
Phase 7 chose 2 ("dispatch, verify, one retry on failure, escalate")
and the trial-run found it sufficient for the cases that *could* close.
The iter 2 trial — `MajorityRequiredForLeadership` — couldn't close at
all because the substrate was wrong; that's an escalation case, not a
retry case. Sprint 2's data will tell us whether the cases that close on
iter 1 stay at iter 1, or whether real-Agent dispatch produces a class
of "iter 1 nearly closed, iter 2 finishes" cases that Phase 7's
same-session substitution masked. If so, `:max-attempts 3` becomes the
default. If iter 2 closure is rare, 2 stays.

**What's structurally fixed regardless of Sprint 2 data.** The shape of
the retry context (preamble + iter-1 report + iter-1 drift + original)
is fixed — it carries the minimum sufficient information for an
implementing-LLM to reconcile. The cadence (per-finding, sequential
within a file, parallel across files) is fixed — it's the only safe way
to compose. What flexes: the *number* (`:max-attempts`), the
*parallelism cap* (`:max-parallel-files`), the *batch size* (`:limit`),
and possibly the *reconciliation prose* (Sprint 2 may suggest the
implementing-LLMs want different framing).

---

## Escalation triggers

A finding escalates (surfaces as needs-human-review in the report)
when one of the following lights up. Each trigger is named so the report
can be filtered/sorted by reason.

| Trigger | Reason | What the canvas-author sees |
|---|---|---|
| `:escalation/attempts-exhausted` | `:max-attempts` reached with no closure | "Finding X failed all N attempts. Iter-1 said Y; iter-2 said Z; drift still names the same gap." |
| `:escalation/no-projection-registered` | Layer A returned `"no project-lens projection registered"` for the finding's element kind | "Finding X has no Layer-A projection registered for canvas-role Y. The substrate gap is upstream — register a projection or change the canvas-side declaration." |
| `:escalation/projection-emits-warning` | Layer A's projection completed but `:context` carries a `:projection/warnings` entry (e.g. handler-payload-shape generic) | "Finding X projected with caveat: <warning>. The implementing-LLM received the projection as-is; verify the resulting code reflects the intent." |
| `:escalation/canvas-side-hint` | Sprint 4's heuristic fires (predicate stubbed-and-failed twice, recent `git log` on `src/` side, etc.) | "Finding X may be canvas-side drift. <heuristic-reason>. Consider retracting/restructuring the canvas declaration." |
| `:escalation/scenario-not-found` | Layer B has no scenario registered for the drift kind | "Finding X's drift-kind has no Layer-B scenario. The default scenario rendered, but the discipline prose was generic. Verify the closure shape matches the drift kind." |
| `:escalation/dispatch-error` | The injected `:dispatch-fn` threw or returned an unhandled-error shape | "Finding X's dispatch failed with error E. The subagent did not return a closure attempt; investigate the dispatch path." |

**Per-trigger report entry shape:**

```clojure
{:stable-id          <finding-id>
 :outcome            :escalated
 :escalation-reason  <one of the keywords above>
 :escalation-detail  <human-readable string>
 :attempts           <count>
 :iter-reports       [<per-iter dispatch result> …]
 :final-drift        <post-final-iter drift entry for this stable-id>}
```

**The canvas-author's action per trigger.**
`:attempts-exhausted` → re-read the iter-N reports and the final-drift;
decide whether to dispatch again manually with a different angle, edit
the canvas to retract the declaration, or open a substrate bug.
`:no-projection-registered` → file a substrate gap (Phase 8 Sprint 5+
fix); meanwhile, manually close the gap or retract the canvas. The
others are similar: each names a specific upstream cause, with a hint at
the right resolution path.

**Sprint 4's canvas-side hints are advisory, not directive.** A
`:escalation/canvas-side-hint` finding still counts as escalated in the
summary — the controller did not autonomously decide "canvas is wrong."
The canvas-author reads the hint and decides.

---

## Report shape

Structured map plus a `:rendered` markdown summary. The structured form
is for programmatic consumers (later phases' tooling, Sprint 7
verification); the markdown is what the canvas-author reads.

```clojure
{:scope
 {:module-coord  <prefix-or-nil>
  :check         <kw-or-nil>
  :stable-id     <id-or-nil>
  :limit         <int>
  :all?          <bool — true when no filter applied>
  :truncated?    <bool — true when :limit cut off findings>
  :remaining     <count of findings beyond :limit>}

 :counts
 {:findings-total      <N>
  :findings-closed     <N>
  :findings-escalated  <N>
  :iter-1-closures     <N>
  :iter-2-closures     <N>
  :dispatch-errors     <N>}

 :elapsed-ms          <total wall-clock>

 :per-finding
 [{:stable-id           <id>
   :expected-code-path  <path>
   :drift-kind          <:check keyword>
   :outcome             <:closed | :escalated | :dispatch-error>
   :attempts            <count>
   :elapsed-ms          <per-finding wall-clock>
   :iter-reports        [<per-iter dispatch result> …]
   ;; only when :outcome = :escalated:
   :escalation-reason   <keyword>
   :escalation-detail   <string>
   :final-drift         <drift entry post-final-iter>
   ;; advisory; nil unless Sprint 4 heuristics fired:
   :canvas-side-hint    {:reason <kw> :detail <string>}} …]

 :rendered            <markdown body — see below>}
```

**`:rendered` markdown.** Mirrors the shape of Phase 7's `(instruct …)`
output — structured headings, terse prose, no marketing. Sections:

1. **Scope.** What was asked, what was found.
2. **Summary.** The `:counts` map as a one-line "X of Y closed, Z
   escalated, retried W times."
3. **Per-finding outcomes.** A table or bulleted list — one line per
   finding, status icon, attempts, elapsed.
4. **Escalations.** One section per escalated finding, with the
   escalation-reason as the heading and the escalation-detail + relevant
   iter reports + final-drift entry below.
5. **Closed findings (collapsed).** Names only, no detail — the report
   is for review, not celebration.

**Load-bearing vs nice-to-have.**

- *Load-bearing:* `:counts`, `:per-finding[].outcome`,
  `:per-finding[].escalation-reason`, `:rendered`. These are the
  decision-ready surface.
- *Nice-to-have:* `:per-finding[].elapsed-ms`, `:counts.iter-1-closures`
  vs `:counts.iter-2-closures`. These are observability signals — useful
  for Sprint 7's verification report, possibly noise in day-to-day use.
- *Defer to data:* `:per-finding[].iter-reports[]` may grow large. Phase
  8 ships them inline; if Sprint 3's smoke test shows the report bloats
  beyond readability, move iter-reports to a side-file (`.fukan/closure-log.edn`)
  and carry only summaries inline. Sprint 3 calls.

---

## State + observability

Where does in-flight state live? Three candidates:

**A. In-memory only.** The controller blocks until done, returns the
report; no persistence. The architect agent (or `bin/fukan eval` caller)
gets the structured return value.

**B. `.fukan/closure-log.edn`.** Every attempt logged for later review.
A side-effecting append per dispatch; persists across daemon restarts.

**C. Both.** In-memory for the return value; persistent log for the
trace.

**Recommendation: A first, C if Sprint 3 reveals need.** Phase 7 trial-
run named "cross-finding context loss" as a pinch — `(instruct …)` calls
carried no memory of prior closes within a session. The controller
itself solves that within a single `(close-drift)` invocation (each
dispatch sees the post-prior-dispatch file state). Across invocations,
persistence matters only if the canvas-author wants to see "what did I
try yesterday?" — a real but soft want. Phase 7's stance ("Sprint 3
trial reveals need") holds.

**Mid-run observability.** The architect agent observes progress how?
Three sub-options:

1. *Blocking call, terminal report only.* The architect calls
   `(close-drift {…})`, waits, gets the report. No mid-run signal. If
   the run takes 5 minutes the user sees nothing for 5 minutes.
2. *Streaming via stderr.* The controller writes one stderr line per
   finding completion (closed/escalated). The architect's Bash call
   gets the lines as they happen.
3. *Polling via a sidecar.* A `.fukan/closure-state.edn` file the
   controller updates per dispatch; the architect polls.

**Recommendation: 1 first, 2 if Sprint 3 trial finds the silence
unbearable.** A typical close-drift run is 2–5 findings × 30–90s per
dispatch (Sprint 2 will refine the per-dispatch latency); a 5-finding
run takes 2–7 minutes. That's borderline — survivable for one run,
painful for many. Sprint 2's latency data tells us whether the silence
is a problem worth solving; Sprint 3's smoke test confirms.

---

## Failure modes the controller does NOT handle

Bound expectations. The controller deliberately punts on:

| What it doesn't do | Why | Deferred to |
|---|---|---|
| Edit canvas | Closure is a code-side operation by definition; canvas-side closure is a separate scenario family (`:canvas-side/*`) deferred to Phase 9+ | Phase 9 |
| Handle cold-write scenarios | Cold-write writes N projections to one empty file — sequential by definition, different concurrency model | Later (post-Phase 8 stability) |
| Pick which scenario per drift kind | Layer B's `defmulti` on `:check` already handles this | Permanent — substrate concern |
| Mediate cross-finding conflicts | When iter-1's edit breaks iter-2's neighbor context, retry might still fail. Detecting this needs canvas-db reasoning across findings | Phase 9 |
| Run tests beyond `(canvas-drift)` verification | Today's verification is "drift cleared"; "tests still pass" is a separate signal | Phase 9 |
| Resolve handler payload shape | Generic `:any` arity is a Layer A limitation; the controller dispatches as-is | Phase 9 (substrate fix) |
| Decide canvas-vs-code direction | Sprint 4's hint is advisory only; the controller never autonomously edits canvas | Phase 9 (`:canvas-side/*` scenarios) |
| Refactor scenario | The third scenario the Phase 7 plan named; deferred from 7 because it depends on delta-instruction shape | Phase 8+ once dispatch loop stable |
| Multi-language lens trial | The Clojure lens is the only lens; cross-lens behavior unverified | Permanently out of Phase 8 scope |
| GC closure-log.edn | If persistence lands later, log retention is a separate concern | Phase 9 if persistence ships |

Each row is a real concern the canvas-author might reach for; each has a
named home elsewhere. The point is: the closure controller is the
dispatch loop for one specific operation (`close-drift`), not the
general-purpose canvas-to-code automation surface.

---

## Interaction with `fukan-architect`

The Phase 8 plan's Sprint 3 Task 7 extends the architect's Phase D
prompt to teach it close-drift mode. The dialogue shape:

1. **Canvas-author** (user): "Close drift in `distributed.cluster`."
2. **Architect** picks the scope from the conversation, optionally
   confirms with the user: "I'll run `(close-drift {:module-coord
   \"distributed.cluster\"})`. Five findings in scope. Proceed?"
3. **User** confirms.
4. **Architect** invokes `bin/fukan eval '(close-drift {:module-coord
   "distributed.cluster" :dispatch-fn …})'`. The dispatch-fn is the
   architect's binding for "invoke `Agent` with this rendered
   instruction + neighbor context; return the subagent's terminal
   report."
5. **Controller** runs the loop, returns the structured report.
6. **Architect** renders the report's `:rendered` markdown for the user.
   If escalations are present, calls them out — "3 closed, 2 escalated:
   `X` exhausted retries with reason Y; `Z` flagged canvas-side hint
   because…"
7. **User** reviews. If escalations need follow-up, conversation
   continues — typically with the architect doing a closer reading of
   the iter reports + final-drift, possibly recommending a canvas-side
   edit (which the user does, or invites the canvas-authoring LLM into
   the conversation to do).

**The critical architectural question: who invokes `Agent`?** Two
sketches:

*Sketch A — Architect dispatches.* The controller's `:dispatch-fn` is
"hand a payload back to the architect, wait." Practically: the
controller can't actually "wait" — it's running inside SCI, which has
no I/O channel back to the architect. Doesn't compose.

*Sketch B — Architect dispatches per-finding, in a loop driven by
controller-rendered plan.* The architect calls `(close-drift {… :dry-run
true})` to get the plan (rendered instructions per finding). For each
finding in the plan, the architect invokes `Agent` and collects the
subagent report. Then the architect calls `(close-drift-verify {:reports
[…]})` (or equivalent) to verify per finding and produce the report.

Sketch B is cleaner but introduces a controller-with-two-entry-points
shape (plan + verify). Workable, but more surface.

*Sketch C — Architect dispatches, controller orchestrates via
return-value protocol.* The controller is a generator. `(close-drift
{…})` returns immediately with a plan + state-token. The architect
invokes `Agent` for the first finding, calls `(close-drift-step
{:state-token …, :report <subagent-report>})` to feed the result back,
gets the next finding to dispatch or the terminal report. The state
lives in the daemon between calls.

Sketch C composes but introduces stateful daemon-side sessions —
a surface area Phase 8 doesn't otherwise need.

**Recommended resolution: Sketch B.** Two entry points:

- `(close-drift-plan {…scope…})` → `{:plan [<per-finding rendered+context> …]
   :scope … :counts {:findings-total N}}`. Pure; no dispatch.
- `(close-drift-verify {:plan … :reports […]})` → the structured report
  with `:counts`, `:per-finding`, etc. Pure; runs `(canvas-drift)` per
  finding to verify.

The architect's Phase D prompt teaches it the two-step shape: "Call
`(close-drift-plan)`; for each entry in `:plan`, invoke `Agent` with the
rendered instruction; collect the reports; call `(close-drift-verify)`
to produce the final report." Retry is the architect's responsibility:
the verify step's per-finding outcome includes `:requires-retry? true`
when iter-1 failed and `:max-attempts` allows iter-2, in which case the
architect dispatches iter-2 with the controller-rendered retry context
and re-verifies.

The single-fn `(close-drift {…})` becomes a convenience wrapper for
non-architect callers (e.g. `bin/fukan eval` directly from the
canvas-author's terminal) that takes a stub dispatch-fn for tests or a
no-op that returns "manual dispatch required" for human invocation.

**Sprint 2's data input here is critical.** Whether Sketch B works
gracefully depends on per-dispatch latency: if dispatch is 30s and the
architect's loop is the orchestrator, a 5-finding scope is ~3 minutes
of sequential architect-driven dispatches with re-verifies between.
Acceptable for now; Sprint 2 confirms or escalates.

---

## Open questions for Sprint 2 to answer

Sprint 2 is the trial-fidelity probe — real-Agent dispatch across the 6
unexercised projection kinds (`event-to-schema`, `rule-to-predicate`,
`checker-to-defn`, `handler-to-defn`, `value-to-def`, plus the
`cold-write` scenario). Its findings amend this design before Sprint 3
implements. The open questions:

1. **Closure rate per projection kind.** Phase 7's same-session trial
   showed 1-of-3 happy-path. Real-Agent dispatch may close more (the
   cold subagent doesn't carry pre-loaded session context that
   accidentally helps) or fewer (the cold subagent doesn't know the
   project's conventions implicitly). Calibrates `:max-attempts`
   default and the iter-1-vs-iter-2 closure-rate expectation.

2. **Common failure-mode shapes.** When iter-1 fails, what does the
   subagent report say? Symbol mismatch? Path mismatch? "I read the
   instruction but the file didn't have what it claimed"? The
   reconciliation-prose for iter-2 should be calibrated to the actual
   failure modes, not the imagined ones.

3. **Latency per dispatch.** A 30s dispatch makes file-fanout 3
   acceptable; a 3-minute dispatch makes fanout 1 the default. Also
   feeds into the "blocking call vs. streaming" observability question.

4. **Whether handler-payload-shape genericity actually bites.** The
   Phase 7 verification flagged this as a Layer A limitation; whether
   it manifests as iter-1 closure failure depends on whether
   implementing-LLMs successfully infer payload structure from
   sibling/canvas context. If they do, the genericity is fine; if
   they don't, Phase 8 Sprint 5+ needs to fix the projection.

Each amendment refines the design before Sprint 3 builds. The numbers
in this doc that are "best guess pending Sprint 2 data" — `:max-attempts
2`, `:max-parallel-files 3`, `:limit 25`, the in-memory-only state
recommendation, the blocking-call observability default — get rechecked
explicitly in the Sprint 2 findings doc and amended here if the data
contradicts.

The numbers that are *structurally fixed regardless* of Sprint 2 data:
the file-batching algorithm (group by `:expected-code-path`, serialize
within a group, parallelize across groups), the retry-context shape
(reconciliation prose + iter-1 report + iter-1 drift + original
instruction), the two-entry-point split for `fukan-architect`
integration (plan + verify, architect drives dispatch), and the
escalation-trigger taxonomy.
