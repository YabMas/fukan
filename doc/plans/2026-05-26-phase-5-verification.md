# Phase 5 Verification Report — Canvas as Thinking-Enhancing Tool

**Date:** 2026-05-27
**Status:** Complete
**Decision:** (1) The thinking-enhancing tool works → Phase 6 can begin

---

## Strategic frame

Phase 5 set out to make fukan's canvas *the* load-bearing intersection between LLM and
human design collaboration, with feedback signals on design integrity and design strength
as the deliverable. Phases 1–4 had built the substrate, the vocabulary, and the analysis
pipeline; what was still flat was the **author's experience of using it**. Phase 5's
strategic frame was the user reframe: *canvas is the intersection between LLM and human;
maximum feedback on the integrity and strength of design; the best thinking-enhancing
tool we can make.*

The 2026-05-27 mid-phase reframe pushed the design one altitude higher. The original plan
described "weigh-tier helpers" as a fixed set of pure-fn analyzers parallel to the trust
tier. The reframe — *"discovering what works and doesn't in terms of agent interaction
will be an ongoing effort... build the integration pluggable from the start. Prompts
should be swappable, but also the different modes of thinking/surveying"* — promoted the
weigh tier to a **pluggable lens substrate**. Lenses are namespaces declaring a contract;
the substrate auto-discovers them; new thinking modes are drop-a-file additions. The plan
absorbed the reframe mid-sprint and Sprint 3 built the substrate accordingly. This shift
is the most consequential design move of the phase — see Section 3.

The product surface of Phase 5 is the REPL, `bin/fukan eval`, the canvas-authoring
system prompt, the extended `fukan-architect` subagent, and the three starter lenses.
No browser UI shipped (deferred to Phase 6); no code-generation work shipped (deferred to
Phase 7+). Phase 5 ships the loop, not the surface.

---

## 1. What was attempted vs. built

Five sprints, ~25 commits clustered between `xmmlwrxv` (Phase 5 plan landing) and `zomszkml`
(trial-run findings doc). Sprint-by-sprint:

### Sprint 1 — Feedback inventory + workflow design (Tasks 1–2)

**Attempted:** Two design docs settling the feedback partition (trust vs. weigh) and the
co-author workflow shape before signal/lens implementation began. Both pause-points
required user review.

**Built:**

- **Task 1** — `doc/plans/2026-05-26-feedback-signals-design.md`. The six-category
  feedback inventory ranked by usefulness for the LLM authoring loop; the four-to-five
  Phase 5 priorities picked (integrity + coverage as trust; patterns + consistency
  as weigh; tar-pit added later via the reframe).
- **Task 2** — `doc/plans/2026-05-26-coauthor-workflow-design.md`. The minimum viable
  loop, the integration point (`bin/fukan eval`), the architect-explorer prompt
  evolution, the discipline failure-modes to guard against, and the Sprint 4 trial-run
  target (`canvas/distributed/` — both Sprint 1 design agents independently picked it).
- **2026-05-27 reframe (mid-phase)** — The pluggable lens substrate was added to the
  plan via three plan-edit commits (`wryunpmt`, `lnsoqxyw`, `nnuvnlzk`). The reframe
  reshaped Sprint 3 from "weigh-tier helpers as pure fns" to "lens substrate + three
  starter lenses validating two lens shapes."

Commits: `nnuvnlzk` (Sprint 1 lands + plan amendment).

### Sprint 2 — Pre-implementation hardening (Tasks 3–5)

Three small additive changes that unblocked accurate signal output in Sprint 3.

- **Task 3 (`oxkutton`)** — promoted the name+role disambiguation convention. Resolves
  the 31 intra-module duplicate names in `validation/*` (paired rule + invariant)
  carried over from Phase 4 as an open design question. The substrate already
  disambiguated by `(name, role)`; Sprint 2 made the idiom explicit in CLAUDE.md +
  AGENTS.md + the canvas-source docstring and added a test asserting same-name
  distinct-role entities co-exist correctly.
- **Task 4 (`zyuorqts`)** — `(emits ...)` form on `function`. Closes a Phase 4 Sprint 3
  finding: command functions emitting events had no first-class structural form.
  Symmetric with `triggers`; `:emits` schema entry with `:db.cardinality/many` and
  `:db.type/ref`; backfilled across `demo/event-driven/`.
- **Task 5 (`svpnktws`)** — `:type/fields` derived substrate attribute. The consistency
  lens needs `(name, type)` queryable per field of every record-shaped Type. Sprint 2
  added the derivation in `:type-record`'s `->datoms` branch before Sprint 3's
  consistency lens needed it.
- **Bonus (`mxnnloqu`, `pxtyxqts`)** — clj-kondo hooks/excludes silence canvas-DSL
  noise; `missing-body-in-when` disabled project-wide (canvas's `rule` lift uses
  `(when ...)` as its trigger form, not Clojure's). 934 unresolved-symbol warnings →
  0. This was not in the plan but made authoring-session feedback navigable.

### Sprint 3 — Build the feedback signals (Tasks 6–10)

The substantive sprint. Two trust-tier helpers; the lens substrate + three starter
lenses; a substrate bug + 5 canvas gaps fixed mid-sprint via a fixup cycle that
itself validated the loop.

- **Task 6 (`trxzykno`)** — `canvas.inspect.integrity` — trust tier. Cross-reference
  resolution + trigger/emit coherence + cross-module ref resolution against the
  segment-matching convention. Returns structured violations with source location and
  stable-id. Run against fukan's live canvas surfaced a real substrate bug and five
  canvas gaps:
  - **`vxqnyutq`** — substrate bug fix: ref-typed card-many attrs (`:triggers`, `:emits`)
    lost eid→uuid translation across the canvas-source merge boundary, causing the
    integrity helper to report false-positives. Fixed in the merge code.
  - **`uroxxtyp`, `nnvuxzwv`, `zyklzvsz`** — five missing `:model/*` declarations in
    `canvas/model/spec.clj` (`:model/PredicateRegistration`, `:model/ArtifactSubCase`,
    `:model/Handler`, `:model/EntityPath`, `:model/EntityDetails`). Real canvas content
    gaps surfaced by the new helper.
  - This fixup cycle is itself the strongest single piece of evidence that the trust
    tier produces decision-ready output: a fresh helper landed, ran against the live
    canvas, surfaced both a substrate bug *and* concrete content gaps, all fixed
    same-session.
- **Task 7 (`rsxruknotlwo`, `mvnonuwm`)** — `canvas.inspect.coverage` — trust tier with
  the severity ladder (`:error` / `:warning` / `:info`). Orphans, unreached entities,
  dead exports, modules without `exports`, rules with no trigger function, events
  with no handler.
- **Task 7 refinement (`uuwulsqz`)** — orphan-check role exemption. The first run
  surfaced that mechanism-driven roles (`invariant`, `checker`, `getter`, `rule`,
  `event`) are structurally never expected to have incoming `:references` — flagging
  them as orphans was noise. The refinement narrowed orphan detection to roles where
  incoming-reference absence is a real signal. After refinement: 335 findings against
  the live canvas, severity-tagged appropriately.
- **Task 8 (`ksxxzuto`, `lrvonxylyztv`, `wqzvurwt`)** — the lens substrate
  (`core` + `registry` + `survey`) and the first lens (`:patterns`). Substrate is
  three small namespaces; `registry/all-lenses` auto-discovers `lens` vars from any
  `fukan.canvas.lens.*` namespace; `survey/run canvas-db lens-ids opts` dispatches
  and synthesizes. The patterns lens clusters Affordances by structural shape
  signature; clusters of ≥3 flag as candidate lift patterns.
- **Task 9 (`yokonrrs`)** — consistency lens. Naming style + field-type symmetry +
  sister-module symmetry. **Zero substrate changes required to drop in.**
- **Task 10 (`qwlsmnmv`, `kutpnkqn`)** — tar-pit lens (theoretical lens, Moseley &
  Marks 2006). Prompt-fragment frames the canvas through essential vs. accidental
  complexity; minimal `compute` extracts candidate-state and candidate-behavior slices.
  **Zero substrate changes required to accommodate a theoretical (non-structural) lens.**

### Sprint 4 — Integration + trial run (Tasks 11–12)

- **Task 11 (`sytoonrt`, `plovsuot`, `mtuzoxwv`)** — extended the `fukan-architect`
  agent with tier awareness + `survey design improvements` mode + reference to the
  permanent canvas-authoring system prompt. Three artifacts:
  - `doc/canvas-authoring-system-prompt.md` — the permanent versioned prompt the
    Phase 2 architect-explorer became.
  - `.claude/agents/fukan-architect.md` — extended with lens-driven survey + tier
    discipline.
  - `AGENTS.md` — trust/weigh tier model + lens substrate primer for any LLM.
- **Task 12 (`rpklzzsz`, `qmxytpqp`, `txplxoyq`, `zomszkml`)** — trial run on
  `canvas/distributed/`. Three modules (`cluster.clj`, `election.clj`, `log.clj`) of
  Raft-flavored leader election authored with the full loop:
  - Used existing vocabulary throughout (no new lifts invented; patterns lens confirmed
    this — all clusters labelled "already lifted").
  - Integrity helper caught a cross-module-ref-convention misread in 30 seconds,
    converting what would have been a hidden bug at 36 sites into a single global
    replace.
  - Tar-Pit framing caught a near-miss in cluster.clj (the `voted_for` field that
    belongs to election protocol, not cluster state).
  - Consistency lens's sister-symmetry finding was technically a false-positive
    against design intent but forced explicit articulation of *why* the three modules
    diverge structurally — itself a useful artifact.
- **Findings doc (`zomszkml`)** — `doc/plans/2026-05-27-trial-run-findings.md`. The
  load-bearing evidence for the phase outcome.

### Sprint 5 — Verification (Task 13)

This document.

---

## 2. Did the trust-tier signals produce useful output?

The trust tier is the load-bearing claim for "decision-ready output." Each helper was run
against fukan's live canvas; each produced findings the author acted on.

### `inspect/integrity` — cross-reference resolution

**What it found at first run against live canvas:**

- A substrate bug: ref-typed card-many attributes (`:triggers`, `:emits`) lost eid→uuid
  translation across the canvas-source merge boundary. The helper's findings were
  false-positives until the bug was fixed — but the false-positives were structurally
  coherent enough to point straight at the bug. **The helper surfaced its own
  substrate dependency.** Fixed in `vxqnyutq`.
- After the substrate fix: five real cross-module reference gaps in `canvas/model/spec.clj`
  — types that other modules referenced but spec.clj never declared
  (`:model/PredicateRegistration`, `:model/ArtifactSubCase`, `:model/Handler`,
  `:model/EntityPath`, `:model/EntityDetails`). Three commits closed them.

**Trial-run encounter:** Round 2 of `canvas/distributed/`, integrity returned **36
`:severity :error` findings** when election.clj used dotted cross-module refs
(`:distributed.cluster/NodeId`) instead of the single-segment convention
(`:cluster/NodeId`). Every offender named both source and target. One global replace
across the file fixed all 36. **The signal was instant, precise, and actionable** — see
Section 5 for the documentation-friction this also surfaced.

**Verdict:** Decision-ready. The helper surfaced a substrate bug, content gaps, and an
edit-time convention error within its first three sessions of use. The output requires
zero interpretation: a broken reference is a broken reference.

### `inspect/coverage` — orphans, dead exports, behavioral coverage

**What it found at first run:** noise. Mechanism-driven roles (`invariant`, `checker`,
`getter`, `rule`, `event`) were never expected to have incoming `:references` — these
roles are *consumed by mechanisms*, not by name. Flagging them as orphans was
structurally meaningless. The refinement (`uuwulsqz`) narrowed orphan detection to roles
where absent-incoming-reference is a real signal.

**After refinement:** 335 findings against the live canvas, severity-tagged
appropriately. The severity ladder (`:error` / `:warning` / `:info`) lets callers filter
without losing context.

**Trial-run encounter:** Coverage warnings against `canvas/distributed/` were almost
entirely expected and benign — orphaned handlers (no synchronous caller is structurally
correct for event-driven), exported leaf types (the outermost module's exports have no
reach-target by construction), pure read accessors with no caller before projection lands.
**The severity ladder was exactly right** — errors caught real issues, warnings were
correctly non-blocking.

**Verdict:** Decision-ready, with the documented caveat that warnings dominate the output
volume for in-flight authoring. The refinement loop itself (mechanism-driven exemption)
is the more interesting evidence — when a trust-tier helper produces noise, it gets
narrowed, not reinterpreted as observation. The trust/weigh boundary held.

### Trust tier overall

Both helpers shipped against the substrate, surfaced real bugs and content gaps within
hours of landing, and produced output the author acted on without interpretation. The
fixup cycle Task 6 → substrate bug fix → 5 model.spec declarations is the strongest
single piece of evidence for the trust tier's value: a fresh helper detected a substrate
bug *and* five canvas content gaps in one session, and same-session fixes closed all of
them. That is the loop working as designed.

---

## 3. Did the lens substrate work?

**The load-bearing pluggability claim.** Phase 5's substrate-design bet was that adding
a new lens should be a drop-a-file operation: the substrate stays small (three
namespaces); lenses are pure data + functions adhering to a contract; the registry
auto-discovers them.

The test: ship the substrate alongside the first lens, then add lens 2 (structural,
different shape) and lens 3 (theoretical, non-structural) and observe whether the
substrate had to bend.

### Lens 1 — `:patterns` (`lrvonxylyztv`)

Shipped alongside the substrate (`ksxxzuto`). Clusters Affordances by structural shape
signature; surfaces clusters of ≥3 as candidate lift patterns. The substrate's
shape (`core` + `registry` + `survey`) was sized to this lens's needs.

### Lens 2 — `:consistency` (`yokonrrs`)

Different structural shape from patterns: scans across modules looking at naming style,
field-type symmetry across records sharing field names, and sister-module structural
symmetry. Uses `:type/fields` (the Sprint 2 derived attribute) for the field-level work.

**Substrate diff between lens 1 and lens 2:** zero. Lens 2 dropped in as a single file
declaring a `lens` var. The registry picked it up automatically. The survey dispatcher
invoked its `compute` and `render` without any per-lens special-casing. **The
drop-a-file promise held for the second structural lens.**

### Lens 3 — `:tar-pit` (`qwlsmnmv`)

A theoretical lens, fundamentally different shape from the first two: the load-bearing
artifact is the **prompt-fragment** (framing the canvas through essential vs. accidental
complexity per Moseley & Marks 2006), not the compute fn. The compute fn is minimal —
it extracts candidate-state and candidate-behavior slices for the LLM to interpret.
Interpretation is LLM-driven, not algorithmic.

**Substrate diff between lens 2 and lens 3:** zero. The contract (`:id`, `:description`,
`:prompt-fragment`, optional `:compute`, `:render`) already had the right shape — the
optional `:compute` was a deliberate substrate choice in `core.clj` to accommodate
theoretical lenses. The substrate validated the lens, the registry discovered it, the
survey assembled its rendered output alongside structural lenses with no special-casing.
**The drop-a-file promise held for the theoretical lens shape.**

### Verdict

The lens substrate worked exactly as designed. Two additional lenses across two
fundamentally different shapes (structural + theoretical) landed with **zero substrate
changes**. The substrate's three namespaces (`core` + `registry` + `survey`) are
together ~150 lines; lenses are 50–150 lines each; the contract is data, not code.
Phase 6+ can add as many lenses as use-evidence supports without revisiting the
substrate.

This is the most important architectural artifact of Phase 5. It is what makes "freely
experiment with thinking modes" tractable rather than an ongoing refactor.

---

## 4. Did the LLM co-author trial run succeed?

The trial run authored `canvas/distributed/` (three modules: cluster, election, log)
through the extended `fukan-architect` agent + canvas-authoring system prompt + full
feedback loop. The detailed findings live in
`doc/plans/2026-05-27-trial-run-findings.md`.

The headline judgment: **yes, the loop produced design-altitude reflexes that would not
have surfaced without it.** Three concrete moments where signal improved design:

1. **The "reach for existing vocab first" anchor caught a near-miss in cluster.clj.**
   The author was about to add a `voted_for` field directly to `Cluster`. The Tar-Pit
   framing — essential vs. accidental — surfaced that voting is election-protocol state,
   not cluster's essential state. The field moved to the election module's handler
   descriptions where it belongs. This is the kind of altitude-level reflex the loop
   exists to produce.
2. **The cross-module ref convention was caught by integrity on the first reflect.**
   36 dotted-form refs against the single-segment convention. The helper named every
   offender with source + target. One global replace fixed all 36 in one step. Without
   the trust-tier signal the file would have shipped with hidden broken refs.
3. **The consistency lens's sister-symmetry finding forced explicit articulation.**
   The lens flagged that cluster/election/log have asymmetric shapes (cluster has
   getters, election has events/handlers, log has both). The finding was technically a
   false-positive against the design intent — the three modules legitimately serve
   different concerns. But the *act of resolving the finding* produced a sharper
   understanding of what each module is for. The lens was wrong about the symptom and
   right about prompting the inquiry.

The patterns lens contributed in an unusual mode: **it confirmed design discipline rather
than directing it.** The author expected the lens to surface candidate lifts; instead it
told the author "every shape you used is already lifted." That is reassurance, not
direction — but reassurance is a real signal when the loop's job is to tell you "no,
don't invent yet."

No new lifts were invented during the trial. Every shape the design needed was covered
by existing vocabulary. The rule-of-three threshold did not trigger anywhere in the
distributed subsystem. This is the loop working exactly as designed: discipline preserved,
vocabulary not source-primed, design articulated through the existing surface.

**Verdict:** The loop succeeded. Real design improvements occurred at edit time that
would not have occurred without the signals. The friction items are real (see Section 5)
but the central claim of Phase 5 — *canvas authoring with the loop is a thinking-enhanced
activity* — is borne out by the trial run.

---

## 5. What did the trial run reveal about gaps?

Six items surfaced as Phase 6 candidates. Ranked roughly by impact × tractability.

1. **First-class `bin/fukan reset` (or self-healing `(refresh)` for new files).** The
   single biggest friction point in the trial. `(refresh)` does not pick up newly-loaded
   namespaces — adding a new canvas file requires a full daemon restart (`pkill` from
   shell, ~10–20s), breaking flow. `(reset)` exists in the REPL but is not exposed
   through `bin/fukan`. Low cost, high relief.

2. **Loud documentation on the cross-module ref convention.** The single-segment rule
   (`:cluster/NodeId`, not `:distributed.cluster/NodeId`) is one line in CLAUDE.md;
   the demos under `demo/event-driven/*` use the **dotted** form (because they were
   authored before the integrity helper existed and are not on the canvas-source
   registry). An authoring agent learning from the demos will be wrong. Either (a) add
   loud callout blocks to `core/shape.md` and every vocab EXAMPLES.md, or (b) widen
   the resolver to accept both dotted and segment forms. The latter is more flexible;
   the former is cheaper. Either way, the demos/canvas convention divergence needs
   reconciling.

3. **Survey scoping.** `(survey)` against the full canvas produces 200+ KB of output
   that's mostly canvas-wide noise from outside the in-flight authoring scope. There
   is no first-class `(survey :scope <prefix-or-module>)` affordance. Authors filter
   with ad-hoc `re-find`. The granularity is wrong by default for focused authoring.

4. **Auto-discover canvas files.** Every new canvas file requires two edits to
   `canvas-source/canvas-namespaces` (require + registry vector). Mechanical but
   easy to miss; classpath scanning of `canvas/**/*.clj` would eliminate the dual-edit.

5. **A `:coupling` or `:dependency` lens.** The trial author noticed they were holding
   the cluster/election/log dependency graph entirely in their head. The patterns and
   consistency lenses look at shape, not at the dependency wiring. An author asking
   "did I get the layering right?" has no lens for it. A natural Phase 6+ lens
   candidate.

6. **Coverage's warning-dominated output for in-flight authoring.** Outermost modules
   produce a predictable wash of `exported-but-unreferenced` and `pure-read-without-caller`
   warnings that are structurally inevitable in a build-up scenario. Not a bug — but
   in-flight authoring would benefit from a "filter expected leaf warnings" affordance
   or per-context defaults.

Items 1, 2, and 4 are quick (hours, not sessions). Items 3 and 5 are larger design
conversations. Item 6 is a small refinement.

---

## 6. Decision

**Outcome (1): The thinking-enhancing tool works → Phase 6 can begin.**

The Phase 5 plan named three possible outcomes:

1. Thinking-enhancing tool works → Phase 6 (browser UI etc.) can begin.
2. Works with caveats → Phase 5.5 to close caveats first.
3. The loop didn't produce noticeable thinking improvement → reset; rethink approach.

**The evidence supports outcome 1 with documented friction.** Three independent lines of
evidence converge:

- **The trust tier produced decision-ready output the author acted on.** Integrity
  found a substrate bug, five canvas content gaps, and an edit-time convention error
  in its first three sessions of use. Coverage's severity ladder distinguished real
  issues from structurally-expected warnings after one refinement. Both helpers earned
  their place.
- **The lens substrate held.** Adding lens 2 (consistency, second structural shape) and
  lens 3 (tar-pit, theoretical shape) required **zero substrate changes**. The
  drop-a-file promise is real. Phase 6+ can add lenses without substrate work.
- **The trial run produced design-altitude reflexes that would not have surfaced
  without the loop.** The `voted_for` near-miss in cluster.clj is the cleanest single
  example: the Tar-Pit framing caught the design error before authoring committed to
  it. That is what "thinking-enhancing" means in practice.

The friction items (Section 5) are real but not blocking. Item 1 (`bin/fukan reset`) is
the only friction item that meaningfully impedes the loop; the others are
documentation, lens additions, and refinements. None of them require reopening Phase 5
design questions. A Phase 5.5 would be over-cautious — the friction items are better
absorbed as the opening tasks of Phase 6 once the user has settled what surface Phase 6
opens up.

Phase 6 can begin.

---

## 7. Phase 6+ implications

Phase 5 closed the authoring-loop substrate; Phase 6 opens whichever surface the user
chooses next. The Phase 5 plan and its predecessors sketch several candidate Phase 6
directions:

### The browser UI (the originally-named Phase 6)

The graph viewer + editing surface deferred through Phases 3, 4, and 5 is the most-named
follow-on. Three coherent paths from the Phase 4 verification carried forward:

1. Embedded canvas editing in the graph viewer.
2. Text-mode interface (canvas-as-Clojure with REPL tooling; the cycle Phase 5 actually
   ran on).
3. Chat-driven authoring via `bin/fukan` extensions.

Phase 5's trial run validated path 2 as a working loop. Path 1 or path 3 require new
infrastructure. The architect-canvas design doc favors path 2 as the Clojure-embedded
thinking tool. Phase 6 decides among them.

### Additional lenses (drop-a-file additions)

The lens substrate is ready to absorb new modes without substrate work. Candidates,
loosely ranked by potential value:

- **`:coupling`** — dependency-graph awareness; the trial run named this gap directly.
- **`:methodology-coherence`** — the original Sprint 1 candidate, deferred because
  fukan doesn't yet have a multi-paradigm corpus to design against. The Sprint 3
  stress-test demos give it material; a Phase 6 corpus expansion gives it more.
- **`:delta`** — what-changed-since-last-snapshot awareness. Originally Sprint 1
  candidate; deferred because snapshot-lifecycle is its own design conversation.
- **`:fcis`** — functional core / imperative shell, applied as a theoretical lens
  (and natural counterpart to tar-pit).
- **`:ddd`** — domain-driven design framing as a theoretical lens.

### Phase 7–8 work (diff detection + impl generation)

The Phase 5 plan's strategic horizon names Phase 7 (diff detection between canvas design
and code implementation) and Phase 8 (generating implementation instructions from
design-vs-code diffs) as fukan's eventual primary product surface. Phase 5 didn't touch
either; both remain on the horizon. The substrate Phase 5 settled (canvas as
load-bearing intersection, integrity as decision-ready signal, lens registry as
experimentation surface) is the foundation those phases sit on.

### Phase 5.5 candidates (if user prefers fix-before-Phase-6)

Items 1, 2, 4 from Section 5 are the natural Phase 5.5 cluster:

- `bin/fukan reset` exposure.
- Cross-module ref convention documentation + demos reconciliation.
- Canvas-files auto-discovery.

A focused 1–2 session sprint absorbs these. The user's call whether they land here or
as the opening of Phase 6.

---

## 8. Carried-forward concerns (from prior tasks, not yet addressed)

Items tracked across the phase that surfaced as either non-blocking observations or
substrate symmetry concerns; logged here so they're not lost between phases.

1. **`vocab.event/handler`'s `emits` form asymmetric with `function`'s emits.** Task 4
   added the `(emits ...)` form to `function`, storing as `:emits` (ref-typed
   card-many). Handler's existing `emits` form, by contrast, stores as `:references`.
   Symmetric semantically, asymmetric in substrate representation. The Task 6 fixup
   addressed merge-time eid translation for the `:emits` attr but did not unify
   handler's storage. Not blocking; surface inconsistency worth resolving in a
   substrate-symmetry pass.

2. **The clojure-lsp `unused-public-var` false-positive pattern.** Every new
   agent-surface namespace and every new canvas module surfaces info-level
   unused-public-var warnings because clj-kondo can't see the canvas DSL's
   indirect usage. Three commits in Phase 5 alone extended exemption lists
   (`nryrmvqo`, `kutpnkqn`, `mvnonuwm`). The pattern is benign noise but recurring;
   a category-level exemption (e.g. "namespaces under `fukan.canvas.lens.*` and
   `fukan.agent.system.*` are exempt by default") would be cleaner than per-namespace
   additions. Cleanup candidate.

3. **The dotted-vs-segment cross-module ref convention divergence.** Captured under
   Section 5 item 2 but worth restating here as a carried-forward substrate concern:
   the demos use dotted refs (`:event-driven.cart/LineItem`) and would fail integrity
   if checked; the canvas tree uses single-segment refs. The convention is correct;
   the demos predate the integrity helper. Reconciliation pending — either widen the
   resolver or backfill the demos.

These are not blocking and not load-bearing. They are the kind of long-lived hygiene
items that accumulate in a maturing system. Logging them here ensures Phase 6 picks
them up rather than rediscovering them.

---

## Appendix: Phase 5 artifact inventory

| Artifact | Description |
|----------|-------------|
| `doc/plans/2026-05-26-feedback-signals-design.md` | Sprint 1 — feedback partition + Phase 5 priorities |
| `doc/plans/2026-05-26-coauthor-workflow-design.md` | Sprint 1 — loop shape + integration point + trial target |
| `src/fukan/canvas/projection/canvas_source.clj` | Sprint 2 — name+role convention + warning text + docstring |
| `src/fukan/canvas/construction.clj` | Sprint 2 — `(emits ...)` form on `function` |
| `src/fukan/canvas/core/substrate/store.clj` | Sprint 2 — `:type/fields` derived attribute; merge-time eid→uuid for `:triggers` / `:emits` |
| `src/fukan/canvas/inspect/integrity.clj` | Sprint 3 — trust-tier cross-reference resolution |
| `src/fukan/canvas/inspect/coverage.clj` | Sprint 3 — trust-tier orphans + severity ladder + role exemptions |
| `src/fukan/canvas/lens/core.clj` | Sprint 3 — lens contract + `validate-lens` |
| `src/fukan/canvas/lens/registry.clj` | Sprint 3 — auto-discovery of lens vars |
| `src/fukan/canvas/lens/survey.clj` | Sprint 3 — survey dispatcher |
| `src/fukan/canvas/lens/patterns.clj` | Sprint 3 — structural lens (rule-of-three lift candidates) |
| `src/fukan/canvas/lens/consistency.clj` | Sprint 3 — structural lens (naming + fields + sister symmetry) |
| `src/fukan/canvas/lens/tar_pit.clj` | Sprint 3 — theoretical lens (Moseley & Marks 2006) |
| `src/fukan/agent/api.clj` | Sprint 3 — `(help)` exposes trust + weigh tiers separately |
| `.claude/agents/fukan-architect.md` | Sprint 4 — tier awareness + `survey design improvements` mode |
| `doc/canvas-authoring-system-prompt.md` | Sprint 4 — permanent versioned activation prompt |
| `AGENTS.md` | Sprint 4 — trust/weigh tier primer + lens substrate pointer |
| `canvas/distributed/cluster.clj` | Sprint 4 — trial-run: essential state + invariants |
| `canvas/distributed/election.clj` | Sprint 4 — trial-run: protocol + safety invariants |
| `canvas/distributed/log.clj` | Sprint 4 — trial-run: replicated log + safety invariants |
| `doc/plans/2026-05-27-trial-run-findings.md` | Sprint 4 — trial-run findings (load-bearing evidence) |
| `doc/plans/2026-05-26-phase-5-verification.md` | Sprint 5 — this document |

**Phase 5 commit count:** ~25 commits — 1 plan + 3 plan amendments + 2 Sprint 1 design
docs + 3 Sprint 2 hardening + 2 Sprint 2 kondo hygiene + 1 integrity + 1 substrate bug
fix + 3 model.spec gap fills + 1 coverage + 1 coverage refinement + 3 lens substrate +
1 patterns + 1 consistency + 1 tar-pit + 2 kondo refinements + 1 system prompt +
1 fukan-architect extension + 1 AGENTS update + 3 distributed-demo modules + 1 trial-run
findings + this verification doc.

**Substrate state at Phase 5 close:** lens substrate (core + registry + survey) + two
trust-tier helpers (integrity, coverage) + three starter lenses (patterns, consistency,
tar-pit) + one substrate bug fix + five canvas content gaps closed + permanent
canvas-authoring system prompt + extended fukan-architect subagent.
