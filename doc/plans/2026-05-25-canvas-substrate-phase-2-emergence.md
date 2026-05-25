# Phase 2 Sprint 2b — Emergence Experiment

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complement Sprint 2a's translation experiment with an emergence experiment. Strip source-vocabulary priming entirely: mechanically project a corpus of Allium specs to raw substrate data, then dispatch parallel agents to extract patterns from the data and propose lifts — agents that never see the source specs or any existing canvas vocabulary. Compare the two experiments' outcomes to evaluate the priming effect on Sprint 2a's results.

**Why this experiment.** Sprint 2a asked agents to *port* specs — a translation exercise. Translation primes vocabulary: agents reading `invariant Foo { ... }` in an Allium file have "invariant" in their working memory. The strongest Sprint 2a convergence (all three sessions landed on an `invariant` lift) could be a genuine natural pattern OR a translation artifact. This experiment isolates the second possibility by removing source priming entirely. Stage-2 agents see only substrate data, never source vocabulary.

**Architecture:**

- **Stage 1 — Mechanical projection.** One agent reads Allium specs and emits a single corpus file of raw substrate facts (Modules, Affordances, States, Types, Relations). Critically, the projection assigns no roles, no tags, no source-derived vocabulary. Affordances are distinguished only by structural facts (shape presence, formal-expression presence).

- **Stage 2 — Pattern extraction.** Three parallel agents in isolated working directories see ONLY the corpus file and a system prompt. They have no access to the source specs, the existing canvas code, the redesign doc, or each other. They extract recurring patterns from the data and propose lifts that would make declaring those patterns ergonomic.

- **Stage 3 — Comparative synthesis.** Compare Stage 2's emergence-driven lifts to Sprint 2a's translation-driven lifts. Convergence = vocabulary is natural (low priming effect). Divergence = Sprint 2a's vocabulary was source-primed; the Stage 2 vocabulary is what canvas-native looks like. Noise from Stage 2 = pattern extraction without source scaffold is too hard.

**Tech stack:** Same as Phase 2. Working directory for Stage 2 sessions is a fresh isolated path (not the main fukan repo) containing only the corpus + prompt — minimizes the chance of accidental source reading.

**Reference documents (read in this order):**

- `doc/plans/2026-05-25-canvas-substrate-phase-2.md` — Phase 2's main plan; this experiment is a sibling of Sprint 2a within Phase 2 Sprint 2.
- `doc/plans/2026-05-25-architect-explorer-system-prompt.md` — Sprint 2a's system prompt (for reference; NOT what Stage 2 agents see).
- `doc/plans/2026-05-25-emergence-stage1-system-prompt.md` — Stage 1 mechanical-projection prompt (created in Task 1 of this plan).
- `doc/plans/2026-05-25-emergence-stage2-system-prompt.md` — Stage 2 pattern-extraction prompt (created in Task 1).

**Scope:** This experiment runs after Sprint 2a's three sessions complete (already done as of 2026-05-25) and before the eventual Phase 2 synthesis (Task 6 in the main plan, currently deferred). The results feed into both the synthesis step and Phase 2's verification report.

---

## File structure

**New artifacts:**

```
doc/plans/
  2026-05-25-emergence-stage1-system-prompt.md   ; created in Task 1
  2026-05-25-emergence-stage2-system-prompt.md   ; created in Task 1
  2026-05-25-emergence-corpus-readme.md          ; created in Task 2 (inventory of corpus contents)
  2026-05-25-emergence-stage2-session-A-notes.md ; created in Task 3, per Stage 2 agent
  2026-05-25-emergence-stage2-session-B-notes.md
  2026-05-25-emergence-stage2-session-C-notes.md
  2026-05-25-emergence-comparison.md             ; created in Task 4 — final cross-experiment comparison

/tmp/fukan-emergence/                            ; isolated workspace; agents work here, NOT in the main repo
  corpus.edn                                     ; the substrate facts — Stage 1 output
  system-prompt.md                               ; Stage 2 system prompt (copy of the file in doc/plans)
  session-A/                                     ; per-Stage-2-session subdirectory
    proposed-lifts.clj
    patterns-extracted.md
  session-B/
  session-C/
```

**Files NOT touched:**

- Sprint 2a's explore workspaces (`../fukan-explore-{1,2,3}`) — preserved for synthesis comparison.
- Existing canvas code, lifts, substrate, helpers, tests — unchanged by this experiment.
- The main `.allium`/`.boundary` files — Stage 1 reads them, but no writes back.
- The main repo's plan docs — Stage 2 agents do not see them.

---

## Sprint 2b, Task 1: Author both system prompts

**Files:**
- Create: `doc/plans/2026-05-25-emergence-stage1-system-prompt.md`
- Create: `doc/plans/2026-05-25-emergence-stage2-system-prompt.md`

The two prompts are different load-bearing artifacts. Stage 1 is mechanical and neutral; Stage 2 is creative and primed for pattern extraction.

### Stage 1 prompt — Mechanical projection

The prompt instructs an agent to read `.allium` and `.boundary` files and emit a corpus of substrate facts. Key constraints:

- The projection is **mechanical**, not interpretive. Every Allium declaration maps to substrate primitives via a fixed rule set; the agent does not "decide" what a declaration means.
- **No roles assigned.** Allium's `invariant`/`guarantee`/`rule`/`assertion` declarations all project to Affordances with no shape and a formal-expression carrying the body text. The substrate's optional `:role` slot is left UNFILLED. The agent does NOT echo source vocabulary into role keywords.
- **No tags.** `exports:` clauses are skipped at this stage (or recorded separately as out-of-corpus notes — they're closure structure, not Affordance structure).
- **Cross-module references** are recorded as substrate Relations (`:references` kind, from the using Affordance/Type to the referenced Type keyword). This is structural and should survive.
- **`triggers:` clauses** project to Relations from the source Affordance to the target Affordance (kind: just `:triggers`). The Relation kind is named structurally; this is unavoidable but stays generic.
- The output is a single edn file (`corpus.edn`) containing a sequence of substrate-entity maps. Each map has `:type` (Module/Affordance/State/Type/Relation), the relevant primitive fields, and nothing else.
- A README (`emergence-corpus-readme.md`) inventories what's in the corpus (counts per type, names per module) WITHOUT interpreting it. Pure inventory.

The agent has read access to source specs and the substrate definition. The agent does NOT have access to existing canvas lifts (`monolith.clj`, `behavioral.clj`, etc.) — to avoid implicitly priming the projection with current lift vocabulary.

### Stage 2 prompt — Pattern extraction

The prompt activates pattern-discovery thinking. Key elements:

- The lineage from the Sprint 2a system prompt (SICP, Steele, Hickey, Felleisen, Backus) — retained.
- Additional references for the pattern-extraction workflow specifically:
  - **Christopher Alexander, *A Pattern Language* and *The Timeless Way of Building*** — the foundational tradition of pattern discovery. Patterns are recurring solutions to recurring problems; you discover them by looking at many examples and finding the common form. Names emerge from use.
  - **Paul Graham, *On Lisp*** — explicitly about climbing abstraction layers bottom-up. Each layer becomes a new language; you write the level above in the language you built. The mode of thinking that designs DSLs from concrete code.
  - **Peter Naur, "Programming as Theory Building"** (1985) — programmers build a theory of the system through engagement with its data and code. The theory is the residue of pattern recognition over experience.
- The task: examine the corpus, identify recurring structural patterns, propose lifts that would make declaring those patterns ergonomic. Each proposed lift must have at least three instances in the corpus (Beck's rule of three).
- **Strict file-access rule:** the agent's working directory contains the corpus + system prompt + an empty session subdirectory. The agent MUST NOT read anything outside its working directory. The system prompt emphasises that reading existing canvas code, `.allium` source, or sibling sessions would invalidate the experiment.
- Output: `proposed-lifts.clj` (Clojure code — defconstructor or macro definitions) and `patterns-extracted.md` (reflection on what patterns the agent saw, what names it chose, what it rejected).

### Steps

- [ ] **Step 1: Draft `2026-05-25-emergence-stage1-system-prompt.md`.** Follow the Stage 1 specification above. Be precise about the projection rules.

- [ ] **Step 2: Draft `2026-05-25-emergence-stage2-system-prompt.md`.** Build on Sprint 2a's system prompt as a base (keep the substrate definition, the existing lineage block) BUT:
  - Replace "Your task" section with the pattern-extraction task description.
  - Replace "Workspace and output" section with the isolated working directory and file-access rule.
  - Add the Alexander / Graham / Naur references in the Lineage section as workflow-specific supplements.
  - Add a new principle (call it "Read only the corpus") with the file-access rule and the rationale.
  - Remove "You are not constrained by Allium" — irrelevant since the agent doesn't see Allium.

- [ ] **Step 3: Commit both prompts**

```bash
jj desc -m "doc(canvas): Sprint 2b emergence experiment system prompts (Stage 1 + Stage 2)"
jj new
```

---

## Sprint 2b, Task 2: Stage 1 — Mechanical corpus projection

**Files:**
- Create: `/tmp/fukan-emergence/corpus.edn`
- Create: `doc/plans/2026-05-25-emergence-corpus-readme.md`

Dispatch a single agent with the Stage 1 system prompt. The agent reads the assigned `.allium` and `.boundary` files, applies the projection rules mechanically, and emits the corpus.

**Corpus contents — eight modules across four subsystems:**

| Module | Source files | Subsystem |
|--------|--------------|-----------|
| infra/server | `src/fukan/infra/server.allium` + `.boundary` | infra |
| infra/model | `src/fukan/infra/model.allium` + `.boundary` | infra |
| constraint/evaluator | `src/fukan/constraint/evaluator.allium` + `.boundary` | constraint |
| constraint/builtins | `src/fukan/constraint/builtins.allium` + `.boundary` | constraint |
| validation/phase4 | `src/fukan/validation/phase4.allium` + `.boundary` | validation |
| validation/rules_4a | `src/fukan/validation/rules_4a.allium` + `.boundary` | validation |
| vocabulary/allium/pipeline | `src/fukan/vocabulary/allium/pipeline.allium` + `.boundary` | vocabulary |
| web/handler | `src/fukan/web/handler.allium` + `.boundary` | web |

Eight modules covers four subsystems with two modules each. Includes overlap with Sprint 2a's targets (so the comparison can be direct) and Phase 1's pilot ports (so we can also check whether Phase 1 captures hold up).

### Steps

- [ ] **Step 1: Create the isolated working directory.**

```bash
mkdir -p /tmp/fukan-emergence
cp doc/plans/2026-05-25-emergence-stage2-system-prompt.md /tmp/fukan-emergence/system-prompt.md
```

(Stage 1 agent doesn't work in this directory — it works in the main repo to read sources — but the directory is prepared for Stage 2.)

- [ ] **Step 2: Dispatch Stage 1 agent** with system prompt = `doc/plans/2026-05-25-emergence-stage1-system-prompt.md` content. User prompt provides the eight source-module list and output paths.

  - Working directory: `/Users/yabmas/Code/fukan` (the main repo — agent needs to read source specs).
  - Allowed reads: the eight pairs of `.allium` and `.boundary` files; the substrate primitive definition at `src/fukan/canvas/substrate.clj`.
  - NOT allowed: any existing canvas lift file (`monolith.clj`, `behavioral.clj`, `closure.clj`, `shape.clj`, the explore_N/ directories) — to keep the projection unaffected by current lift vocabulary.
  - Output 1: `/tmp/fukan-emergence/corpus.edn` — a sequence of substrate-entity maps.
  - Output 2: `doc/plans/2026-05-25-emergence-corpus-readme.md` — pure inventory.

- [ ] **Step 3: Verify the corpus.**

  - Read the corpus README. Confirm coverage (eight modules, expected entity counts).
  - Open the corpus file. Confirm it has the expected shape: a sequence of maps with `:type`, structural fields, no roles, no tags.
  - Sanity check: do Affordances with formal-expressions (Allium invariants/rules) have NO role set? They MUST not — that's the load-bearing isolation.

  If the corpus echoes source vocabulary (e.g. role keywords like `:invariant`), the Stage 1 agent failed to follow the projection rules. Rerun with sharper instructions.

- [ ] **Step 4: Commit the README + corpus.**

```bash
# The corpus lives at /tmp; only the README is in-repo
jj desc -m "doc(canvas): Sprint 2b emergence corpus inventory"
jj new
```

(The corpus itself is in `/tmp` and intentionally NOT committed — it's regenerable from sources and we don't want it leaking into agents' context as a doc file.)

---

## Sprint 2b, Task 3: Stage 2 — Three parallel pattern-extraction sessions

**Files:** created by the sessions themselves in `/tmp/fukan-emergence/session-{A,B,C}/`.

Dispatch three Agent calls in parallel via `run_in_background: true`. Each agent's working directory is its session subdirectory in `/tmp/fukan-emergence/`. Each agent sees ONLY the corpus + system prompt + its own (initially empty) session directory.

### Per-session setup

```bash
mkdir -p /tmp/fukan-emergence/session-A
mkdir -p /tmp/fukan-emergence/session-B
mkdir -p /tmp/fukan-emergence/session-C
```

Each session's dispatched agent receives:

- System prompt (verbatim): contents of `doc/plans/2026-05-25-emergence-stage2-system-prompt.md`.
- User prompt header: session label (A/B/C), working directory path, file-access rule reminder.
- The corpus is accessible at `../corpus.edn` from the session directory; the system prompt copy at `../system-prompt.md`.

### Independence rules

- Sessions A, B, C do NOT see each other's outputs during their work. The synthesis step (Task 4) compares across.
- Sessions cannot read the main `/Users/yabmas/Code/fukan/` repo. The system prompt makes this explicit; the working directory is `/tmp/fukan-emergence/session-X/`. If an agent tries, the result is contaminated and we re-dispatch.

### What each session produces

Per Stage 2 system prompt's "How you know you're done" section (defined in Task 1):

- `proposed-lifts.clj` — Clojure source containing the lift definitions the agent proposes.
- `patterns-extracted.md` — a reflection doc covering:
  - Each lift, with the recurring pattern in the corpus that justifies it (the rule-of-three constraint).
  - Patterns the agent identified but rejected as too specific (one or two instances only) — these are evidence too.
  - Anything ambiguous or unsettled.
  - Self-reported list of files the agent read (for audit purposes — confirms the file-access rule held).

### Steps

- [ ] **Step 1: Prepare the three session directories.**

- [ ] **Step 2: Dispatch all three Stage 2 agents in parallel** (one Agent tool call per session, all in a single message, `run_in_background: true`). Each agent's cwd is its session subdirectory. Each receives the same system prompt.

- [ ] **Step 3: Wait for completion notifications.** Do not poll.

- [ ] **Step 4: Audit each session's file-access self-report.** Each session's `patterns-extracted.md` includes a list of files read. Confirm none of them are outside `/tmp/fukan-emergence/session-X/` and `../corpus.edn`. If any session read outside, mark it CONTAMINATED and re-dispatch.

- [ ] **Step 5: Commit the three sessions' outputs as docs in the main repo.**

  Move each session's outputs from `/tmp/fukan-emergence/session-X/` into `doc/plans/`:
  - `2026-05-25-emergence-stage2-session-A-notes.md` (the reflection doc)
  - Similarly for B and C
  - The `proposed-lifts.clj` files can be inlined in the reflection docs OR stored as separate Clojure files alongside — pick the cleaner option per session.

```bash
jj desc -m "doc(canvas): Sprint 2b emergence — three independent pattern-extraction sessions"
jj new
```

---

## Sprint 2b, Task 4: Stage 3 — Comparative synthesis

**Files:**
- Create: `doc/plans/2026-05-25-emergence-comparison.md`

This is the load-bearing analytical step of Sprint 2b. It compares Stage 2's emergence results to Sprint 2a's translation results and answers:

1. **Did Stage 2 sessions converge on a vocabulary?** If three independent agents seeing only data converged on similar lifts, the vocabulary is genuinely emergent. If they diverged or produced noise, pattern extraction without source priming is harder than expected.

2. **Does Stage 2's vocabulary match Sprint 2a's?** Compare lift-by-lift:
   - Same conceptual lift, same name (e.g. all sessions across 2a + 2b proposed `invariant`) → vocabulary is natural; source priming was not a confounder.
   - Same conceptual lift, different name (e.g. 2a's `invariant` = 2b's `commitment`) → priming affected naming but not structure; the concept is real.
   - Different conceptual lifts → priming affected what 2a saw as patterns; 2b's view is the unprimed natural shape.

3. **What did Stage 2 reject that Sprint 2a accepted (or vice versa)?** Rejection patterns are evidence about what each set of agents thought was load-bearing vs. cosmetic.

### Synthesis subagent or human-in-loop conversation?

The plan recommends **human-in-loop conversation** for this step. The comparison involves judgment about what "convergence" means and how to weigh evidence — exactly the kind of synthesis that a human + AI collaborator handles better than an autonomous subagent. The user reads the Stage 2 outputs, reads the Sprint 2a outputs, and has a structured comparison conversation with the controller.

A subagent CAN be dispatched to produce a first-pass comparison document if useful (e.g. "list every lift across all six sessions, group by conceptual similarity, note naming variants"). The synthesis CALL is human.

### Steps

- [ ] **Step 1: Optional first-pass subagent.** Dispatch a comparison-only agent with read access to:
  - Sprint 2a workspaces: `../fukan-explore-1/doc/plans/2026-05-25-explore-1-notes.md` (and 2, 3) and the `library/explore_N/` code
  - Sprint 2b outputs: `doc/plans/2026-05-25-emergence-stage2-session-{A,B,C}-notes.md`
  - Its task: produce a structured comparison table — every lift across all six sessions, grouped by conceptual similarity, with naming variants noted.

  Output: a draft of `doc/plans/2026-05-25-emergence-comparison.md`'s "lift catalog" section.

- [ ] **Step 2: Human-in-loop conversation.** The user reads the first-pass comparison, the Sprint 2a outputs, and the Sprint 2b outputs. Together with the controller, walks through:
  - Conceptual convergences (lifts that appeared across 2a AND 2b independently)
  - Naming convergences (same concept, same name) vs naming divergences (same concept, different name)
  - Concepts in 2a that didn't appear in 2b (potential source priming)
  - Concepts in 2b that didn't appear in 2a (potential emergence-only insights)
  - Final assessment of source-priming effect.

- [ ] **Step 3: Write the comparison doc** (`2026-05-25-emergence-comparison.md`) capturing the synthesis:
  - **Lift catalog** — every lift across all sessions, grouped.
  - **Convergence assessment** — strong/medium/weak per lift.
  - **Source-priming assessment** — was 2a's vocabulary natural or primed?
  - **Recommendation for the converged library** — which lifts should be in the actual converged Phase 2 library design, with rationale grounded in evidence from both experiments.

- [ ] **Step 4: Commit.**

```bash
jj desc -m "doc(canvas): Sprint 2b emergence comparison + source-priming assessment"
jj new
```

---

## Outcomes and what feeds forward

Three possible outcomes:

1. **High convergence between 2a and 2b** (most lifts in 2a appear independently in 2b under similar names) → source priming was mild. Sprint 2a's vocabulary is naturally emergent and the converged library can be drawn primarily from Sprint 2a results, with 2b serving as evidence backing.

2. **Partial convergence** (some lifts converge, others diverge) → priming affected specific lifts but not the whole vocabulary. The convergent lifts (e.g. `invariant` across all six sessions) are well-evidenced; the divergent ones need closer reading to pick the right form for the converged library.

3. **Low convergence** (Stage 2 produces substantively different vocabulary) → Sprint 2a was heavily primed. The 2b vocabulary is closer to what canvas-native looks like. The converged library should be drawn primarily from 2b, with 2a serving as a useful counterpoint about what source-primed thinking produces.

The result feeds into Phase 2's main synthesis (Task 6 in the main plan, currently deferred) and Phase 2's verification report (Task 9). The verification's Section 2 — "was the Architect Explorer experiment validated?" — gains a stronger answer because we have two complementary experiments instead of one.

---

## Self-review notes

- **The isolation is honor-system, not enforced.** Stage 2 agents have file-system access; they CAN read outside their working directory. The system prompt forbids it; we audit via self-report. If audit reveals contamination, re-dispatch.
- **Stage 1's projection still makes choices.** What attributes survive to the corpus? What gets dropped? The corpus is "raw" relative to source vocabulary, but it's still a representation choice. The Stage 1 prompt has to be tight about what passes through.
- **Three sessions might be too few.** If Stage 2 sessions produce highly divergent results, more sessions would help. Budget allowing, we could run six instead of three. For Sprint 2b, three is a sane starting point; expand if needed.
- **The synthesis (Stage 3) is human-in-loop on purpose.** This is judgment work. Don't autonomous it.
- **Sprint 2a's results are preserved unchanged.** Sprint 2b is purely additive evidence; it doesn't invalidate or replace Sprint 2a. Both experiments contribute to the eventual converged library.

---

## Tracking summary

| Stage | Task | Outcome |
|-------|------|---------|
| 1 | Sprint 2b Task 1 | Two system prompts authored |
| 2 | Sprint 2b Task 2 | Single corpus produced by one mechanical agent |
| 3 | Sprint 2b Task 3 | Three independent pattern-extraction sessions |
| 4 | Sprint 2b Task 4 | Comparative synthesis (2a vs 2b) + source-priming assessment |

**Estimated calendar:** Task 1 ≈ one session (drafting prompts). Task 2 ≈ one session (corpus production + audit). Task 3 ≈ one parallel-dispatch session. Task 4 ≈ one synthesis conversation. **Total: 3–4 working sessions.**
