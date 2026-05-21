# Fukan Agent Workflows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the project-level agent surface (`fukan-architect` + `fukan-reconciler`) and its five slash commands; retire the existing `spec`/`structurize` pair.

**Architecture:** Two subagent definitions under `.claude/agents/`; five slash-command files under `.claude/commands/`. Both agents share a condensed Fukan vision; the architect has airtight Bash-only access scoped to `bin/fukan`; the reconciler has full read/write/dispatch with the compose-and-critique loop. Pairs each agent with at least one command so every task is end-to-end testable.

**Tech Stack:** Markdown + YAML frontmatter (Claude Code agent + slash-command format); references `bin/fukan` CLI; dispatches `allium:*` subagents via the `Agent` tool.

**Spec:** [`doc/specs/2026-05-21-fukan-agent-workflows-design.md`](../specs/2026-05-21-fukan-agent-workflows-design.md). Cross-references below are to that document.

**VCS:** This repo uses Jujutsu (`jj`). Each task starts a fresh changeset with `jj new -m "…"`; file edits auto-snapshot into `@`. Verify with `jj st`.

---

## Task 1: fukan-architect agent + /fukan-design command

Deliver the architect end-to-end. Tool restriction is enforced at the frontmatter level; the system prompt carries the condensed vision and model-only stance.

**Files:**
- Create: `.claude/agents/fukan-architect.md`
- Create: `.claude/commands/fukan-design.md`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(claude): add fukan-architect agent + /fukan-design command"
jj st
```

Expected: `@` is empty, parent is the design-spec commit.

- [ ] **Step 2: Draft `.claude/agents/fukan-architect.md`**

Frontmatter (exact):

```yaml
---
name: fukan-architect
description: High-altitude design partner for fukan-modelled systems. Reasons through the fukan model only; reviews existing structure and explores improvements/expansions. Read-only at the tool level.
tools: Bash(fukan eval *|fukan status|fukan primer)
---
```

Body sections (in order):

1. **`# Fukan Architect`** — single-line statement of role.
2. **`## Stance`** — pure thinker; model-only; conversation is the artefact. Cite the model's view explicitly. Surface gaps as gaps (per spec §4).
3. **`## Fukan vision (condensed)`** — three altitudes + one-up reference rule; three boundary protocols (View / Signal / Call); spec→code as projection; project layer (projection inputs + constraints + idioms). Match the substance currently in `.claude/agents/spec.md` Part 1, condensed and rephrased for the design-thinking stance.
4. **`## The model surface — L0 / L1 / L2 toolbox`** — table summarising what each layer is for; when to drop to `q`; how to use `(drift)` / `(idioms)` / `(constraints)` / `(violations)` / `(neighborhood)` / `(vocabulary)` / `(get-primitive id)`. Reference AGENTS.md for the live catalog and `(help)` inside `fukan eval`.
5. **`## Operating principles`** — must-have phrasings:
   - "The model is the only surface."
   - "When a question can't be answered from `fukan eval`, surface that as a model gap rather than guessing."
   - "Cite the model's view explicitly; engage as a peer, not a reporter."
6. **`## Reference depth`** — agent cannot Read files; references `doc/VISION.md`, `doc/DESIGN.md`, `doc/MODEL.md`, `AGENTS.md` by path so the user can pull canon if needed. Use `fukan primer` for AGENTS.md content.

Target length: 90–130 lines.

- [ ] **Step 3: Draft `.claude/commands/fukan-design.md`**

Frontmatter (exact):

```yaml
---
name: fukan-design
description: High-altitude design conversation grounded in the fukan model.
context: fork
agent: fukan-architect
argument-hint: [topic-or-scope]
---
```

Body — workflow recipe per spec §6 `/fukan-design`:

- Intro line: "High-altitude design conversation. Argument is a path (consult that scope) or a free-text question (engage on that question)."
- **`## How to interpret `$ARGUMENTS`** — path detection rule: if argument resolves as a relative-or-absolute filesystem path, treat as scope; else treat as free-text question. Empty argument → invite the user to provide one.
- **`## Workflow`** — numbered list mirroring spec §6:
  1. Path argument → query the model for primitives in that scope using `(primitives :kind …)` filtered client-side by owner (or drop to L0 Datalog when scope-by-owner is the point) plus `(neighborhood …)`. Free-text → gather state via `(vocabulary)` / `(drift)` / `(violations)` / `(idioms)` as relevant.
  2. Engage conversationally — frame proposals, present tradeoffs, cite the model's view explicitly.
  3. Surface gaps as model-completeness questions; do not guess past the model.
  4. No file artefacts — the conversation is the output.

Target length: 25–45 lines.

- [ ] **Step 4: Inspect files**

```bash
ls .claude/agents/fukan-architect.md .claude/commands/fukan-design.md
wc -l .claude/agents/fukan-architect.md .claude/commands/fukan-design.md
```

Expected: both files exist; line counts in target ranges.

- [ ] **Step 5: Smoke-test (manual, user invokes)**

In the Claude Code session:

1. `/agents` → confirm `fukan-architect` appears.
2. `/commands` → confirm `/fukan-design` appears.
3. Invoke `/fukan-design src/fukan/projection` — architect should query primitives in that module, surface structural observations, and cite the model.
4. Invoke `/fukan-design how would we add a Python target language?` — architect should consult existing analyzer-pattern primitives, idioms, and surface any model gaps relevant to a new target.
5. Confirm the architect refuses (or surfaces inability) when asked to read a source file — tool restriction is the safety net, but the agent should also frame it as model-only.

If any step fails, refine the agent body or workflow and re-test.

- [ ] **Step 6: Commit**

```bash
jj st
```

Expected: `A .claude/agents/fukan-architect.md`, `A .claude/commands/fukan-design.md`, description matches Step 1.

---

## Task 2: fukan-reconciler agent + /fukan-audit command

Deliver the reconciler end-to-end via its audit workflow. Audit is the canonical "find disagreements + dispatch confident fixes" mode and exercises the compose-and-critique loop most fully.

**Files:**
- Create: `.claude/agents/fukan-reconciler.md`
- Create: `.claude/commands/fukan-audit.md`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(claude): add fukan-reconciler agent + /fukan-audit command"
jj st
```

- [ ] **Step 2: Draft `.claude/agents/fukan-reconciler.md`**

Frontmatter (exact):

```yaml
---
name: fukan-reconciler
description: Write-mode reshaper for fukan-modelled systems. Reads code/spec, queries the live model, writes changes directly or via dispatched allium:* agents, and verifies. Carries the compose-and-critique loop.
tools: Read, Edit, Write, Glob, Grep, Bash, Agent
---
```

Body sections (in order):

1. **`# Fukan Reconciler`** — single-line role statement.
2. **`## Stance`** — the doer. Reads, writes, dispatches, critiques. Effects change on system or model. Verification is non-negotiable.
3. **`## Fukan vision (condensed)`** — same substrate as architect (lift the same content). The agent files duplicate the vision intentionally so neither is contingent on the other.
4. **`## Operating rules`** — adapt from `.claude/agents/spec.md` Part 2:
   - **File ownership** — table (per spec §5): Entities / Values / Variants / Actors / Rules / Events / Surfaces / Contracts → `.allium`. `fn` / `exports:` / `subsystem` → `.boundary`.
   - **Open by default; `exports:` closes.**
   - **Authoring discipline** — spec is authoritative; language-agnostic for model/projection/view specs; underscore-to-kebab is mechanical.
5. **`## The compose-and-critique loop`** — full 9-step loop from spec §5 ("Receive task" through "Report"). Include the iteration ceiling: 2 round-trips with `allium:*` before falling back to direct Edit or reporting failure. Must include phrasings:
   - "Treat `allium:*` output as a proposal, not a result."
   - "Confident misalignment → fix directly via Edit."
   - "Structural misalignment → re-dispatch with refined direction."
6. **`## Verification discipline`** — `fukan eval '(refresh)'` after every write; re-query relevant slice (`(drift)`, `(violations)`, `(get-primitive …)`); iterate if the change didn't land. "A workflow that doesn't verify isn't done."
7. **`## Single-canonical-address discipline`** — code edits land at the address derived from the spec primitive; no second canonical address. One root-prefix knob; mechanical transliteration.
8. **`## Dispatching `allium:*`** — short table mapping the four workflows to the allium agent each invokes (or none for align-code). Note that the reconciler never delegates final authority — it critiques every proposal.

Target length: 140–200 lines.

- [ ] **Step 3: Draft `.claude/commands/fukan-audit.md`**

Frontmatter (exact):

```yaml
---
name: fukan-audit
description: Audit the system's integrity against the fukan model; apply confident fixes, report judgment calls.
context: fork
agent: fukan-reconciler
argument-hint: [scope]
---
```

Body — workflow recipe per spec §6 `/fukan-audit`:

- Intro: "Audit the system's integrity against the fukan model. `$ARGUMENTS` is the optional scope (file or directory); empty → project-wide."
- **`## Workflow`** — numbered list:
  1. Query `(vocabulary)`, `(violations)`, `(drift)` (filter the results client-side by scope; only `(violations :severity …)` and `(drift :projection-kind …)` accept built-in filters). Gap-detection queries: signal gaps (Events with no subscribing Rule), unfulfilled contract operations (Operations with no fulfilling party), idiom violations.
  2. Sweep code in scope for primitives that should exist in the model but don't (uncovered code).
  3. Per issue, classify:
     - **Confident fix** — dispatch the matching focused workflow on the affected scope: `/fukan-conform-spec` for spec issues, `/fukan-align-code` for projection drift, `/fukan-distill-spec` for uncovered code. (Implementation note: dispatch via the Agent tool with `subagent_type=fukan-reconciler` and a workflow-hint prompt; see *open items* in spec §9 for the alternative shape.)
     - **Judgment call** — report only.
  4. `fukan eval '(refresh)'`; re-query; iterate until stable or report-only items remain.
- **`## Report format`** — code block exactly as in spec §6:

```
## Applied
<file path or model address>
  - <one-line fix description>

## Reported
<file path or model address>
  Concern: <category>
  Issue: <one-line description>
  Proposal: <suggested action>
```

- Closing line: "If nothing is wrong in a file, don't list it. If nothing is wrong at all, say so."

Target length: 50–75 lines.

- [ ] **Step 4: Inspect files**

```bash
ls .claude/agents/fukan-reconciler.md .claude/commands/fukan-audit.md
wc -l .claude/agents/fukan-reconciler.md .claude/commands/fukan-audit.md
```

- [ ] **Step 5: Smoke-test**

1. `/agents` → confirm `fukan-reconciler` appears.
2. `/commands` → confirm `/fukan-audit` appears.
3. Confirm `bin/fukan status` reports a healthy daemon and loaded model (start with `clj -M:run --src src --analyzers clojure,allium,boundary --port 8080` if not).
4. Invoke `/fukan-audit src/fukan/projection` — audit should query the model, surface issues categorized, and either apply confident fixes (with refresh + verify) or report judgment calls in the structured format above.
5. Invoke `/fukan-audit` (no scope) — full-project audit; expect a longer report.
6. Inspect any applied changes via `jj diff`; confirm they are scoped, small, and aligned with the spec's Fukan-vision concerns.

If a confident-fix step misfires (e.g., wrong dispatch shape), refine the workflow body and re-test.

- [ ] **Step 6: Commit**

```bash
jj st
```

Expected: `A .claude/agents/fukan-reconciler.md`, `A .claude/commands/fukan-audit.md`, plus any applied audit fixes from the smoke test if you want them in the commit (otherwise revert them to keep the commit focused on the new files).

If the audit applied fixes, prefer: revert them with `jj restore src/...` so the commit covers only the new agent + command. Audit fixes can land in a follow-up.

---

## Task 3: /fukan-distill-spec command

Extends the existing reconciler. No new agent.

**Files:**
- Create: `.claude/commands/fukan-distill-spec.md`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(claude): add /fukan-distill-spec command"
```

- [ ] **Step 2: Draft `.claude/commands/fukan-distill-spec.md`**

Frontmatter (exact):

```yaml
---
name: fukan-distill-spec
description: Distill an Allium + Boundary spec from existing code, dispatching allium:distill and critiquing the result against the Fukan vision.
context: fork
agent: fukan-reconciler
argument-hint: <code-path>
---
```

Body — workflow recipe per spec §6 `/fukan-distill-spec`:

- Intro: "Distill spec from code. `$ARGUMENTS` is the required code path. If empty, refuse and ask the user."
- **`## Workflow`** — numbered list:
  1. Read target code; identify candidate primitives (modules, callables, types).
  2. Query the live model for existing context to avoid duplicating: `(neighborhood …)` on parent module, `(idioms)`, `(constraints)`.
  3. Dispatch `allium:distill` via the Agent tool with the code path + a brief on fukan conventions (altitude discipline; project's idioms; underscore-to-kebab; no behaviour for getters/lifecycle — those go in `.boundary fn`).
  4. **Critique the proposal** against Fukan vision (compose-and-critique loop, per agent system prompt):
     - Altitude split — does behaviour live in `.allium` and structure in `.boundary`?
     - Three-pillar partition — rule vs surface vs fn applied correctly?
     - Idioms — does the proposal use the project's primitive choices?
     - Projection-friendliness — would generated artifacts land at canonical addresses?
  5. Confident misalignment → fix directly via Edit. Structural misalignment → re-dispatch `allium:distill` with refined direction (ceiling: 2 round-trips).
  6. Write `.allium` / `.boundary` files at the module path.
  7. `fukan eval '(refresh)'`; verify the distilled spec produces `:valid` projections for the code under analysis. The L2 `(drift :projection-kind :clojure)` view returns only `:absent` projections — the new primitives should not appear there. For primitive-specific confirmation, drop to L1: `(relations :kind :relation/projects :validity :valid :from <id>)`.
- **`## Output`** — applied changes summary + ambiguity report (primitives the agent couldn't confidently classify).

Target length: 50–70 lines.

- [ ] **Step 3: Smoke-test**

1. Pick a partially-specced Clojure namespace as the target (e.g., `src/fukan/web/handler.clj` if currently lean on spec coverage; verify via `(drift)` in `fukan eval`).
2. Invoke `/fukan-distill-spec src/fukan/web/handler.clj`.
3. Confirm the reconciler:
   - Queries the model for existing context.
   - Dispatches `allium:distill` (visible in the Agent tool call).
   - Critiques the output before writing.
   - Writes `.allium` and/or `.boundary` files.
   - Refreshes and re-queries to verify.
4. Inspect written files; confirm altitude discipline and idiom conformance.
5. If the distillation misclassified anything (e.g., put a getter as a Rule), confirm the agent flags it in the ambiguity report.

- [ ] **Step 4: Commit**

```bash
jj st
```

Expected: `A .claude/commands/fukan-distill-spec.md`. Distilled spec files from the smoke test should land in a follow-up commit (or be reverted to keep this one focused on the command).

---

## Task 4: /fukan-conform-spec command

Extends the existing reconciler. This is the live-model-informed successor to `/structurize`.

**Files:**
- Create: `.claude/commands/fukan-conform-spec.md`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(claude): add /fukan-conform-spec command"
```

- [ ] **Step 2: Draft `.claude/commands/fukan-conform-spec.md`**

Frontmatter (exact):

```yaml
---
name: fukan-conform-spec
description: Conform existing Allium + Boundary specs to the Fukan way using live-model state (drift, idioms, constraints, violations).
context: fork
agent: fukan-reconciler
argument-hint: [scope]
---
```

Body — workflow recipe per spec §6 `/fukan-conform-spec`:

- Intro: "Conform specs in scope to the Fukan way. `$ARGUMENTS` is the optional scope; empty → project-wide."
- **`## Workflow`**:
  1. Read specs in scope (`*.allium`, `*.boundary`).
  2. Query `(drift)`, `(idioms)`, `(constraints)`, `(violations)` filtered to the scope.
  3. Diagnose Fukan-vision violations:
     - Mixed altitudes — behaviour-only `.allium` content that should split to `.boundary` (e.g., getters modelled as Rules).
     - Missing `.boundary` for closed modules.
     - Constraint violations (e.g., naming preferences, architectural laws).
     - Pre-fukan patterns — `external entity.provides` standing in for module API (migrate to `.boundary fn`).
     - Three-pillar mis-partitioning — rules that should be surfaces, surfaces re-declaring `fn` shapes, etc.
  4. Classify each divergence:
     - **Confident fix** → apply directly via Edit (e.g., extract `.boundary fn` from `.allium` Rule; fix identifier casing; migrate `external entity.provides` to `.boundary fn`).
     - **Probe allium:tend** → dispatch with refined direction when the change is structurally non-trivial (e.g., re-shaping a Contract).
  5. Critique any `allium:tend` output before keeping it.
  6. `fukan eval '(refresh)'`; verify no new violations; verify projection validity at least preserved.
- **`## Output`** — applied changes + judgment-call report (same structured format as `/fukan-audit`).

Target length: 55–75 lines.

- [ ] **Step 3: Smoke-test**

1. Pick a scope with known divergence — e.g., `src/fukan/web/views` if it has evolving Boundary coverage, or a stress-test introduction of a pre-fukan pattern.
2. Invoke `/fukan-conform-spec src/fukan/web/views`.
3. Confirm reconciler:
   - Queries model state for scope.
   - Applies small confident fixes via Edit (e.g., identifier casing, getter→`.boundary fn` migration).
   - Dispatches `allium:tend` for structural changes.
   - Critiques `allium:tend` output.
   - Refreshes + verifies.
4. Inspect with `jj diff`; confirm changes are minimal and aligned.

- [ ] **Step 4: Commit**

```bash
jj st
```

---

## Task 5: /fukan-align-code command

Extends the existing reconciler. No `allium:*` dispatch — pure code-side work.

**Files:**
- Create: `.claude/commands/fukan-align-code.md`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "feat(claude): add /fukan-align-code command"
```

- [ ] **Step 2: Draft `.claude/commands/fukan-align-code.md`**

Frontmatter (exact):

```yaml
---
name: fukan-align-code
description: Align code with the fukan model — find :absent projections and realise them at their canonical addresses.
context: fork
agent: fukan-reconciler
argument-hint: [scope]
---
```

Body — workflow recipe per spec §6 `/fukan-align-code`:

- Intro: "Align code with spec projections. `$ARGUMENTS` is the optional scope (filter for which absent projections to realise); empty → project-wide."
- **`## Workflow`**:
  1. Query `(drift :projection-kind :clojure)` (the L2 view returns `:absent` projections by definition) to find spec primitives without realisation. Filter the result client-side to the provided scope if any.
  2. For each absent projection, assemble the **Implementation Blueprint** via `fukan eval`:
     - If the Projector is implemented, call its current `fukan eval` entry point (discover via `(help)` inside `fukan eval`; if absent, the Projector hasn't landed yet — fall through to manual assembly).
     - Manual assembly: `(get-primitive id)` + `(idioms)` + the canonical-address rule (one root-prefix knob + mechanical transliteration). Document the manual-assembly path with an inline rationale in the generated code or the status report.
  3. Read existing code at the canonical address (if any).
  4. Write code that realises the spec; respect single-canonical-address discipline (no second address).
  5. `fukan eval '(refresh)'`; verify the projection flips to `:valid` via `(get-primitive id)` or `(drift)`.
- **`## Output`** — code changes + status report (which projections flipped to `:valid`; which couldn't be realised and why).
- **Note:** `allium:*` is not invoked from this workflow.

Target length: 50–70 lines.

- [ ] **Step 3: Smoke-test**

1. Find an absent projection: invoke `fukan eval '(drift :projection-kind :clojure)'` (the L2 view returns `:absent` projections by definition) and pick one with a clear canonical address.
2. Invoke `/fukan-align-code <scope-containing-that-projection>`.
3. Confirm reconciler:
   - Queries `(drift)` to find the absent projection.
   - Assembles a Blueprint (call the Projector if available, otherwise manual assembly with rationale).
   - Writes code at the canonical address.
   - Refreshes + re-queries; confirms the projection flipped to `:valid`.
4. Inspect generated code; confirm it follows project conventions (kebab-case, idiomatic Clojure, etc.).

- [ ] **Step 4: Commit**

```bash
jj st
```

---

## Task 6: Retire spec.md + structurize.md

After all five new commands are landed and smoke-tested, retire the predecessors.

**Files:**
- Delete: `.claude/agents/spec.md`
- Delete: `.claude/commands/structurize.md`
- Modify (if referenced): `README.md`, `CLAUDE.md`, any doc that mentions `spec` agent or `/structurize`

- [ ] **Step 1: Start a fresh changeset**

```bash
jj new -m "refactor(claude): retire spec agent and /structurize (superseded by fukan-architect + fukan-reconciler)"
```

- [ ] **Step 2: Sweep for references**

```bash
grep -rn "structurize\|agents/spec\|agent: spec\b" --include="*.md" --include="*.clj" /Users/yabmas/Code/fukan/
```

Expected: only matches inside `.claude/agents/spec.md` and `.claude/commands/structurize.md` themselves (plus possibly this plan and design spec — which are appropriate references).

If any other file references them as live tools (e.g., README, CLAUDE.md), update the wording.

- [ ] **Step 3: Delete the retired files**

```bash
rm .claude/agents/spec.md .claude/commands/structurize.md
jj st
```

Expected: `D .claude/agents/spec.md`, `D .claude/commands/structurize.md`.

- [ ] **Step 4: Smoke-test that retirements don't break anything**

1. `/agents` — confirm `spec` no longer appears.
2. `/commands` — confirm `/structurize` no longer appears.
3. Invoke `/fukan-audit src/fukan/projection` — confirm the new audit picks up the work the old `/structurize` did (and more, via live-model awareness).

- [ ] **Step 5: Commit**

```bash
jj st
```

Expected: only the two deletes (plus any reference-sweep edits).

---

## Task 7: Final end-to-end pass

A sanity sweep across the whole new surface in one session.

- [ ] **Step 1: Verify all five commands are listed**

In Claude Code:
- `/agents` shows `fukan-architect`, `fukan-reconciler` (and not `spec`).
- `/commands` shows `/fukan-design`, `/fukan-audit`, `/fukan-distill-spec`, `/fukan-conform-spec`, `/fukan-align-code` (and not `/structurize`).

- [ ] **Step 2: One real-world scenario per command**

1. `/fukan-design src/fukan/web/views` — get a model-grounded structural read.
2. `/fukan-audit` (no scope) — full audit; confirm it dispatches confident fixes to the focused workflows.
3. `/fukan-distill-spec <a sparsely-specced namespace>` — confirm end-to-end distillation with critique.
4. `/fukan-conform-spec <a scope with known evolution>` — confirm reshaping with `allium:tend` dispatch + critique.
5. `/fukan-align-code <scope with absent projections>` — confirm code lands at canonical address with verification.

- [ ] **Step 3: Note any rough edges**

Append observations to `doc/specs/2026-05-21-fukan-agent-workflows-design.md` §9 (Open items) if anything systemic surfaces. Small per-workflow improvements can land as direct refinements to the relevant command body in follow-up commits.

- [ ] **Step 4: No commit required for the pass itself**

This task is verification; the deliverables already landed in Tasks 1–6.
