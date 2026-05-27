# Phase 6 Drift Trial Run — findings

**Date:** 2026-05-27
**Subsystem implemented:** `src/fukan/distributed/` (3 files; ~14 implemented
entities + 25 deliberate omissions across 42 canvas declarations in
`canvas/distributed/{cluster,election,log}.clj`)

## What was built

Three Clojure files implementing a deliberately-partial slice of the canvas-
declared leader-election design. Naming-convention is the one fukan already
encodes in its address-registry: canvas PascalCase identifiers for record /
event types (Malli `[:map …]`); kebab-case for functions, handlers, getters.

- **`src/fukan/distributed/cluster.clj`** — full type coverage (NodeId, Term,
  NodeRole, Node, Cluster), 2 of 3 getters (`get-current-term`,
  `get-current-leader`), both functions as throwing stubs (`get-node`,
  `members`). Deliberately omitted: `get-self-role`, all 3 invariants.

- **`src/fukan/distributed/election.clj`** — both records (Vote,
  ElectionRound), 4 of 7 events as Malli payload schemas (HeartbeatExpired,
  ElectionStarted, VoteRequested, VoteGranted), 2 of 4 handlers as throwing
  stubs (`on-heartbeat-expired`, `on-vote-requested`). Deliberately omitted:
  3 events, 2 handlers, all 3 invariants.

- **`src/fukan/distributed/log.clj`** — the "mostly absent" case. Only
  LogIndex + LogEntry types, only ClientCommandReceived event, only
  `on-client-command` handler. Everything else deliberately absent (2 types,
  3 events, 2 handlers, 1 getter, 1 function, 4 invariants).

Drift baseline → trial end: **distributed.* 46 → 30 findings**; global
**446 → 430**. Every delta is inside the trial scope, confirming the loop
isolates work cleanly.

## The loop in practice — turn-by-turn observations

### Round 1 — cluster (13 → 6 drift findings)

- **Orient.** Read `canvas/distributed/cluster.clj`. Read `src/fukan/infra/model.clj`
  as a shape reference for the kebab-case mapping (`load_model` → `load-model`,
  `get_model` → `get-model`). Queried drift up front for the expected-symbol
  list — this is what made the round mechanical: the canvas already names the
  exact code identifiers it expects, including the field's `expected-code-path`.
- **Edit.** Wrote one file matching the drift-emitted address list. Used
  Malli `[:map …]` for records to satisfy the shape-drift check.
- **Reflect.** `(integrity)` → `[]`; cluster-scoped `(canvas-drift)` →
  6 remaining (4 deliberate absences + 2 shape-drift). The cleared findings
  are exactly the entities I wrote; the survivors are exactly what I omitted
  plus the shape-drift compound-shape artifact (see "Drift signal quality").

### Round 2 — election (16 → 10 drift findings)

- **Orient.** Read `canvas/distributed/election.clj`. Read `vocab/event.md`
  for the projection convention — events project as Code.DataStructure, so a
  `(def EventName [:map …])` is what the analyzer expects. Confirmed by
  grepping src/: no existing src declares `(def PascalCase [:map …])` for an
  event (canvas/distributed is the first place vocab.event is exercised in
  this codebase). This is canvas dictating a code convention that has no
  prior anchor.
- **Edit.** Defined records, 4-of-7 events as Malli schemas, 2-of-4 handlers
  as throwing stubs. Reused the cluster types via `:require`.
- **Reflect.** Drift dropped from 16 to 10. The 5 deliberate absences (3
  events + 2 handlers) all surface as `:check :missing-implementation`
  findings with bidirectional naming (canvas stable-id + expected code path
  + expected symbol).

### Round 3 — log (17 → 14 drift findings, "mostly absent" case)

- **Orient.** Drift-emitted symbol list as the working spec.
- **Edit.** Only LogIndex + LogEntry types, only ClientCommandReceived event,
  only `on-client-command` handler. Used `:command :any` in LogEntry to
  surface what happens when a referenced canvas type (Command) is itself
  absent on the code side.
- **Reflect.** Drift dropped from 17 to 14 — modest closure, large remaining
  gap. The deliberate `:command :any` produced both a `missing-implementation`
  finding (Command type absent) AND a `shape-drift-on-record` finding
  (LogEntry's `:command` field shape disagrees). Drift correctly reports
  both — they are not redundant signals.

### Final drift sweep

Distributed-only breakdown of the 30 remaining findings:

| check                      | canvas-kind | count |
|----------------------------|-------------|-------|
| missing-implementation     | invariant   | 10    |
| missing-implementation     | event       | 6     |
| missing-implementation     | handler     | 4     |
| missing-implementation     | getter      | 2     |
| missing-implementation     | function    | 1     |
| missing-implementation     | type        | 2     |
| shape-drift-on-record      | (n/a)       | 5     |

The 25 missing-implementation findings split cleanly: every single one is a
deliberate omission. The signal is precise — there is no noise inside the
distributed subsystem.

## Loop quality

The canvas↔code loop felt like a closed-loop tool in three concrete ways
and like ceremony in one.

**Closed-loop wins.**

1. **Drift was the work plan.** I ran drift first to enumerate what each
   round needed to produce; the `expected-code-path` and `expected-symbol`
   fields *are* the spec. Round 1 took ~2 minutes of authoring because the
   drift output told me exactly what to write. The "canvas as the design
   surface" claim landed: I never needed to translate from canvas notation
   to code identifiers manually.

2. **Bidirectional framing held.** The shape-drift finding on
   `cluster/Cluster` is structurally a tooling artifact (see below) — but
   the finding itself names both sides verbatim (canvas declared `:NodeId`,
   code declared `:any`). I did not reflexively rewrite my code to "fix"
   it; I read both sides and judged that neither was wrong, the comparison
   was lossy. Failure mode #6 was caught by the framing itself.

3. **Round 3's sparse implementation produced sane drift.** When most of a
   module is absent, the signal scales linearly — one finding per absent
   declaration, no cascade. The 17 → 14 dropped exactly the 3 entities I
   implemented.

**Where it felt like ceremony.**

The `bin/fukan reset` after every src edit takes ~6 seconds, and the
unrelated 19 pre-existing coverage findings within distributed (orphan
entities, handlers-without-emit-resolutions, etc.) cannot be filtered out
of `canvas-coverage` cleanly because the offender stable-ids and the
finding-level filtering surface aren't quite aligned. I had to write a
`some` predicate over each finding's offenders to scope the count. The
`canvas-drift` helper has a clean single-offender shape; `canvas-coverage`
emits offenders-as-vector which makes per-module filtering more awkward.

## Drift signal quality

**Missing-implementation: highly effective.** Every entity I deliberately
omitted produced exactly one finding; every entity I wrote produced zero
findings. The umbrella check covers all six declared canvas kinds (function,
event, invariant, rule, getter, handler) uniformly — that uniformity is what
makes the signal feel like one tool rather than six. The
`expected-code-path` + `expected-symbol` pair is the design-altitude
artefact: it externalises the address-registry convention so the LLM does
not need to memorise it.

**Shape-drift-on-record: useful but rough.** Of the 5 shape-drift findings
in distributed:

- 4 are caused by **compound-shape flattening on both sides**. Canvas
  declares `(field members (set-of :NodeId))` but `:type/fields` stores the
  pair `[:members :NodeId]` — the `set-of` wrapper is dropped at canvas
  ingestion. Code declares `[:members [:set NodeId]]`; the analyzer reads
  this as type `:set` (the outer keyword), dropping the inner element type.
  The comparison then says `canvas :NodeId vs code :set`. That is a
  type-mismatch finding the LLM cannot act on without understanding the
  tooling's flattening rules. The shape-drift check is sound for scalar
  fields but lossy for compound fields, and 4 of 5 fields in this trial
  involved compound shapes.
- 1 finding (LogEntry's `:command :any` vs canvas `:Command`) is a real
  type mismatch caused by my `:any` stand-in — useful signal.

The signal is therefore **directionally correct but noisy** for any record
whose fields are mostly compound shapes. The fix is on the tooling side
(canvas-source should preserve the collection-of wrapper as a structured
type, or shape-drift should normalise both sides to head-only and compare
inner types separately) — not on the canvas or code I wrote.

Invariants flag 10 missing-implementation findings across the three
modules. Those are canvas commitments with no callable counterpart (and
should not have one — property-test fodder, not function-shaped code). The
drift finding correctly *fact-of-discrepancy* reports the absence; the
*judgment* is "do not project invariants as Code.Function — change the
projection rule, or accept the gap as deferred". The bidirectional framing
is what makes this judgment available rather than reading as a directive
to write 10 nonsense functions.

## Where the loop pinched

- **Daemon-init had a silent footgun.** `clj -M:run --src <repo-root>` is
  syntactically accepted but causes the analyzer to recurse the whole tree
  via `file-seq`, finding a Clojure file somewhere outside `src/` with an
  unbalanced bracket (probably under `.legacy-allium/` or a generated EDN
  cache); the daemon crashes with `EOF while reading` and no per-file
  attribution. The fix is to pass `--src src` exactly. The right
  improvement is for `read-forms` to wrap the read in a try/catch and emit
  one finding per unreadable file rather than aborting the entire build.
- **`(status)`/`reset`'s `artifact-count` is stale or misreported.** It
  reported 1137 artifacts before AND after all three rounds even though
  `(artifacts :sub-case :code/function)` reported 918 (vs ~907 baseline).
  Either the count is computed on the pre-analyzer model, or it does not
  re-tally after Phase 6 materialisation. Either way, the visible status
  number lies a little; I trusted drift-count deltas instead.
- **Per-edit drift reset overhead** (~6s) is comfortable for the loop but
  adds up if drift is invoked per Phase C. The system prompt's "drift at
  session boundaries, not per edit" guidance is well-tuned. I would not
  want to invoke drift per edit.

## What surprised me

- **The drift output is precise enough to be the spec.** I expected drift
  to be evaluative (after-the-fact). It is in fact prescriptive (during
  authoring) — the `expected-symbol` is what to type next. The
  bidirectional framing makes this safe: drift is *informing*, the LLM is
  *deciding*. But the directional reach is strong.
- **The "expected-symbol" for invariants is the holds-that clause text.**
  `expected-symbol = "current-term is monotonically non-decreasing per
  node"` for `TermMonotonicity`. That is not a code identifier; it is a
  property description. This is a small finding — the analyzer's address-
  registry for invariants synthesises a name from the holds-that text,
  which round-trips strangely. Invariants probably should not have an
  `expected-symbol` at all (since they have no Code.Function counterpart);
  the field should be `nil` or omitted.
- **Cross-module type refs (`cluster/Term` in election) just worked.** I
  was prepared for a layer of friction here; there was none. The
  `:require` in the code follows the canvas namespace convention 1:1.

## Drift-specific failure modes — self-audit

- **#6 (treating drift as unidirectional)**: actively caught. The 10
  invariant missing-impl findings are the canonical test of this failure
  mode — a naive read says "write 10 functions"; the correct read says
  "invariants should not project to Code.Function in this codebase". I
  weighed both sides and recorded the right judgment in the cluster.clj
  docstring rather than reflexively closing the findings.
- **#2 (hallucinating refs)**: not exercised — I didn't edit canvas.
- **#4 (passing trust-tier but degrading coherence)**: this trial's
  output is intentionally rough (stubs, deliberate omissions), so the
  closing survey is the only way to catch it. See the Tar-Pit synthesis
  below — the survey did not surface coherence loss specific to my work.

## Recommendations for Phase 7+

1. **Suppress shape-drift on compound fields, or normalise both sides
   semantically.** Currently `set-of` vs `[:set …]` produces noisy type-
   mismatch findings. Either preserve the collection-wrapper on both sides
   and compare structurally, or skip the field entirely when either side's
   declared type is a collection-of-element.
2. **Invariants should not carry an `expected-symbol`** in their drift
   offender. Either omit them from the umbrella check (with a note that
   invariants are property-test material) or carry `nil` for the symbol
   field. The current "expected-symbol = holds-that-clause-text" round-
   trip is misleading.
3. **`(status)`/`reset` artifact-count needs to be re-tallied post-
   Phase 6.** Or, better, surface it via a counter the analyzer publishes
   into the model — same shape as primitive-count.
4. **`bin/fukan eval` should tolerate `--src` pointing at the repo root**
   either by defaulting to `src/` automatically or by emitting one drift
   finding per unreadable file in the tree. Today, the daemon dies and
   the operator has to read a clojure EDN crash report to learn why.
5. **A "filtered" affordance on canvas-coverage/canvas-drift** —
   `(canvas-drift :module-coord "distributed.*")` would let agents scope
   queries cleanly without writing inline predicates over offender shapes.

## Closing self-survey through Tar-Pit (applied to the trial output)

The Tar-Pit lens, applied to `canvas/distributed/{cluster,election,log}`
together with the partial `src/fukan/distributed/*` that now exists,
reveals a sharp asymmetry the canvas alone does not.

**Canvas-only view.** The three distributed modules read as a clean
essential/accidental partition: NodeId, Term, NodeRole, LogIndex are
essential atomic vocabulary (they would exist in any distributed-log
specification); Node, Cluster, Vote, ElectionRound, LogEntry, Log are
essential state (state the protocol must remember to function); the seven
election events + four log events are essential protocol surface (the
messages of the protocol are essential, not incidental). The invariants
(11 in total across the three modules) are pure declarative essential-
logic statements — the most Tar-Pit-aligned shape in the canvas surface.
Read alone, the canvas slice scores extremely well: ~100% essential, near-
zero accidental, no representation noise.

**Canvas + code view (the trial's perspective).** When the partial
implementation lands, the picture is more honest. The Malli `[:map …]`
schemas import a *representational* layer (the `:any`, `:maybe`, `:set`,
`:int` keywords) that the canvas's `set-of`/`optional`/`list-of` notation
abstracts over. The code's Malli choice is accidental complexity — chosen
because Malli is the schema library, not because the design requires it.
Different representation choices (spec.alpha, plumatic/schema, plain
defrecord) would produce different "type mismatches" against the same
canvas — a clean signal that the code-side type-names are accidental.

**The headline.** The Tar-Pit lens does not condemn this — Moseley & Marks
explicitly carve out *isolated* accidental complexity at the edges as
legitimate — but it does tell me where the line is. The canvas slice is
~100% essential; the code slice imports a representational layer of
accidental complexity that must be lived with. The drift signal's role is
to let the LLM see this asymmetry and choose where to put which complexity
— not to flatten the two into a single shared representation.

That is the load-bearing finding of the trial: **canvas + code together
make the essential/accidental partition visible in a way canvas alone
does not.** The drift loop is the mechanism that surfaces it.

The 11 absent-invariant findings are the cleanest example. Reading them
through Tar-Pit: invariants are essential declarative logic; their natural
code counterpart is a property-test or proof obligation, not a
`defn`. The drift finding correctly reports "no code-side counterpart" —
the *judgment* the LLM is invited to make is that this is a deferred gap
in the proof layer, not a missing function. Without the canvas+code
loop, the question "are these invariants realised anywhere?" would have
no purchase. With it, the question is *the* design conversation Phase 7
should foreground.
