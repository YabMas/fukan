# Phase 6 Sprint 1 — Drift signals design

**Date:** 2026-05-27
**Status:** Draft for user review (pause point before Sprint 2 dispatch)

---

## Strategic frame

Phase 5 closed the canvas-side feedback loop. Trust-tier helpers (`inspect/integrity`,
`inspect/coverage`) catch broken canvas; weigh-tier lenses (`patterns`, `consistency`,
`tar-pit`) surface design observations. Both run over the canvas db alone — *intent*
querying itself. Phase 6 opens the second loop: **intent compared against implementation**.
Canvas describes what the system should be; `src/` is what it is. Drift findings tell the
LLM author and the human collaborator where the two have separated, in either direction.

Phase 6 inherits Phase 5's trust/weigh discipline. A finding that *something is or isn't
there at the expected code-side address* is decision-ready: the canvas declares a function
`infra.server/start_server`, the analyzer projects an expected address
`src/fukan/infra/server.clj#start-server`, and either the symbol is present or it isn't.
That is a fact, not a judgment. By contrast, a finding that *the implementation has grown
beyond what the canvas describes* requires per-project judgment (some functions
legitimately have no canvas counterpart — utility helpers, private implementation glue).
Those findings belong in weigh tier.

This doc settles which of the seven candidate drift categories ship in Sprint 3 and which
defer. The selection is grounded in two constraints: (a) what the existing
`fukan.target.clojure.analyzer` can already detect today vs. what would need new analyzer
work, and (b) what the live canvas+src/ pair in fukan-itself can give us as first-run
evidence. Both shape the priority more than any abstract ranking of "what kinds of drift
matter."

---

## The two-tier model (carried forward from Phase 5)

- **Trust tier** (`src/fukan/canvas/inspect/drift.clj`): pure fns over the post-analyzer
  model. Output is decision-ready. A canvas function with no code at its canonical address
  is missing; the message and severity are computable; the LLM acts without weighing. Each
  finding carries `:severity` (`:error` / `:warning` / `:info`) per the coverage helper's
  pattern so callers filter.
- **Weigh tier** (`src/fukan/canvas/lens/drift.clj`, optional): lens content for findings
  that are interpretive — "module X has 12 drift findings clustered around field-name
  mismatch — is this a renaming-in-progress signal?" Whether to ship a drift lens is
  decided after Sprint 3; if the trust-tier output is sufficient on its own, no lens
  ships in Phase 6.

The Phase 5 verification doc (Section 2) showed the partition holds best when
trust-tier severity is in-band and the LLM is told `:error` is factual while
`:warning`/`:info` invite weighing. Phase 6's drift findings follow the same rule.

---

## Detection-feasibility ground truth

A pivotal observation from reading `analyzer.clj` + `source.clj` + canvas-source: **the
analyzer ALREADY runs against canvas-projected primitives.** Canvas-source emits
`:primitives` keyed by stable id with `:kind` ∈ `{:primitive/operation, :primitive/rule,
:primitive/container, :primitive/event}` (canvas-source.clj L445-L450 and L498-L563).
The analyzer's `(operations model)`, `(rules model)`, `(entities-values-variants model)`,
`(events model)` selectors filter exactly those kinds (analyzer.clj L123-L142). Every
canvas-declared `function`, `getter`, `checker`, `rule`, `invariant`, `record`, `value`,
and `event` therefore *already* produces a `:relation/projects` edge tagged with
`:validity {:valid | :absent}` and a canonical-address Code.* artifact.

What that means concretely:

- **Categories 1 (missing implementation) and 2 (missing canvas) ride existing analyzer
  capability.** The substrate already tells us, for every canvas affordance, whether a
  code symbol exists at its canonical address. The substrate also already materializes
  Code.* artifacts for every src/ defn that *isn't* projected (`materialize-unprojected`
  in analyzer.clj L174-L188) — those are the "code without canvas" candidates.
- **Category 3 (shape drift on records) is partially supported.** The analyzer creates a
  Code.DataStructure artifact for every canvas record at its expected address, and
  `source/extract-symbols` picks up `def` forms. But the analyzer does NOT compare *field
  contents*: it only checks the symbol's existence (`find-symbol source-index ns name
  :data-structure`). To detect field-level drift the analyzer needs new work — reading the
  `def`'s body, parsing the Malli schema or map shape, normalizing field-name pairs.
  Doable but non-trivial.
- **Category 4 (signature drift on functions) is the same story.** Symbol existence is
  checked; arity and parameter types are not. The analyzer would need to either (a) read
  the `defn` form and pull its arglist + `:malli/schema` metadata, or (b) shell out to
  clj-kondo's analysis. Both are real work.
- **Categories 5 (invariant without enforcement) and 6 (rule without trigger in code) are
  weakly supported.** An invariant projects to a code function at a canonical address per
  the projector's `function-kinds` set (`:projection-kind/invariant`) — but the canvas's
  `(invariant …)` lift currently doesn't drive that projection (canvas invariants are
  prose `holds-that` clauses, not code). The "is the invariant enforced anywhere?" check
  is therefore a *semantic* question the analyzer has no machinery for. Rule-trigger
  coverage is already covered canvas-side by `inspect/coverage`'s
  `check-rules-without-trigger`; the code-side variant would need to detect *call sites*
  in `src/` that match the rule, which is grep-level work the analyzer doesn't do.
- **Category 7 (event without emit/handler in code) is similar.** Canvas already exposes
  `:emits` (Phase 5 Sprint 2 Task 4). The code-side variant requires detecting which src/
  fns emit which events. The analyzer reads symbol declarations but doesn't trace
  call/emit relationships.

**Summary table:**

| Category | Analyzer capability today | New work needed |
|---|---|---|
| 1. Missing implementation | Full — `:validity :absent` on projects edges | None |
| 2. Missing canvas | Full — `materialize-unprojected` + filter projected | None |
| 3. Shape drift on records | Symbol existence only | Field-name + field-type extraction from `def` bodies |
| 4. Signature drift on functions | Symbol existence only | Arity + Malli schema extraction from `defn` metadata |
| 5. Invariant without enforcement | None | Semantic mapping invariant → enforcement site |
| 6. Rule without trigger in code | None (canvas side covered) | Call-site detection in src/ |
| 7. Event without emit/handler in code | None | Call-site detection of emit/handler invocations |

This shapes the Phase 6 priority. Categories 1 and 2 are "free" — read existing analyzer
output, structure it as findings, register in the agent API. Categories 3 and 4 are
"one analyzer extension each." Categories 5, 6, 7 are deeper work that ships better
in Phase 7+ once the cheaper signals have produced authoring evidence.

---

## Signal catalog

For each of the seven categories: what it computes, what it surfaces, tier verdict,
detection feasibility, Phase 6 priority, and why.

### 1. Missing implementation — TRUST tier — SHIP (Sprint 3)

**What it computes.** Walk the model's `:relation/projects` edges. For each edge whose
endpoint is a canvas-projected primitive (`:endpoint/primitive`) and whose `:validity` is
`:absent`, emit a finding. Cross-reference the primitive id back to its canvas stable id
(via `module-coord-of-primitive` already in analyzer.clj) so the message names both the
canvas-side originator and the expected code-side address.

Query sketch:

```clojure
(defn check-missing-implementation [model]
  (->> (:edges model)
       (filter #(and (= :relation/projects (:kind %))
                     (= :absent (:validity %))
                     (= :endpoint/primitive (-> % :from :case))))
       (map (fn [edge]
              (let [pid (-> edge :from :id)
                    prim (get-in model [:primitives pid])
                    aid (-> edge :to :id)
                    art (get-in model [:artifacts aid])]
                {:check     :inspect.drift/missing-implementation
                 :severity  :warning
                 :message   (str "Canvas " (name (:kind prim)) " " pid
                                 " has no implementation at " (:qualified-name art))
                 :offenders [{:canvas-id pid :expected-address (:qualified-name art)}]
                 :detail    {:primitive-kind  (:kind prim)
                             :projection-kind (-> edge :metadata :projection-kind)}})))))
```

**What it surfaces.** Mirrors the integrity/coverage shape: `:check`, `:severity`,
`:message`, `:offenders`, `:detail`. The `:offenders` carry both sides — canvas stable id
+ expected code address — so the LLM can act without further lookups.

**Tier verification.** Clean fit for trust tier with severity `:warning` (not `:error`):
a missing implementation is a fact, but the resolution is a judgment call — could be a
forthcoming implementation, a deliberately deferred TODO, or a canvas-side declaration
that should be retracted. The fact is decision-ready; the action isn't.

**Detection feasibility.** Already supported. Zero analyzer changes. Just read existing
`:validity :absent` projects edges.

**Phase 6 priority.** Ship in Sprint 3, Task 6. The highest-confidence pick — both
because it's free (no analyzer work) and because the live canvas+src/ pair will
produce real findings on day one against fukan-itself.

**Why.** Concrete predicted hits when run against fukan-itself:
- `canvas/infra/server.clj` declares `function "start_server"` and `function "stop_server"`,
  which project to `src/fukan/infra/server.clj#start-server`/`stop-server` — both present
  (verified by reading the src). Expected: clean.
- `canvas/distributed/cluster.clj` declares `function "get_node"` and `function "members"`
  — the trial-run distributed subsystem has NO matching `src/fukan/distributed/cluster.clj`
  implementation. Expected: a cluster of findings against the entire distributed canvas.
  This is exactly the gap drift exists to catch and is also the natural target shape for
  Phase 6 Sprint 4's trial run (extend the distributed canvas with matching code).
- Several canvas modules under `canvas/validation/rules_4*` declare `checker "check"`
  affordances. The corresponding src/ contains `src/fukan/validation/rules_4a.clj` etc.
  Expected: mostly clean with possibly a few `:absent` for checkers that never landed.

This is the **load-bearing first signal**. It anchors the Phase 6 thesis: drift findings
close the loop between design and implementation.

---

### 2. Missing canvas — WEIGH tier — DEFER (or compact-ship as a lens if Sprint 1 reframes)

**What it would compute.** Walk the model's `:artifacts`. For every Code.* artifact that
is NOT the target of any `:relation/projects` edge, emit a finding. These are the
"unprojected" artifacts — src/ defns / defs with no canvas claim.

```clojure
(defn- projected-artifact-ids [model]
  (set (keep (fn [e] (when (= :relation/projects (:kind e))
                       (-> e :to :id)))
             (:edges model))))

(defn check-missing-canvas [model]
  (let [projected (projected-artifact-ids model)]
    (->> (:artifacts model)
         (remove (fn [[aid _]] (projected aid)))
         (map ...))))
```

**Tier verification.** **Weigh tier, not trust.** Whether every public defn deserves a
canvas counterpart is a per-project judgment. fukan-itself has hundreds of internal
helpers, private fns, and reload-machinery defns that legitimately have no canvas
presence. Flagging all of them as `:error` or even `:warning` would drown the signal.

**Detection feasibility.** Already supported (analyzer's `materialize-unprojected`).
Zero analyzer changes.

**Phase 6 priority.** **Defer** — but as a *signal-design* deferral, not a feasibility
one. The data is free; the noise is the problem. Three options for Phase 7+ resolution:

1. Filter to only `^:export`-tagged or otherwise-public symbols, treating private helpers
   as legitimately uncanvased.
2. Apply a "canvas-coverage threshold" per module: flag modules where >50% of public
   symbols have no canvas counterpart, rather than per-symbol.
3. Ship as a **weigh-tier lens** that surfaces patterns ("module X has 12 unprojected
   public defns — is this an undocumented surface?") rather than as a trust-tier check.

Option 3 is the natural home if drift gets a lens at all. Sprint 1 leaves this open for
the user; the default recommendation is to **defer all three to Phase 7**, because
shipping any of them before the trust-tier signals have produced authoring evidence
risks the same noise-domination Phase 5 Sprint 3 Task 7 hit with coverage's orphan check
before the role-exemption refinement.

**Why defer.** The Phase 5 verification doc explicitly warned that warning-dominated
output is the failure mode to avoid for in-flight authoring. fukan's `src/` is mature and
has plenty of structurally-legitimate unprojected code; the first run would produce
hundreds of findings. Better to wait until Phase 7 where filter heuristics or a lens
framing can be designed against real authoring evidence rather than designed first and
adjusted after.

---

### 3. Shape drift on records — TRUST tier — SHIP (Sprint 3) — IF analyzer extension lands

**What it computes.** For each canvas `record` (which projects to a Code.DataStructure
artifact via `:projection-kind/schema`), compare the canvas-declared field list
(`:type/fields` derived attribute, added in Phase 5 Sprint 2 Task 5) against the
implementation's actual fields.

Required analyzer extension: when `source/extract-symbols` encounters a `:data-structure`,
also read the `def`'s body. Parse Malli-style schemas (`[:map [:field-name :Type] ...]`)
or plain Clojure maps; emit a per-field `{:name :type}` list alongside the existing
symbol record.

Query sketch:

```clojure
(defn check-record-shape-drift [canvas-db model]
  (for [[t fields] (canvas-records-with-fields canvas-db)
        :let [expected (set (map :name fields))
              addr     (canvas->code-address t)
              code-fields (get-in model [:code-records addr :fields])
              actual   (set (map :name code-fields))]
        :when (and code-fields (not= expected actual))]
    {:check     :inspect.drift/record-shape-drift
     :severity  :warning
     :message   (str "Record " (:entity/name t)
                     " field-name set differs between canvas and code.")
     :offenders [{:canvas-id (canvas-stable-id t) :code-address addr}]
     :detail    {:canvas-only (clojure.set/difference expected actual)
                 :code-only   (clojure.set/difference actual expected)
                 :common      (clojure.set/intersection expected actual)}}))
```

A field-type drift sub-check is the second pass: for each common field name, compare the
declared type keyword. Surface as a separate finding code
(`:inspect.drift/record-field-type-drift`).

**What it surfaces.** Two finding subcodes: `record-shape-drift` (field-name set
mismatch) and `record-field-type-drift` (per-field type mismatch). Both carry the canvas
side, the code side, and a per-field diff for ergonomic resolution.

**Tier verification.** Clean fit for trust tier. A field-name mismatch is structural
fact. Severity `:warning` (not `:error`) because the resolution is a judgment call —
maybe canvas should add the field, maybe code should remove it, maybe the schema
representation in code legitimately differs from the canvas shape (canvas is design
altitude; code may impose Malli constraints that canvas doesn't need).

**Detection feasibility.** **Needs new analyzer work.** Specifically:

- Source-walker extension: parse `def` bodies past the symbol header. Pull Malli schema
  or map shape. ~30-50 LoC of structural reading in `source.clj`.
- Type-keyword normalization: canvas declares `:Integer`, `:String`, etc. as atomic; the
  code-side `^:schema` annotations use `:int`, `:string`. Need a small alias table to
  reconcile.
- A new attribute on Code.DataStructure artifacts carrying the parsed fields. Or a sibling
  derived structure (`:code-records` map keyed by canonical address).

Estimated: half a session for the analyzer extension + half a session for the drift
check + tests + run-against-fukan. Inside Sprint 3's task budget if it's prioritized.

**Phase 6 priority.** **Ship — conditionally on Sprint 1 Task 2's design conversation
confirming the analyzer extension fits within Sprint 3's scope.** If Sprint 1 Task 2
(canvas→code projection design) concludes that field-level introspection is bigger than a
half-session, drop this category and ship a leaner Sprint 3 with categories 1 + 7 only.

**Why.** Predicted hits against fukan-itself: the `:model/Model` record in
`canvas/model/spec.clj` declares six fields
(`primitives`, `edges`, `tag_definitions`, `tag_applications`, `predicate_registrations`,
`renderer_registrations`). The src/ implementation in `src/fukan/model/build.clj` should
match. Drift here would be an immediate, decision-ready finding the author wants to know
about — the model record's field list is load-bearing. Similar high-value targets:
`:infra.server/ServerOpts` (one field, easy), `:infra.server/ServerInfo` (one field),
the `:distributed.cluster/Cluster` record (four fields, no current implementation so this
would show as missing-implementation, not shape-drift, but indicates the future shape
once distributed lands).

---

### 4. Signature drift on functions — TRUST tier — DEFER

**What it would compute.** For each canvas `function`, compare the declared
`takes`/`gives` against the implementation's actual arglist + return type. Reading the
canvas side is already done (`:affordance/input-types`, `:affordance/output-types`). The
code side requires arity extraction from `defn` forms plus optional Malli-schema reading
from `:malli/schema` metadata.

**Tier verification.** Trust tier; same severity discipline as shape drift (`:warning`).

**Detection feasibility.** **Needs new analyzer work, larger than shape drift.**
Specifically:

- Source-walker extension: parse `defn` forms past the symbol header. Extract the
  arglist vector (and optionally destructuring keys). Pull `:malli/schema` from the
  attribute map if present.
- Type-keyword normalization more delicate than for records: canvas uses
  `(takes [opts :ServerOpts])` and `(gives (optional :ServerInfo))`; code uses
  `:=> [:cat :ServerOpts] [:maybe :ServerInfo]`. Mapping between the two is a non-trivial
  reconciliation step.
- Multi-arity defns: most fukan code is single-arity but some isn't. The check needs a
  rule for which arity to compare (probably: canvas commits to ONE arity, drift fires if
  any code arity diverges).
- Function-shaped affordances include `getter`, `checker`, plus regular `function`. Each
  has its own canonical-signature convention. The comparison rule branches.

Estimated: ≥ 1 full session for the analyzer extension alone, plus another for the drift
check + reconciliation + tests + run-against-fukan. **Heavier than half-of-Sprint-3.**

**Phase 6 priority.** **Defer to Phase 7.** Three reasons:

1. The cost-benefit is worse than shape drift. Records have ~3-6 fields; functions have
   ~1-3 typed parameters. The drift surface per primitive is smaller, so the value-per-
   analyzer-line is lower.
2. The reconciliation work between canvas type expressions and Malli schemas needs
   careful design — Phase 6 Sprint 1 Task 2 (canvas→code projection) is where that
   reconciliation should be settled, and rushing it in Sprint 3 risks producing a brittle
   first version.
3. Function existence is already covered by category 1 (missing-implementation).
   Signature *drift* is a secondary refinement once existence is established. Better to
   ship existence checking, observe authoring use, then extend into signature.

If user prefers, the slot freed by deferring category 4 goes to category 3 (shape drift)
to ensure that one lands cleanly within Sprint 3.

---

### 5. Invariant without enforcement — DEFER

**What it would compute.** Walk canvas `:canvas/invariant` affordances. For each, look
for a code mechanism enforcing it — a schema check, a `defn` whose body asserts the
condition, a test that exercises the invariant. Emit a finding when none found.

**Tier verification.** Trust if "enforcement found" / "not found" is mechanizable; weigh
if it requires interpreting whether *partial* enforcement counts. The latter is the
realistic case — an invariant like `AtMostOneLeaderPerTerm` is enforced *across* multiple
code paths (vote-granting in election.clj, term-adoption in cluster.clj, leadership
acceptance), none of which is the canonical enforcement site. Reducing this to a binary
"yes/no" requires either a naming convention (e.g. invariant names match test names) or a
direct canvas→code coupling (a future canvas form like
`(enforces InvariantName by ...)`).

**Detection feasibility.** **No support today.** The analyzer has no machinery for
matching an invariant's prose `holds-that` clause to a code path. The projector's
`:projection-kind/invariant` exists in the type vocabulary but is dead code today
(canvas's `invariant` lift doesn't drive a projection per current canvas-source).

**Phase 6 priority.** **Defer to Phase 7+.** This is the category Phase 6 most wishes
it could ship — invariants without enforcement are the most consequential drift kind —
but the path from "invariant declared as `holds-that` prose" to "code path enforces it"
is a research direction, not a Sprint 3 task. Phase 7 (implementation-instruction
generation from drift findings) is where this category logically lands: once the
substrate can articulate *how* an invariant should be enforced (in code, test, or schema
form), then it can also detect *whether* it's enforced.

The Phase 6 plan's "What drift detection means concretely" #5 notes this trade-off:
"The author can decide: write the enforcement, or relax the invariant to documentation."
Deferring acknowledges that *both decision arms require canvas→code-enforcement
machinery* the substrate doesn't have yet.

---

### 6. Rule without trigger in code — DEFER

**What it would compute.** Canvas declares `(rule "X" (when X (...)))`. The trigger side
in canvas is already covered by `inspect/coverage`'s `check-rules-without-trigger`. The
code-side variant: scan src/ for fns that call/emit the rule's trigger; flag rules whose
trigger has no call site in code.

**Tier verification.** Trust if the trigger-call-site detection is mechanical; weigh if
trigger fan-out is interpretive.

**Detection feasibility.** **No support today.** The analyzer extracts symbol
declarations but not call relationships. Detecting "this src/ fn calls fn X" requires
either a runtime trace (out of scope), clj-kondo's analysis output (a real integration
path but new tooling), or our own form-walker tracking calls.

**Phase 6 priority.** **Defer to Phase 7+.** The canvas-side rule-without-trigger
check already exists in `inspect/coverage`. The code-side variant adds value but requires
significant new infrastructure for marginal benefit. Same trajectory as category 5:
useful but only after Phase 7's enforcement-mapping work is in place.

---

### 7. Event without emit/handler in code — TRUST tier — SHIP (Sprint 3)

**What it computes.** For each canvas `:canvas/event` affordance, look for code-side
evidence of the event being emitted or handled. The check has TWO sub-flavors:

- **7a. Event projected as artifact, but artifact absent.** Riding category 1's
  capability: a canvas `event` already projects to a Code.DataStructure at a canonical
  address (per analyzer.clj L141-L142 `events` selector + `emit-data-structure-projection`).
  If the schema/record isn't there, drift fires. **Free — same machinery as
  missing-implementation.**
- **7b. Event present in code but neither emitted nor handled.** This is the trickier
  variant — requires call-site analysis. Defer to Phase 7.

Sprint 3 ships 7a only. Surface message clearly distinguishes "the event's data structure
isn't defined in code" from "no code path emits/handles the event."

**What it surfaces.**

```clojure
{:check     :inspect.drift/event-schema-missing
 :severity  :warning
 :message   "Event LeaderElected declared in canvas distributed.election has no code-side schema at src/fukan/distributed/election.clj#LeaderElected."
 :offenders [{:canvas-id "distributed.election::LeaderElected"
              :expected-address "fukan.distributed.election/LeaderElected"}]
 :detail    {:role :canvas/event}}
```

**Tier verification.** Clean fit for trust tier. Severity `:warning` — same logic as
missing-implementation; the fact is decision-ready, the resolution is judgment.

**Detection feasibility.** Already supported for 7a (rides on the same `:validity
:absent` machinery as category 1). The 7b variant (call-site detection) defers.

**Phase 6 priority.** **Ship 7a in Sprint 3** as part of the same task as category 1
(it's a subset — events are one of the four kinds the analyzer already projects). 7b
defers to Phase 7.

**Why.** Predicted hits against fukan-itself: the `/demo/event-driven/` corpus has rich
canvas event declarations (`PaymentSucceeded`, `LineItemAdded`, etc.) which according to
the trial-run findings doc were authored before Phase 5's integrity helper. Whether the
*demo* corpus has matching code is irrelevant per the user's "demos out of scope" default,
but the same machinery against any canvas events in `canvas/` proper will produce real
findings against the distributed canvas (event-shaped state-machine messages once
distributed/election lands its code side).

Because 7a is structurally a subset of category 1 — both ride `:validity :absent` on
projection edges — Sprint 3 Task 6 can ship them together. The drift helper would have
*one* `check-missing-implementation` fn that distinguishes by primitive kind in its
finding messages.

---

## The directional question

The user's default is **option (3) — both directions; LLM decides per-finding**. The
design doc confirms this default with one refinement.

The seven categories split naturally by direction:

| Category | Direction | Framing |
|---|---|---|
| 1. Missing implementation | canvas → code | "canvas declares X; code is missing it" |
| 2. Missing canvas | code → canvas | "code has Y; canvas doesn't describe it" |
| 3. Shape drift on records | bidirectional | per-field diff names which side has what |
| 4. Signature drift on functions | bidirectional | same |
| 5. Invariant without enforcement | canvas → code | "canvas declares X; no code enforces it" |
| 6. Rule without trigger in code | canvas → code | "canvas declares X; no code triggers it" |
| 7. Event w/o emit/handler in code | canvas → code (7a)<br/>bidirectional (7b) | same |

**Refinement:** the *per-finding `:message` field always names both sides explicitly*.
A missing-implementation finding says "Canvas `function infra.server/start_server`
declares an implementation; no `defn start-server` at `src/fukan/infra/server.clj`."
A shape-drift finding says "Record `Model` has fields `[a b]` in canvas;
fields `[a c]` in code."

This framing avoids declaring one side authoritative. The LLM is given a fact and chooses
how to respond. The `fukan-architect` system prompt (extended in Sprint 4) instructs the
LLM that *resolution* is contextual: if the missing implementation is a deliberate
deferral, mark it; if it's an oversight, write the code; if the canvas declaration was
speculative, retract it.

**Confirmation: option (3), with bilateral framing in every finding's `:message`.**

---

## Tier partition verification

| Signal | Tier | Clean fit? |
|---|---|---|
| 1. Missing implementation | trust | Clean (severity `:warning` — fact is clear, resolution is judgment) |
| 2. Missing canvas | weigh (eventual) | Clean; deferred to Phase 7 because filter heuristics need authoring evidence first |
| 3. Shape drift on records | trust | Clean (severity `:warning`) |
| 4. Signature drift on functions | trust | Clean; deferred for analyzer-cost reasons |
| 5. Invariant without enforcement | trust if mechanizable; weigh in practice | **Bends** — no detection mechanism today; defer |
| 6. Rule without trigger in code | trust | Clean; defer for analyzer-cost reasons (call-site detection) |
| 7. Event w/o emit/handler in code | trust (7a) / mixed (7b) | 7a clean; 7b bends like category 6 |

The Phase 5 precedent for handling severity ambiguity (coverage's `:error`/`:warning`/
`:info` ladder) carries forward unchanged. Drift findings ship with `:warning` as the
default — every drift finding is a *fact about a discrepancy*, but the resolution is
*always* judgment (write code, retract canvas, accept the gap as deferred). No drift
finding should ship as `:error`-severity in Phase 6: there's no "broken under any
methodology" drift the way an unresolved cross-reference is broken under any methodology.

---

## Phase 6 priority — recommended signal set

Sprint 3 has roughly 3-4 task slots for drift categories (Sprint 3 Tasks 5-8 in the
plan, with Task 5 reserved for the analyzer adaptation and Task 8 reserved for an
optional lens). The recommended set fits within 3 slots:

| Sprint 3 task | Signal | Tier | Detection cost |
|---|---|---|---|
| Task 5 | Canvas-as-projection-input adaptation (Sprint 1 Task 2 settles details) | — | Substantive — wires canvas content as analyzer's expected-side input |
| Task 6 | **Missing implementation + Event-schema-missing** (categories 1 + 7a, same machinery) | trust | Zero analyzer change |
| Task 7 | **Shape drift on records** (category 3) | trust | One analyzer extension (~half a session) |
| Task 8 | (optional) Drift lens — only if Sprint 3 evidence warrants | weigh | Lens drop-in |

**Three signals ship in Sprint 3.** Categories 1 + 7a (Task 6) and category 3 (Task 7).

**Deferred to Phase 7+:**
- Category 2 (missing canvas) — needs noise-control heuristics designed against authoring evidence.
- Category 4 (signature drift) — analyzer extension is heavier than shape drift; ship after shape evidence.
- Category 5 (invariant without enforcement) — research direction; needs canvas→enforcement-mapping substrate work.
- Category 6 (rule without trigger in code) — call-site detection infrastructure.
- Category 7b (event without emit/handler) — same.

**Push-back on the user's default of four:** the user's starting position was
{missing-implementation, shape-drift-on-records, invariant-without-enforcement,
event-without-handler-in-code}. The design recommends **dropping invariant-without-
enforcement (cat 5) and event-without-handler (cat 7b)** because both need analyzer
machinery the existing substrate doesn't have, and adding it within Phase 6 Sprint 3
would balloon scope past the 3-4 session estimate. They are kept as deliberate Phase 7
candidates with clear scope notes.

The shipped set ({1, 7a, 3}) is **three signals**, not four. Sprint 3 stays lean; the
trial run (Sprint 4) exercises this set against real authoring; Phase 7 picks up the
deferred items with the benefit of Sprint 4's evidence on which gaps actually felt
load-bearing.

---

## Open questions for the user

1. **Confirm three-signal Sprint 3 (vs. user's four-signal default).** Recommended:
   {missing-implementation, event-schema-missing, record-shape-drift}. Deferred:
   {missing-canvas, signature-drift, invariant-without-enforcement,
   rule-without-trigger-in-code, event-without-emit/handler-call-site}.
   The deferrals are detection-cost driven; the shipped set rides existing analyzer
   capability plus one half-session extension. Override if you want the heavier set
   regardless.

2. **Confirm directional framing (option 3 with bilateral `:message`s).** Every drift
   finding's `:message` names both canvas side and code side, regardless of which side
   has the gap. LLM decides per-finding which side should move. Push back if you prefer
   a unidirectional default (recommend against — see "The directional question").

3. **Shape-drift analyzer extension scope.** Category 3 requires extending
   `target/clojure/source.clj` to parse `def` bodies for field structures. The cost is
   ~half a session; if Sprint 1 Task 2 (canvas→code projection design) concludes this is
   bigger than estimated, drop category 3 from Sprint 3 and ship only categories 1 + 7a.
   Sprint 1 Task 2 has discretion to confirm or push back here.

4. **Severity default.** Recommended default for all drift findings is `:warning`. No
   drift finding ships as `:error` (there's no methodology-independent drift the way
   broken references are methodology-independent). Confirm or override.

5. **Drift lens (Sprint 3 Task 8) — ship or skip?** Recommended: **skip in Phase 6**.
   The trust-tier findings carry enough structure that a lens would mostly be a
   pattern-aggregation view ("3 modules cluster around shape drift; 2 around missing
   impl"). Useful eventually, but the Phase 5 verification doc showed trust-tier output
   alone produced authoring value without lens overlay. Defer to Phase 7+ once
   authoring evidence accumulates.

6. **Demos-out-of-scope confirmation.** The user's default is "drift checks `src/` only,
   not `/demo/*/`." The shape of the analyzer (`code-root` parameter) makes this a
   configuration concern, not a code concern — the analyzer already takes a single root
   path. Confirm `code-root = "src"` for Phase 6.

7. **Trial-run target shape (Sprint 4 Task 10) — hint for the controller.** The clearest
   shipping target for the trial run is **the distributed canvas + a partial src/
   implementation**. `canvas/distributed/cluster.clj` and friends already declare three
   modules' worth of canvas content with NO matching `src/fukan/distributed/`
   implementation. The trial run can build out *some* of it (cluster state + a getter or
   two), deliberately leave gaps, invoke drift, close gaps, re-invoke. The distributed
   canvas was Phase 5's trial-run target; reusing it for Phase 6's trial run carries the
   substantive design context forward.

---

## Amendment — 2026-05-27 user-review settlement

After review, the user pushed back on this doc's recommendation to defer invariant-without-enforcement: **"invariants and rules should definitely be projected into code."** The reasoning is sound — canvas declarations already encode the code-side counterpart (`holds-that "X"` for invariants; the rule's own name for rules). Detection is mechanical, not blocked by absent analyzer capability.

**The reframe collapses the drift "categories" into two signals**, not three or four:

| Signal | Catches | Mechanism |
|---|---|---|
| **Missing implementation** | Canvas-declared fn / event / invariant / rule (and getter, checker) with no code counterpart | Reads `:validity :absent` on any projected entity, uniformly |
| **Shape drift on records** | Field-level mismatch between canvas record and code defrecord/Malli schema | Field-pair comparison after the analyzer's def-body extension |

Each canvas declaration's code-side counterpart is the canonical mechanical projection:

- `function "X"` → fn named `kebab(X)` in the canvas module's matching src/ namespace
- `event "X" (payload …)` → event-schema artifact at the matching address
- `invariant "X" (holds-that "Y")` → predicate fn named `Y` (the `holds-that` clause names it directly)
- `rule "X" (when X (…))` → predicate fn named `kebab(X)` (option a from the user-review conversation — symmetric with invariants, mechanically simple)
- `getter "X"` → fn named `kebab(X)` (same pattern as function)
- `checker "X"` → fn named `kebab(X)` (same pattern)
- `record "X" (field …)` → defrecord/Malli schema named `X`; shape-drift signal compares fields

**Events stay deferred** (event-without-handler-in-code, Category 7b from this doc). Event "enforcement" isn't named in the canvas declaration the way `holds-that` names invariant enforcement; handler detection needs call-site analysis the analyzer doesn't have. Phase 7+ candidate.

**Sprint 3 task list becomes:**
- Task 6: Missing-implementation drift helper (umbrella across all projected entity types)
- Task 7: Shape-drift-on-records helper + the half-session `def`-body analyzer extension
- Task 8 (was conditional, now omitted): Drift lens — defer until Sprint 4 trial-run evidence warrants

**Open question 1 (3 vs 4 signal Sprint 3) — resolved**: 2 signals.
**Open question 2 (directional framing both ways) — confirmed**: yes.
**Open question 3 (half-session shape-drift extension) — confirmed**: yes, Sprint 3 Task 7.
**Open question 4 (severity `:warning` default) — confirmed**: yes.
**Open question 5 (skip drift lens for Phase 6) — confirmed**: yes, defer.
**Open question 6 (`code-root = "src"`) — confirmed**: yes.
**Open question 7 (trial-run target) — confirmed**: `canvas/distributed/*` paired with new `src/fukan/distributed/*`.

The amendment makes Phase 6 simpler than originally drafted: 2 signals instead of 4, but with broader projection coverage. The mechanical simplicity comes from canvas declarations naming their own code-side counterparts — `holds-that` was the load-bearing observation.
