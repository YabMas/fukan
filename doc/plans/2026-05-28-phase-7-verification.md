# Phase 7 + 7.5 Verification Report — Canvas → Code Implementation Instructions

**Date:** 2026-05-28
**Status:** Complete
**Decision:** (2) Works with caveats — Phase 7 + 7.5 ship; concerns carry forward to Phase 8

**Phase scope:** Turn drift findings into actionable implementation
instructions. Phase 6 detected where canvas (design) and `src/` (code)
diverged; Phase 7 produces the structured handoff that tells an implementing
LLM exactly what code to write to close each gap. Two layers ship — a
project-lens that projects each Model element to a deterministic code spec
(Layer A), and a scenario wrapper that frames the spec for the situation
(Layer B). Phase 7.5 cleaned up the three substantive defects the original
Phase 7 trial-run surfaced before the verification rubber-stamp.

---

## Strategic frame

Phase 6 closed the canvas↔code loop *detection*: drift findings name where
canvas and code separated, on both sides. Phase 7's strategic move was to
make those findings *actionable* — when the canvas-author decides "close in
code," the substrate now produces the precise instruction the implementing
LLM consumes (target path, symbol, structural template, prose envelope,
scenario framing). The architectural separation respects each LLM's
strength: fukan's canvas reasoning stays in fukan; Clojure synthesis
delegates to a capable implementing LLM downstream.

The two-layer split was load-bearing for this phase. The original draft
conflated projection (generic-Model → project-specific-code-spec) with
scenario framing (raw spec → situation prose) into per-instruction-type
generators. The amendment captured the right cleavage:

- **Layer A — Project-lens projection.** Pluggable per project; the
  fukan-on-fukan Clojure lens is the reference implementation. One
  `defmethod project [:clojure <dispatch-key>]` per Model-element kind.
- **Layer B — Scenario-aware instruction.** Wraps a Layer-A projection
  with situation-specific framing. Two scenarios ship — `drift-close`
  and `cold-write`.

The product surface stays the REPL, `bin/fukan eval`, `(help)`, and the
extended `fukan-architect` agent — extended in Phase 7 Sprint 4 Task N+1
with `Agent` + `Read` tool grants and a new Phase D dispatch mode. No
browser UI work (deferred indefinitely); no automated dispatch (Phase 8).

The Phase 7 trial-run uncovered three substantive defects in the loop
before Sprint 5 even began. Phase 7.5 — three small cleanup sprints —
closed all three; two workflow fixes landed alongside (LSP config mirror,
`(reset)` bug). This verification covers Phase 7 + 7.5 + the two workflow
fixes as one body of work.

---

## 1. What was attempted vs. built

Phase 7 ran four working sprints + a deferred verification; Phase 7.5 ran
three follow-up sprints to close the trial-run defects; two workflow fixes
landed mid-Sprint 5. ~33 commits clustered between Phase 7 plan landing
(`xtnqwxxu`) and Sprint 5 verification preparation (`lrsrnsmt`).

### Phase 7 Sprint 1 — Project-lens + scenario-handoff design

Two design docs landing the load-bearing distinction the original draft
conflated.

| Planned | Delivered | Status |
|---|---|---|
| Task 1: Project-lens design (Layer A) | `doc/plans/2026-05-27-project-lens-design.md` | ✅ |
| Task 2: Scenario + handoff design (Layer B) | `doc/plans/2026-05-27-scenario-handoff-design.md` | ✅ |

Both docs paused for user review before downstream sprints began. Task 1
settled the multimethod-keyed-on-`[lens-id dispatch-key]` registry; Task
2 specified the drift-close + cold-write scenarios + the
canvas-author→implementing-LLM handoff protocol.

### Phase 7 Sprint 2 — Pre-implementation hardening

Three small mechanical fixes Sprint 1 surfaced as prereqs for Layer A.

| Planned | Delivered | Status |
|---|---|---|
| Task 3: Compound-shape comparator for shape-drift | Recursive shape comparator in `inspect/drift.clj` | ✅ |
| Task 4: Addressing gaps in `target/clojure/*` | `signature-for` lifted public; invariant `holds-that`-absent fallback; event-schema address symmetry | ✅ |
| Task 5: Scoped drift via `:module-coord` filter | `(canvas-drift :module-coord <prefix>)` | ✅ |

Per-gap hygiene preserved. Sprint 2 was structurally small but unblocking
for Layer A.

### Phase 7 Sprint 3 — Layer A + Layer B substrates + 9 projections + 2 scenarios

The substantive sprint. Two substrates and their occupants. Mirrors Phase
5's lens-substrate sprint shape, doubled.

| Planned | Delivered | Status |
|---|---|---|
| Task 6: Layer A substrate (core + registry + render) | `src/fukan/canvas/project/{core,registry,render}.clj` | ✅ |
| 5–7 Clojure-lens projections | 6 shipped in Phase 7 Sprint 3 (`value-to-def`, `type-to-malli`, `event-to-schema`, `function-to-defn`, `invariant-to-predicate`, `rule-to-predicate`) | ✅ |
| Task M+1: Layer B substrate | `src/fukan/canvas/instruct/{core,registry,render}.clj` | ✅ |
| 2 scenarios (drift-close, cold-write) | `instruct/{drift_close,cold_write}.clj` | ✅ |
| Task N: Agent API integration | `(spec …)` + `(instruct …)` + `(canvas-projections)` + `(canvas-scenarios)` under `:trust` | ✅ |

Sprint 3 ran cleanly per-task. The scope crept upward (6 projections, not
5) because `rule-to-predicate` was symmetric-enough with
`invariant-to-predicate` to ship together. `getter-to-defn`,
`handler-to-defn`, `checker-to-defn` were *deferred* in Phase 7 Sprint 3 —
this deferral became the load-bearing Phase 7.5 Sprint 1 cleanup item.

### Phase 7 Sprint 4 — Agent integration + trial run

Two artifacts plus the trial-run findings doc.

| Planned | Delivered | Status |
|---|---|---|
| Task N+1: Extend architect agent + system prompt + AGENTS.md for Phase D | All three updated; `Agent`+`Read` granted | ✅ |
| Task N+2: Trial run closing `canvas/distributed/*` drift gaps | 3 iterations; partial canvas/code work in `distributed/*` | ✅ |
| Trial-run findings doc | `doc/plans/2026-05-27-instruction-trial-run-findings.md` | ✅ |

The trial-run surfaced three substantive defects (see §4). Sprint 5
(verification) was deferred and Phase 7.5 inserted between Sprint 4 and
verification, mirroring the Phase 5 → 5.5 → verification cadence.

### Phase 7.5 — Instruction-loop cleanup (three small sprints)

| Sprint | Planned | Delivered | Status |
|---|---|---|---|
| 7.5 Sprint 1 | Fill Layer A coverage gap for `:canvas/getter`, `:canvas/handler`, `:canvas/checker` + add coverage regression test | All three projections shipped (`getter_to_defn`, `handler_to_defn`, `checker_to_defn`); `test/fukan/canvas/project/coverage_test.clj` added | ✅ |
| 7.5 Sprint 2 | Sanitize invariant symbol — derive from entity-name not `holds-that` | `canvas-source/project-affordances` now uses `name` as primitive `:label`; `addr/canonical` produces kebab-case from PascalCase; invariant projection's `:invariant-name` opts hack dropped | ✅ |
| 7.5 Sprint 3 | Kind-polymorphic drift-close — branch frame/gap/neighbors/output on `:check` | `defmulti` backbone in `drift_close.clj` keyed on `drift-kind`; `missing-implementation`, `shape-drift-on-record`, `:default` branches | ✅ |

Plus the trial-run code closures landed pre-7.5 (`MajorityRequiredForLeadership`
predicate stub, `Cluster` shape rewrite, `get_entry` closure) so Phase 7.5
had clean canonical cases to verify against.

### Workflow fixes (mid-Sprint 5, pre-verification)

Two operational bugs the trial-run surfaced that didn't fit cleanly into a
7.5 sprint:

| Fix | Symptom | Resolution |
|---|---|---|
| LSP config mirror | clojure-lsp re-emitted `unused-public-var` info on `distributed/*` + dynamic-load namespaces that clj-kondo already exempted | `.lsp/config.edn` mirrors the clj-kondo `:exclude-when-meta #{:export}` + namespace exemptions verbatim |
| `(reset)` reload of dynamic-load registries | `bin/fukan reset` reloaded canvas `.clj` files but not the loader namespaces (`fukan.canvas.project.clojure`, `fukan.canvas.lens.registry`, `fukan.canvas.instruct.registry`); newly-added projection/scenario files in `src/fukan/canvas/{project,lens,instruct}/*` weren't picked up | `src/fukan/agent/system.clj` adds an explicit `dynamic-load-loaders` list reloaded after canvas `:reload`-walk |

Both verified by inspection; both narrow + targeted.

### Sprint 5 — Verification (this document)

---

## 2. Layer A projection quality

Nine projections are registered against `[:clojure <discriminator>]` —
confirmed via daemon smoke test:

```
$ bin/fukan eval '(count (canvas-projections))' → 9
$ bin/fukan eval '(canvas-projections)' →
  [{:lens-id :clojure :dispatch-key :Type/atomic}
   {:lens-id :clojure :dispatch-key :Type/record}
   {:lens-id :clojure :dispatch-key :canvas/checker}
   {:lens-id :clojure :dispatch-key :canvas/event}
   {:lens-id :clojure :dispatch-key :canvas/getter}
   {:lens-id :clojure :dispatch-key :canvas/handler}
   {:lens-id :clojure :dispatch-key :canvas/invariant}
   {:lens-id :clojure :dispatch-key :canvas/rule}
   {:lens-id :clojure :dispatch-key :fukan.canvas.monolith/exposed-call}]
```

Per-projection status:

| Dispatch key | Emits legal Clojure? | End-to-end exercised? | Known limitation |
|---|---|---|---|
| `Type/atomic` | yes (`(def …)` opaque marker) | unit tests only | none material |
| `Type/record` | yes (Malli `[:map [...]]`) | trial iter 1 (`Cluster` rewrite-in-place) | none material |
| `canvas/event` | yes (Malli event-schema `def`) | unit tests only | not exercised end-to-end |
| `fukan.canvas.monolith/exposed-call` | yes (`(defn …)` with kebab name) | trial iter 3 (`get_entry`) | none material — the happy-path projection |
| `canvas/invariant` | yes after 7.5 Sprint 2 (`(defn majority-required-for-leadership [model] …)`) | trial iter 2 + 7.5 stub commit | symbol now derived from entity-name; pre-7.5 emitted illegal symbols. Whether invariants belong as predicates *at all* is the §6/§8 forward question |
| `canvas/rule` | yes (predicate-fn skeleton symmetric with invariant) | unit tests only | not exercised end-to-end |
| `canvas/getter` | yes after 7.5 Sprint 1 (zero-arg `(defn get-self-role [] …)`) | smoke-tested in this verification | none material |
| `canvas/checker` | yes after 7.5 Sprint 1 (`(defn check [model] …)`) | unit tests only | not exercised end-to-end |
| `canvas/handler` | yes after 7.5 Sprint 1 (`(defn on-<event> [payload] …)`) | unit tests only | input shape is a generic `:cat <event-ref> :any` — the `(on …)` clause carries an *event reference*, not the payload's structural shape; Sprint 1 known limitation flagged in `doc/plans/2026-05-27-project-lens-design.md` |

The regression guard at `test/fukan/canvas/project/coverage_test.clj`
enumerates `:affordance/role` values emitted by `canvas-source` plus the
`Type/*` dispatch keys (`:atomic`, `:record`), cross-references against
`(methods project)`, and asserts no role is silently uncovered. The test
also includes a `canonical-sprint-1-example-projects-through` assertion
exercising the previously-failing `distributed.cluster/get_self_role`
case end-to-end. Tests pass against the live canvas surface (989 tests
total, 0 failures).

**Verdict:** Layer A's projection set covers the full canvas-source
emission surface. The four canvas-roles that the original Phase 7 trial
identified as uncovered (`:canvas/getter`, `:canvas/handler`,
`:canvas/checker`, and the defensively-checked `:canvas/operation`) now
project. The Sprint 1 canonical example — which couldn't be exercised
through Phase 7's loop at all — now returns a valid getter projection
with `:symbol "get-self-role"`. The handler-projection's input-shape
generality is the only material limitation, and it's documented.

---

## 3. Layer B scenario quality

Two scenarios are registered. Confirmed via daemon smoke test:

```
$ bin/fukan eval '(canvas-scenarios)' →
  [{:scenario-id :code-side/drift-close   :description "Close a known canvas↔code drift gap." …}
   {:scenario-id :code-side/cold-write    :description "Write a canvas module's implementation from scratch." …}]
```

### drift-close

Phase 7.5 Sprint 3 added kind-polymorphism. The scenario's `frame-section`,
`gap-section`, `neighbors-section`, and `output-section` are all
`defmulti`-dispatched on `drift-kind` — the finding's `:check` keyword.
Three branches: `:inspect.drift/missing-implementation`,
`:inspect.drift/shape-drift-on-record`, and a generic `:default` so an
unknown future drift kind doesn't crash drift-close or hard-code
missing-impl prose.

Behaviour per kind, smoke-verified against the live model:

| `:check` | Frame prose | Neighbor prose | Output prose |
|---|---|---|---|
| `:inspect.drift/missing-implementation` | "add the missing definition" | "Insertion point: end-of-file" | "Write the edit … report symbol added + insertion point used" |
| `:inspect.drift/shape-drift-on-record` | "rewrite the existing def in place — do NOT append a duplicate" | "rewrite-in-place at lines N–M of `<path>`. Existing form opens with: …" (when located) | "Rewrite the existing def using the Edit tool … report line range + which fields added/removed/retyped" |
| `:default` | generic reconcile prose | generic neighbor block | generic edit prose |

The shape-drift branch additionally calls `locate-existing-def` against
the on-disk source to provide the exact line range to rewrite, and renders
a `shape-drift-delta-bullets` summary citing only-in-canvas, only-in-code,
and type-mismatched fields. Both confirmed against
`web.views.cytoscape/type/CytoscapeGraph` in the smoke test.

**One carry-forward.** The `discipline-prose` is still branched via `if
shape-drift?` inside `build-context` (lines 213–222 of `drift_close.clj`)
rather than a fourth `defmulti`. The branch is correct — shape-drift's
"rewrite in place; do NOT append" lands in discipline, missing-impl's "add
at end" lands in discipline — but the asymmetry with the four
`defmulti`-keyed sections is mild ugliness. Acceptable for ship; flag
in §6.

### cold-write

Wraps a canvas module's projections with conventions + matching-pattern
neighbors. Not exercised end-to-end in any trial run; unit tests cover
the rendering path. Cold-write becomes more interesting once Phase 8
introduces automated dispatch — the human trial of cold-write is the
implicit canvas-author workflow that Phase 6 + 7's canvas/distributed/*
trial *was*, just at a higher altitude.

**Verdict:** Layer B carries one polymorphic scenario (drift-close)
whose three branches behave distinctly per drift kind, plus one
batched-multi-projection scenario (cold-write). The kind-polymorphism
defect that Phase 7's trial named is closed; the `discipline-prose`
asymmetry is a hygiene item.

---

## 4. Trial-run loop outcome

The Phase 7 Sprint 4 trial run found three substantive defects in the
instruction loop. The trial doc
(`doc/plans/2026-05-27-instruction-trial-run-findings.md`) names them
crisply; each maps to a Phase 7.5 sprint.

| Trial defect | Phase 7.5 sprint | Verified closed via |
|---|---|---|
| Layer A coverage gap — `:canvas/getter` / `:handler` / `:checker` / `:operation` returned `"no project-lens projection registered"`; the Sprint 1 canonical example couldn't be exercised at all | Sprint 1 | `bin/fukan eval '(:target (spec "distributed.cluster/get_self_role"))'` returns `{:symbol "get-self-role"}`; coverage regression test asserts no emitted role is silently uncovered |
| Layer A invariant projection emitted illegal Clojure — symbol derived from `holds-that` prose verbatim (`(defn leader holds majority for its term …)`) | Sprint 2 | `bin/fukan eval '(:target (spec "distributed.cluster/MajorityRequiredForLeadership"))'` returns `{:symbol "majority-required-for-leadership"}` — legal kebab-case |
| Layer B drift-close was monomorphic — hard-coded "add at end" framing wrong for shape-drift findings | Sprint 3 | shape-drift render against `web.views.cytoscape/type/CytoscapeGraph` returns "rewrite the existing def in place — do **not** append a duplicate def" prose plus line-range hint |

All three Phase 7.5 sprints carried a definition-of-done that re-traced
the original failing case end-to-end. The Sprint 4 trial commits
(`bbd2432`, `76945c3`, `0fce24d`) landed the three canonical closures —
`Cluster` shape-rewrite, `MajorityRequiredForLeadership` predicate stub,
`get_entry` function stub — verifying each defect's resolution in real
canvas/code material before Phase 7.5 began. Each is now a closure example
the next trial can recheck.

The iter-2 `MajorityRequiredForLeadership` case is the cleanest
verification: pre-7.5 the projection emitted `(defn leader holds majority
for its term …)` — illegal Clojure — and the `expected-symbol` in the
drift finding carried the same prose. No code could ever close that
finding. Post-7.5: the projection emits `(defn
majority-required-for-leadership [model] …)`, the drift comparator's
expected symbol aligns (Sprint 2 changed the primitive `:label` to
`entity-name` on both sides), and the stub-commit `76945c3` actually
closed the finding in the live model.

**Verdict:** All three substantive defects close cleanly. The fourth
trial concern — that `Agent` tool dispatch couldn't be tested in-harness
(see §6) — remains open as a verification-fidelity limitation; it's not
a substrate gap.

---

## 5. Workflow fixes

### `.lsp/config.edn` mirror

The trial-run surfaced clojure-lsp re-emitting `unused-public-var` info
warnings on namespaces clj-kondo already exempted — most painfully
`fukan.distributed.{cluster,election,log}` (intentional trial-run
scaffolding), `fukan.agent.{api,system}` (SCI sandbox surface), and every
`fukan.canvas.{project,instruct}.*` namespace registered via dynamic
discovery. clojure-lsp doesn't honour clj-kondo's `:config-in-ns` per-ns
config; the noise persists even when clj-kondo is silent.

Fix (commit `6063a448`): `.lsp/config.edn` mirrors clj-kondo's
`:exclude-when-meta #{:export}` plus the namespace exemption list
verbatim. Header comment in the file flags the mirroring requirement
explicitly so future namespace additions cross-update.

Verified by inspection. The two files now have parallel exemption
surfaces; the maintenance burden is the cross-update requirement, which
is the smaller of two evils versus tolerating the noise.

### `bin/fukan reset` reload of dynamic-load loaders

Symptom (caught during Phase 7.5 Sprint 1 development): after adding
`src/fukan/canvas/project/clojure/getter_to_defn.clj`, running
`bin/fukan reset` did NOT pick it up — the multimethod `defmethod`
inside the new file never ran. The reset cycle was reloading every
`canvas.*` port namespace via `require :reload` (so canvas spec edits
were picked up) but the dynamic-load loader namespaces
(`fukan.canvas.project.clojure`, `fukan.canvas.lens.registry`,
`fukan.canvas.instruct.registry`) — which carry the `:require` lists
that pull projection/lens/scenario files into the multimethod — were
NOT reloaded.

Fix (commit `915cb952`): `src/fukan/agent/system.clj` adds an explicit
`dynamic-load-loaders` registry and a Step 2 in `reset` that runs
`require :reload` on each. Verified by inspection of the current
`reset` body:

```clojure
(def ^:private dynamic-load-loaders
  '[fukan.canvas.project.clojure
    fukan.canvas.lens.registry
    fukan.canvas.instruct.registry])
```

**Edge cases that remain.** `defmulti` has `defonce` semantics — once a
`defmethod` is registered, removing the source file does NOT unregister
the method until JVM restart. So `(reset)` correctly *adds* new
projection files but cannot *remove* a deleted one until the daemon
restarts. This is documented in the `reset` docstring's "Heavier than
`refresh`" note and is the right design — `(reset)` is meant to
approximate "minus the server bounce," not replace it entirely.

---

## 6. Defects surfaced but NOT closed

Compiled from the trial-run findings doc plus each sprint's known
limitations. None block Phase 8.

1. **Handler projection's input-shape is generic.** The `(on <event-ref>
   …)` clause holds an event *reference*, not the payload's structural
   shape. `clojure/handler-to-defn` emits a `:cat <event-ref> :any`
   Malli arity in lieu of a dereferenced payload shape. To get the
   payload shape, the projection would need a resolution step that
   walks from the event ref back through the canvas db to the event's
   declared payload. Phase 8 candidate; documented in
   `doc/plans/2026-05-27-project-lens-design.md` § handler-to-defn.

2. **`Agent` tool dispatch fidelity unverified in-harness.** The Phase
   7 Sprint 4 trial brief specified subagent dispatch via the `Agent`
   tool; the trial harness didn't expose that tool, so the
   implementing-LLM step ran in-session. The instruction-quality
   observations transfer, but the "did a cold subagent understand the
   instruction from zero prior context" axis stays underexercised.
   Phase 8 verification work — and a Phase 8 trial-run is the right
   place to test it, not a Phase 7 substrate concern.

3. **drift-close `discipline-prose` is `if`-branched, not
   `defmulti`-keyed.** The four major sections (frame / gap / neighbors
   / output) are kind-polymorphic via `defmulti`; the discipline-prose
   branch sits inside `build-context` as a 6-line `if shape-drift?`.
   Behaviour-correct; symmetry-mild-ugly. Carry into Phase 8 cleanup
   only if a third drift kind lands.

4. **Type-mismatch shape-drift branch verified in unit tests only,
   not end-to-end.** The `shape-drift-delta-bullets` renderer covers
   `only-in-canvas`, `only-in-code`, and `type-mismatch` shapes; live
   canvas drift only exercises the first two (the cytoscape
   snake↔camel boundaries). A type-mismatch case ran in the
   `drift_close_test.clj` synthetic fixtures. Phase 8's first
   shape-drift trial will be the end-to-end probe.

5. **Cold-write scenario not exercised end-to-end.** Unit tests cover
   the renderer; no Phase 7 trial ran cold-write against an empty
   `src/fukan/<module>/` target. Phase 8 trial-run candidate.

6. **Invariants might project to property tests, not predicates.**
   Phase 7.5 Sprint 2's symbol-sanitisation fix made invariant
   projections produce *legal* Clojure (`(defn
   majority-required-for-leadership [model] …)`); whether a `defn`
   stub is the *right* code-side home for an invariant remains an
   open Phase 8 design question. The trial doc raises this as
   recommendation #4 and the doc explicitly defers it. Out of scope
   for Phase 7 + 7.5.

7. **Event projection not exercised end-to-end.** Unit tests cover
   `event-to-schema`; no Phase 7 trial drove an event drift through
   the full loop. Phase 8 candidate.

---

## 7. Decision

**Outcome (2): Phase 7 + 7.5 ship; concerns carry forward to Phase 8.**

The Phase 7 plan named three outcomes:

1. Loop works → Phase 8 (automation) can begin.
2. Works with caveats → Phase 7.5.
3. Loop didn't close gaps reliably → reset.

Phase 7 alone met outcome 2 — three substantive defects (Layer A
coverage, illegal invariant symbol, monomorphic Layer B); Phase 7.5
closed all three within the 7.5 cleanup window the plan named for
exactly this kind of cleanup. Verifying Phase 7 + 7.5 together as one
body of work:

- All three substantive defects close cleanly. The Phase 7.5
  definition-of-done re-runs each defect's canonical case end-to-end
  in the live model; this verification confirmed each via smoke test.
- The 9 Layer A projections cover the full canvas-source emission
  surface; the coverage regression test asserts the surface stays
  covered as canvas-source grows.
- Layer B's drift-close is now kind-polymorphic via `defmulti`; the
  three branches render distinct framings against the smoke-tested
  live model.
- The two workflow fixes (LSP mirror, reset loader reload) eliminate
  the operational friction the trial-run surfaced.
- 989 tests pass, 0 failures; `clj-kondo` on `src/` is clean (0
  errors, 1 noise warning about an unused require in `vocab/event.clj`
  that pre-dates Phase 7). The 7 errors in `test/` are intentional
  fixtures for testing error-handling paths.

The carried-forward concerns in §6 are real but small and well-bounded:
one Phase 8 design conversation (invariants→property-tests), several
Phase 8 trial-run candidates (handler payload shape, cold-write,
event projection, agent dispatch fidelity), and one cosmetic cleanup
(`discipline-prose` defmulti). None require reopening Phase 7's
substrate; none block Phase 8's automation goal.

The decision is *outcome 2 — works with caveats* rather than outcome 1
because (a) the trial-run's instruction-quality observations were
collected with same-session implementing-LLM substitution (the trial
doc is honest about this), not real subagent dispatch — verification
fidelity is genuinely partial; and (b) three out of nine projections
ship with end-to-end exercise (function, getter, type-record);
the other six rely on unit tests + the synthetic shape coverage of the
regression guard. Phase 8's trial-run is the right time to exercise
the rest.

---

## 8. Phase 8 implications

Phase 8's strategic frame, per the Phase 7 plan: *close the canvas-author
human-in-the-loop step that Phase 7 keeps explicit*. Drift findings
auto-trigger instruction generation; instructions auto-dispatch to the
implementing LLM; verification auto-confirms closure (or feeds back
into a second iteration). The substrate Phase 7 + 7.5 ship is the input
to that automation; Phase 8's work is the dispatch + verification +
retry control loop, not new projection or scenario work.

The carried-forward items in §6 map to Phase 8 opening tasks:

- **Trial-run with real `Agent` dispatch.** Item #2. The single
  highest-fidelity test of the loop. Phase 8 Sprint 1 candidate.
- **Exercise the unexercised projections end-to-end.** Items #4, #5,
  #7. One trial run per remaining canvas-role would settle the loop's
  performance across the full emission surface.
- **Resolve the invariant→property-test design question.** Item #6.
  The trial doc names this as the load-bearing long-term framing —
  invariants are essential declarative logic whose natural code
  counterpart is a property obligation, not a `defn` stub. Phase 8's
  design conversation. Touches both Layer A (a second invariant
  projection targeting `test/.../properties.clj`) and the drift
  comparator (an invariant-aware path that allows the canvas to point
  at a test-side counterpart rather than an implementation-side one).
- **Multi-instruction batching.** Phase 7 ships one instruction per
  dispatch by design. Phase 8 candidate — useful once automation
  removes the human review step that benefited from per-instruction
  cadence.
- **Refactor scenario.** The third scenario the Phase 7 plan named,
  deferred from Phase 7 because it depends on delta-instruction
  shape Phase 8 will need anyway.
- **Handler payload-shape resolution.** Item #1. Touches Layer A;
  needs a canvas-db resolution step. Small but non-trivial.

The deeper Phase 8+ conversation the trial-run findings doc names —
*canvas is the intersection between LLM and human; fukan tells
implementing LLM what to write in `src/`* — is now substrate-ready.
Phase 7's two-layer architecture (project-lens + scenario) is exactly
the mechanism that makes the canvas a load-bearing design surface for
human-LLM collaboration: the canvas-author drafts the design at canvas
altitude, Layer A produces the deterministic low-level spec, Layer B
frames it for the implementing LLM's situation, and the implementing
LLM writes the code. The Phase 8 work is to close the dispatch loop
around that substrate.

---

## Appendix: Phase 7 + 7.5 artifact inventory

| Artifact | Phase | Description |
|---|---|---|
| `doc/plans/2026-05-27-canvas-substrate-phase-7.md` | 7 | The Phase 7 plan |
| `doc/plans/2026-05-27-project-lens-design.md` | 7 S1 | Layer A design |
| `doc/plans/2026-05-27-scenario-handoff-design.md` | 7 S1 | Layer B + handoff design |
| `doc/plans/2026-05-27-canvas-substrate-phase-7-5.md` | 7.5 | Phase 7.5 plan |
| `doc/plans/2026-05-27-instruction-trial-run-findings.md` | 7 S4 | Trial-run evidence |
| `doc/plans/2026-05-28-phase-7-verification.md` | 7 S5 | This document |
| `src/fukan/canvas/project/{core,registry,render}.clj` | 7 S3 | Layer A substrate |
| `src/fukan/canvas/project/clojure.clj` | 7 S3 | Clojure-lens loader |
| `src/fukan/canvas/project/clojure/value_to_def.clj` | 7 S3 | |
| `src/fukan/canvas/project/clojure/type_to_malli.clj` | 7 S3 | |
| `src/fukan/canvas/project/clojure/event_to_schema.clj` | 7 S3 | |
| `src/fukan/canvas/project/clojure/function_to_defn.clj` | 7 S3 | |
| `src/fukan/canvas/project/clojure/invariant_to_predicate.clj` | 7 S3 + 7.5 S2 | Symbol from entity-name post-7.5 |
| `src/fukan/canvas/project/clojure/rule_to_predicate.clj` | 7 S3 | |
| `src/fukan/canvas/project/clojure/getter_to_defn.clj` | 7.5 S1 | New |
| `src/fukan/canvas/project/clojure/checker_to_defn.clj` | 7.5 S1 | New |
| `src/fukan/canvas/project/clojure/handler_to_defn.clj` | 7.5 S1 | New |
| `src/fukan/canvas/instruct/{core,registry,render}.clj` | 7 S3 | Layer B substrate |
| `src/fukan/canvas/instruct/drift_close.clj` | 7 S3 + 7.5 S3 | Kind-polymorphic post-7.5 |
| `src/fukan/canvas/instruct/cold_write.clj` | 7 S3 | |
| `src/fukan/canvas/inspect/drift.clj` | 7 S2 | Compound-shape comparator + scoped filter |
| `src/fukan/canvas/projection/canvas_source.clj` | 7.5 S2 | Invariant label = entity-name |
| `src/fukan/target/clojure/address.clj` | 7 S2 + 7.5 S2 | Lifted helpers + invariant address symmetry |
| `src/fukan/target/clojure/projector.clj` | 7 S2 | `signature-for` public |
| `src/fukan/agent/api.clj` | 7 S3 | `(spec)` / `(instruct)` / `(canvas-projections)` / `(canvas-scenarios)` |
| `src/fukan/agent/system.clj` | post-7 | `(reset)` reloads dynamic-load loaders |
| `.claude/agents/fukan-architect.md` | 7 S4 | Phase D dispatch + Agent/Read grant |
| `doc/canvas-authoring-system-prompt.md` | 7 S4 | Phase D — Instruct + Dispatch |
| `AGENTS.md` | 7 S4 | Trust-tier instruction surface (Layer A + Layer B) |
| `.lsp/config.edn` | post-7 | Mirror of clj-kondo exemptions |
| `test/fukan/canvas/project/coverage_test.clj` | 7.5 S1 | Coverage regression guard |
| `test/fukan/canvas/project/clojure/*_test.clj` | 7 S3 + 7.5 S1 | 9 projection test files |
| `test/fukan/canvas/instruct/*_test.clj` | 7 S3 + 7.5 S3 | 6 scenario test files |
| `src/fukan/distributed/cluster.clj` | 7 S4 | Trial closures: `Cluster` rewrite + `MajorityRequiredForLeadership` |
| `src/fukan/distributed/log.clj` | 7 S4 | Trial closure: `get_entry` |

**Phase 7 + 7.5 commit count:** ~33 commits — 1 Phase 7 plan + 2 Phase 7 S1
design docs + 3 Phase 7 S2 hardening + Layer A substrate + 6 Phase 7 S3
projections + Layer B substrate + 2 Phase 7 S3 scenarios + agent API
integration + Phase 7 S4 architect + system prompt + AGENTS.md + 3 Phase 7
S4 trial closures + trial-run findings + Phase 7.5 plan + 3 Phase 7.5 S1
projections + coverage regression test + 3 Phase 7.5 S2 commits + 2 Phase
7.5 S3 commits + LSP mirror + reset fix + this verification doc.

**Substrate state at Phase 7 + 7.5 close.** Layer A: 9 Clojure-lens
projections covering the full canvas-source emission surface, guarded by
a regression test. Layer B: 2 scenarios — drift-close (kind-polymorphic
via `defmulti` on drift kind) and cold-write. Agent API: `(spec)`,
`(instruct)`, `(canvas-projections)`, `(canvas-scenarios)` registered
under `:trust` with `:severity :info`. `fukan-architect` agent extended
with `Agent` + `Read` tool grants and a Phase D — Instruct + Dispatch
mode. The canvas-authoring system prompt and AGENTS.md primer carry
matching surface descriptions. Workflow: `(reset)` now reloads
dynamic-load loaders so new projection/scenario files in
`src/fukan/canvas/{project,lens,instruct}/*` register without a JVM
restart; `.lsp/config.edn` mirrors clj-kondo's exemption surface so
clojure-lsp doesn't re-emit suppressed warnings. Phase 6's trust-tier
helpers (integrity, coverage, drift) and Phase 5's weigh-tier lenses
(patterns, consistency, tar-pit) all carry forward unchanged.
