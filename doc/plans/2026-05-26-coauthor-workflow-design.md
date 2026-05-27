# Phase 5 Sprint 1 — LLM co-author workflow design

**Date:** 2026-05-26
**Status:** Draft for user review (pause point before Sprint 2 dispatch)
**Companion doc:** `doc/plans/2026-05-26-feedback-signals-design.md` (Sprint 1 Task 1)
**Scope:** Phase 5 Sprint 1 Task 2 only.

---

## Strategic frame

Phases 1-4 built a canvas-native analysis substrate. The substrate is queryable, the vocabulary is principled, the examples library exists, and Phase 2's emergence experiment proved that with the right system prompt an LLM can independently discover canvas vocabulary in a layered-language style.

What is still missing is the *author's experience* of using it. A human or LLM editing canvas content today gets test pass/fail and a graph viewer, not a conversation with the substrate. Phase 5's reframe — *"canvas is the intersection between LLM and human; maximum feedback on the integrity and strength of design; the best thinking-enhancing tool we can make"* — names that gap. This design doc specifies the loop that makes canvas authoring feel like that conversation.

The loop is small, deliberate, and built from existing pieces: canvas files on disk, `bin/fukan eval` for trust-tier queries, the extended `fukan-architect` subagent for weigh-tier survey, and a permanent reusable system prompt that carries Phase 2's architect-explorer principles forward.

---

## Settled by the user (recap)

- **Extend `fukan-architect`** rather than create a new `fukan-design-surveyor` agent. The existing description ("reviews existing structure and explores improvements/expansions") already encompasses the survey capability; keeping the agent surface compact preserves discoverability.
- **Monolithic survey for Phase 5.** A single dispatch produces a unified survey covering pattern recurrence + consistency. Split into separate verbs only if the unified report grows long enough to lose coherence (rule of three on its own usage).
- **No new CLI subcommands.** Both trust- and weigh-tier helpers are invoked through `bin/fukan eval`. The survey is invoked via subagent dispatch. `bin/fukan` itself is unchanged in Phase 5.

These choices shape everything below.

---

## The minimum viable loop

The unit of work is one **authoring session**: a human (or driving LLM) is editing one canvas module — adding declarations, refactoring shape, porting from an existing description, extending a vocabulary. The session may last one turn or fifty; the loop's shape is the same at every step.

### Session entry context

At the start of the session, the author-LLM is given (in addition to its system prompt):

1. The **target module path** (e.g. `canvas/model/build.clj`) and a one-sentence statement of intent ("extend with X", "refactor for clarity", "port spec Y").
2. The **canvas-authoring system prompt** (`doc/canvas-authoring-system-prompt.md`, see below) — the permanent successor to Phase 2's architect-explorer prompt. Carries the layered-language stance, the tier model, the failure modes, and pointers to vocab EXAMPLES.
3. Pointers (not contents) to the relevant **EXAMPLES.md** files: `construction.md`, `core/shape.md`, and the four `vocab/*.md` files. The author-LLM Reads them on demand rather than carrying them all into context up front.
4. Access to `bin/fukan eval` and the live `(help)` catalog. No other surface is required.

The author-LLM does **not** invoke the survey subagent at session entry. The survey is on-demand and scoped to a finished or near-finished candidate edit, not to the empty starting state.

### Turn shape

Every turn has three phases. Not every phase produces visible output every turn, but each phase has a defined moment.

**Phase A — Orient.** The author-LLM reads the target module file, queries `(neighborhood "<module-id>")` and `(get-primitive "<module-id>")` to see what's already there and what it connects to, and (when the task involves an unfamiliar concept) Reads the relevant EXAMPLES.md to confirm the lift it's reaching for is the right one.

**Phase B — Edit.** The author-LLM writes the canvas edit to disk — a new affordance, a refactored shape, a new module file. The edit follows the architect-explorer principles: compose first, vocabulary justified by use, reads-naturally is the test, the substrate is sacred. Edit granularity is one logical change per turn — not "the whole module" and not "one line".

**Phase C — Reflect.** The author-LLM runs the **trust-tier** signals against the post-edit state:

```clojure
;; via bin/fukan eval after (refresh) and possibly with a snapshot taken before Phase B
(canvas.inspect.integrity/check (model))
(canvas.inspect.coverage/check (model) :module "model.build")
(canvas.inspect.delta/since-snapshot (model))
```

Trust-tier output is **decision-ready** — a broken reference is a fact. The author-LLM treats trust-tier findings as authoritative and either fixes them immediately (the typical case) or surfaces them to the human if the fix is non-local. No interpretation, no judgment.

Phases A-B-C iterate until the trust tier is clean and the edit's intent is realised.

### Weigh-tier interludes

Periodically — not every turn — the author-LLM dispatches the **`fukan-architect` subagent in survey mode**:

```
Dispatch: fukan-architect
Mode: survey design improvements
Scope: module model.build (or: scope: recent delta)
```

The subagent invokes `(canvas.lens.survey/run (model) [:patterns :consistency :tar-pit])` (default lens-set; per-dispatch override on request), synthesises the per-lens output into a unified survey, and returns it as a structured report (annotated clusters + observations + naming/style notes + tar-pit essential/accidental analysis). The author-LLM reads the survey, weighs each observation, and decides whether to act. The author-LLM does **not** treat the survey as authoritative — it's input to judgment, not a verdict.

When to invoke the survey (heuristics, not rules):
- After a meaningful unit of authoring lands (a new affordance plus its supporting types; a refactor of a record's field set).
- When the author-LLM senses repetition forming ("am I writing the same shape for the third time?") and wants confirmation.
- Before declaring the session done. The closing survey is the strongest discipline against the Phase 2 failure mode of inventing speculative vocabulary.

When **not** to invoke the survey:
- Mid-edit on a single declaration. Survey output is noisy at the wrong granularity.
- On a brand-new empty module. There is nothing to cluster against; the survey will produce no useful signal.
- Faster than ~3 minutes between dispatches. Surveys are expensive (full canvas db scan + LLM synthesis) and the author-LLM should accumulate enough state between dispatches to make the output worth reading.

### The human partner

The human sits at the edge of the loop, not inside every turn. Three points of human involvement:

1. **Session entry.** Human states the target and intent; possibly invokes the author-LLM via a slash command or shell.
2. **Survey review.** When the author-LLM surfaces a survey, the human is the primary reader. Patterns and consistency notes are judgment-laden; the human weighs them alongside the LLM and steers ("yes, lift that to a new vocab" / "no, leave it; the duplication is intentional").
3. **Trust-tier escalations.** When a trust-tier signal surfaces an issue the author-LLM can't fix locally — e.g. a broken reference into a sister module the author-LLM hasn't been given authority to edit — the human is brought in.

Between these touchpoints, the author-LLM operates autonomously. The loop is not human-in-every-turn; it is human-in-decision-points.

### One concrete turn (illustrative)

```
[Phase A]
- Read canvas/model/build.clj
- (neighborhood "model.build") via bin/fukan eval
- Note: module already exposes `build-model`, `merge-module-dbs`. Task is to add a
  `(rule "RefreshOnEdit" ...)` so a constraint can verify rule coverage.

[Phase B]
- Edit canvas/model/build.clj: add (rule "RefreshOnEdit" "..." (when EditDetected ...))
- Save.

[Phase C]
- Call (refresh) via the daemon.
- Run (canvas.inspect.integrity/check (model)) → 0 violations. Good.
- Run (canvas.inspect.coverage/check (model) :module "model.build") → 1 warning:
  "rule RefreshOnEdit has trigger EditDetected; no affordance emits EditDetected".
- Decision: this is a known gap; the file watcher isn't in canvas yet. Surface to
  human: "added the rule; coverage warns no emitter — should I add the emitter or
  is that out of scope?"
```

The author-LLM's turn output to the human is:
- The edit itself (visible on disk).
- A short summary citing which signals it consulted and what they returned.
- An explicit ask if anything is non-local.

The author-LLM does *not* paste the full canvas db, the full inspect report, or the EXAMPLES.md contents. Discipline of return: signal, not noise.

---

## The extended fukan-architect agent

The existing agent file at `.claude/agents/fukan-architect.md` carries the "high-altitude design partner; pure thinker; reads the model only" stance. Phase 5 extends it (not replaces it) with three additions. The agent definition itself remains read-only at the tool level — it still has only `Bash(fukan eval *|fukan status|fukan primer)`. The survey mode does its weighing through those tools.

### Addition 1 — Tier awareness

A new section after **Operating principles** explaining the trust/weigh partition:

- **Trust tier helpers (`inspect/*`)** produce decision-ready findings. The agent invokes them first to establish a structural baseline. Their output is stated as facts in the agent's response ("`model.build` has 3 unresolved references; their stable-ids are…").
- **Weigh tier helpers (`architect/*`)** produce observations. The agent invokes them when surveying improvements and frames their output as observations plus judgment ("three affordances in `validation/*` share shape `(Model) -> [Violation]`; vocab.validation/checker already covers this pattern — consider applying it").
- The architect agent never collapses the distinction. A weigh-tier cluster is not "an error"; a trust-tier broken reference is not "something to consider".

### Addition 2 — The `survey design improvements` mode

A new operating mode the agent enters when the dispatching prompt names "survey", "weigh", "improvements", or "design review" alongside a scope. The mode does the following deterministically:

1. Invokes the trust tier first via `bin/fukan eval` against the scope. If trust-tier findings are non-empty, the survey is paused and the trust-tier findings are returned as the agent's primary response. (Discipline: don't survey for refinements on top of structurally broken canvas.)
2. With trust tier clean, invokes `(canvas.lens.survey/run (model) <lens-ids>)` — default lens-set or per-request scope.
3. Synthesises the two outputs into a unified report with sections:
   - **Recurring patterns** — clusters of 3+ with the suggested lift (existing or new) and the entities involved.
   - **Consistency observations** — naming/style and structural-symmetry notes, each annotated with "likely intentional" vs "candidate normalization" framing.
   - **Open judgments** — items the agent surfaces but cannot decide ("these 3 records share `:id :String :timestamp :Instant`; could be lifted, or coincidental — caller's call").
4. Returns the report. Does not propose edits, does not invoke the author-LLM to act.

The survey is **monolithic** (Phase 5 user decision). If usage shows the report growing past readable length, Phase 6 splits it into named verbs (`survey patterns`, `survey consistency`) under a shared dispatch shape.

### Addition 3 — Reference to the permanent canvas-authoring system prompt

The agent definition gains a pointer to `doc/canvas-authoring-system-prompt.md` and a one-line statement that the architect agent reasons in that tradition. This is how the layered-language posture from Phase 2 becomes ambient across both authoring and surveying — the author-LLM has the prompt as its operating frame, and the architect agent uses the same prompt as the lens through which it surveys.

The agent file does not embed the full prompt; it Reads it via repo path. The prompt is a separate, versioned artifact.

---

## The architect-explorer prompt evolution

Phase 2's `doc/plans/2026-05-25-architect-explorer-system-prompt.md` is the load-bearing reference. It activated layered-language thinking in three independent LLM sessions. Phase 5 promotes it to a permanent reusable artifact at `doc/canvas-authoring-system-prompt.md`.

The transformation is selective. Some parts generalize directly; some are experiment-specific framing that has to be removed; new content must be added.

### What generalizes (carries forward unchanged or lightly edited)

- **"Fukan in brief"** and **"The substrate (do not change it)"** — the six primitives, the three structural rules, the architecture-neutrality stance. Permanent canon.
- **"Lineage"** — Abelson & Sussman, Steele, Hickey, Felleisen, Backus. The activation surface for layered-language thinking. Maybe the single most important part of the original prompt; ship it intact.
- **Principles 1-6**:
  - 1 Compose first, fuse second.
  - 2 Vocabulary is justified by use.
  - 3 Reads-naturally is the test.
  - 4 The substrate is sacred; the lift library is not.
  - 5 Doubt is data.
  - 6 Lift fluently across altitudes.
  These are universal canvas-authoring principles, not experiment-specific.

### What was experiment-specific (cut or rewrite)

- **"Your task" framing** ("Port the specs assigned to you...") — the Phase 2 prompt addressed a specific port-from-Allium exercise. The permanent prompt addresses *ongoing canvas authoring* (extending modules, refactoring, writing new ones) so the task framing must be generalized: "You are authoring canvas content. The specific task is in the user prompt."
- **Principle 7 — "You are not constrained by Allium"** — Phase 2-specific. Allium is now archived. The permanent prompt replaces this with: "Canvas is the surface. You are not constrained by any prior spec language; you are constrained by what reads naturally for the system you're describing."
- **"Workspace and output"** referring to `src/fukan/canvas/library/explore_N/` and `src/fukan/canvas/pilot/explore_N/` — Phase 2's exploration sandbox. The permanent prompt directs to the live `canvas/` tree and the existing vocab namespaces (`src/fukan/canvas/vocab/*`).
- **"How you know you're done"** referring to "the provided specs are fully ported" — replaced with general session-end discipline: trust tier clean, survey reviewed, intent realised.
- **"A closing note on stance"** — the Phase 2 framing ("output of a great session might be 200 lines of canvas, 50 lines of new lifts, 400 lines of reflection") is still good but specific to the exploration exercise. Generalize to: "Output of a great session is canvas that reads at module-design altitude, plus an honest accounting of what was considered and rejected."

### What is new (added for the authoring loop)

- **A "Tier model" section** between "Principles" and the task framing. Explicitly: trust tier under `inspect/*` is authoritative; weigh tier under `architect/*` is observational. The author-LLM treats them differently. Three lines, not three pages.
- **A "The loop" section** describing the Phase A/B/C turn shape from this doc. Short — three or four bullet points so the LLM can hold it in working context.
- **A "Reach for existing vocab first" section** — names the four vocab namespaces (`behavioral`, `lifecycle`, `validation`, `event`) and the EXAMPLES.md pattern. Explicit instruction: before inventing a new lift, the LLM Reads the existing EXAMPLES, then surveys for similar patterns in the canvas tree (via `(primitives :kind ...)` and shape-similarity queries).
- **A "Failure modes" section** — the four documented in this doc (inventing vocab that doesn't recur, hallucinating entity refs, mid-module methodology drift, collapsing the tier distinction). Surface them explicitly so the LLM has named anti-patterns to check against.
- **The survey-dispatch contract** — one paragraph on when and how the author-LLM dispatches the `fukan-architect` agent in survey mode, what to expect back, and how to read the result (observations, not verdicts).

### What the prompt is NOT

It is not a tutorial on the substrate. The substrate is documented in `doc/MODEL.md`, the construction primitives are documented in `src/fukan/canvas/construction.md`, etc. The prompt activates a *stance*; the EXAMPLES and docs supply the *mechanics*. Keeping the prompt tight (~300 lines, not 1000) is part of its job.

---

## Failure modes and discipline

The loop is designed against five known failure modes. Each one has a specific point in the loop where it's caught.

**1. Inventing vocab that doesn't recur (Phase 2's source-priming failure).** Phase 2 saw at least one session invent a `:provides` lift that turned out to mirror Allium's vocabulary rather than serve a recurrent canvas need. The discipline against this is **rule-of-three via the weigh-tier survey**. A new lift cannot ship until `architect/patterns` shows it would simplify three or more concrete sites. The author-LLM does not promote a candidate vocab on the first occurrence; it notes the pattern, continues authoring, and lets the survey confirm or deny.

**2. Hallucinating entity references.** The author-LLM writes `:model.spec/Model` and the entity doesn't exist. **Trust-tier `inspect/integrity`** catches this on the very next Phase C reflection. The author-LLM treats integrity findings as authoritative and either fixes the reference or escalates.

**3. Drifting methodology mid-module.** The module starts using `vocab.event` and halfway through switches to `vocab.validation/checker` for what should still be a handler. The discipline lives in **weigh-tier consistency observations** plus the prompt's "reach for existing vocab first" rule. The survey flags methodology drift (sister-module asymmetry, mixed vocab fingerprint) as an observation; the author-LLM reads it and either justifies the mix or normalizes.

**4. Producing changes that survive trust-tier checks but degrade design coherence.** This is the most insidious failure: structurally valid canvas that is harder to read than what it replaced. The discipline is the **closing survey before session-end**. The author-LLM does not declare done until it has run a final `survey design improvements` against the modified scope and weighed the result. A clean integrity check is necessary but not sufficient; the session ends with both tiers consulted.

**5. Collapsing the tier distinction (treating weigh as authoritative).** The author-LLM reads "three affordances share a shape" and immediately extracts a lift without weighing whether the cluster is real recurrence or coincidence. The discipline is **prompt-level and report-level**: the canvas-authoring system prompt names the distinction; the survey report frames observations explicitly as "candidate"/"likely intentional"/"open judgment" rather than as verdicts. The author-LLM is instructed to pause and weigh, not to act on first read.

---

## Trial-run target (recommended)

The Phase 5 plan offers three options for Sprint 4 Task 11. The recommendation is **target (c) — author a new small subsystem hand-in-hand**, specifically a small new entry under `demo/` modeled in a paradigm the current canvas hasn't deeply tested.

### Reasoning

Target (a) — refactoring an existing canvas module — has real value but low novelty for the loop. The author-LLM would be working with content already at module-design altitude; the feedback signals would surface refinements, not authoring decisions. The loop's stress points (vocab choice, methodology selection, first-cluster detection) wouldn't be exercised. Verification of the loop demands richer authoring than refactor allows.

Target (b) — extending an existing demo (e.g. `vocab.cqrs` in `demo/event-driven/`) — tests the vocab promotion path, which is genuinely valuable. But Phase 4's stress-test findings explicitly named the demos as **frozen stress-test artifacts**. Mucking with `demo/event-driven/` dilutes its value as a paradigm-bracket validation. The vocab-promotion path can be tested in target (c) without that cost.

Target (c) — a new small subsystem authored hand-in-hand — exercises the full loop:
- Module-design choices from scratch (the author-LLM has to decide structure, not refine it).
- Vocab selection (does this paradigm need `vocab.behavioral`? `vocab.event`? something new?).
- Rule-of-three discipline (will the third instance of a pattern surface organically?).
- Trust-tier integrity checks at every turn (cross-module references in fresh content).
- Weigh-tier survey at the end (does the new module read at altitude?).

The variability of outcome is the *point*. Phase 5's verification needs subjective evidence ("the loop felt like a thinking-enhancing tool") plus structural evidence ("the LLM consulted N feedback signals and acted on M"). Target (c) produces both.

### Sketch of the target

The parallel Sprint 1 Task 1 doc leans toward a new handler-rich event-driven demo. The proposal here echoes that:

- **Target subsystem:** `demo/distributed/` — a small distributed coordination paradigm (e.g. a 3-node leader election, or a request-routing service with retries and circuit-breakers).
- **Why distributed:** Phase 4's two paradigm-brackets (static-lib lower, event-driven upper) didn't test concepts like timeouts, retries, idempotency, and consensus. Distributed paradigms also tend to surface new vocab candidates (`vocab.retry`? `vocab.consensus`?) without being so alien that the substrate can't hold them.
- **Scope:** 3-4 small modules, similar in size to the existing demo subsystems. Authored over Sprint 4 Task 11 in one or two sessions.
- **Success criteria (subjective, weighed by user):** does the loop's feedback meaningfully shape the LLM's authoring? Does the survey surface real candidate lifts? Does the canvas at session-end read at module-design altitude?

### What this trial-run is NOT

It is not a verification of feedback-signal correctness — that's Sprint 3's job, against fukan-itself. It is not a paradigm-coverage exercise — Phase 4 already validated paradigm-bracketing. It is a verification of **the loop as a thinking-enhancing tool**, which is Phase 5's core deliverable.

The trial run should be brief, honest, and willing to fail. If the loop does not produce a noticeable thinking improvement, Phase 5 verification surfaces that and the loop is redesigned — that's the value of running the trial, not avoided by hedging the target.

---

## Open questions for the user

These do not block Sprint 2 dispatch but shape downstream work:

1. **Author-LLM driver in the trial run.** The trial-run target is "author a small subsystem hand-in-hand". Who's the author? Options: (a) a dispatched Claude Code session with the canvas-authoring system prompt, the human in the loop at decision points; (b) the human author, with the `fukan-architect` agent invoked only for surveys; (c) a true mixed mode where both LLM and human alternate phases. Recommendation: (a) for cleanest verification — the loop's stress points show up under LLM authoring; but the user may have a stronger view.

2. **System prompt versioning.** `doc/canvas-authoring-system-prompt.md` is intended as a permanent reusable artifact. Should it be versioned (`doc/canvas-authoring-system-prompt-v1.md`) so future evolutions don't silently change behavior, or kept at a single path with git history as the version trail? Default lean: single path; the architect-explorer prompt at `doc/plans/2026-05-25-architect-explorer-system-prompt.md` stays as a frozen Phase 2 artifact for reference.

3. **Survey dispatch interface.** The author-LLM dispatches `fukan-architect` in survey mode. The dispatch mechanism within Claude Code is the standard Task tool (assuming standard agent dispatch). Is there a preferred slash-command or prompt shape the user wants to see (`/survey-design <scope>`)? Phase 5 currently assumes free-form dispatch.

4. **Trial-run target confirmation.** Recommendation is `demo/distributed/`. Is there a different paradigm the user would rather see exercised? Phase 4's findings noted CQRS and actor model as untested brackets; either could substitute.

5. **Closing-survey discipline as hard rule.** The doc proposes the closing survey as an end-of-session discipline. Should the canvas-authoring system prompt name this as a hard rule ("no session ends without a closing survey") or a strong default? Hard rules constrain unhelpful corner cases (e.g. a one-line documentation fix); strong defaults allow LLM judgment but risk the discipline eroding.

6. **Survey-tier output rendering.** The architect agent currently returns prose. The Phase 5 survey returns more structured content (clusters, observations, judgments). Should the survey output be plain markdown, structured EDN the author-LLM can re-query, or both? Default lean: structured EDN inside a markdown wrapper, so the report is readable to humans and re-parseable by the LLM.

---

## Tracking summary

| Section | Status |
|---|---|
| Strategic frame | Drafted |
| Settled choices | Recapped |
| Minimum viable loop | Drafted (Phase A/B/C turn shape; survey-interlude rules; human edge-of-loop) |
| Extended `fukan-architect` agent | Sketched (tier awareness; survey mode; prompt reference) |
| Architect-explorer prompt evolution | Mapped (generalize / cut / add) |
| Failure modes | Five named with discipline point |
| Trial-run target | Recommended: `demo/distributed/` |
| Open questions | Six surfaced for user review |

**Next:** User review. On approval, Sprint 2 dispatches (Tasks 3–5: name+role convention promotion + `emits` form + `:type/fields` substrate addition). The canvas-authoring system prompt itself is drafted in Sprint 4 Task 11 once the trust-tier helpers + lens substrate are built; Sprint 1 settles only its intent.

---

## Amendment — 2026-05-27 lens-substrate reframe

The user's reframe of 2026-05-27 introduces a **pluggable lens substrate** for the weigh tier. The minimum-viable-loop sections above are essentially unchanged in shape, but the weigh-tier invocation changes from "the architect runs `architect/patterns` + `architect/consistency`" to "the architect dispatches a survey through the lens registry: `(canvas.lens.survey/run (model) <lens-ids>)`".

**Key shifts:**

1. **`src/fukan/canvas/architect/` becomes `src/fukan/canvas/lens/`.** Lenses are first-class entities with a contract (id, description, prompt-fragment, optional compute, render).
2. **The monolithic survey becomes a configurable lens-set.** Default set covers `[:patterns :consistency :tar-pit]` for Phase 5. Per-dispatch override allows narrower or experimental lens-sets.
3. **Phase 5 ships a theoretical lens (`tar-pit`).** This proves the substrate handles non-structural lenses where the prompt fragment is the primary artifact and the compute fn extracts canvas slices to feed the LLM.
4. **Prompts are data.** Each lens carries its own prompt-fragment editable without code changes. The architect base prompt at `doc/canvas-authoring-system-prompt.md` is similarly editable. Experimentation with thinking modes is therefore a file-edit operation, not a recompile.
5. **`fukan-architect` discovers lenses at runtime.** `(canvas.lens.registry/all-lenses)` lists what's available; the agent's instructions teach it to pick a relevant lens-set per task.

The five named failure modes (vocab-without-recurrence, hallucinated-references, methodology-drift, structurally-valid-but-design-degrading, treating-weigh-as-authoritative) all carry forward. The lens substrate makes the fifth one more salient: when the survey now includes theoretical lenses (Tar Pit, etc.), the trust/weigh distinction must be especially clear in the agent prompt.

Sprint 3's task list is amended to ship the lens substrate + 3 starter lenses (see `doc/plans/2026-05-26-canvas-substrate-phase-5.md` Sprint 3, Tasks 8-10). The remaining design choices in this doc — turn shape, when to invoke the survey, human position in the loop — are unchanged.
