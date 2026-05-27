# Canvas + Substrate Implementation Plan — Phase 5

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make fukan's canvas the powerful thinking-enhancing surface for human-LLM design collaboration. Maximum feedback on design integrity and strength as the author works. **No web/browser UI in this phase** — deferred. **No code-generation or implementation-diff work in this phase** — also deferred. The product surface is the REPL, `bin/fukan` CLI, system-prompt artifacts, and Clojure helpers that turn canvas authoring into a thinking-augmented activity.

**Phase 5's strategic frame:** Phases 1-4 produced a canvas-native analysis substrate with rich queryability. The substrate works; the vocabulary is principled; the examples library exists. What's still flat is the **author's experience of using it**. A human or LLM editing canvas files gets test-pass-or-fail and a graph viewer. They don't get: "this Affordance has no incoming references," "these 4 records have nearly identical shapes," "your new lift pattern appears 5 times — consider promoting to vocab.*," "your changes since last refresh broke 2 invariants." Phase 5 builds the feedback signals that make canvas authoring feel like a conversation with the substrate.

The user reframe: *canvas is the intersection between LLM and human; maximum feedback on the integrity and strength of design; the best thinking-enhancing tool we can make.*

**Architecture:** All Phase 5 work happens in `src/fukan/canvas/` (mostly new namespaces for feedback signals) plus `bin/fukan` (CLI surface for invoking them) plus `doc/` (system prompts and authoring workflows). The substrate, vocabulary libraries, projection pipeline are unchanged unless a feedback signal needs new substrate queryability — in which case a small additive change like Sprint 1's `type-names` extraction is acceptable.

**Tech stack:** Same. Clojure 1.11, Datascript. New: probably some clojure.spec or malli for cross-reference checking; possibly clj-async-profiler-style instrumentation if a signal needs it. nREPL for the live workflow.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-26-phase-4-verification.md` — Phase 4 outcomes; what's already in place to build on
- `doc/plans/2026-05-25-architect-explorer-system-prompt.md` — the Phase 2 Sprint 2 system prompt that activated layered-language thinking in LLMs; Phase 5 promotes this to permanent reusable status
- `doc/plans/2026-05-26-stress-test-findings.md` — Sprint 3 stress-test, including the notification module finding (substrate disappearing into pure design vocabulary)
- `doc/plans/2026-05-26-phase-4-sprint-1-notes.md` — the 31 intra-module duplicate names design question
- `src/fukan/canvas/core/check.clj` — Phase 4 Sprint 4's enriched diagnostics
- `src/fukan/canvas/core/defquery.clj` — the `(this :module/name ?var)` resolution form
- `src/fukan/canvas/identity.clj` — stable-id contract

**Scope of this plan:** Phase 5 only.

**Subsequent phases (out of scope here):**
- Phase 6 — Web/browser UI for canvas (the viewer + editing surfaces the user explicitly deferred)
- Phase 7 — Diff detection between canvas design and code implementation
- Phase 8 — Generating implementation instructions from design-vs-code diffs
- Per-phase vocab expansion (`vocab.cqrs`, `vocab.actor`, etc.) is ongoing across phases as use evidence warrants

---

## What "thinking-enhancing tool" means concretely

A human or LLM authoring canvas content should, while they work, receive these kinds of feedback (Phase 5 builds the ones that matter most):

1. **Structural integrity** — Does this entity reference resolve? Are there orphan entities? Do my invariants name things that exist?
2. **Pattern recurrence** — Am I writing the same shape repeatedly? Should I lift it?
3. **Methodology coherence** — Does this module's vocabulary match the methodology I implicitly chose? Or am I drifting?
4. **Consistency** — Are my entity names following the conventions sister modules use? Are field types consistent across records that share a concept?
5. **Behavioral coverage** — Do declared rules have corresponding invariants? Are all events handled?
6. **Delta awareness** — What did I just change? Did it break anything elsewhere? Did it strengthen or weaken the design?

Phase 5 ships infrastructure for these signals + integrates them into a workflow the LLM can invoke. Not all six become first-class — Sprint 1's design conversation picks which to prioritize.

### Two tiers: trust vs weigh

A second axis cuts across the six signals: **does the signal produce facts that can be acted on by code, or observations that require interpretation?**

| Signal | Output | Tier | Lives |
|---|---|---|---|
| Structural integrity | "this reference doesn't resolve" — broken refs are errors | **trust** | `src/fukan/canvas/inspect/` |
| Behavioral coverage | "this entity has no incoming refs; this rule has no trigger" — severity varies | **trust** | `src/fukan/canvas/inspect/` |
| Delta awareness | "X removed since snapshot; these refs now break" — mechanical impact | **trust** | `src/fukan/canvas/inspect/` |
| Pattern recurrence | "these 5 affordances share a shape" — interpretation needed | **weigh** (lens) | `src/fukan/canvas/lens/` |
| Consistency | "naming style varies in this module" — intentional? worth normalizing? | **weigh** (lens) | `src/fukan/canvas/lens/` |
| Methodology coherence | "vocabulary fingerprint doesn't match the dominant paradigm" — drift? mixed methodology by design? | **weigh** (lens) | `src/fukan/canvas/lens/` |
| Theoretical perspectives (Tar Pit, FCIS, DDD, …) | "does this design honor essential-complexity minimization?" — pure interpretation | **weigh** (lens) | `src/fukan/canvas/lens/` |

**Trust tier (`inspect/*`):** pure functions over the canvas db; output is decision-ready. If `inspect/integrity` says a reference is broken, the reference is broken — no interpretation required. The LLM treats these as authoritative.

**Weigh tier (`lens/*`):** pluggable *lenses* — each lens is a single namespace declaring a contract: identifier, description, prompt fragment, optional compute fn over the canvas db, render fn. Lenses output observations that the LLM weighs. The lens substrate is the **load-bearing pluggability surface of Phase 5** — see the next section.

**Discovery surface:** the agent API's `(help)` fn (in `fukan.agent.api`) lists trust-tier fns and registered lenses separately so the LLM sees the trust distinction at discovery time. `AGENTS.md` gets a short section explaining the partition.

**Invocation surface:** both tiers go through `bin/fukan eval`. Example:
- Trust: `bin/fukan eval '(canvas.inspect.integrity/check (model))'` → structured violations report
- Weigh: `bin/fukan eval '(canvas.lens.survey/run (model) [:patterns :consistency])'` → unified survey through requested lenses

**Subagent surface (orchestrated weighing):** the existing `fukan-architect` agent is extended (Sprint 4 Task 10) to dispatch surveys via the lens substrate. A `survey design improvements` request invokes `(survey/run (model) <lens-set>)` and synthesizes findings. The lens set is configurable per dispatch — default set in agent definition; specific lenses requestable in the prompt.

**Constraint integration deferred.** Trust-tier helpers COULD be wired as built-in `fc/check` constraints (every `:references` Relation must resolve, etc.) but Phase 5 ships them as plain pure fns. Constraint wiring is a follow-on if use evidence demands automatic enforcement — requires `fc/check` to grow severity-awareness, which today's binary model doesn't have.

### The lens substrate (Phase 5's pluggability core)

The user reframe (2026-05-27): *"discovering what works and doesn't in terms of agent interaction will be an ongoing effort... build the integration pluggable from the start. Prompts should be swappable, but also the different modes of thinking/surveying; e.g. looking for recurring patterns is one, but checking the existing high-level design against certain perspectives/theories (e.g. Out of the Tar Pit paper) would be another, and I want to freely experiment with different modes."*

**What a lens is.** A lens is a way of seeing the canvas. Each lens is a single Clojure namespace under `src/fukan/canvas/lens/` declaring a `lens` var of shape:

```clojure
(def lens
  {:id          :patterns                          ; namespaced or simple keyword
   :description "Recurring-shape detection — rule-of-three lift candidates"
   :prompt-fragment "..."                          ; LLM priming content for this lens
   :compute     (fn [canvas-db opts] ...)          ; optional — structural lenses compute findings; theoretical lenses may skip
   :render      (fn [findings opts] ...)})         ; composes findings + prompt-fragment into LLM-consumable context
```

**Two lens shapes.** Structural lenses (patterns, consistency) compute findings from canvas-db queries. Theoretical lenses (Tar Pit, FCIS, DDD, ...) primarily contribute a prompt fragment that frames the canvas through a theory; their `compute` fn may extract relevant slices (e.g. for Tar Pit: all `:canvas/getter` Affordances as candidate state, all `function` Affordances as candidate behavior) but the interpretation is LLM-driven.

**Substrate machinery** (lives at `src/fukan/canvas/lens/`):

- `core.clj` — the lens contract; a small spec for the `lens` map; `validate-lens` helper
- `registry.clj` — discovers `lens` vars from all `fukan.canvas.lens.*` namespaces; `all-lenses`, `lens-by-id`
- `survey.clj` — `(run canvas-db lens-ids opts)`; invokes each requested lens's compute + render; concatenates into a unified survey

**Plugging in a new lens** = drop a `.clj` file under `src/fukan/canvas/lens/` that declares a valid `lens` var. The registry picks it up automatically. Adding the file + a require in `canvas-source/canvas-builders`-equivalent registry is the entire integration step.

**Swapping a prompt** = edit the lens's `:prompt-fragment` field. (No system-level coordination needed; prompts are data.)

**Swapping the architect's base prompt** = edit `doc/canvas-authoring-system-prompt.md` (the permanent versioned prompt the `fukan-architect` agent references). Sprint 4 Task 10 establishes this artifact.

**Why this matters for Phase 5.** The user wants to freely experiment with thinking modes. The architecture must accommodate that from the start, not as a refactor. Phase 5 ships 3 starter lenses (patterns, consistency, tar-pit) to validate the substrate handles both structural and theoretical lens shapes; subsequent phases add more.

---

## File structure (Phase 5)

**Likely new namespaces:**

```
src/fukan/canvas/inspect/             ; trust tier — decision-ready output
  integrity.clj                  ; cross-reference resolution + trigger/emit coherence
  coverage.clj                   ; orphans, unreachable entities, dead exports, missing trigger/handler coverage
  ; delta.clj                    ; deferred to Phase 6+; snapshot lifecycle is its own design conversation

src/fukan/canvas/lens/                ; weigh tier — pluggable lenses (the experimentation surface)
  core.clj                       ; lens contract; validate-lens
  registry.clj                   ; auto-discovers lens vars from src/fukan/canvas/lens/*.clj
  survey.clj                     ; (run canvas-db lens-ids opts) — dispatch + synthesis

  patterns.clj                   ; structural lens — recurring shapes; rule-of-three lift candidates
  consistency.clj                ; structural lens — naming style, field-type symmetry, sister-module symmetry
  tar_pit.clj                    ; theoretical lens — essential vs accidental complexity (Moseley & Marks 2006)

doc/
  canvas-authoring-system-prompt.md     ; permanent system prompt the fukan-architect agent references

doc/plans/
  2026-05-26-feedback-signals-design.md   ; Sprint 1 output
  2026-05-26-coauthor-workflow-design.md  ; Sprint 1 output
  2026-05-26-phase-5-verification.md      ; Sprint 5 output
```

**Likely modified files:**

```
src/fukan/agent/api.clj                ; expose new fns through (help); list trust-tier fns + registered lenses separately
src/fukan/canvas/construction.clj      ; Sprint 2 — emits form
src/fukan/canvas/projection/canvas_source.clj  ; Sprint 2 — duplicate-name convention promotion
src/fukan/canvas/core/substrate/store.clj      ; Sprint 2 — :type/fields derived attribute
.claude/agents/fukan-architect.md      ; Sprint 4 — extend with survey-improvements capability (or equivalent path)
dev/user.clj                           ; REPL convenience for invoking signals (optional)
CLAUDE.md, AGENTS.md                   ; Sprint 5 — authoring workflow guidance + tier explanation + lens registry pointer
```

**`bin/fukan` is NOT modified.** Phase 5 adds no CLI subcommands. Both tiers are invoked through `bin/fukan eval`.

**Files NOT touched:**

- Substrate primitives (`substrate.clj`, `substrate/store.clj`) — Phase 4 finalized them
- The canvas vocab libraries — Phase 5 may notice pattern-detection candidates but doesn't ship vocab unless rule-of-three within actual Phase 5 authoring evidence
- `web/` and `web/views/*` — UI work explicitly deferred
- `.legacy-allium/` — archive stays archived
- `/demo/` — stress-test artifacts frozen

---

# Sprint 1 — Feedback inventory + workflow design (Tasks 1–2)

The opening sprint settles two design questions before any signals are built. Both produce docs reviewed by the user before downstream sprints begin.

---

## Phase 5, Task 1: Feedback signals — design doc

**Files:**
- Create: `doc/plans/2026-05-26-feedback-signals-design.md`

### What the design doc must cover

For each of the six signal categories listed in the "thinking-enhancing tool" section above:

- **What it computes.** Concretely. Datalog query, walk over the canvas db, set comparison, etc.
- **What it surfaces.** A list of findings, structured for both LLM and human consumption.
- **Tier.** Trust-tier helper (decision-ready output, pure fn under `inspect/`) or weigh-tier lens (interpretive output under `lens/`). Verify the partition holds — surface anything that resists the split.
- **How it integrates with workflow.** REPL fn? `bin/fukan` subcommand? Both?
- **Priority for Phase 5.** Ship in Phase 5 vs defer to Phase 6 or beyond.

Specifically address:

**Category 1: Structural integrity**
- Cross-reference checker: every `:references` Relation's `:to` resolves to an entity that exists
- Orphan checker: entities with no incoming references (might be dead canvas)
- Reachability: every entity is reachable from at least one Module's `:module/child` graph
- Triggers/emits coherence: every `:triggers` Relation points to an actual rule; every `:emits` Relation points to an actual event

**Category 2: Pattern recurrence**
- Shape clustering: find Affordances with structurally similar arrow shapes
- Lift candidate detection: when a manual pattern (e.g. ad-hoc invariants with similar formal expressions) recurs 3+ times, flag as a vocab candidate
- Naming patterns: detect Affordances with similar name suffixes/prefixes (e.g. `*_id`, `make_*`, `*_is_pure`)

**Category 3: Methodology coherence**
- Vocabulary fingerprint: which vocab.* lifts does this module use? Does the mix make sense for a coherent methodology?
- Inconsistency detection: e.g. a module mixing `vocab.event/handler` with `vocab.validation/checker` might be doing two things; flag for review

**Category 4: Consistency**
- Naming style consistency within a module
- Field-type consistency across records that share concept names (e.g. all records with a `:status` field — are they all `:String`?)
- Sister-module symmetry (e.g. `rules_4a..g` all checker-shaped; if one has invariants the others don't, flag it)

**Category 5: Behavioral coverage**
- Every rule with a `when:` clause has a corresponding `function` that emits the trigger (or is otherwise reachable)
- Every event has at least one handler somewhere in the corpus
- Every guarantee has at least one structural mechanism enforcing it (heuristic)

**Category 6: Delta awareness**
- Diff between two canvas db states: what entities added/removed/modified
- Cross-reference impact analysis: when you remove entity X, what references break?
- Test-impact analysis: which tests exercise the entities you changed?

### Steps

- [ ] **Step 1: For each category, sketch concrete computational shape.** Write enough that an implementer could start.

- [ ] **Step 2: Rank by usefulness for the LLM authoring loop.** Some signals matter more than others when you're 30 seconds into a co-authoring session.

- [ ] **Step 3: Pick the 4-5 highest-priority signals for Phase 5.** Defer the others to follow-on phases.

- [ ] **Step 4: Pause for user review.** This is the load-bearing design conversation of Phase 5.

- [ ] **Step 5: Commit.**

```bash
jj desc -m "doc(canvas): Phase 5 feedback-signals design"
jj new
```

---

## Phase 5, Task 2: LLM co-author workflow design

**Files:**
- Create: `doc/plans/2026-05-26-coauthor-workflow-design.md`

### Context

The Phase 2 Sprint 2 architect-explorer system prompt activated layered-language thinking in LLMs during the emergence experiment. It worked. It's currently a one-shot artifact frozen in `doc/plans/2026-05-25-architect-explorer-system-prompt.md`. Phase 5 promotes it to a permanent reusable workflow tool.

The workflow needs to integrate with:
- The architect-explorer system prompt (or its successor)
- The feedback signals from Task 1
- The author's iterative loop (write canvas → invoke feedback → adjust → repeat)

### What the design doc must cover

- **The minimum viable loop.** When the LLM partner is asked to author or extend canvas content, what context does it get? Which feedback signals does it invoke? What does its turn-by-turn output look like?
- **The integration point.** `bin/fukan` CLI extension? An nREPL middleware? A reusable Clojure function callable from the LLM's tool surface?
- **The architect-explorer prompt evolution.** Phase 2's prompt is for one-shot vocab discovery; Phase 5's authoring loop is iterative co-authoring. What changes?
- **The discipline.** How does the LLM avoid:
  - Inventing vocab that doesn't recur (the Phase 2 source-priming failure mode)
  - Hallucinating entity references that don't exist (the feedback signals should catch this)
  - Drifting from the chosen methodology mid-module
- **The trial-run target.** Phase 5 must run the loop against one real authoring task in Sprint 4. The design doc picks the task.

### Steps

- [ ] **Step 1: Reread the architect-explorer system prompt.** Note which parts are one-shot-specific and which generalize.

- [ ] **Step 2: Draft the loop shape.** Per-turn input/output spec. Feedback-signal invocation timing.

- [ ] **Step 3: Pick the Phase 5 trial-run target.** Suggestions:
  - Port one un-touched fukan subsystem (none remain — all 62 modules are ported; would need a synthetic exercise)
  - Refactor one existing canvas module for clarity (real value)
  - Author a small extension to one demo (e.g. add `vocab.cqrs` lifts to a new demo subsystem)

- [ ] **Step 4: Pause for user review.**

- [ ] **Step 5: Commit.**

```bash
jj desc -m "doc(canvas): Phase 5 co-author workflow design"
jj new
```

---

# Sprint 2 — Pre-implementation hardening (Tasks 3–5)

Three items needed before Sprint 3 signal/lens work begins. All three are small additive changes that unblock accurate signal output.

---

## Phase 5, Task 3: Promote the duplicate-name convention (resolves 31 intra-module duplicates)

**User decision (2026-05-27):** option 3 — **promote the convention**. The substrate already disambiguates same-name entities by `name + role` silently; option 3 makes the idiom explicit in canvas documentation without churning the validation subsystem's 31 rule+invariant pairs. The Allium-derived pattern (paired rule + invariant sharing a name) is a real design idiom worth preserving.

**Files:**
- Modify: `CLAUDE.md` — add a "Canvas conventions" section documenting the name+role disambiguation idiom
- Modify: `AGENTS.md` — same note for the agent surface
- Modify: `src/fukan/canvas/projection/canvas_source.clj` — confirm warn-not-throw behavior is final, not transitional; add a docstring referencing the convention
- Test: extend `test/fukan/canvas/projection/canvas_source_test.clj` with an assertion that same-name entities with different roles co-exist correctly

Steps:

- [ ] **Step 1: Document the convention.** A canvas module may declare multiple entities with the same name PROVIDED they have distinct roles (e.g. `rule` + `invariant` named the same describe two views of the same behavioral commitment). Reference resolution uses `(name, role)` as the disambiguation tuple where role is unambiguous from context.
- [ ] **Step 2: Codify in docstring.** Update `canvas-source/build-canvas-db`'s docstring to reference the convention; remove any TODO/transitional language.
- [ ] **Step 3: Confirm warn-not-throw is the final behavior** (not transitional); the warning text becomes "duplicate name X — distinct roles A, B — confirm intentional via the name+role convention".
- [ ] **Step 4: Test.** Assert two same-name same-module entities with distinct roles produce two distinct datoms and resolve correctly when queried by `(name, role)`.
- [ ] **Step 5: Commit.**

```bash
jj desc -m "feat(canvas): promote name+role disambiguation as canvas convention; resolve 31 intra-module duplicates"
jj new
```

---

## Phase 5, Task 4: `emits` form on function

**Files:**
- Modify: `src/fukan/canvas/construction.clj`
- Modify: `src/fukan/canvas/core/substrate/store.clj`
- Test: `test/fukan/canvas/construction_test.clj`
- Backfill: `demo/event-driven/*`

Sprint 3 stress-test surfaced: command functions emitting events have no first-class form. Symmetric with `triggers`:

- `(triggers RuleName)` — function couples to a rule
- `(emits EventName)` — function emits an event (new in Phase 5)

Implementation pattern mirrors Phase 4 Sprint 2 Task 5.

- [ ] **Step 1: Schema** — `:emits {:db/cardinality :db.cardinality/many :db/valueType :db.type/ref}`.
- [ ] **Step 2: Test + Implement** — extend `function`'s form grammar.
- [ ] **Step 3: Backfill demo content.**
- [ ] **Step 4: Commit.**

```bash
jj desc -m "feat(canvas): emits form on function — command-to-event coupling first-class"
jj new
```

---

## Phase 5, Task 5: `:type/fields` derived attribute (substrate addition)

Sprint 1 Task 1 design surfaced that field-type consistency analysis needs `(name, type)` queryable per field of every record-shaped Type. Today the substrate stores field names + parsed field shapes but doesn't expose a queryable derived attribute. Sprint 2 ships the addition before Sprint 3's consistency lens needs it.

**Files:**
- Modify: `src/fukan/canvas/core/substrate/store.clj` — add `:type/fields` schema entry + derivation in `->datoms` for `:type-record`
- Test: `test/fukan/canvas/core/substrate/store_test.clj` — assert a query for "all records with a `:status` field, grouped by its type" works
- Confirm no canvas content changes — the addition is derived from existing data

- [ ] **Step 1: Schema** — `:type/fields` as a cardinality-many attribute holding tuple values `[field-name type-name]` (or a ref to a small intermediate entity, depending on what queries the consistency lens wants).
- [ ] **Step 2: Test the query** consistency relies on.
- [ ] **Step 3: Implement** in the `:type-record` `->datoms` multimethod.
- [ ] **Step 4: Run against fukan-itself.** Confirm no regressions in existing canvas suite.
- [ ] **Step 5: Commit.**

```bash
jj desc -m "feat(canvas/substrate): :type/fields derived attribute for field-level queries"
jj new
```

---

# Sprint 3 — Build the feedback signals (Tasks 6–10)

The substantive sprint. Two trust-tier helpers, then the lens substrate plus three starter lenses (two structural, one theoretical). The lens substrate is the **load-bearing pluggability core** introduced in this sprint — Task 8 lands the substrate alongside the first lens; subsequent tasks validate the substrate accepts both additional structural lenses and a non-structural theoretical lens.

Sprint 3 splits into two groups corresponding to the two tiers:

- **Trust tier** — Tasks 6–7. Land under `src/fukan/canvas/inspect/`. Pure fns; decision-ready output; the LLM treats them as authoritative.
- **Weigh tier (lens substrate + starter lenses)** — Tasks 8–10. Land under `src/fukan/canvas/lens/`. Each lens declares the lens contract (`:id`, `:description`, `:prompt-fragment`, optional `:compute`, `:render`). The lens substrate (Task 8) lands alongside the first lens.

Each task lands as:
- One namespace under the tier's directory
- Tests
- Registration in the agent API's `(help)` surface, grouped by tier; lenses additionally register with the lens registry automatically
- A short EXAMPLES.md showing typical use

**No new `bin/fukan` subcommands.** All fns are invoked through `bin/fukan eval`. The agent API surface (and AGENTS.md) is responsible for telling the LLM these fns exist and what tier each one belongs to.

---

## Phase 5, Task 6: Cross-reference integrity — trust tier

**Files:**
- Create: `src/fukan/canvas/inspect/integrity.clj`
- Test: `test/fukan/canvas/inspect/integrity_test.clj`
- Update: `src/fukan/agent/api.clj` (register in `(help)`)

What it computes:
- For every `:references` Relation: does the `:to` target resolve to an actual entity in the canvas db?
- For every `:triggers` Relation: does the rule named in the trigger exist?
- For every `:emits` Relation: does the event named in the trigger exist?
- For every cross-module reference keyword in a shape: does the target module + entity exist?

What it returns: a structured report of unresolved references with source-locations and entity stable-ids.

What it computes:
- For every `:references` Relation: does the `:to` target resolve to an actual entity in the canvas db?
- For every `:triggers` Relation: does the rule named in the trigger exist?
- For every `:emits` Relation: does the event named exist?
- For every cross-module reference keyword in a shape: does the target module + entity exist?

What it returns: a structured report of unresolved references with source-locations and entity stable-ids. **Decision-ready** — a broken reference is an error.

- [ ] **Step 1: Test the failure cases first.** Construct a canvas with deliberately broken references; assert the signal catches them.
- [ ] **Step 2: Implement** using Datascript queries against `(canvas-source/build-canvas-db)`.
- [ ] **Step 3: Register in `(help)`** under a `Trust` group with a one-line summary.
- [ ] **Step 4: Run against fukan-itself.** What does the live canvas surface? Document findings in the test or notes.
- [ ] **Step 5: Commit.**

---

## Phase 5, Task 7: Coverage analysis — trust tier

**Files:**
- Create: `src/fukan/canvas/inspect/coverage.clj`
- Test: `test/fukan/canvas/inspect/coverage_test.clj`

What it computes:
- Orphan entities (no incoming references; possibly dead)
- Unreached entities (not reachable from any Module's `:module/child` graph; substrate-floating)
- Exported entities never referenced externally
- Modules without `(exports …)` declarations
- Rules with a `when:` clause and no function triggering them (behavioral coverage gap)
- Events with no handler anywhere in the corpus

Each finding has a severity (error / warning / info) callers can filter on. Output is **decision-ready** — the call to act is structural, not interpretive.

- [ ] **Step 1: Define each coverage check** as a Datalog query.
- [ ] **Step 2: Tests.**
- [ ] **Step 3: Register in `(help)`** under the `Trust` group.
- [ ] **Step 4: Run against fukan-itself.** Expect to find some real issues (dead exports, etc.).
- [ ] **Step 5: Commit.**

---

## Phase 5, Task 8: Lens substrate + patterns lens (the first lens)

**Files:**
- Create: `src/fukan/canvas/lens/core.clj` — the lens contract (spec for a valid `lens` map; `validate-lens`)
- Create: `src/fukan/canvas/lens/registry.clj` — discovers `lens` vars from `fukan.canvas.lens.*` namespaces; `all-lenses`, `lens-by-id`
- Create: `src/fukan/canvas/lens/survey.clj` — `(run canvas-db lens-ids opts)`; dispatches and synthesizes
- Create: `src/fukan/canvas/lens/patterns.clj` — the first lens; structural
- Tests for each of the four

**What the lens substrate must provide.** This task ships the pluggability core. Read the "Lens substrate" section in the framing earlier in this plan. The contract a lens must satisfy:

```clojure
(def lens
  {:id          :patterns                          ; required; namespaced or simple keyword
   :description "..."                              ; required; short one-line summary
   :prompt-fragment "..."                          ; required; LLM priming content for this lens
   :compute     (fn [canvas-db opts] ...)          ; optional; returns findings data
   :render      (fn [findings opts] ...)})         ; required; composes findings + prompt-fragment → context
```

The substrate validates lenses on registration. Adding a new lens = drop a `.clj` file under `src/fukan/canvas/lens/` declaring a valid `lens` var; the registry picks it up.

**Survey dispatch.** `(survey/run canvas-db [:patterns :consistency])` invokes each requested lens's `compute` (if provided), then each lens's `render`, then concatenates the rendered output into a single LLM-consumable survey.

**The patterns lens specifically.** Computes:
- Cluster Affordances by structural shape similarity (input/output type sets, role, presence/absence of formal-expression)
- Flag clusters of 3+ as candidate lift patterns
- For each cluster: emit the entities, the shared structure, the suggestion ("consider extracting a lift named X")

The prompt-fragment frames the LLM through the rule-of-three discipline: "If the same shape appears 3+ times, consider a lift. Existing lifts in `fukan.canvas.vocab.*` are the first place to look before inventing new vocabulary."

Steps:

- [ ] **Step 1: Lens contract.** Spec + `validate-lens`. Tests: valid lens passes; missing required keys fail with clear errors.
- [ ] **Step 2: Registry.** Auto-discovery of `lens` vars from `fukan.canvas.lens.*` namespaces. Tests: register two synthetic lenses; assert `all-lenses` returns both; `lens-by-id` retrieves.
- [ ] **Step 3: Survey dispatch.** `(run canvas-db lens-ids opts)`. Tests: run a survey with one synthetic lens; assert compute + render are invoked and output assembled correctly.
- [ ] **Step 4: Patterns lens implementation.** Define "structural similarity" rigorously; cluster by signature; emit clusters >= 3 with the suggestion frame above.
- [ ] **Step 5: Register lens id + description in `(help)`** under a `Lenses` group; agent API exposes `(canvas.lens/all-lenses)` for discovery.
- [ ] **Step 6: Run against fukan-itself.** The existing `checker` lift should surface for the validation rules. If new patterns surface, document them as Phase 6 vocab candidates.
- [ ] **Step 7: Per-commit logical changes.** Substrate (core + registry + survey) is one commit; patterns lens is a separate commit.

---

## Phase 5, Task 9: Consistency lens — validates substrate accepts additional structural lenses

**Files:**
- Create: `src/fukan/canvas/lens/consistency.clj`
- Test: `test/fukan/canvas/lens/consistency_test.clj`

What it computes (using `:type/fields` shipped in Sprint 2 Task 5):
- Naming-style consistency within a module (entities named `snake_case` vs `camelCase` vs `PascalCase`)
- Field-name consistency across records (e.g. all records with a `:status` field — is it always `:String`?)
- Sister-module structural symmetry (e.g. `rules_4a..g` should all look alike)

Output is **observational** — the prompt-fragment frames each finding as a question the LLM weighs, not an error to fix.

The point of this task: **prove the lens substrate accepts a second structural lens with zero changes to the substrate machinery.** If anything in the substrate has to change to accommodate this lens, that's a substrate bug worth surfacing.

- [ ] **Step 1: Define each consistency check** as a query over the canvas db.
- [ ] **Step 2: Implement as a lens** — `def lens` with the four required keys + `compute` + `render`.
- [ ] **Step 3: Make checks optional / configurable** via `opts` passed to `compute`.
- [ ] **Step 4: Run against fukan-itself.** Expect surprises.
- [ ] **Step 5: Verify substrate unchanged.** If `core`/`registry`/`survey` changed, surface the reason.
- [ ] **Step 6: Commit.**

---

## Phase 5, Task 10: Tar-pit lens — validates substrate accepts theoretical (non-structural) lenses

**Files:**
- Create: `src/fukan/canvas/lens/tar_pit.clj`
- Test: `test/fukan/canvas/lens/tar_pit_test.clj`

**Why this task exists.** The user wants to "freely experiment with different modes" of thinking about canvas designs. Modes range from purely structural (patterns, consistency) to purely theoretical (a paper's framework applied as a lens). The tar-pit lens is the first theoretical lens — it proves the substrate handles both shapes.

Based on Moseley & Marks (2006) "Out of the Tar Pit," the lens frames the canvas through the paper's central distinction: **essential complexity** (inherent to the problem domain) vs **accidental complexity** (introduced by representation choices, mutable state, control flow). The prompt-fragment poses Tar-Pit questions to the LLM:

- Which Types in this module are essential state vs derived/accidental state?
- Which Affordances accomplish essential behavior vs incidental coordination?
- Where does mutable state appear; is it justified by essentiality or absorbed accidentally?
- (Tar Pit's prescription: essential state + functional derivations; minimize accidental state. How does this module score?)

`compute` is allowed but optional for this lens. A minimal version extracts the relevant canvas slice (all `:canvas/getter` affordances as candidate state; all `function` affordances as candidate behavior) and hands it to `render` along with the prompt-fragment; the LLM does the interpretation.

- [ ] **Step 1: Read Out of the Tar Pit** carefully — the lens's prompt-fragment quality is the load-bearing artifact here, not the compute fn.
- [ ] **Step 2: Draft the prompt-fragment.** Pose the paper's central questions in canvas-vocabulary terms.
- [ ] **Step 3: Minimal compute fn.** Extract candidate-state + candidate-behavior slices from the canvas db; pass to render.
- [ ] **Step 4: Run against fukan-itself.** The output goes to a markdown file for review (the human reads the LLM's tar-pit analysis of fukan's own canvas). Subjective evidence.
- [ ] **Step 5: Verify substrate unchanged.** If anything in core/registry/survey had to bend to accommodate a theoretical lens, surface why.
- [ ] **Step 6: Commit.**

---

**Note on deferred signals.** Methodology coherence and Delta awareness are not Phase 5 lenses. Methodology coherence wants a multi-paradigm corpus to design against, which fukan doesn't have yet. Delta awareness wants a snapshot-lifecycle design (when is a snapshot taken; what's the human-visible diff; impact analysis on removed entities) that's its own conversation. Both revisit in Phase 6+ once Phase 5's lens substrate is in use.

---

# Sprint 4 — LLM workflow integration + trial run (Tasks 11–12)

The trust-tier helpers + lens substrate from Sprint 3 are useful in isolation. Sprint 4 integrates them into a workflow the LLM can use to author canvas content with feedback.

---

## Phase 5, Task 11: Extend the `fukan-architect` agent with tier awareness + lens-driven `survey design improvements`

**Files:**
- Update: `.claude/agents/fukan-architect.md` (or equivalent agent-definition path)
- Create: `doc/canvas-authoring-system-prompt.md` — permanent versioned system-prompt content the agent references
- Update: `AGENTS.md` — explain trust/weigh tier model + the lens substrate so any LLM understands the partition

The Phase 2 architect-explorer system prompt activated layered-language thinking. Phase 5 promotes it from one-shot artifact to permanent reusable subagent behavior, expressed through the existing `fukan-architect` agent and the pluggable lens substrate.

**Why extend rather than create a new agent:** `fukan-architect` is already described as "High-altitude design partner... reviews existing structure and explores improvements/expansions." That description already encompasses the survey capability. Keeping it unified keeps the agent surface compact and discoverable. If `survey design improvements` becomes a workload distinct enough from generalist design-partner work to warrant its own agent, split in Phase 6+.

**Lens-driven survey.** The agent dispatches surveys through the lens registry. A default lens set is configured in the agent definition (likely `[:patterns :consistency :tar-pit]` for Phase 5). A specific lens set is requestable per dispatch — e.g. "survey design improvements through the tar-pit lens only" → `(survey/run (model) [:tar-pit])`. The pluggable lens substrate is what makes this trivial: new lenses become available to the agent automatically once registered.

Key additions to the agent definition:

- **Tier awareness.** Agent instructions explicitly distinguish trust vs weigh. Trust-tier helpers (`inspect/*`) are invoked first to establish a structural baseline; their findings are stated as facts. Weigh-tier output (from the lens survey) is framed as observations + judgments.
- **References to the existing vocab libraries** (behavioral, validation, lifecycle, event) and EXAMPLES.md files.
- **Lens registry discovery.** The agent calls `(canvas.lens.registry/all-lenses)` at session start to know what lenses are available; instructions teach it how to pick a relevant lens-set per task.
- **The `survey design improvements` mode.** When dispatched with a survey request, the agent invokes `(canvas.lens.survey/run (model) <lens-ids>)` via `bin/fukan eval`, then synthesizes the survey output into a structured response.
- **The original architect-explorer principles** (compose first, vocabulary justified by use, etc.) carry forward as the agent's reasoning posture.

- [ ] **Step 1: Reread the Phase 2 architect-explorer system prompt.** Identify generalizable principles vs experiment-specific framing.
- [ ] **Step 2: Draft `doc/canvas-authoring-system-prompt.md`** as the permanent versioned prompt content. Includes: trust/weigh tier framing; lens substrate explanation; reach-for-existing-vocab-first anchor; named failure modes; the Lineage section preserved verbatim (it does the activation work).
- [ ] **Step 3: Update the `fukan-architect` agent definition.** Reference the prompt; add the lens-driven survey mode; ensure agent tool surface includes `bin/fukan eval` for invocation.
- [ ] **Step 4: Update AGENTS.md** with the tier + lens explanations so any LLM (not just the dispatched subagent) understands the model.
- [ ] **Step 5: Commit.**

---

## Phase 5, Task 12: Trial run — `demo/distributed/` authoring with the full loop

**Files:**
- Create: `demo/distributed/*.clj` — 3-4 canvas modules covering leader election or routing-with-retries (target chosen in Sprint 1 Task 2; can adjust)
- Create: `doc/plans/2026-05-26-trial-run-findings.md`

Execute the loop end-to-end on a real authoring task. **Both Sprint 1 design agents independently picked this target** (a new distributed-systems demo, paradigm not previously stress-tested), which provides good evidence value.

**Loop shape for the trial:**
1. Dispatch the extended `fukan-architect` agent (or a peer LLM subagent loaded with the canvas-authoring system prompt) with the authoring task.
2. The agent reads the existing canvas + demos, calls `inspect/integrity` for a baseline, picks a relevant initial lens-set, drafts canvas content.
3. After each module lands, the agent runs `inspect/integrity` + `inspect/coverage` + the survey through the active lens-set; iterates.
4. Human observer (you) can correct mid-run if the LLM drifts; no automation enforces correctness.
5. At the end of the trial, the agent produces a self-review through the tar-pit lens applied to the new subsystem.

- [ ] **Step 1: Dispatch the trial.** Give the agent the authoring goal + access to all Phase 5 infrastructure.
- [ ] **Step 2: Observe the loop.** What did the LLM invoke? Did the feedback signals catch the right things? Did the lens-driven survey shape the design? Where did the LLM go astray?
- [ ] **Step 3: Document findings.** What worked, what didn't. Be specific about which lens output the LLM actually used vs ignored.
- [ ] **Step 4: Identify gaps.** What feedback would have made the loop tighter? What lenses are missing? What does the substrate need next?
- [ ] **Step 5: Commit.**

---

# Sprint 5 — Verification (Task 13)

## Phase 5, Task 13: Phase 5 verification report

**Files:**
- Create: `doc/plans/2026-05-26-phase-5-verification.md`

Standard verification template per Phases 1, 3, 4:

- [ ] **Section 1: What was attempted vs. built.** Recap Sprints 1-4.
- [ ] **Section 2: Did the trust-tier signals produce useful output against fukan's own canvas?** What did integrity + coverage find? What surprised?
- [ ] **Section 3: Did the lens substrate work?** Was adding the consistency lens really just a single-file addition? Did the tar-pit lens validate the theoretical-lens shape? Were any substrate bends required to accommodate the second/third lens (substrate bug)?
- [ ] **Section 4: Did the LLM co-author trial run succeed?** Was the loop tight? Did the feedback genuinely shape the LLM's authoring? Which lenses did the LLM actually reach for?
- [ ] **Section 5: What did the trial run reveal about gaps?** Specific lenses, helpers, or workflow improvements that would help next.
- [ ] **Section 6: Decision.** Three outcomes:
  1. The thinking-enhancing tool works → Phase 6 (browser UI) can begin.
  2. Works with caveats → Phase 5.5 to close caveats first.
  3. The loop didn't produce a noticeable thinking improvement → reset; rethink approach.
- [ ] **Section 7: Phase 6+ implications.** Browser UI as the next phase. Plus: additional lenses (methodology coherence, delta awareness, FCIS, DDD, ...). Plus: diff detection + impl generation as the eventual product surface (per the user's strategic frame).

```bash
jj desc -m "doc(canvas): phase-5 verification + phase-6 brief"
jj new
```

---

## Subsequent phases (sketches; each gets its own plan after Phase 5)

**Phase 6 — Browser UI for canvas authoring**: the editing surface deferred from Phase 5. Embedded editor, text-mode UI, or chat-driven authoring — decided per Phase 5's evidence.

**Phase 7 — Diff detection between canvas design and code implementation**: the substrate already supports queries; Phase 7 adds the canvas-to-code-tree diff inspector. "What did the implementation drift from what was designed?"

**Phase 8 — Implementation instruction generation from design diffs**: given a design diff (Phase 7's output), generate the precise implementation steps to bring code into alignment with design. This is fukan's eventual primary product surface.

---

## Self-review notes

- **Sprint 1's design docs (Tasks 1 + 2) are the load-bearing artifacts of Phase 5.** Both pause points completed 2026-05-26; the 2026-05-27 lens-substrate reframe builds on top.
- **Don't speculate signals.** Each one in Sprint 3 is justified by Sprint 1 evidence + the lens substrate's two-shape coverage (structural + theoretical).
- **Resist scope creep on lenses.** Phase 5 ships three starter lenses (patterns, consistency, tar-pit) to validate the substrate. New lenses (methodology, FCIS, DDD, etc.) wait for Phase 6+ unless trial-run evidence demands them.
- **The lens substrate must stay small.** core + registry + survey = three small namespaces. If the substrate grows in Sprint 3, surface the reason — it's a substrate-design question, not a Sprint 3 implementation detail.
- **Prompts are data.** Every prompt fragment (per-lens) and the architect base prompt are plain files editable without code changes.
- **Phase 5 is about FEELING the canvas as a thinking tool.** Hard to verify without authoring something real (Sprint 4's trial run). Plan for subjective evidence.

---

## Open-question status (settled 2026-05-27 unless noted)

The user reframe of 2026-05-27 settled most open questions. Recorded here for reference.

1. **Feedback-signal priority** — Settled: ship integrity + coverage (trust tier); patterns + consistency + tar-pit (lens tier). Defer methodology + delta to Phase 6+.

2. **Trial-run target** — Settled: `demo/distributed/` (new subsystem; both Sprint 1 design agents independently chose this).

3. **31 intra-module duplicate names** — Settled: option 3, promote the convention. Substrate already disambiguates by name+role silently; canvas docs make the idiom explicit. Sprint 2 Task 3 codifies this.

4. **Agent organization** — Settled: extend `fukan-architect` (not new agent). Lens-driven survey, configurable lens-set per dispatch. Split into separate verbs only if it grows.

5. **Lens substrate framing** — Settled by the 2026-05-27 user reframe: the weigh-tier helpers become pluggable lenses to support open-ended experimentation with thinking modes. Three starter lenses (patterns, consistency, tar-pit) cover the two lens shapes (structural + theoretical).

6. **In-band severity on coverage findings** — Default: yes, reuse Phase 4 violation-diagnostics pattern.

7. **`:type/fields` substrate addition** — Default: yes, ship in Sprint 2 Task 5.

8. **System-prompt versioning** — Default: flat path (`doc/canvas-authoring-system-prompt.md`); add `-v1` only when there's a `v2` to compare.

9. **Trial-run drive** — Default: LLM subagent does the authoring with you observing; you can correct mid-run.

10. **Survey-dispatch interface** — Default: free-form prompt to `fukan-architect`; specific lens-set requestable in the prompt body.

Any of the "default" items remain open for pushback before the relevant sprint dispatches. Items 1-5 are settled.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–2 | Feedback signals design + co-author workflow design (✅ both docs landed 2026-05-26) |
| 2 | 3–5 | Pre-implementation hardening: name+role convention promotion + emits form + `:type/fields` substrate attr |
| 3 | 6–10 | Trust-tier helpers (integrity, coverage) + lens substrate + 3 starter lenses (patterns, consistency, tar-pit) |
| 4 | 11–12 | Extend `fukan-architect` with tier + lens awareness + trial run on `demo/distributed/` |
| 5 | 13 | Phase 5 verification + Phase 6 brief |

**Estimated calendar:** Sprint 1 ≈ done. Sprint 2 ≈ 1-2 sessions. Sprint 3 ≈ 4-5 sessions (substrate + 2 trust-tier + 3 lenses; the substrate task is the heaviest). Sprint 4 ≈ 2 sessions (workflow integration + trial run). Sprint 5 ≈ 1 session. **Total remaining: 8-10 working sessions.**
