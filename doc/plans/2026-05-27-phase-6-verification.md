# Phase 6 Verification Report — Canvas ↔ Code Drift Detection

**Date:** 2026-05-27
**Status:** Complete
**Decision:** (1) Drift detection works → Phase 7 can begin

**Phase scope:** Detect, structure, and surface design-vs-implementation drift.
Close the loop between canvas (design) and `src/` (code) via trust-tier drift
signals.

---

## Strategic frame

Phase 5 made the canvas a thinking-enhancing tool for *design* — the LLM author
and the human collaborator now have decision-ready signal about canvas
integrity, coverage, and recurring shapes. What stayed silent through Phase 5
was the *other* half of the loop: how design intent compares to its
implementation in `src/`. Phase 6's strategic move is the natural extension —
*intent compared against implementation*. The canvas declares what the system
should be; `src/` is what it is. Drift findings tell the author and the
collaborator, in either direction, where the two have separated.

The bet was that this loop could be closed *without redesigning the analyzer*.
The Clojure target analyzer already walked source and tagged projection edges
`:valid` / `:absent`; the substrate was structurally complete. Sprint 1's
investigation confirmed the bet ("structure yes, wiring no") and the rest of
the phase ran on that conclusion: fix the wiring; lift the existing analyzer
output through a finding-shaped lens; integrate into the trust tier alongside
integrity and coverage. The phase shipped two signals — missing-implementation
(an umbrella over functions, events, invariants, rules, getters, checkers,
handlers) and shape-drift-on-records — into the existing trust/weigh feedback
partition. The product surface is unchanged from Phase 5: the REPL, `bin/fukan
eval`, `(help)`, and the extended `fukan-architect` agent.

The trial run validates the central claim: *the canvas↔code loop produces
authoring-altitude signal that drives implementation work the way Phase 5's
canvas-only loop drove design work*. See Sections 4 and 6.

---

## 1. What was attempted vs. built

Four working sprints plus this verification. ~17 commits clustered between
`ymrnossr` (Phase 6 plan lands) and `zqqylzsy` (Sprint 4 trial-run findings).

### Sprint 1 — Drift signals + canvas→code projection design (Tasks 1–2)

Two design docs landing in a single commit (`kppqoqqr`) with the plan amended
to reflect the substantive pushback both produced.

- **Task 1** — `doc/plans/2026-05-27-drift-signals-design.md`. Settled the
  drift tier model (warning-default, severity ladder carried from Phase 5),
  the directional framing (option 3 — both directions; every finding names
  both sides), and the signal selection. The doc opened with seven candidate
  categories and recommended a three-signal Sprint 3
  ({missing-implementation, event-schema-missing, record-shape-drift});
  user review pushed back: invariants and rules *should* be projected into
  code (the `holds-that` clause is the canonical code-side name). The
  amendment collapsed the seven categories into **two signals** — broader
  projection coverage means the missing-implementation umbrella uniformly
  catches functions, events, invariants, rules, getters, checkers, and
  handlers.
- **Task 2** — `doc/plans/2026-05-27-canvas-to-code-projection-design.md`.
  Verified that canvas content *already projects* into the analyzer's
  expected `:primitives` input shape — option (b) from the plan was real,
  not aspirational. But the wiring was partial and broken in load-bearing
  places: the module-coord separator was wrong, the root-prefix was empty
  against fukan-itself, the type selector required a vestigial Allium tag,
  events were misrouted. The doc enumerated four wiring gaps; user review
  added three more (invariant projection, rule projection, rules-4* naming
  alignment). Sprint 2 inherited a final list of seven gaps plus the Phase
  5.5 auto-discover carryover.

The design docs are the load-bearing artifacts of Phase 6 — every downstream
sprint executed against the conclusions they reached.

### Sprint 2 — Projection-layer wiring fixes (Tasks 3–5)

Nine commits, per-gap hygiene preserved across the run. Pre-Sprint-2: against
fukan-itself the analyzer produced essentially zero `:valid` edges (false
negative drift signal across the board). Post-Sprint-2: ~170 `:valid` /
~443 `:absent` projection edges accurately reflecting canvas↔code reality.

- **Task 3 — Gaps 1-4** (`vwnmttvn`, `rttxwqzx`, `uztmprlw`, `kvzqvrpu`).
  Four one-liner-ish fixes, one commit per gap. (1) `module-coord-of-primitive`
  splits on `/` not `::`; (2) `fukan-on-fukan` `:root-prefix` `""` → `"fukan"`;
  (3) type selector becomes kind-based, dropping the Allium-tag requirement;
  (4) `:canvas/event` routes to `:primitive/event` in `affordance-kind`.
- **Task 4 — Gaps 5-7** (`mzqmyynw`, `zxrprvmz`, `pxvrmlux`, `qnsssvoo`).
  Invariant projection (canvas `(invariant X (holds-that "Y"))` → expected
  predicate fn `Y`); rule projection verified (already routed; carry-through
  added); address-derivation hardened for invariant/rule labels; a follow-up
  fix for underscore↔hyphen conversion in module-path segments surfaced when
  rules-4* canvas labels were aligned to the code-side `check` convention.
- **Task 5 — Auto-discover canvas files** (`rupttupz`). The Phase 5.5
  carryover absorbed into this sprint. Classpath scanning of `canvas/**/*.clj`
  replaced the explicit `canvas-namespaces` registry; new canvas files no
  longer require dual edits.

Sprint 2 is the structurally largest part of Phase 6 measured in commits,
but each commit is small. The per-gap discipline made the wiring change →
behavioural change correspondence legible.

### Sprint 3 — Build drift detection (Tasks 6–7)

Three commits landing the drift trust-tier helper.

- **Task 6 — Missing-implementation drift helper** (`yylzklvl`). The umbrella
  signal: a single `check-missing-implementation` fn walks the analyzer's
  projection edges, filters `:validity :absent`, and emits a finding per
  edge naming both sides — canvas stable-id + expected code path +
  expected symbol + canvas-kind. The helper lives at
  `src/fukan/canvas/inspect/drift.clj`, mirrors integrity/coverage shape,
  and registers in `(help)` under `:trust`. First run against fukan-itself:
  **443 findings** broken down by canvas-kind.
- **Task 7 — Shape-drift on records + analyzer extension** (`pwmqupot`,
  `wmuuppso`). The half-session analyzer extension promised by Sprint 1
  Task 1: extend `target/clojure/source.clj` to parse `def` bodies for
  Malli `[:map …]` schemas and `defrecord` field-lists, attach parsed
  `:fields` to Code.DataStructure artifacts. Then a `check-shape-drift`
  per-check fn that compares canvas-side fields against artifact-side
  fields. First run: **3 findings**, all in `web.views.cytoscape` — every
  one a legitimate snake↔camel boundary the wire JSON requires (intentional
  representational divergence at an edge).

Two helpers, one new analyzer attribute, ~446 total drift findings against
fukan-itself at Sprint 3 close. The trust tier now has three helpers:
`integrity`, `canvas-coverage`, `canvas-drift`.

### Sprint 4 — Workflow integration + trial run (Tasks 8–9)

Seven commits, two distinct activities.

- **Task 8 — System prompt + agent + AGENTS.md extensions** (`lkuknyxm`,
  `tnkttvsx`, `ltzooktk`). Three artifacts updated with drift awareness:
  the canvas-authoring system prompt gains a tier-model section on drift;
  the `fukan-architect` agent definition gains survey-mode integration of
  drift findings; AGENTS.md adds the trust-tier drift entry to its primer.
  Tight prose throughout — no bloat.
- **Task 9 — Trial run** (`rtvyxykv`, `ouqqztuy`, `pusmpmxz`, `zqqylzsy`).
  The agent built `src/fukan/distributed/{cluster,election,log}.clj` against
  the pre-existing `canvas/distributed/*`, deliberately partial across all
  three modules (~14 implemented entities, ~25 omitted across 42 canvas
  declarations). Drift caught every deliberate omission and only deliberate
  omissions. Distributed-only drift baseline 46 → 30; global 446 → 430. The
  trial-run findings doc (`zqqylzsy`) is the load-bearing evidence document
  for Phase 6's outcome.

### Sprint 5 — Verification (Task 10)

This document.

---

## 2. Did the canvas→code projection wiring fixes work?

The pre-Sprint-2 state was unambiguous: the analyzer ran end-to-end against
canvas content without exceptions, but **every canvas-declared function
projected as `:absent`** against fukan-itself. The pipeline was wired
correctly in isolation; the canonical-address derivation hit two
simultaneous gaps (`::` vs `/` separator + empty root-prefix) that made
every validity tag wrong. Records and values were entirely invisible
(tag-based selector + missing Allium tag). Events were misrouted to
operations. The drift signal was unusable.

Sprint 2's seven wiring gaps were small (1-line to ~10 LoC each) and
individually mechanical. The interesting question was whether the
"structure yes, wiring no" diagnosis from Sprint 1 Task 2 would hold —
whether fixing the wiring would produce trustworthy validity tags without
deeper analyzer rework. **It did.**

**Pre/post evidence:**

- **Pre-Sprint-2:** ~0 `:valid` edges against fukan-itself; almost everything
  `:absent` (false negative).
- **Post-Sprint-2 (after gaps 1-4):** functions, events, getters, checkers,
  handlers project at the right canonical addresses. Records and values
  reach the analyzer via the new kind-based selector. Validity tags reflect
  reality.
- **Post-Task-4 (after gaps 5-7 + the underscore↔hyphen module-path fix):**
  invariants and rules also project. The umbrella signal now spans seven
  canvas-kinds uniformly.
- **Post-Sprint-3 (drift helper landed):** ~170 `:valid` and ~443 `:absent`
  projection edges, producing 446 drift findings, each naming both canvas
  and code sides.

The Sprint 1 Task 2 diagnosis was correct: no analyzer redesign was needed,
only localised projection-layer adjustments. The seven fixes total ~50 LoC
of wiring change plus a handful of address-derivation refinements. The
analyzer's projection mechanic (artifact emission, projects-edge with
`:validity`, materialize-unprojected for code-without-canvas) stayed
unchanged through Phase 6.

**Verdict:** The wiring fixes worked exactly as designed. The diagnostic
discipline of Sprint 1 — *verify the substrate before touching it* — paid
for itself in Sprint 2's mechanical execution.

---

## 3. Did the drift trust-tier signals produce useful output?

Two helpers under `inspect/drift.clj`, both registered in `(help)` under
`:trust`. Both run against fukan-itself produced findings the author and
the trial agent acted on.

### `inspect.drift/missing-implementation` — the umbrella

**Output against fukan-itself at Sprint 3 close: 443 findings.** Broken
down by canvas-kind, the distribution matches expectations — invariants
dominate (most have no code counterpart and structurally should not, per
the trial-run synthesis), followed by functions, events, handlers,
getters, rules, checkers.

Three properties make the signal feel like one tool rather than seven:

1. **Uniformity.** A single check fn covers all canvas-kinds. The author
   reads one finding shape, regardless of whether the absent counterpart
   is a function, an event schema, or an invariant predicate.
2. **Bidirectional framing.** Every finding's `:message` and `:offenders`
   name both sides explicitly — canvas stable-id + expected code path +
   expected symbol + canvas-kind. The LLM is given a fact and chooses
   how to respond.
3. **Severity discipline carried from Phase 5.** Every drift finding ships
   `:warning` — drift is fact-of-discrepancy; resolution is judgment.
   No drift finding is `:error` because there is no methodology-independent
   drift the way a broken cross-reference is methodology-independent.

**Trial-run encounter.** The trial agent used drift output *as the work
plan*. The `expected-code-path` + `expected-symbol` pair externalises the
address-registry convention so the LLM does not need to memorise it — the
canvas declarations name the exact code identifiers expected. Round 1
took ~2 minutes of authoring because drift output told the agent exactly
what to write. This is a stronger claim than "decision-ready" — drift is
*prescriptive during authoring*, not just evaluative after.

**Verdict:** Decision-ready. The umbrella check works as a uniform spec
across canvas-kinds. Authoring-round drift deltas isolate work cleanly
(every closure matched an implemented entity; every survivor matched a
deliberate omission).

### `inspect.drift/shape-drift-on-record` — the structural check

**Output against fukan-itself: 3 findings, all in `web.views.cytoscape`.**
Every finding is a real snake↔camel boundary the wire JSON requires —
the cytoscape views serialise to a JSON shape that intentionally diverges
from the canvas record's Clojure-side field naming. The signal is
correctly pointing at a representational boundary; the *resolution* is
"accept the gap as deliberate" rather than "fix the code".

**Trial-run encounter.** Of the 5 shape-drift findings inside
`distributed.*` during the trial run, 4 were caused by **compound-shape
flattening on both sides** — canvas `(field members (set-of :NodeId))`
stores as `[:members :NodeId]` (dropping the `set-of` wrapper); code
`[:members [:set NodeId]]` reads as type `:set` (dropping the inner
element). The comparison reports "canvas `:NodeId` vs code `:set`" — a
type-mismatch the LLM cannot act on without understanding the tooling's
flattening rules. The 5th finding (LogEntry's `:command :any` vs canvas
`:Command`) was a real type mismatch and a useful signal.

The check is sound for scalar fields but lossy for compound fields. This
is a tooling-side concern — see Section 5.

**Verdict:** Decision-ready for scalar fields; noisy for compound fields.
The 3 findings against fukan-itself happen to all be scalar boundaries
and surfaced correctly; the compound-field noise becomes visible only at
record-shape density that fukan's own canvas does not currently exhibit.

### Trust tier overall

Both helpers shipped against the substrate, surfaced findings within the
first session of use, and produced output the author and the trial agent
acted on without reinterpretation. The umbrella signal is the load-bearing
artifact — it carries the canvas↔code loop's design-altitude work. The
shape-drift check is a useful refinement with a documented rough edge.
Together they extend the trust tier from "broken canvas" (Phase 5) to
"broken canvas ↔ code correspondence" (Phase 6).

---

## 4. Did the canvas↔code authoring loop succeed?

The trial run authored `src/fukan/distributed/{cluster,election,log}.clj`
against the pre-existing `canvas/distributed/*` over three rounds, with
deliberate omissions across all three modules. Drift went 46 → 30 inside
distributed; globally 446 → 430. Every delta was inside the trial scope —
the loop isolates work cleanly. The detailed findings live in
`doc/plans/2026-05-27-drift-trial-run-findings.md`.

The headline judgment: **yes, the loop closed.** Three concrete properties
emerged in the trial that justify the strategic frame:

1. **Drift was the work plan, not the audit.** The trial agent ran drift
   first to enumerate what each round needed to produce. The
   `expected-code-path` and `expected-symbol` fields *are* the spec. This
   inverts the relationship the Phase 5 trial run had with integrity:
   integrity was a *reflect* signal after a canvas round; drift is an
   *orient* signal before a code round. The Phase 6 plan's "informing,
   not directive" framing held — but the directional reach is stronger
   than expected.

2. **The bidirectional framing caught failure mode #6 (treating drift as
   unidirectional) in real time.** The 10 invariant missing-implementation
   findings in distributed are the canonical test: a naive read says
   "write 10 functions". The correct read says "invariants are property-
   test fodder, not function-shaped code — the gap is in the proof layer,
   not the implementation layer". The trial agent weighed both sides and
   recorded the right judgment in the cluster.clj docstring rather than
   reflexively closing the findings. The framing was load-bearing.

3. **The drift-cadence shift held.** The system prompt's "drift at session
   boundaries, not per edit" guidance proved well-tuned. Per-edit drift
   reset overhead (~6 s) is comfortable for the loop but adds up across
   a session. The per-session cadence was the right granularity — frequent
   enough to keep findings actionable, infrequent enough not to interrupt
   the authoring flow.

The patterns/consistency/tar-pit lenses (Phase 5) were not used during
trial-run authoring — the canvas was unchanged and the lenses are
canvas-side observations. Drift carried the entire canvas↔code signal
load; the lenses re-entered only at the closing synthesis (the Tar-Pit
self-survey at the end of the findings doc — see Section 5).

**Verdict:** The loop succeeded. The trial agent produced code the canvas
described — at the right altitude, with the right naming, with the right
field shapes — using drift as the working spec. The friction items (Section
5) are real but small; the central claim — *canvas↔code authoring with
drift signal is a thinking-enhanced activity* — is borne out.

---

## 5. What did the trial run reveal about gaps?

Four tooling-side concerns surfaced during Sprint 4 plus one architectural
insight from the closing Tar-Pit self-survey. Ranked roughly by impact ×
tractability.

### Tooling-side (carryable into Phase 6.5 or Phase 7+ opening tasks)

1. **Shape-drift comparator is noisy on compound fields.** The 4-of-5
   compound-field findings in distributed.* are the canonical example. Both
   sides flatten compound shapes lossily: canvas drops `set-of`/`list-of`
   wrappers when projecting `:type/fields`; the analyzer reads outer
   collection keywords from Malli `[:set …]` / `[:vector …]` as the field's
   *type*, dropping the inner element type. The comparison then mismatches
   on noise. Fix is on the tooling side — preserve the collection wrapper
   structurally on both sides and compare head + inner separately, or skip
   the field when either side declares a collection-of-element. Moderate
   tractability (touches `canvas-source/project-types` + `source/extract-symbols`
   parsing); high impact for any record with compound fields.

2. **Daemon-init silent footgun.** `clj -M:run --src <repo-root>` is
   syntactically accepted but causes `read-forms` to recurse the whole
   tree, finding a Clojure file with unbalanced brackets (likely under
   `.legacy-allium/` or a generated cache) and crashing the daemon with
   `EOF while reading` and no per-file attribution. The right fix is for
   `read-forms` to wrap the read in `try/catch` and emit one drift-style
   finding per unreadable file rather than aborting. Low cost, high
   diagnostic value.

3. **`(status)`/`reset` artifact-count is stale.** The visible count
   reported 1137 before and after all three trial rounds despite
   `(artifacts :sub-case :code/function)` reporting different counts.
   Either the count is computed pre-analyzer or it does not re-tally
   after Phase 6 materialisation. Small fix; restores trust in the status
   summary.

4. **Invariants project `:expected-symbol` = `holds-that` clause text.**
   For an invariant like `TermMonotonicity (holds-that "current-term is
   monotonically non-decreasing per node")`, the drift finding's
   `:expected-symbol` is the prose clause. Round-trips strangely — the
   field expects a code identifier; the projection synthesises a name
   from `holds-that` text because that *is* the canonical naming rule
   for invariants. Symptom of the rule+invariant name+role collision in
   the primitives map: invariants currently collapse onto the rule
   projection-kind, inheriting its `expected-symbol` semantic. Either
   omit `:expected-symbol` for invariants (signalling that property-test
   material is the right counterpart, not a `defn`), or carry the
   distinction through the primitives map so the projection-kind can
   surface "this entity has no code-counterpart by design".

### Architectural insight from the closing self-survey

The trial-run findings doc closes with a Tar-Pit synthesis applied to
canvas + code together:

> *Canvas alone is ~100% essential; the code-side Malli `[:map …]` choice
> imports an accidental representational layer (`:any`, `:set`, `:maybe`)
> that the canvas's `set-of` / `optional` abstracts over.*

This is the load-bearing finding of the trial: **canvas + code together
make the essential/accidental partition visible in a way canvas alone does
not.** The drift loop is the mechanism that surfaces it. The 11 absent-
invariant findings are the cleanest example — invariants are essential
declarative logic whose natural code counterpart is a property-test or
proof obligation, not a `defn`. Without the canvas+code loop, the question
"are these invariants realised anywhere?" has no purchase. With it, the
question becomes *the* design conversation Phase 7 should foreground.

This is not a tooling concern — it is a Phase 7+ design conversation. The
drift signal does not need to flatten the essential/accidental partition;
it needs to expose it.

---

## 6. Decision

**Outcome (1): Drift detection works → Phase 7 can begin.**

The Phase 6 plan named three possible outcomes:

1. Drift works → Phase 7 (implementation-instruction generation) can begin.
2. Works with caveats → Phase 6.5 to close caveats first.
3. Drift didn't produce useful output → reset; rethink approach.

**The evidence supports outcome 1 with documented friction.** Three
independent lines of evidence converge:

- **The wiring fixes restored validity-tag truthfulness.** Sprint 1 Task 2's
  "structure yes, wiring no" diagnosis held; Sprint 2's seven mechanical
  fixes shifted the analyzer's output from ~0 valid edges to ~170 valid /
  ~443 absent — a regime change in signal quality without any analyzer
  redesign.
- **The trust-tier drift helpers produced findings the author acted on.**
  Missing-implementation produced 443 findings against fukan-itself, all
  decision-ready; the trial run's 30 distributed-scope findings split
  cleanly into 25 deliberate omissions + 5 shape-drift artifacts with no
  false positives inside the trial scope. The shape-drift check produced
  3 findings against fukan-itself, all real boundaries.
- **The canvas↔code loop closed in the trial run.** Three rounds of
  partial implementation against `canvas/distributed/*` produced 16
  drift-closing edits and 14 drift-surviving omissions, all isolated to
  scope. The trial agent used drift as the working spec, not as an audit
  — the strongest possible evidence that the loop is design-altitude,
  not after-the-fact.

The friction items (Section 5) are real but not blocking. The shape-drift
comparator's compound-field noise is the most consequential and has a
clear tooling-side fix path. The daemon-init footgun and stale artifact-
count are small operational items. The invariant `:expected-symbol`
oddity is a symptom of a deeper representational concern (the rule+invariant
collision) that resolves naturally in Phase 7's implementation-instruction
work.

A Phase 6.5 would be over-cautious — none of the friction items require
reopening Phase 6 design questions. They are absorbed cleanly as opening
tasks of Phase 7 or as small hygiene commits between phases.

Mirroring Phase 5's outcome: **the substrate works; the loop is real;
the next surface can open.**

Phase 7 can begin.

---

## 7. Phase 7+ implications

Phase 6 closed the canvas↔code loop substrate; Phase 7 opens the surface
the user chooses next. The Phase 6 plan's "Subsequent phases" sketches
name Phase 7 explicitly:

> Phase 7 — Implementation-instruction generation from drift findings.
> Given drift output, produce the precise code edits to bring implementation
> into alignment with canvas (or vice versa).

This is the natural next step. Phase 6 produces structured drift output;
Phase 7 consumes it. The trial run already revealed how the loop wants
to be used: drift output is *prescriptive during authoring*, not just
evaluative after. Implementation-instruction generation formalises that
mode — given a `missing-implementation` finding, produce the canvas-derived
specification of what to write; given a `shape-drift-on-record` finding,
produce the diff between canvas and code shapes; given the invariant case
(no code-counterpart by design), produce a property-test obligation
instead of a function stub.

### Other carryover for Phase 7+ (ranked by impact × tractability)

| Item | Source | Tractability | Impact |
|---|---|---|---|
| Compound-shape comparator for shape-drift | Sprint 4 finding | Moderate | High (any record-dense subsystem) |
| Daemon-init `--src` footgun | Sprint 4 finding | Low | Medium (diagnostic) |
| `(status)` artifact-count staleness | Sprint 4 finding | Low | Low (trust restoration) |
| Invariant `:expected-symbol` semantic | Sprint 4 finding | Moderate (touches rule+invariant collision) | Medium |
| `:coupling` / `:dependency` lens | Phase 5 Sprint 4 carryover | Moderate | High (orthogonal signal axis) |
| `(survey :scope <prefix>)` scoping | Phase 5 Sprint 4 carryover | Low | High (focused authoring) |
| Filtered affordances on `canvas-coverage` / `canvas-drift` | Sprint 4 finding | Low | High (offender-shape alignment) |
| Coverage warning-filtering for in-flight authoring | Phase 5 Sprint 4 carryover | Moderate | Medium |
| Rule+invariant collision resolution in primitives map | Sprint 4 + Task 4 surface | Substantial | Medium (representational symmetry) |
| Stale `:stale`/`:unknown` validity values in agent definition | Sprint 4 Task 8 surface | Low | Low |

The first three rows are the Phase 7-opening cluster: small, mechanical,
material to drift-driven workflows. The lens additions (coupling, scope)
and the filtered affordances unlock workflow shapes the trial run named
directly.

### The essential/accidental conversation (Phase 7+ design)

The trial-run Tar-Pit synthesis names what may be the most consequential
Phase 7+ direction: *canvas + code together expose the essential/accidental
partition; drift's role is to surface it, not flatten it*. This is not a
mechanical task — it is a design conversation about what implementation-
instruction generation should *do* when the canvas declaration's natural
code counterpart is a property-test obligation rather than a function.
Phase 7's deeper work is here. The mechanical work (Phase 7's opening
tasks) is the bridge.

---

## 8. Carried-forward concerns

Items logged across Phase 6 that surfaced as non-blocking observations
or substrate-symmetry concerns; logged here so they're not lost between
phases.

1. **Rule + invariant name+role collision in the primitives map.** Surfaced
   in Task 4 when invariants and rules began projecting to the same
   `:primitive/rule` kind; confirmed in the trial run by the `:expected-symbol`
   = `holds-that`-text round-trip. The collision is benign today
   (resolution still works via the `(name, role)` tuple per the canvas
   convention) but the projection layer's reduction to a single primitive
   kind loses the role distinction, which downstream surfaces (the drift
   helper's `:expected-symbol` field, future enforcement-mapping work) need.
   Phase 6.5 candidate if Phase 7 chooses to address it as a prerequisite;
   otherwise opens cleanly inside Phase 7's representational work.

2. **Daemon startup footgun** (`clj -M:run --src <repo-root>` silently
   accepts non-`src` paths and crashes mid-analysis). Sprint 4 surface.
   Tooling hygiene; small fix.

3. **`(status)` artifact-count staleness.** Sprint 4 surface. The visible
   counter does not re-tally after Phase 6 materialisation; agents must
   trust drift-count deltas instead. Small fix.

4. **The clojure-lsp `unused-public-var` info-level noise pattern.**
   Carried from Phase 5 Section 8 item 2; recurred in Phase 6 with the
   distributed.* trial-run code. Every new namespace surfacing fns invoked
   only via canvas-DSL indirect dispatch produces unused-public-var noise.
   The `^:export` metadata convention adopted in `ntrynzsr` (Phase 5
   close) addresses this for canvas DSL builders; broader `:export`
   adoption for trial-run code is an ongoing cleanup pattern. Category-
   level exemption (e.g. "namespaces under `fukan.distributed.*` exempt
   while subsystem is in-development") would be cleaner than per-namespace
   additions for active subsystems.

5. **Stale validity references in agent definition.** Sprint 4 Task 8
   surfaced that the architect agent definition mentions `:stale` /
   `:unknown` validity values that do not exist in the current substrate
   (only `:valid` and `:absent` are emitted). The agent definition's prose
   should be tightened to match the substrate's actual validity vocabulary.
   Low-stakes documentation hygiene.

6. **AGENTS.md `:warning` gloss is now load-bearing for two distinct
   framings.** Sprint 4 Task 8 surface. The `:warning` severity gloss in
   AGENTS.md covers both Phase 5's "structurally suspect but not broken"
   (coverage) and Phase 6's "fact-of-discrepancy, judgment-required"
   (drift). Both are correct uses, but the single gloss now serves two
   distinct framings. Worth restating with the dual reading explicit
   when the AGENTS.md primer next gets a pass.

7. **The dotted-vs-segment cross-module ref convention divergence.**
   Carried from Phase 5 Section 8 item 3. Phase 6's resolver work
   (`xwkvxotq`) widened the resolver to accept fully-qualified module refs
   alongside segment-matching, which addresses the demo divergence without
   forcing a demo rewrite. The convention concern is therefore softened
   but not closed — the canvas tree uses single-segment refs by convention;
   the demos use dotted; both now resolve, but the convention question
   (which is canonical?) is still open.

These items are not blocking and not load-bearing. They are the ongoing
hygiene cluster of a maturing system. Logging them here ensures Phase 7
picks them up rather than rediscovering them.

---

## Appendix: Phase 6 artifact inventory

| Artifact | Description |
|----------|-------------|
| `doc/plans/2026-05-27-canvas-substrate-phase-6.md` | The Phase 6 plan; amended after Sprint 1 to 2 signals + 7 wiring gaps |
| `doc/plans/2026-05-27-drift-signals-design.md` | Sprint 1 Task 1 — drift signal selection + tier model + directional framing |
| `doc/plans/2026-05-27-canvas-to-code-projection-design.md` | Sprint 1 Task 2 — canvas→code projection design + wiring gap inventory |
| `src/fukan/target/clojure/analyzer.clj` | Sprint 2 — gap 1 (`::`→`/`) + gap 3 (kind-based type selector) |
| `src/fukan/project_layer/defaults.clj` | Sprint 2 — gap 2 (`fukan-on-fukan` root-prefix) |
| `src/fukan/canvas/projection/canvas_source.clj` | Sprint 2 — gap 4 (event routing) + gap 5 (invariant projection) + gap 6 (rule projection + canvas-role) + auto-discover |
| `src/fukan/target/clojure/address.clj` | Sprint 2 — invariant/rule label derivation + underscore↔hyphen module-path conversion |
| `src/fukan/target/clojure/source.clj` | Sprint 3 — `def`-body extraction (Malli + defrecord) |
| `src/fukan/canvas/inspect/drift.clj` | Sprint 3 — trust-tier drift helper (missing-implementation + shape-drift-on-record) |
| `src/fukan/agent/api.clj` | Sprint 3 — `canvas-drift` registered in `(help)` under `:trust` |
| `doc/canvas-authoring-system-prompt.md` | Sprint 4 — drift awareness section |
| `.claude/agents/fukan-architect.md` | Sprint 4 — survey-mode drift integration |
| `AGENTS.md` | Sprint 4 — trust-tier drift primer entry |
| `src/fukan/distributed/cluster.clj` | Sprint 4 trial — partial implementation matching canvas |
| `src/fukan/distributed/election.clj` | Sprint 4 trial — partial implementation matching canvas |
| `src/fukan/distributed/log.clj` | Sprint 4 trial — "mostly absent" case |
| `doc/plans/2026-05-27-drift-trial-run-findings.md` | Sprint 4 — trial-run findings (load-bearing evidence) |
| `doc/plans/2026-05-27-phase-6-verification.md` | Sprint 5 — this document |

**Phase 6 commit count:** ~17 commits — 1 plan + 1 plan amendment + 2 Sprint
1 design docs + 4 Sprint 2 wiring gaps (Task 3) + 4 Sprint 2 projection +
address (Task 4) + 1 Sprint 2 auto-discover (Task 5) + 1 Sprint 3 analyzer
extension + 2 Sprint 3 drift helper (missing-impl + shape-drift) + 1 Sprint
4 system prompt + 1 Sprint 4 agent + 1 Sprint 4 AGENTS + 3 Sprint 4
distributed modules + 1 Sprint 4 trial-run findings + this verification doc.

**Substrate state at Phase 6 close:** trust tier extended to three helpers
(`integrity`, `canvas-coverage`, `canvas-drift`); analyzer projection-layer
wiring repaired across seven gaps; canvas-source auto-discovers
`canvas/**/*.clj`; canvas declarations now project uniformly into the
analyzer's expected-side for seven entity kinds (functions, events,
invariants, rules, getters, checkers, handlers); shape-drift-on-record
ships with `def`-body extraction; canvas-authoring system prompt, agent
definition, and AGENTS.md primer all extended with drift awareness; one
working trial run with 14 implemented entities + 25 deliberate omissions
across 42 canvas declarations demonstrates the canvas↔code loop end-to-end.
