# Phase 5 Sprint 1 — Feedback signals design

**Date:** 2026-05-26
**Status:** Draft for user review (pause point before Sprint 2 dispatch)

---

## Strategic frame

Phase 5's product is a thinking-enhancing tool: canvas is the intersection between LLM and
human, and Phase 5 maximises the feedback the substrate gives back about the integrity and
strength of a design as the author works. No web UI, no code-gen, no spec-vs-impl diff —
those are deferred. The product surface is plain Clojure fns under `src/fukan/canvas/{inspect,
architect}/`, surfaced to LLM authors through `(help)` in `fukan.agent.api` and invoked via
`bin/fukan eval`. A subagent (`fukan-architect`) orchestrates the interpretive end.

This doc settles which signals Sprint 3 builds. Sprint 3 has five task slots (Tasks 5–9); we
pick four to five and explicitly defer the rest. Every pick is justified against the canvas
corpus that already exists — the 62 fukan modules plus the two `/demo/` stress-tests — not
against speculative future use.

## The two-tier model

Phase 5 partitions signals along a single axis: **does the signal produce decision-ready
facts, or interpretive observations?**

- **Trust tier** (`src/fukan/canvas/inspect/`): pure fns over the canvas db. Output is
  decision-ready. A broken reference is broken; there is no judgment call. The LLM treats
  these as authoritative — if `inspect/integrity` reports something, fix it before continuing.
- **Weigh tier** (`src/fukan/canvas/architect/`): pure fns over the canvas db. Output is
  observational. A pattern cluster is real but whether to lift it is the author's
  call. Consistency drift may be intentional. The LLM (or the `fukan-architect` subagent)
  weighs the finding against context before acting.

The Phase 5 plan establishes the partition (`doc/plans/2026-05-26-canvas-substrate-phase-5.md`
§ "Two tiers: trust vs weigh"). This doc verifies the partition against each of the six
signal categories and flags any that resist it.

---

## Signal catalog

For each of the six categories from the Phase 5 plan: what it computes, what it surfaces,
which tier it lives in, and whether it ships in Sprint 3.

### 1. Structural integrity — TRUST tier — SHIP (Task 5)

**What it computes.** A handful of Datalog queries against the canvas db, each finding
entities whose declared references don't resolve:

1. **Unresolved `:references` Relations.** The substrate stores `:references` as a datom
   carrying a raw keyword target (e.g. `:model/Model`) — resolution to a concrete entity
   happens during projection (`canvas-source/resolve-reference-target`). The integrity check
   re-runs that resolution against the db and reports any keyword that fails to land.
   Query sketch:
   ```clojure
   (->> (d/datoms db :aevt :references)
        (keep (fn [d]
                (let [target (.-v d)
                      from   (.-e d)]
                  (when (and (keyword? target)
                             (nil? (resolve-reference-target db uuid->sid target)))
                    {:from (stable-id-for-eid db from)
                     :target target
                     :code   :unresolved-reference})))))
   ```
2. **Unresolved `:triggers` Relations.** `:triggers` is stored as `:db.type/ref`, so a typed
   rule lookup is sufficient — find any `:triggers` whose target eid has no `:entity/type`
   `:Affordance` with `:affordance/role :canvas/rule`. Today every `(triggers RuleName)` form
   binds the ref at construction time within `within-module` so this should be silent on the
   current corpus; the check guards against future drift (e.g. a rename that misses a call
   site).
3. **Cross-module shape-target resolution.** Every keyword captured in
   `:affordance/input-types`, `:affordance/output-types`, `:type/field-types` that has a
   namespace must resolve via `resolve-reference-target`. Note: these sets are *type names*,
   so atomic types (`:Integer`, `:String`, `:Unit`, `:Any`) are intentionally unresolvable
   and must be filtered out (they have no namespace).
4. **`:event/handler` `on` references.** `vocab.event/handler` stores its `on` target as a
   keyword in its formal expression and emits a `:references` for it. Covered by check (1)
   once we ensure the handler lift emits the `:references`. (Verify this in implementation;
   if not, this is a small Sprint 2 fix.)

**What it surfaces.** A vector of finding maps, each shaped like the existing constraint
violation map produced by `core/check.clj` (we reuse the schema for ergonomic uniformity):
```clojure
{:check     :inspect.integrity/unresolved-reference
 :severity  :error
 :message   "Reference :model/MOdel does not resolve to any module's child."
 :offenders [{:eid 42 :stable-id "model.pipeline/run_phase"}]
 :detail    {:target :model/MOdel
             :hint   "Did you mean :model/Model? Available modules with 'model' segment: model.spec, model.build, ..."}}
```
A `:hint` suggesting near-matches (Levenshtein over candidate entity names within
segment-matched modules) costs little and dramatically raises authoring value.

**Tier verification.** Clean fit for trust tier. If a keyword in a `:references` datom
doesn't resolve, that is a broken reference under any methodology. No interpretive judgment
required.

**Phase 5 priority.** Ship in Sprint 3 Task 5. Highest-confidence pick.

**Why.** Two pieces of evidence justify the priority:

- The Phase 4 verification doc names this as the most concrete thing the substrate
  gained from Sprint 1 Task 3 (shape-queryability) — input/output/field type names are now
  first-class datoms specifically so this kind of check becomes a single query.
- The notification module finding from the Sprint 3 stress-test (substrate disappears into
  pure design vocabulary) was a high point. The cost of that ergonomic win is that a typo in
  `:event-driven.payment/PaymentSucceded` produces no compile error, no test failure —
  silently nothing handles it. Trust-tier integrity is the safety net that keeps the
  ergonomic win from becoming a footgun in iterative authoring.

---

### 2. Pattern recurrence — WEIGH tier — SHIP (Task 8)

**What it computes.** Cluster Affordances by a structural similarity signature; flag clusters
of size ≥ 3.

The signature for an Affordance is a tuple:
```clojure
{:role            (:affordance/role aff)
 :input-types     (sort (:affordance/input-types aff))
 :output-types    (sort (:affordance/output-types aff))
 :has-formal-expr (boolean (:affordance/formal-expression aff))
 :returns-label?  (boolean (:affordance/returns-label aff))}
```
Group by signature, retain groups of ≥ 3, sort by group size descending. For each cluster,
emit the cluster's signature plus the stable ids of its members.

A second pass should detect *near-clusters* — affordances whose signatures differ by exactly
one type — because those are the candidates where adding one field/arg lifts an outlier into
the cluster. Phase 5 ships the exact match only; near-cluster is a follow-on if Sprint 4's
trial run wants it.

**What it surfaces.**
```clojure
{:cluster :affordances-by-shape
 :size 7
 :signature  {:role :canvas/checker
              :input-types  [:model/Model]
              :output-types [:agent/Violation]
              :has-formal-expr false
              :returns-label?  false}
 :members    ["validation.rules-4a/check"
              "validation.rules-4b/check"
              "validation.rules-4c/check"
              "validation.rules-4d/check"
              "validation.rules-4e/check"
              "validation.rules-4f/check"
              "validation.rules-4g/check"]
 :existing-lift "vocab.validation/checker"   ; if all share a role that is itself a lift
 :note      "All 7 affordances already use the checker lift — no action."}
```
When the cluster's role corresponds to an existing vocab lift (e.g. `:canvas/checker`), the
finding is annotated as "lift already exists, no action." When it doesn't, the finding
says "consider extracting a lift" and includes the proposed signature as a starting point
for the human/LLM to evaluate.

**Tier verification.** Clean fit for weigh tier. The cluster is a real fact; whether to
extract a lift is a judgment call — the architect-explorer Phase 2 reflection explicitly
warns against speculative lifts (rule-of-three plus rejection where the pattern doesn't
read well). Surfacing the cluster lets the LLM apply that judgment with evidence.

**Phase 5 priority.** Ship in Sprint 3 Task 8. Highest-leverage weigh-tier pick.

**Why.** The rule-of-three discipline drove the Phase 4 Sprint 3 success (10 `event`s and 6
`handler`s in the stress-test → both shipped; 0 `topic`s → deferred). Automating cluster
detection means the LLM in Phase 5 authoring loops doesn't have to mentally maintain a
cross-module counter. Concrete predicted hits when run against the live canvas:
- `validation.rules_4a..rules_4g/check` — 7 affordances, signature `:canvas/checker (Model)
  → [Violation]`. Already lifted; finding annotated as "no action."
- `canvas.constraint.phase5/run`, `canvas.target.clojure.analyzer/run`,
  `canvas.validation.phase4/run` — three `run`-named functions all `(takes [model
  :model/Model]) (gives :Phase4Result-or-Model) (triggers …) (returns "post.…")`. Signature
  near-match; might suggest a `phase-runner` lift but the human/LLM probably says no.
- The 11 `add_*` functions in `model.build` — all `(Model, X) → Model`. Already idiomatic
  Clojure; weighing concludes no lift needed.

These predicted hits tell us the signal will produce real findings on day one — at least
one cluster of seven and a handful of smaller ones — without false positives, because the
weigh tier treats interpretation as the author's responsibility.

---

### 3. Methodology coherence — WEIGH tier — DEFER

**What it would compute.** For each module, a fingerprint of which vocab lifts it uses
(`construction`, `vocab.behavioral`, `vocab.event`, etc.). Then a corpus-wide map of
fingerprints, with outliers flagged as potential drift.

**Tier verification.** Weigh tier — the same module mixing behavioral and event-driven
vocabulary may be intentional in a notification-style module that has both invariants and
handlers.

**Phase 5 priority.** Defer (Phase 6+ at earliest).

**Why defer.** The Phase 5 plan already calls this out: "Methodology fingerprint computation
is genuinely hard to design without a corpus of multi-paradigm projects to test against —
fukan's canvas is too uniformly fukan-shaped." Verified by the corpus:

- 62 fukan canvas modules: nearly all use `construction` + `vocab.behavioral`. A handful add
  `vocab.lifecycle` (getters) or `vocab.validation` (checkers in `validation/*`). There is no
  internal methodology contrast that a coherence checker could meaningfully observe.
- The `/demo/static-lib/*` and `/demo/event-driven/*` modules each use their methodology
  uniformly — static-lib uses `construction` only, event-driven adds `vocab.event`. The two
  demos don't mix, so there's no observed drift to validate against.

Building a methodology-coherence signal on a corpus that has no methodology contrast is the
kind of speculative work the architect-explorer reflection warns against. Revisit when the
canvas corpus contains a project that genuinely mixes paradigms.

---

### 4. Consistency — WEIGH tier — SHIP (Task 9)

**What it computes.** Three independent sub-checks, each filterable so authoring projects
can opt in/out:

1. **Naming-style consistency within a module.** For each module, classify each child's name
   into one of `:snake_case`, `:camelCase`, `:PascalCase`, `:kebab-case`, `:other` and flag
   modules where children mix styles across the *same kind*. (Mixing styles across kinds —
   e.g. `PascalCase` types and `snake_case` affordances — is expected canvas idiom and is
   not flagged.)
   Live-corpus example: `model.build` contains `add_primitive`, `edges_by_kind`,
   `empty_model` — uniformly `snake_case` for affordances. Expected silent. `model.spec`
   contains `Model` (type), `Primitive` (type) — uniformly `PascalCase`. Expected silent.
   A finding would fire if e.g. someone added `addPrimitive` mid-module.

2. **Field-name → field-type consistency across records.** For each field-name string that
   appears in two or more `record` declarations, check whether the type is the same. A
   typed `:status` that's `:String` in one record and `:Boolean` in another is a candidate
   normalisation.
   Query sketch:
   ```clojure
   (->> (d/q '[:find ?fname ?ftype
                :where [?t :entity/type :Type]
                       [?t :type/field-types ?ftype]]
              db)
        ; group by fname; flag where ftype set has > 1 distinct shape
        ; — caveat: :type/field-types is set-of-target-names, not field-name-typed
        )
   ```
   **Caveat noted:** `:type/field-types` is currently the set of referenced type *names*,
   not a `[field-name → type-name]` map. To run this check properly we either (a) add a
   second derived attribute `:type/fields [{:name :type}…]` during projection, or (b) parse
   `:affordance/shape` / the equivalent type shape blob from the source pr-str. Option (a)
   is the right move and Sprint 3 may need to add it as a small substrate addition (additive,
   like Sprint 1 Task 3 added `:affordance/input-types`). Flagging this as an open question
   below.

3. **Sister-module structural symmetry.** When a set of modules share a name prefix
   (`validation.rules-4a`, `validation.rules-4b`, …), expect them to have structurally
   parallel content. The check: group such sister modules, compare their child *kind +
   role* signature, flag mismatches.
   Live-corpus example: `rules_4a` through `rules_4g` should all expose exactly one
   `checker` named `check`, plus rules + invariants. If `rules_4d` is missing the checker
   or has a second public function, the human probably wants to know.

**What it surfaces.** Three families of observation, each annotated as observational not
prescriptive:
```clojure
{:check :lens.consistency/sister-module-asymmetry
 :note  "validation.rules-4a..rules-4g declare a `check` checker each; rules_4d additionally declares `check_extended`."
 :modules ["validation.rules-4a" "validation.rules-4d"]
 :asymmetry {:rules-4d-extras #{"check_extended"}}
 :interpret "If `check_extended` is intentional (e.g. a more thorough variant), this is fine. If it is a porting leftover, rename or remove."}
```

**Tier verification.** Clean fit for weigh tier. Naming-style mixing may be intentional
(authors might keep an external library's snake_case for ported names). Field-type
inconsistency might be deliberate domain modelling (`:status` is `:String` in one record and
`:UserStatus` enum in another for good reasons). Sister-module asymmetry may be the
intentional point of the asymmetric module.

**Phase 5 priority.** Ship in Sprint 3 Task 9. Sister-module symmetry is the highest-value
of the three sub-checks; ship at least that one. Naming-style + field-type are bundled.

**Why.** The 31 intra-module duplicate names in `validation/*` (Sprint 1 Task 1 surprise)
are the strongest existing evidence that the validation subsystem has structural symmetry
the substrate already trusts: same name, same pattern, sister modules. The consistency
signal catches the inverse failure — when a sister module starts to *drift*. This is the
exact kind of feedback that lets the architect-author lock in a methodology choice as a
module family grows.

---

### 5. Behavioral coverage — TRUST tier (mostly) — SHIP (Task 6)

**What it computes.** Several queries that flag entities whose substrate role implies they
need a coupling that isn't there:

1. **Orphan entities** (no incoming `:references`, `:triggers`, or `:module/child` from
   anywhere). Substrate-floating entities should not exist — every entity is owned by a
   module via `:module/child`. The check is a sanity guard against future projection bugs.
2. **Modules with no `(exports …)` declaration.** Per the canvas idiom, every module should
   declare its API closure via `exports`. The check finds modules with `:Affordance` children
   that carry no `:exported` tag. Some canvas modules legitimately export nothing public
   (pure-internal modules), so this finding is *info*-severity not *error*-severity. The LLM
   then weighs.
   Live-corpus expectation: most modules will be clean. `validation/rules_4*` modules expose
   one `check` each via `exports`; `infra/server` exports `ServerOpts ServerInfo` — but
   doesn't `exports` `start_server`/`stop_server`. Whether that's intentional or a bug is
   exactly the kind of question coverage should surface.
3. **Rules with no triggering function.** Every `(rule "X" (when X (…)))` should be reachable
   from some `(function … (triggers X))`. Sprint 2 Task 4 adds the `emits` form to symmetric
   coverage. Until then, this check captures the trigger half.
   Query:
   ```clojure
   (let [rule-eids (d/q '[:find [?r ...]
                           :where [?r :affordance/role :canvas/rule]] db)
         triggered (set (d/q '[:find [?r ...]
                                :where [?fn :triggers ?r]] db))]
     (remove triggered rule-eids))
   ```
4. **Exported entities never referenced externally.** An entity that's exported but no
   cross-module `:references` targets it may be dead canvas. Info severity, because (a) the
   reference might come from outside the canvas (real code consumers), and (b) export is a
   declared promise, not a usage claim.
5. **Events with no handler.** Every `vocab.event/event` declaration should have at least
   one `vocab.event/handler` somewhere with `(on :that/Event)`. Especially relevant for the
   `/demo/event-driven/` corpus and any future event-driven canvas in fukan.

**What it surfaces.** Each finding carries severity:
```clojure
{:check     :inspect.coverage/rule-never-triggered
 :severity  :warning
 :message   "Rule RunPhase4 has no function with (triggers RunPhase4)."
 :offenders [{:eid 88 :stable-id "validation.phase4/RunPhase4"}]
 :detail    {:expected-trigger "function in module validation.phase4 with (triggers RunPhase4)"}}
```

**Tier verification.** This one is interesting. Orphans, missing exports, and rule-trigger
gaps are *mostly* trust-tier: structural facts about the canvas db. But the partition
softens at the edges:

- "Module has no `(exports …)`" — info severity, weighing element.
- "Exported entity never referenced externally" — could be perfectly valid; reference
  consumer lives outside the canvas (real impl code), or is a public API consumed by
  consumers we don't track.

**Recommendation:** keep coverage in trust tier under `inspect/coverage.clj`, but carry an
explicit `:severity` field on every finding (`:error`, `:warning`, `:info`). LLM authors
treat `:error` findings as facts; `:warning`s and `:info`s as observations to weigh. The
`fukan-architect` agent's tier-awareness instructions explicitly include "severity tells you
when even a trust-tier finding wants weighing."

This is the one signal where the binary tier model bends. Documenting the bend explicitly is
better than forcing every coverage check into one tier or splitting the namespace.

**Phase 5 priority.** Ship in Sprint 3 Task 6.

**Why.** Coverage is what catches the failure modes Phase 4 didn't address: the 31
intra-module duplicate names *might* be coverage artifacts (every rule has a matched
invariant — is each invariant reachable structurally, or is the rule the only entry point?).
The signal will produce real findings against the current corpus and against the trial-run
authoring target in Sprint 4.

---

### 6. Delta awareness — TRUST tier — DEFER (or compact-ship if room)

**What it would compute.** Given two canvas db snapshots, diff them:
- Entities added (new `:entity/id` UUIDs)
- Entities removed (UUIDs in snapshot, absent from current)
- Entity attributes changed (docstring updates, shape changes, role changes)
- Reference impact: when entity X disappears, which `:references` datoms now break?

Implementation: serialize the canvas db to edn at a known location (`.fukan/canvas-snapshot.edn`),
diff against the current `build-canvas-db` output, present the delta.

**What it would surface.** A diff report keyed by stable id, with a "downstream breakage"
section listing references that now resolve to nothing.

**Tier verification.** Clean fit for trust tier. The diff is mechanical; the breakage impact
is mechanical.

**Phase 5 priority.** Defer.

**Why defer.** Three reasons:

- **Snapshot lifecycle is a design conversation in itself.** When is the snapshot taken?
  Manually? On `(refresh)`? On commit? Each answer implies different ergonomics, and the
  right choice is informed by Sprint 4's trial-run experience, not by speculation.
- **Sprint 3 has five task slots; the four trust-tier signals already chosen
  (integrity + coverage) are higher-value-per-slot.** Delta requires snapshot
  infrastructure to be useful at all, whereas integrity/coverage/patterns/consistency are
  one-shot queries against the live db.
- **The intent behind delta — "did my last change strengthen or weaken the design?" — is
  largely covered by re-running integrity + coverage after each `(refresh)`.** Delta adds
  precision (*which* refs broke) but the broken-refs information is already in integrity.
  Phase 5's primary thinking-enhancing win is "what's wrong now"; "what changed" is a Phase
  6+ refinement once the live signals are battle-tested.

Defer to Phase 6 with the understanding that revisiting it then is cheap — the snapshot
machinery is small and the diff query is straightforward against the existing db schema.

If the user disagrees on the priority, the natural slot is to drop consistency
(Task 9) and ship delta in its place. Consistency vs delta is a real fork in the road; my
default pick is consistency because it has direct corpus evidence (sister-module families
already exist) whereas delta is hypothetical until the authoring loop runs.

---

## Tier partition verification

Five of six signals fit the partition cleanly. One — **behavioral coverage** — bends it.

| Signal | Tier | Clean fit? |
|---|---|---|
| Structural integrity | trust | Clean |
| Pattern recurrence | weigh | Clean |
| Methodology coherence | weigh | Clean (but deferred — corpus too uniform) |
| Consistency | weigh | Clean |
| Behavioral coverage | trust | **Bends** — some findings are facts, others are observations; resolved by adding `:severity` to every finding |
| Delta awareness | trust | Clean (deferred for other reasons) |

The behavioral-coverage bend is genuine and worth surfacing to the user. The recommended
resolution — carry `:severity` on every coverage finding, instruct the LLM that `:error`
findings are factual and `:warning`/`:info` findings want weighing — is consistent with the
existing constraint diagnostics pattern (Phase 4 Task 9 already produced violation maps with
structured metadata; severity slots in naturally). No new infrastructure is required.

---

## Phase 5 priority — recommended signal set

Sprint 3 Tasks 5–9 (five slots). Recommended assignment:

| Task | Signal | Tier | Namespace |
|---|---|---|---|
| 5 | Structural integrity | trust | `src/fukan/canvas/inspect/integrity.clj` |
| 6 | Behavioral coverage | trust | `src/fukan/canvas/inspect/coverage.clj` |
| 7 | (optional) Field-name → field-type derived attribute on Type, or held in reserve for unknowns | substrate addition | `src/fukan/canvas/core/substrate/store.clj` + `projection/canvas_source.clj` |
| 8 | Pattern recurrence | weigh | `src/fukan/canvas/architect/patterns.clj` |
| 9 | Consistency | weigh | `src/fukan/canvas/architect/consistency.clj` |

**Deferred:** Methodology coherence (corpus too uniform; Phase 6+), Delta awareness
(snapshot-lifecycle design conversation; informed by Sprint 4's trial run; Phase 6+).

**Why not five strict signals.** Slot 7 is reserved either for the field-name→field-type
substrate addition that consistency needs, or — if the user prefers — for shipping delta
awareness instead. My default pick is slot 7 = substrate addition because:

- The plan's "Files NOT touched" section explicitly permits "a small additive change like
  Sprint 1's `type-names` extraction" if a feedback signal needs new substrate queryability.
  Field-name→field-type is exactly that.
- Without it, the field-type consistency sub-check has to parse `pr-str`-stored shapes at
  query time — workable but ugly, and a real-data parsing concern that risks bugs.
- The change is small (additive `:type/fields` cardinality-many map-of attribute populated by
  the existing `->datoms :Type` branch) and Sprint 3 already has hardening (Tasks 3–4) in
  Sprint 2 immediately preceding it, so substrate-touching work is contextually warm.

**Alternative if the user prefers delta over consistency:** drop Task 9, move consistency
sister-module-symmetry sub-check into coverage (it's the closest fit and the field-type
sub-check is dropped), and use the freed slot for delta. I do not recommend this default —
consistency has direct corpus evidence, delta does not.

---

## Open questions for the user

1. **Delta vs consistency.** Default pick is to ship consistency in Sprint 3 and defer delta
   to Phase 6 (covered above). Override?

2. **Field-name → field-type substrate addition.** Should Task 7 ship the additive
   `:type/fields` attribute, or should consistency punt on the field-type sub-check entirely
   and ship only naming + sister-module symmetry? My default is to ship the addition — it's
   small, additive, and unblocks a real check.

3. **Severity in coverage findings.** The recommendation is to carry an explicit `:severity`
   field on every coverage finding (`:error`/`:warning`/`:info`) and instruct the LLM
   accordingly. Acceptable? An alternative is to split coverage into two namespaces
   (`inspect/coverage` for errors, `architect/coverage-observations` for warnings/infos) but
   that splits a coherent computation across the tier boundary, which is worse.

4. **`:hint` quality for unresolved references (integrity).** Edit-distance suggestion on
   typoed reference keywords is a real ergonomic win but it adds complexity. Worth a small
   sub-task or out of scope for Sprint 3? My default: ship without `:hint` first, add
   in a follow-on if Sprint 4 trial-run shows reference typos are common.

5. **Sprint 4 trial-run target choice** (separate decision in Task 2, but mentioned here
   because pattern-recurrence and consistency findings only become evidence under live
   authoring). The Phase 5 plan suggests three candidates: refactor an existing canvas
   module, extend a demo, or author a new small subsystem. Pattern recurrence is most
   useful when the trial creates new affordances; consistency is most useful when the trial
   extends a sister-module family. The two-criteria pick is: extend `/demo/event-driven/`
   with a new handler-rich module (e.g. `event-driven.audit`), because that exercises both
   pattern recurrence (new handlers cluster with the notification handlers) and consistency
   (audit-as-sister-of-notification). But this is for Task 2 to settle.

6. **Subagent vs direct fn invocation surface.** The plan extends `fukan-architect` with a
   `survey design improvements` capability that invokes `architect/*` and synthesizes. The
   trust-tier signals are reachable directly via `bin/fukan eval`. Are we comfortable with
   that asymmetry, or should integrity/coverage also have a subagent path (e.g.
   `fukan-architect check`)? My default: keep the asymmetry. Trust-tier output is
   structured and short; the LLM can read the report directly. Weigh-tier output benefits
   from synthesis.


---

## Amendment — 2026-05-27 lens-substrate reframe

The user's reframe of 2026-05-27 introduces a **pluggable lens substrate** for the weigh tier. This doc's signal selection is largely unchanged; the architectural shift is in *how* the weigh-tier signals are organized and invoked.

**Key shifts:**

1. **`src/fukan/canvas/architect/` becomes `src/fukan/canvas/lens/`.** Each weigh-tier signal becomes a *lens* — a single namespace declaring `(def lens {:id ... :description ... :prompt-fragment ... :compute ... :render ...})`. Patterns and consistency carry forward as lenses.

2. **Phase 5 ships a third lens: `tar-pit` (theoretical).** This proves the substrate handles non-structural lenses where the prompt fragment is the primary artifact. Methodology coherence and Delta still defer.

3. **Sprint 3 task numbering shifts.** Was Tasks 5-9 (5 trust+weigh signals); now Tasks 6-10 with Task 8 landing the lens substrate + first lens together. Sprint 2 grows to Tasks 3-5 to absorb the `:type/fields` substrate addition this doc recommended.

4. **The signal catalog above is still valid.** Each signal selected (integrity, coverage, patterns, consistency) carries forward; only their organizational home shifts (weigh-tier signals now live in `lens/` not `architect/`).

5. **`fukan-architect` dispatches surveys via the lens registry.** The default lens-set covers `[:patterns :consistency :tar-pit]` for Phase 5. Per-dispatch override allows narrower experimentation.

See `doc/plans/2026-05-26-canvas-substrate-phase-5.md` Sprint 3 (Tasks 6-10) for the amended task list.
