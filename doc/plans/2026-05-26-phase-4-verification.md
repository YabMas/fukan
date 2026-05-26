# Phase 4 Verification Report — Substrate Hardening + Ergonomics

**Date:** 2026-05-26
**Status:** Complete
**Decision:** (1) Canvas + substrate are ready for Phase 5 authoring loop work

---

## 1. What Was Attempted vs. Built

Phase 4 ran four sprints across a single session day with one verified Sprint 1 notes doc
and one supplementary findings document for Sprint 3.

### Sprint 1 — Substrate hardening: identity + queryability (Tasks 1–4)

**Attempted:** Module-qualified cross-module reference resolution; stable-id contract with
alias mechanism; shape type names extracted as first-class queryable datoms.

**Built:**

- **Task 1 (module-qualified resolution):** `canvas-source/build-canvas-db` updated so
  `:model/Model` resolves by finding the Module whose dot-path name contains the keyword's
  namespace as a segment, then searching that module's children by entity name. Intra-module
  duplicate detection switched to warn-not-throw; strict throwing variant implemented and
  tested with synthetic data. Commit `fix(canvas/projection): module-qualified cross-module
  reference resolution (closes Phase 3 Q3)` / `6e6c4d8a`.

- **Task 2 (stable-id contract):** `src/fukan/canvas/identity.clj` created (190 lines).
  Three public functions: `stable-id` (pure; entity-type + module-name + entity-name →
  canonical string), `resolve-id` (pure; db + id-string → canonical id or nil), and `alias`
  (within-module side-effect; adds `:entity/alias` datom). Schema addition:
  `:entity/alias {:db/cardinality :db.cardinality/many}`. Commit `feat(canvas/identity):
  stable id contract with alias mechanism` / `6c4284cc`.

- **Task 3 (shape queryability):** `src/fukan/canvas/core/shape.clj` gained `type-names`
  (pure traversal returning a set of all atomic type names + ref targets in a parsed shape).
  `store.clj` schema extended with `:affordance/input-types`, `:affordance/output-types`,
  `:type/field-types` (all `cardinality/many`). The `->datoms :Affordance` and `->datoms :Type`
  multimethod branches emit these sets when the shape contains ref nodes. Commit
  `feat(canvas/substrate): extract input/output and field type names to first-class queryable
  datoms` / `adc8819b`.

**Sprint 1 surprise:** Task 1's stricter duplicate detection surfaced 31 intra-module
duplicate names, all in the validation subsystem — each `rules_4X.clj` and
`validation/violation.clj` declares both a `(rule "X")` and an `(invariant "X")` with the
same name. These are genuinely distinct entities (different roles, different formal
expressions), not data errors. The production pipeline uses warn-not-throw. Design
resolution deferred — see Section 5.

**Test count at Sprint 1 end:** 238 canvas suite tests / 785 assertions. 609 full project
tests / 1664 assertions.

**REPL verification:**
- `[?a :affordance/output-types :Phase4Result]` returns `[run, gate_g2]`.
- 31 affordances queryable as taking `:model/Model`.
- `identity/resolve-id db "infra.server/start"` resolves to `"infra.server/start_server"`.

### Sprint 2 — Phase 3 deferred decisions (Tasks 5–6)

**Attempted:** `triggers:`/`returns:` first-class; `tuple-of` combinator decision.

**Built:**

- **Task 5 (triggers/returns):** `construction.clj`'s `function` lift extended with two new
  body forms: `(triggers RuleName)` emits a `:triggers` Relation from the function's
  Affordance to the named rule's Affordance within the enclosing module; `(returns "label")`
  stores the label string on `:affordance/returns-label` (`:db/index true`). Schema entry
  `:triggers {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}` added.
  Backfilled four instances: `validation/phase4.clj` (`run` triggers `RunPhase4`, returns
  `post.result`), `target/clojure/analyzer.clj` (`run` triggers `RunClojureAnalyzer`, returns
  `post.model`), `constraint/phase5.clj` (`run` triggers `RunPhase5`, returns `post.model`),
  and one further instance. Commit `feat(canvas): triggers/returns first-class — function lift
  gains structural coupling forms (closes Phase 3 Q4)` / `d1037501`.

- **Task 6 (tuple-of):** Grep across all canvas ports found 5 tuple-shaped instances across 3
  modules (`model/artifact.clj`, `model/relations.clj`, `model/primitives.clj` ×3) — at and
  above the rule-of-three threshold. `(tuple-of T1 T2 ...)` shipped in `core/shape.clj`;
  `emit-refs!` in `construction.clj` and `vocab/event.clj`'s `emit-payload-refs!` extended
  to walk `:tuple` nodes. Commit `feat(canvas/shape): tuple-of combinator — 3 distinct tuple
  shapes at rule-of-three threshold` / `31a297d4`.

**Test count at Sprint 2 end:** 614 tests / 1669 assertions.

### Sprint 3 — Substrate stress-test (Tasks 7–8)

**Attempted:** Two paradigm stress-tests bracketing the substrate's architecture-neutrality
claim: static-lib (lower bound) and event-driven (upper bound). Both in `/demo/`.

**Built:**

- **`:demo` alias + workspace:** `deps.edn` extended with `:demo` alias using `["demo" "test"]`
  as extra-paths and `"test/demo"` as test directory. Demo tests carry `demo.*` namespace
  prefix; no classpath collision with `fukan.*` test namespaces. `dev/user.clj` extended with
  `(load-demo "<name>")` REPL helper that requires demo namespaces on demand and merges their
  per-module dbs via `canvas-source/merge-module-dbs`. Commit `build: add :demo alias and
  demo workspace structure` / `3c2763f3`.

- **Test IV (static-lib):** Five modules ported: `static-lib.vec2`, `static-lib.vec3`,
  `static-lib.matrix`, `static-lib.transform`, `static-lib.operations`. Used exclusively
  `construction` primitives (`record`, `function`, `exports`). No new vocab required.
  Five per-port commits.

- **Test I (event-driven):** Five modules ported: `event-driven.cart`, `.order`, `.payment`,
  `.shipping`, `.notification`. Surfaced two new lifts meeting rule-of-three:
  - `event` (10 instances across 4 modules) → `:canvas/event` role Affordance
  - `handler` (6 instances across 4 modules) → `:canvas/handler` role Affordance
  Both shipped in `src/fukan/canvas/vocab/event.clj` (117 lines). Five per-port commits.

- **Findings doc:** `doc/plans/2026-05-26-stress-test-findings.md`. Commit `doc(canvas):
  stress-test findings — event-driven + static-lib paradigm demos (Sprint 3)` / `f4c43108`.

**Demo test suite:** 43 tests / 52 assertions (`:demo` runner). These are separate from the
671 full-project tests and not included in that count.

**Test count at Sprint 3 end:** 667 full project tests / 1736 assertions.

### Sprint 4 — LLM authoring ergonomics (Tasks 9–11)

**Attempted:** Enriched constraint diagnostics; named-entity resolution in queries;
examples library per vocab.

**Built:**

- **Task 9 (diagnostics):** `src/fukan/canvas/core/check.clj` updated. `register-constraint!`
  macro now captures `{:ns *ns* :line (:line (meta &form))}` at registration call site.
  `check-all` enriches each violation's `:offenders` from raw eids to `[{:eid eid :stable-id
  <string-or-nil>}]` maps via `identity/stable-id-for-eid`. Violation maps now have shape:
  `{:constraint name :message string :source-location {:ns string :line int-or-nil}
  :offenders [{:eid eid :stable-id string-or-nil}]}`. Commit `feat(canvas/check): violation
  maps carry :source-location and :stable-id` / `eedeb878`.

- **Task 10 (named-entity resolution):** `src/fukan/canvas/core/defquery.clj` extended with
  `(this :module/name ?var)` special form. `(this :infra.server/start_server ?a)` expands into
  four datom patterns that bind `?a` to the entity named `start_server` within the module named
  `infra.server`. A fresh gensym'd module variable prevents clash when multiple `(this ...)`
  forms appear in one query body. Commit `feat(canvas/defquery): (this :module/name ?var) form
  for named-entity resolution in queries` / `c6cfa87b`.

- **Task 11 (examples library):** Six EXAMPLES docs created, one per vocab namespace:
  `src/fukan/canvas/construction.md`, `src/fukan/canvas/core/shape.md`,
  `src/fukan/canvas/vocab/behavioral.md`, `src/fukan/canvas/vocab/validation.md`,
  `src/fukan/canvas/vocab/lifecycle.md`, `src/fukan/canvas/vocab/event.md`. Each covers 2–3
  representative use cases, the resulting substrate shape, and naming conventions. Commit
  `doc(canvas): examples library per vocab — construction, shape, behavioral, validation,
  lifecycle, event` / `d15f5440`.

**Test count at Phase 4 end:** 671 tests / 1753 assertions (full project suite).

### Test count progression summary

| Phase milestone | Suite | Tests | Assertions |
|-----------------|-------|-------|------------|
| Phase 4 start (= Phase 3 end) | `:test` | 560 | 1578 |
| Sprint 1 end | `:test` | 609 | 1664 |
| Sprint 2 end | `:test` | 614 | 1669 |
| Sprint 3 end | `:test` | 667 | 1736 |
| Phase 4 end (Sprint 4) | `:test` | 671 | 1753 |
| Sprint 3 demo suite | `:demo` | 43 | 52 |

The `:demo` suite runs separately via `clj -M:demo`. It tests the stress-test modules as
independent canvas ports and does not affect the `:test` count.

---

## 2. Did Phase 3's Open Items Close Cleanly?

| Open item | Closed? | Where | Caveat |
|-----------|---------|-------|--------|
| Q3 — module-qualified name resolution | Yes | Sprint 1 Task 1 (`6e6c4d8a`) | Segment-matching, not full-name match — see below |
| Q4 — triggers/returns coupling | Yes | Sprint 2 Task 5 (`d1037501`) | Symmetric: Relation + attribute |
| Q5 — stable id evolution contract | Yes | Sprint 1 Task 2 (`6c4284cc`) | Alias mechanism in place; URL persistence not yet built |
| Phase 2 leftover — tuple-of combinator | Yes | Sprint 2 Task 6 (`31a297d4`) | 5 instances, 3 modules; rule-of-three met |
| Phase 3 broader shape-queryability gap | Yes | Sprint 1 Task 3 (`adc8819b`) | input/output/field types as first-class datoms |

**Q3 — segment-matching limitation.** The Task 1 implementation resolves `:model/Model` by
finding any Module whose dot-separated name contains `"model"` as a segment. This works for
all 25 distinct reference targets in the fukan corpus (`:model/Model`, `:ast/ConstraintRule`,
`:registry/Registry`, etc.). It also held up in the Sprint 3 event-driven demo
(`:event-driven.cart/CartCheckedOut` found `event-driven.cart` cleanly). The Sprint 1 notes
document the limitation explicitly: in a project where two modules share a path segment (e.g.
`accounts.users` and `users.accounts`), `:users/X` would match both — ambiguous, back to
documented first-match-wins. The real fix (full module-name matching) requires backfilling
all canvas cross-module references to use full dot-path namespaces (`:model.spec/Model` rather
than `:model/Model`). Deferred.

**Q4 — triggers/returns coupling.** The symmetric design shipped as planned: `(triggers
RuleName)` emits a `:triggers` Relation (graph edge); `(returns "label")` stores a string on
`:affordance/returns-label` (queryable attribute, not a graph edge). Four instances backfilled
across `validation/phase4`, `target/clojure/analyzer`, `constraint/phase5`, and one further
instance. The implementation is sound; formal-expression grammar to reference the
returns-label in post-conditions is Phase 5+ scope.

**Q5 — stable id contract.** The `identity` namespace provides the canonical format
(`module-name/entity-name`, with `/state/` and `/type/` infixes), `resolve-id` with alias
lookup, and the `alias` within-module form to declare old-id mappings. The contract is
specified and tested. What it does not yet do: wire into URL-based selection (Phase 5 scope)
or provide a rename-detection tool. Aliases are manually declared by authors, not auto-tracked
from git history.

**Duplicate-name detection update (Sprint 1 side-effect).** The 57 cross-module duplicate
names that Phase 3 warned about are no longer a problem with module-qualified resolution.
Task 1's detection now distinguishes: cross-module duplicates are expected and silenced;
intra-module duplicates warn. The 31 intra-module duplicates surfaced in validation/* are
a pending design question (see Section 5, caveat 1).

---

## 3. Did the Substrate Stress-Test Reveal Architecture-Bias?

Sprint 3's question: is the substrate's "architecture-neutral" claim empirically defensible,
or is it shaped by fukan-the-monolith?

### Lower bound (static-lib): is the substrate over-built?

The static-lib demo ported a 2D/3D geometry library — 5 modules of types and pure functions,
no behavioral content. Lifts used: `record`, `function`, `exports` only. No behavioral vocab
required, no new vocab considered.

**Finding:** The substrate is NOT over-built. Porting the static lib felt lighter than porting
a fukan module. The `construction` namespace provides exactly the right minimum: types, typed
functions, API closure. An author who only needs these never encounters `vocab.behavioral`,
`vocab.event`, or any behavioral machinery. The layered opt-in design works at both ends of
the complexity spectrum.

**What this falsifies:** The hypothesis "the substrate has hidden coupling to behavioral
vocabulary that makes trivial cases awkward." It doesn't. `construction` + `exports` is a
clean primitive for any project at the lower end.

### Upper bound (event-driven): does architecture-neutrality hold under stress?

The event-driven demo ported an order-fulfillment service — 5 modules (cart, order, payment,
shipping, notification) with event declarations, reactive handlers, and cross-module event
routing. This paradigm is as far from fukan-the-monolith as the stress-test could practically
reach in one sprint.

**Finding:** Architecture-neutral, validated under stress. The substrate's six primitives
(Module, Affordance, State, Type, Relation, Tag) were sufficient. Two new vocabulary lifts
were required and shipped (`event` and `handler`), but both were additive to the vocabulary
layer — not changes to the substrate primitives. The boundary held: new paradigm concepts
belong in `vocab.*`, not in the substrate.

**Rule-of-three table:**

| Lift | Instances | Modules | Verdict |
|------|-----------|---------|---------|
| `event` | 10 | cart(3), order(1), payment(3), shipping(3) | Shipped |
| `handler` | 6 | order(1), payment(1), shipping(1), notification(3) | Shipped |
| `topic` | 0 | — | Deferred (not observed) |

**Most important finding of Sprint 3:** The notification module — a module containing only
handlers and no events of its own — read as pure design content with no substrate plumbing
visible. A reader of `notification.clj` sees three named reactions to three named events
with zero ceremony. `(handler "handle_payment_succeeded" ... (on :payment/PaymentSucceeded))`
is the entire module description. This is the strongest evidence to date that the `vocab.*`
layer sits at the right altitude.

**One semantic gap observed (non-blocking):** command functions in the event-driven demo that
emit events (e.g. `checkout` in `cart.clj`) use docstring annotations to say "emits
CartCheckedOut" rather than a first-class `emits` form within `function`. The existing
`effect` form handles imperative effects; there is no structural way today to say "this
function emits an event that downstream handlers will react to." This is a design question for
Phase 5 or 6, not a blocking gap. Docstring annotation is a reasonable encoding for now.

**What was NOT tested:** Actor model and CQRS paradigms were chosen as sprint-3 targets but
not executed — the plan's Task 7 selected two representative brackets (static-lib as lower,
event-driven as upper). Erlang-style actor messaging is structurally similar to event handlers
(named reactions to messages); CQRS is structurally a refinement of function + event. Both
fall within the bracket validated by the two tests. That said, formally we tested two
paradigms, not four. The claim is "held at both ends" not "held universally."

---

## 4. LLM Authoring Ergonomics — Are They Noticeably Better?

Three concrete improvements shipped in Sprint 4.

### Enriched diagnostics (Task 9)

When a constraint registered with `register-constraint!` fires a violation, the violation map
now carries:

- `:source-location {:ns "fukan.canvas.validation.rules-4c" :line 42}` — the file and line
  where the constraint was registered. Previously, tracking a firing constraint required
  searching the codebase for the constraint name.
- `:offenders [{:eid 17 :stable-id "validation.rules-4c/AttachReturnsRequiresTriggers"}]`
  — the stable id of each offending entity. Previously, raw Datascript eids were returned with
  no further context.

The diagnostic is structurally equivalent to `clj-kondo`'s output format. An LLM receiving
a violation can answer "where is this constraint defined?" and "which entity triggered it?"
from the violation map alone, without a follow-up substrate query.

### Named-entity resolution in queries (Task 10)

`(this :model/Model ?e)` in a `defquery` body expands into four datom patterns that bind `?e`
to the entity named `Model` in module `model`. Without this, reaching a specific named entity
required knowing its Datascript eid or grinding through `[:find ?e :where [?e :entity/name
"Model"] [?m :module/child ?e] [?m :entity/name "model"]]` by hand each time.

The form composes with the rest of `defquery`'s expansion grammar and is particularly useful
for constraints that start from a specific anchor and traverse outward (e.g. "find all
affordances of the pipeline module that have no `:triggers` Relation").

One caveat: `(this :module/name)` is now wired into `defquery` expansion and `fc/check`
constraint bodies, but there is no higher-level "find module X's children of role Y" REPL
helper. An LLM authoring a new constraint still writes the full clause chain. The form removes
the name-lookup boilerplate but not the structural traversal.

### Examples library (Task 11)

Six EXAMPLES docs co-located with the vocab source files:

| File | Coverage |
|------|----------|
| `src/fukan/canvas/construction.md` | `function`, `record`, `value`, `exports` |
| `src/fukan/canvas/core/shape.md` | Shape grammar (`optional`, `list-of`, `tuple-of`, `map-of`, etc.) |
| `src/fukan/canvas/vocab/behavioral.md` | `invariant`, `rule` |
| `src/fukan/canvas/vocab/validation.md` | `checker` |
| `src/fukan/canvas/vocab/lifecycle.md` | `getter` |
| `src/fukan/canvas/vocab/event.md` | `event`, `handler` |

An LLM reaching for canvas vocabulary via grep or file-reads now finds concrete patterns
adjacent to definitions. The format is uniform across all six: pattern name, representative
example, resulting substrate shape, naming conventions, and when to use a different lift.

**Caveat on "noticeably better":** We have no controlled comparison. There is no benchmark
of "fresh subagent time to first correct port" before and after these changes. The evidence is
structural — the improvements are tangible — but whether they translate to meaningfully
faster LLM authoring will only be visible in Phase 5 usage. Phase 4 built the scaffolding;
Phase 5 runs the experiment.

---

## 5. Decision

**Outcome (1): Canvas + substrate are ready for Phase 5 authoring loop work.**

### Evidence

Phase 4 closed all five open items inherited from Phase 3 (Q3, Q4, Q5, tuple-of, shape
queryability). The substrate stress-test validated architecture-neutrality at both bounds —
no primitive changes were required for either paradigm. The vocabulary layer absorbed a new
paradigm (event-driven) with two additive lifts and no substrate surgery. LLM authoring
ergonomics received three concrete improvements.

The specific evidence:

| Criterion | Result |
|-----------|--------|
| Phase 3 open items closed | 5 of 5 — Q3, Q4, Q5, tuple-of, shape-queryability |
| Substrate primitive stability | No primitive changes across Sprint 1-4 |
| Architecture-neutrality claim | Validated at lower (static-lib) and upper (event-driven) bounds |
| Ergonomics improvements | 3 shipped: diagnostics, named-entity resolution, examples library |
| Test suite | 671 tests / 1753 assertions / 0 failures / 0 errors |

### Caveats (not blocking Phase 5)

**Caveat 1 — 31 intra-module duplicate names in validation/*.** The 31 cases where a canvas
port declares both `(rule "X")` and `(invariant "X")` with the same name are genuine design
content (related rule + invariant pairs), not errors. The production pipeline warns rather
than throws. Three resolution options are documented in the Sprint 1 notes doc but no decision
was reached. This is a design conversation, not a blocking defect.

**Caveat 2 — Segment-matching resolution heuristic.** `:model/Model` resolves by segment
match, not by full module name. Works for all current corpus references and held up in the
event-driven stress-test. Would fail in a project where two modules share a path segment.
The real fix (full-path backfill across all canvas ports) is not Phase 5 work; the heuristic
is adequate for fukan-itself.

**Caveat 3 — `(this :module/name)` not yet a higher-level REPL helper.** The form removes
name-lookup boilerplate in constraint bodies but an "all children of module X with role Y"
query still requires writing out the full clause chain. Marginal ergonomics gap; addressable
in Phase 5 if usage surfaces the need.

**Caveat 4 — Browser sanity check still skipped.** Phase 3 Q1 deferred a live browser
validation. Phase 4 did not address it. The canvas-to-graph pipeline is structurally wired
and test-verified but has never been observed in the browser against real canvas-sourced
content. This caveat carries into Phase 5 as its first action.

None of these caveats require a Phase 4.5. The substrate is structurally sound. Phase 5's
first session resolves Caveat 4 (browser check) and the remaining caveats can be addressed
as they surface during authoring work.

---

## 6. Phase 5 Implications

Phase 5 is the authoring loop. Phase 4 explicitly deferred this: "Phase 5 picks up the
browser-side authoring loop once Phase 4 settles the rest." Phase 4 settled the rest.

### Browser sanity check first

Phase 3 Q1 and Phase 4 Caveat 4: before any authoring tooling, run the server, open `/graph`,
confirm canvas-sourced entities appear and render correctly. Everything downstream depends on
this. The specific risk: a schema mismatch between what `canvas-source/project` emits and
what the cytoscape transformer or Malli validator expects (e.g. a required field that
canvas-source doesn't emit). Phase 3 noted that at least one such gap was caught during
implementation (`:parameters []` added explicitly to `:primitive/operation` entries). There
may be others. The browser check is the single most important first action for Phase 5.

### Editing surface decision (Phase 3 Q2)

Three coherent paths:

1. **Embedded canvas editing in the graph viewer.** Inline canvas edits while viewing the
   graph. High implementation cost; requires Phase 5 UI work. Browser check must pass first.

2. **Text-mode interface (separate).** Canvas files edited as Clojure with REPL tooling; graph
   updates on `(refresh)`. The edit → `(refresh)` → browser-refresh cycle is already
   functional. Lowest-complexity starting point and the most natural fit for the current
   Clojure-native canvas design.

3. **Chat-driven authoring via the bin/fukan CLI.** LLM proposes canvas declarations in
   response to natural-language design questions; human approves and commits. Requires
   extending `bin/fukan` for write operations (currently read-only).

The architect canvas design doc (`doc/plans/2026-05-25-architect-canvas.md`) positions
fukan as a Clojure-embedded thinking tool, which maps to path 2. Phase 5 should confirm or
revise this before building tooling.

### LLM as co-author

Phase 2's emergence experiment validated one-shot LLM-driven design discovery (three
independent sessions converged on vocabulary without source priming). Phase 5 should test
the iterative loop: not porting but co-authoring a module from scratch.

Directions enabled by Phase 4:

- **Completion / autosuggest.** The six EXAMPLES docs give LLMs concrete patterns to
  interpolate from. A co-author reaching for the next lift form finds the right template
  adjacent to the vocabulary definition.
- **Inconsistency detection.** The enriched `fc/check` diagnostics (`:source-location`,
  `:stable-id` in violations) give LLMs enough context to diagnose constraint failures
  without additional substrate queries.
- **Cross-reference suggestion.** The `(this :module/name)` form and the
  `:affordance/input-types`/`:affordance/output-types` datoms enable queries of the form
  "find all affordances in the model subsystem that take a `Model` input and have no
  `:triggers` Relation" — a useful co-author capability.
- **Paradigm-aware suggestion.** The architect-explorer pattern from Phase 2 Sprint 2
  (a system prompt that activated layered-language thinking across three independent LLM
  sessions) is not yet formalized. Phase 5 should either formalize it as a
  CLAUDE.md/AGENTS.md authoring convention or test it empirically in the authoring loop.

### Real-time canvas → graph reflection

The current pipeline rebuilds the canvas db on every `(refresh)`. The `(refresh)` cycle is:
reload changed namespaces → re-run `canvas-source/build` → cache the projected model. This is
adequate for the text-mode editing path: author edits a canvas file, calls `(refresh)`, sees
changes on next browser request.

Per-keystroke reflection would be expensive (full rebuild on every edit event). Phase 5 does
not need to solve this. The `(refresh)` granularity is appropriate for the architect-as-author
workflow, where edits are deliberate and reflection latency of 2–5 seconds is acceptable.
If a finer-grained loop is needed, the natural approach is a watch-mode wrapper around
`(refresh)` triggered on file save — not a pipeline rewrite.

### Authoring conventions to surface in CLAUDE.md / AGENTS.md

Phase 5 should add canvas-authoring guidance to both docs. At minimum:

- Which vocab namespace to reach for based on the concept being modeled (the "when to use a
  different lift" sections in the EXAMPLES docs are the starting material).
- The `(refresh)` workflow — authors working in canvas files should know that
  `(refresh)` replaces `(reset)` for canvas-only changes.
- The stable-id convention — agents proposing entity renames should know to add an `alias`
  declaration.

### Open questions Phase 5 needs to resolve

**Q1 — Does the graph viewer actually render canvas-sourced content?** The browser sanity
check has been deferred through two phases. Phase 5's first commit is a session log of "ran
server, opened /graph, observed X." If it fails, understanding why is the priority before
any authoring tooling is built.

**Q2 — Which editing surface for Phase 5?** Embedded vs text-mode vs chat-driven. The
architect canvas design doc favors text-mode; confirm before building tooling. The answer
shapes every subsequent Phase 5 task.

**Q3 — What does `emits` look like on `function`?** The event-driven stress-test surfaced
that command functions which emit events have no first-class form to express the
relationship. The `effect` form covers imperative effects; event emission is semantically
distinct. Phase 5 authoring of event-driven content will surface whether this gap is
blocking or whether docstring annotation remains adequate.

**Q4 — How to resolve the 31 intra-module duplicate names?** The three options (rename to
differentiate, allow by name+role, promote as convention) remain open. Any Phase 5 work that
extends the validation subsystem or adds similar rule+invariant pairings will encounter this.
A decision before that work starts is cheaper than a retroactive rename of 31 pairs.

**Q5 — What is the formal shape of the LLM co-authoring loop?** Phase 2 tested one-shot
discovery; Phase 5 tests iterative co-authoring. What does the loop look like structurally?
Who proposes, who evaluates, how are proposals committed? The architect canvas design doc
sketches a direction; Phase 5 needs to make it concrete enough to run a first experiment.

---

## Appendix: Phase 4 Artifact Inventory

| Artifact | Description |
|----------|-------------|
| `src/fukan/canvas/identity.clj` | 190 lines — stable-id contract: stable-id, resolve-id, alias |
| `src/fukan/canvas/core/shape.clj` | Extended with `type-names` traversal + `tuple-of` case |
| `src/fukan/canvas/core/substrate/store.clj` | Schema additions: input-types, output-types, field-types, returns-label, triggers |
| `src/fukan/canvas/core/check.clj` | Enriched violation maps: source-location + stable-id |
| `src/fukan/canvas/core/defquery.clj` | `(this :module/name ?var)` named-entity resolution form |
| `src/fukan/canvas/construction.clj` | `function` lift extended with `(triggers ...)` and `(returns ...)` forms |
| `src/fukan/canvas/vocab/event.clj` | 117 lines — `event` + `handler` lifts (rule-of-three: 10 + 6 instances) |
| `src/fukan/canvas/projection/canvas_source.clj` | Module-qualified segment-matching resolution |
| `demo/event_driven/` (5 files) | Event-driven upper-bound stress-test |
| `demo/static_lib/` (5 files) | Static-lib lower-bound stress-test |
| `src/fukan/canvas/construction.md` | EXAMPLES: function, record, value, exports |
| `src/fukan/canvas/core/shape.md` | EXAMPLES: shape grammar |
| `src/fukan/canvas/vocab/behavioral.md` | EXAMPLES: invariant, rule |
| `src/fukan/canvas/vocab/validation.md` | EXAMPLES: checker |
| `src/fukan/canvas/vocab/lifecycle.md` | EXAMPLES: getter |
| `src/fukan/canvas/vocab/event.md` | EXAMPLES: event, handler |
| `doc/plans/2026-05-26-phase-4-sprint-1-notes.md` | Sprint 1 design questions deferred |
| `doc/plans/2026-05-26-stress-test-findings.md` | Sprint 3 findings: lower + upper bound analysis |

**Phase 4 commit count:** ~25 commits (3 Sprint 1 hardening + 2 Sprint 2 + 1 build + 10 demo
ports + 1 vocab.event + 1 stress-test findings + 3 Sprint 4 ergonomics + this doc).

**Test state at Phase 4 close:** Full project suite 671 tests / 1753 assertions / 0 failures /
0 errors. Demo suite 43 tests / 52 assertions / 0 failures / 0 errors.
