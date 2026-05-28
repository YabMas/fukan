# Phase 8 Sprint 2 Trial Run — Real-Agent dispatch findings

**Date:** 2026-05-28
**Loop tested:** instruction-rendering probes × 6 drift kinds + cold-write
**Scope:** distributed.log (events, handlers, atomic), validation.* (rules,
checkers), constraint.sort (function), distributed.election (cold-write)

## Preamble — fidelity of this trial vs. Phase 7's

Phase 7's trial-run explicitly carried forward concern #2: **no `Agent`
tool in the harness, so cold-context implementing-LLM dispatch could not
be exercised**. Sprint 2 was scoped specifically to close that gap. I
have to report up-front that the gap **did not close in this trial
either** — the harness running this Sprint 2 trial does not expose an
`Agent` / `Task` subagent-dispatch tool. I checked twice (a direct
`select:Agent` against `ToolSearch` and a keyword search against the
deferred-tools list) and the only `Task*` tools surfaced are the
todo-list management family (`TaskCreate`, `TaskUpdate`, `TaskList`,
`TaskGet`, `TaskStop`), not subagent dispatch.

Per the trial brief's blocker policy ("STOP and report the blocker —
don't try to fix it"), the strict-reading interpretation is BLOCKED on
the **end-to-end closure rate** axis: I cannot measure how often a
real cold-subagent successfully implements a Phase 7 instruction.

But the brief also names another axis Sprint 3 design needs: the
**instruction-quality axis** — does the rendered instruction contain
everything an implementing-LLM would need to write working code? That
axis is answerable from the canvas-author seat alone, by inspecting
what `(instruct …)` emits for each of the six unexercised projection
kinds. Phase 7 only exercised three projection kinds (record,
invariant, function), and two of them surfaced Layer A defects. This
trial extends that survey to the six unexercised kinds plus
cold-write. The findings below are honest about which axis each
observation covers.

The implementing-LLM-step (iter 1 / iter 2 dispatch) is absent
throughout. Where the Phase 7 trial substituted in-session
implementing-LLM behavior, I do not, because Phase 7 already did
that, and re-doing it generates no new data Sprint 3 can use. I
instead record what the rendered instruction would offer a real
subagent and where it would force them to invent or guess.

## Probes

### Probe 1 — `event-to-schema` (`:canvas/event`)

- **Target finding:** `distributed.log/AppendEntriesRequested`
  (missing-implementation; canvas-kind `event`; projection-kind
  `projection-kind/schema`).
- **Instruction rendered** (excerpt):
  ```clojure
  (def ^:event AppendEntriesRequested
    [:map {:description "The leader is asking a follower …"}])
  ```
- **Defect surfaced — Layer A drops payload fields.** The canvas
  declares six payload fields on `AppendEntriesRequested`
  (`leader_term`, `leader`, `prev_index`, `prev_term`, `entries`,
  `leader_commit`). The Malli schema body emitted by the projection
  is an empty `[:map {:description "…"}]` — none of the payload fields
  appear. A real subagent receiving this instruction would have no way
  to write a schema that matches the canvas declaration: they would
  either (a) write an empty `[:map]` (the projection's literal output)
  and drift would never close, because the comparator would see
  zero-fields-in-code vs six-in-canvas, or (b) open the canvas file
  themselves and copy fields over, defeating the point of the
  rendered instruction being self-contained.
- **Instruction-quality verdict:** **BROKEN for events with payload
  fields.** The skeleton is paste-clean but semantically empty.
  Implementing-LLM would have to leave the loop and read canvas/* to
  recover the fields the projection should have surfaced. This is a
  blocker for Phase 8 automation of event drift-close.
- **End-to-end closure (iter 1, iter 2):** not exercisable — no
  Agent tool.

### Probe 2 — `rule-to-predicate` (`:canvas/rule`)

- **Target finding:** `validation.rules-4c/BindingOperationResolves`
  (missing-implementation; canvas-kind `rule`; projection-kind
  `projection-kind/rule`).
- **Instruction rendered** (excerpt):
  ```clojure
  (defn binding-operation-resolves
    "Every fn binding of the form `fn Contract.op` or …"
    [model]
    (throw (ex-info "binding-operation-resolves: not yet implemented"
                    {:canvas-id "validation.rules-4c/BindingOperationResolves"
                     :invariant-name "BindingOperationResolves"
                     :holds-that "binding-operation-reference-resolves-in-model"})))
  ```
- **Observation — Phase 7 iter-2 defect closed.** Phase 7's invariant
  projection emitted illegal Clojure (`(defn leader holds majority for
  its term ...)`). This trial's rule rendering — with a PascalCase
  canvas-id and a kebab `holds-that` value — produces clean kebab-case
  `binding-operation-resolves`. The defect was specific to
  invariants whose `holds-that` value contained spaces; PascalCase
  canvas-ids with kebab-friendly `holds-that` render correctly. Phase
  7 invariant-projection design document (`2026-05-28-invariant-
  projection-design.md`) addresses the structural root cause via the
  property-test rehome. The rule/invariant projection used here
  appears to share the function-form path, so the Phase 7 iter-2 defect
  may already be substrate-fixed in this trial's daemon. **Worth
  re-running Phase 7 iter 2 directly to confirm.**
- **Naming collision** — the canvas declares both
  `(rule "BindingOperationResolves")` AND
  `(invariant "BindingOperationResolves")`. Both project to the same
  expected code symbol. Drift will name both findings; closing one
  expression of the canvas commitment doesn't close the other unless
  the comparator collapses them. Worth confirming Phase 8 plan's
  invariant-property-test rehome handles this collision cleanly (the
  `(invariant …)` projects to a test file; the `(rule …)` projects to
  the predicate fn in src; same name, different target paths).
- **Instruction-quality verdict:** clean Clojure. Symbol legal,
  ex-info carrier metadata is consistent.
- **End-to-end closure:** not exercisable — no Agent tool.

### Probe 3 — `checker-to-defn` (`:canvas/checker`)

- **Search outcome — no checker drift findings exist in the current
  codebase.** `(canvas-drift)` produces no offenders with
  `canvas-kind` of `checker`; every `(checker "…")` declaration in
  the canvas validation modules has a matching code-side `check` fn.
  This means probe 3 cannot be run against a real drift finding on
  live data. The probe survives by directly projecting an existing
  checker via `(spec "validation.rules-4c/check")`.
- **Spec output (excerpt):**
  ```clojure
  (defn check
    "Run all five 4c binding rules against the Model and return
           the aggregated Violation sequence."
    {:malli/schema [:=> [:cat :model/Model] [:sequential :agent/Violation]]}
    [model]
    (throw (ex-info "check: not yet implemented"
                    {:canvas-id "validation.rules-4c/check"})))
  ```
- **Synthetic instruction (drift-close as if missing):** clean
  Clojure. Kebab `check`, malli schema with arrow shape and
  `Violation` return type, ex-info carrier.
- **Instruction-quality verdict:** GOOD. The `:canvas/checker`
  projection emits paste-ready Clojure.
- **Observation — checker projection is registered.** Phase 7's
  trial flagged `:canvas/checker` as one of four unregistered
  projections (`:canvas/getter`, `:canvas/handler`, `:canvas/checker`,
  `:canvas/operation`). The Phase 7+7.5 work landed all four; this
  trial confirms via `(canvas-projections)` that all are now
  registered.
- **End-to-end closure:** not exercisable — no real drift finding,
  no Agent tool.

### Probe 4 — `handler-to-defn` (`:canvas/handler`)

- **Target finding:** `distributed.log/on_append_entries_requested`
  (missing-implementation; canvas-kind `handler`).
- **Instruction rendered** (excerpt):
  ```clojure
  (defn on-append-entries-requested
    "A follower validates the request (leader_term not stale, prev_index/prev_term match), …"
    {:malli/schema [:=> [:cat :log/AppendEntriesRequested :any] :any]}
    [payload state]
    (throw (ex-info "on-append-entries-requested: not yet implemented"
                    {:canvas-id "distributed.log/on_append_entries_requested"
                     :on :log/AppendEntriesRequested
                     :emits [:log/AppendEntriesAcknowledged]})))
  ```
- **Instruction-quality verdict:** GOOD. Kebab symbol matches the
  expected-symbol in the drift finding; malli arrow shape carries the
  event keyword and a `:any` state; ex-info captures `on` and `emits`
  so closing a handler doesn't lose the canvas's reactive wiring.
  Prose section names "fires on" and "may emit" cleanly.
- **End-to-end closure:** not exercisable — no Agent tool.

### Probe 5 — `value-to-def` (atomic, `:Type/atomic`)

- **Target finding:** `distributed.log/type/Command` (missing-
  implementation; canvas-kind `type`).
- **Instruction rendered** (excerpt):
  ```clojure
  (def ^:schema Command
    [:any {:description "An opaque application-level command — the payload of a log entry. …"}])
  ```
- **Observation — atomic projects to `[:any …]`.** That is correct
  for an opaque atomic (the canvas explicitly declares Command as
  opaque), but a real subagent might second-guess and ask "why am I
  writing `[:any …]`?". The projection could add a one-line note
  ("Atomic = opaque; downstream code may refine; intentional `[:any]`
  here") so the implementing-LLM doesn't over-interpret. Minor
  signal-quality issue; not a blocker.
- **Instruction-quality verdict:** GOOD. Clean, legal Clojure.
- **End-to-end closure:** not exercisable — no Agent tool.

### Probe 6 — `function-to-defn` (second function-shaped drift)

- **Target finding:** `constraint.sort/is_primitive_id` (missing-
  implementation; canvas-kind `function`; projection-kind
  `projection-kind/operation`).
- **Instruction rendered** (excerpt):
  ```clojure
  (defn is-primitive-id
    "True iff x is a String containing the '::' separator. …"
    {:malli/schema [:=> [:cat :any] :Bool]}
    [x]
    (throw (ex-info "is-primitive-id: not yet implemented"
                    {:canvas-id "constraint.sort/is_primitive_id"})))
  ```
- **Defect surfaced — predicate-suffix convention mismatch.** The
  target file `src/fukan/constraint/sort.clj` already contains a
  `is-primitive-id?` def (note the `?` suffix — idiomatic Clojure for
  boolean-returning fns). The projection emits `is-primitive-id`
  (without `?`) because the canvas declares the function as
  `is_primitive_id` (without `?`). A real subagent, paste-following,
  would add a SECOND `is-primitive-id` def alongside the existing
  `is-primitive-id?`. The codebase ends up with two nearly-identical
  defs, neither of which the drift comparator can recognize as the
  intended closure (the comparator expects symbol `is-primitive-id`
  exactly; `is-primitive-id?` doesn't match it; the new
  `is-primitive-id` does match but creates the duplicate).
- **Root cause:** the canvas's `function` declaration doesn't
  capture predicate-ness, so the projection can't add the idiomatic
  `?`. Either (a) canvas needs a way to tag a function as
  `:predicate?`, OR (b) drift comparator needs to normalize `?` away
  on the code side, OR (c) the canvas-author needs to manually
  rename the canvas declaration to `is_primitive_id_p` or similar
  to signal predicate intent.
- **Instruction-quality verdict:** **MISLEADING for boolean-returning
  functions when sibling code already follows the `?`-suffix
  convention.** The instruction is paste-clean but it tells the LLM
  to write code that conflicts with file-local convention.
- **End-to-end closure:** not exercisable — no Agent tool. If
  exercised, the prediction is iter-1 produces duplicate
  `is-primitive-id` def alongside `is-primitive-id?`, drift does
  NOT close (still expects `is-primitive-id` exact, and the
  duplicate produces it, so drift would actually close — but the
  codebase is messier). Hard call: drift closure SUCCESS but
  semantic FAILURE. Sprint 3 design needs to consider this case.

### Probe 7 — `cold-write` scenario (distributed.election)

- **Target module:** `distributed.election` (10 drift findings; module
  src file exists but most declarations are absent).
- **Render call attempted:**
  `(instruct {:module-coord "distributed.election"} :code-side/cold-write)`
  per brief.
- **Result — entry point rejects the call.** `(instruct …)` calls
  `(spec finding-or-id)` first. `(spec)` only accepts a stable-id
  string, an element map, or a drift finding — it does NOT accept
  `{:module-coord …}`. The call returns
  `{"ok?":false,"error/kind":"bad-argument","error/message":"spec:
  expected stable-id string, element map, or drift finding"}`.
- **Workaround attempted:** pass a stable-id from inside the module
  plus `:module-id "distributed.election"` in opts. The call now
  succeeds but cold-write's `:projections` opt is not auto-derived
  from `:module-id`. The rendered output reads:
  > "The canvas declares **0 entities** (counted after any
  > `:include-entity-ids` subsetting)."
  > "0 entities total. Kind-mix:"
  with no body content. The `:projections` field in the scenario
  context is `[]`.
- **Two defects surfaced:**
  1. **No public entry point for cold-write.** The brief's invocation
     pattern (`(instruct {:module-coord …} :code-side/cold-write)`)
     does not work. The cold-write scenario expects a pre-built
     projections vector in opts; the public `(instruct …)` agent API
     doesn't construct one. Sprint 3's controller would need to either
     extend `(spec)` to accept a module-coord scope and walk the
     canvas db to build the projections vector, OR cold-write needs
     a separate `(cold-write-instruct …)` agent fn.
  2. **`target-file-exists?` does not filesystem-check by default.**
     `src/fukan/distributed/election.clj` exists on disk (3982
     bytes) but the rendered instruction says "(does not yet
     exist)". The scenario relies on opts; if opts omit
     `:target-file-exists?`, the rendered text is wrong.
- **Instruction-quality verdict:** **NOT EXERCISABLE end-to-end from
  the agent surface.** The cold-write scenario is registered, its
  internal render fn works, but no canvas-author or controller can
  reach it via the public surface without first reconstructing
  projections by hand.
- **N-of-M closure:** undefined — the rendered instruction's
  projection body is empty, so a real subagent receiving it would
  write a near-empty file (just the ns form per "match canvas
  declaration order"). Closure rate ≈ 0/M.

## Closure-rate summary

| Projection kind | iter-1 closure | iter-2 closure | Dispatch latency | Instruction-quality |
|----|----|----|----|----|
| `event-to-schema` | — (no Agent) | — | — | BROKEN — empty `[:map]` body, payload fields dropped |
| `rule-to-predicate` | — | — | — | GOOD — Phase 7 iter-2 defect appears resolved on the rule path |
| `checker-to-defn` | — | — | — | GOOD — no drift to close, projection emits clean Clojure |
| `handler-to-defn` | — | — | — | GOOD — `on`/`emits` carried in ex-info |
| `value-to-def` | — | — | — | GOOD — minor "why `[:any]`?" prose-clarity nit |
| `function-to-defn` (predicate case) | — | — | — | MISLEADING — `?`-suffix convention not honored |
| `cold-write` | — | — | — | NOT EXERCISABLE — no public entry point |

The dashes mean: not measured because no Agent dispatch was possible.

The columns Sprint 3 would have populated empirically — iter-1 closure
rate, iter-2 closure rate, dispatch latency — are absent. The
closure-controller design's retry policy thus cannot be calibrated
against measured data from this trial. Sprint 3 design either has to
defer the retry-policy decision until a future trial run on a harness
with `Agent` access, OR proceed with the proposed thresholds and
expect post-implementation calibration.

## Loop quality

What worked in the available-axis exploration:

- **`(canvas-drift :module-coord …)` is fast and surgical.** ~90ms
  per call on a 1269-artifact model. Picking probe targets across
  six different projection kinds took <2 minutes.
- **`(instruct {:stable-id …} :code-side/drift-close)` accepts
  finding-shaped maps directly.** The trial fabricated finding
  maps (with `:check`, `:canvas-kind`, `:expected-code-path`,
  `:expected-symbol`) and `(instruct)` rendered them correctly. This
  matches how a controller would feed findings into the loop.
- **The drift-close scenario's `:check`-dispatch fix** (Phase 7+7.5
  iter-1 defect) is visible in the rendered output — the prose
  selects missing-implementation framing for missing-implementation
  findings. Shape-drift-on-record (probe 1's LogEntry case, not
  directly probed but observed in the drift output) would presumably
  get rewrite-in-place framing.

What didn't:

- **Cold-write's public entry point is missing.** The brief assumed
  `:module-coord` would work; it doesn't. Either the agent surface
  needs to grow or the brief needs to document the workaround.
- **Event projection drops payload fields silently.** This is
  load-bearing for Phase 8 — events are common, and an event drift
  closure that produces an empty schema is worse than no closure
  (drift would not close, and the code would have an empty schema
  blocking compilation that uses the schema).
- **The Agent tool gap is not closing on its own.** Two consecutive
  trial runs have flagged it; Phase 8 plan needs to acknowledge that
  the empirical closure-rate data Sprint 3 design depends on may have
  to come from a different seat (perhaps `fukan-architect` invoked
  from the user's primary chat with the Agent tool grant), or from
  a Sprint 4 controller running against a real Anthropic API
  endpoint that can spawn subagents.

## Defects surfaced

Pre-classified by guess at "blocks Phase 8 automation?":

| # | Defect | Blocks? |
|----|----|----|
| 1 | Event projection drops payload fields — emits empty `[:map]` body when canvas declares N payload fields | **YES** — automated event drift-close would produce schemas that never close drift and break downstream consumers |
| 2 | Cold-write scenario unreachable via public `(instruct …)` agent API; requires pre-built projections vector | **YES** — Sprint 3 controller cannot drive cold-write without an entry-point fix or a controller-side projection assembler |
| 3 | Cold-write `target-file-exists?` does not filesystem-check; defaults to "does not yet exist" even when target exists | **NO** — wrong prose, not wrong action; implementing-LLM can recover by opening the file |
| 4 | `function-to-defn` for predicate-shape functions emits non-`?`-suffixed symbol, conflicting with file-local convention | **MAYBE** — duplicate-def case; drift closes mechanically but codebase quality degrades. Sprint 3 should detect-and-warn |
| 5 | `Type/atomic` rendering for opaque types emits `[:any …]` with no prose explanation that opacity is intentional | **NO** — minor signal-quality nit |
| 6 | Canvas name+role collision (rule + invariant with same name) — both project to same code symbol; closing one doesn't close the other unless comparator collapses | **MAYBE** — depends on whether Phase 8's invariant-property-test rehome dispatches by role |
| 7 | No `Agent` tool in harness running Sprint 2; trial-fidelity gap from Phase 7 unclosed | **YES** — Sprint 3's retry policy + concurrency cap cannot be calibrated against empirical data |

Final classification (block-Phase-8-automation? yes/no/maybe) is Task
4's call after canvas-author review.

## Recommendations for Sprint 3 design amendment

1. **Fix defect #1 before Sprint 3 MVP**, not after. Event projection
   producing an empty schema body is a category error; the substrate
   defect must close before the closure controller can dispatch event
   drift-close at all. This is a Task 4 priority.
2. **Fix defect #2 before Sprint 3 MVP.** Cold-write needs a public
   entry point. Options: (a) extend `(spec)` to accept
   `{:module-coord …}` and walk the canvas to build projections, OR
   (b) add `(cold-write-instruct …)` as a sibling agent fn. The
   former preserves the unified `(instruct)` interface; the latter
   surfaces cold-write as a first-class scenario.
3. **Defer retry-policy calibration.** Sprint 3's closure-controller
   MVP should ship with the Sprint-1-proposed retry/concurrency
   thresholds as defaults and a `:trial/calibration-pending` tag in
   the doc, with the explicit note that calibration awaits a real-
   Agent dispatch trial. Don't block the MVP on empirical data the
   harness can't produce.

(Implicit fourth recommendation, slightly out of Sprint-3 scope: the
function-to-defn predicate-suffix issue (defect #4) should be
addressed before automation lands at scale, because dispatched closure
that produces duplicate near-identical defs is the kind of garbage
that erodes codebase trust quickly. But it's a one-off projection fix,
not a controller-design concern.)

## Closing assessment

Sprint 2 was scoped to close the real-Agent dispatch fidelity gap
Phase 7 carried forward as concern #2. **It did not close.** The
harness running this trial does not expose subagent dispatch, the
same limitation Phase 7 flagged. The end-to-end closure-rate data
Sprint 3's controller design needs is therefore not produced by this
trial; the gap is carried forward again to Sprint 4 (or to a future
seat with `Agent` tool access).

What this trial *did* produce: instruction-rendering data across the
six projection kinds Phase 7 didn't exercise, plus cold-write. Two
new substrate defects surface — event projection drops payload
fields, and cold-write has no callable public entry point — both
plausibly blocking for Phase 8 automation and both fixable in Task 4
before Sprint 3 MVP begins. One Phase 7 defect (invariant illegal-
symbol generation) appears resolved on the rule projection path; the
canvas-author should confirm by re-running Phase 7's iter-2 case
directly. Function-shape projections handle predicate-suffix Clojure
idiom poorly; that's a smaller Task 4 candidate or a Phase 9
follow-up.

The honest summary: Sprint 2's *stated* purpose (empirical closure-
rate calibration) is unmet; Sprint 2's *unstated but useful*
side-effect (instruction-quality survey across unexercised
projections) produced actionable defects for Task 4. Sprint 3 should
proceed but with the retry-policy thresholds explicitly marked
provisional and a TODO to recalibrate from real-Agent data when the
seat permits.
