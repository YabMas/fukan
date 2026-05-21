# Fukan Agent Workflows — Design

**Status:** Design spec for the project-level agent surface that wraps `bin/fukan` for opinionated, fukan-aware workflows. Supersedes `.claude/agents/spec.md` and `.claude/commands/structurize.md`.

**Reading order:** This spec; then [AGENTS.md](../../AGENTS.md) for the live `fukan eval` primer; then [VISION.md](../VISION.md), [DESIGN.md](../DESIGN.md), [MODEL.md](../MODEL.md) for the substrate.

---

## 1. Goals

- Opinionated, fukan-aware workflows for four core activities: high-altitude design reasoning, integrity audit, distilling specs from code, conforming specs to the Fukan way, and aligning code to spec projections.
- Lean on `allium:*` (`tend`, `distill`, `propagate`, …) as the trusted substrate for Allium mechanics; layer Fukan's expanded vision — altitudes, projection validity, project layer, single-canonical-address — on top with **compose-and-critique**, not blind delegation.
- Mirror the slash-command + subagent pattern proven by the existing `spec`+`structurize` pair.

### Non-goals

- Distribution to non-fukan-on-fukan projects. Phase 1 ships in this repo's `.claude/`; cross-project distribution is deferred.
- Replacing `bin/fukan` or the underlying `fukan eval` surface.
- Replacing `allium:*`; they remain the substrate.

---

## 2. Context

### What exists

- **`bin/fukan` CLI:** `status`, `eval`, `primer`, `init`. Hits `/agent/eval` on the daemon.
- **`AGENTS.md`:** live-catalog primer for the L0 / L1 / L2 layering of `fukan.agent.api`.
- **`allium:*` family** (user-level): `tend`, `weed`, `elicit`, `distill`, `propagate`. Generic Allium tooling, no fukan model awareness.
- **`.claude/agents/spec.md`** + **`.claude/commands/structurize.md`** (project-level): spec audit pair. **Retired by this design.**

### What's missing

- No agent or command currently consults the live fukan model when reasoning about spec or code.
- No mechanic for translating model state (`drift`, `idioms`, `constraints`, `violations`) into actionable workflows.
- No write-side workflow that critiques `allium:*` output against the Fukan vision.

### What this design supersedes

| Retired | Replaced by |
|---------|-------------|
| `.claude/agents/spec.md` | `fukan-architect` + `fukan-reconciler` (vision shared; operating rules diverge by stance) |
| `.claude/commands/structurize.md` | `/fukan-audit` (live-model-informed; more capable) |

---

## 3. Architecture

Two subagents, five slash commands.

```
.claude/agents/
  fukan-architect.md       # design-mode thinker; tools airtight to bin/fukan
  fukan-reconciler.md      # write-side reshaper; audit + distill + conform + align

.claude/commands/
  fukan-design.md          # → fukan-architect
  fukan-audit.md           # → fukan-reconciler
  fukan-distill-spec.md    # → fukan-reconciler
  fukan-conform-spec.md    # → fukan-reconciler
  fukan-align-code.md      # → fukan-reconciler
```

### Stance split

| Agent | Stance | Tools |
|-------|--------|-------|
| `fukan-architect` | Pure thinker; reasons only through the fukan model. No file I/O. | `Bash(fukan eval *\|fukan status\|fukan primer)` |
| `fukan-reconciler` | Doer; reads, writes, dispatches, critiques. Effects change on system or model. | `Read, Edit, Write, Glob, Grep, Bash, Agent` |

The architect's tool restriction is enforced at the Claude Code tool-permission layer, not as system-prompt discipline. The architect cannot `cat` source files, `grep` code, or `Read` anything. All knowledge of the system flows through `bin/fukan`.

This is a deliberate constraint with two effects:

1. **Forces design conversations to ground in the model surface.** The architect can't fall back to reading source; if a question can't be answered from the model, that's the answer.
2. **Tests model completeness.** When the architect bumps into a gap, that's a real model gap — the reconciler's audit should close it.

---

## 4. Agent: `fukan-architect`

### Role

High-altitude design thinker. Engages the user about the system's design — reviewing structure, exploring improvements, considering new use-cases — constrained to the fukan model surface. Doesn't write proposals to disk; the conversation is the artefact.

### Tools

```yaml
tools: Bash(fukan eval *|fukan status|fukan primer)
```

No `Read`, `Glob`, `Grep`, `Edit`, `Write`, `Agent`.

### System prompt carries

- **Fukan vision (condensed):** spec-graph framing; the three altitudes + one-up reference rule; the three boundary protocols (View / Signal / Call); spec→code as projection; the project layer (projection inputs + constraints).
- **The L0 / L1 / L2 toolbox:** what each layer is for; when to drop to `q`; how to read `(drift)` / `(idioms)` / `(constraints)` / `(violations)` / `(neighborhood)` / `(vocabulary)`.
- **Operating principle:** the model is the only surface; if a question can't be answered from `fukan eval`, surface that as a model gap rather than guessing.
- **Stance discipline:** cite the model's view explicitly; don't propose changes unless the user invites design exploration; engage as a peer, not a reporter.

The agent references `doc/VISION.md`, `doc/DESIGN.md`, `doc/MODEL.md`, `AGENTS.md` by path when canonical depth matters — but cannot Read them. The system prompt carries enough vision to operate; deep canon lives in the user's hands.

---

## 5. Agent: `fukan-reconciler`

### Role

Write-mode operative. Reads code and spec, queries the live model, writes changes (directly or via dispatched `allium:*`), and verifies via `fukan eval` that the change lands. Five workflows: audit, distill, conform, align — distinguished by which slash command invokes it.

### Tools

```yaml
tools: Read, Edit, Write, Glob, Grep, Bash, Agent
```

`Agent` is used to dispatch `allium:*` family.

### System prompt carries

- **Fukan vision (condensed):** same substrate as the architect.
- **Operating rules (full):**
  - **File ownership.** Entities / Values / Variants / Actors / Rules / Events / Surfaces / Contracts go in `.allium`. `fn` / `exports:` / `subsystem` go in `.boundary`.
  - **Open by default; `exports:` closes.** A module without `.boundary`, or without `exports:`, is open.
  - **Authoring discipline.** Spec is authoritative. Language-agnostic for model/projection/view specs. Underscore-to-kebab is mechanical.
- **The compose-and-critique loop** (detailed below).
- **Verification discipline.** Every write is followed by `fukan eval '(refresh)'` and a re-query.
- **Single-canonical-address discipline** on the code side.

### Compose-and-critique loop

```
1. Parse task (from command body).
2. Read necessary spec/code; query live model for context.
3. Plan change.
4. Dispatch allium:* when writing .allium; edit directly otherwise.
5. Critique result against Fukan vision (altitude split, three-pillar
   partition, idioms, projection-friendliness, single-canonical-address).
6. Confident misalignment → fix directly via Edit.
   Structural misalignment → re-dispatch allium:* with refined direction.
   (Iteration ceiling: 2 round-trips, then fall back to direct Edit or report.)
7. fukan eval '(refresh)'.
8. Re-query model; verify intended change is reflected — no new violations,
   projections in expected state.
9. Report.
```

The reconciler does not pass `allium:*` output through unchanged. Every proposal is reviewed against the Fukan vision; the reconciler is the arbiter.

---

## 6. Commands

Each command is a slash-command file under `.claude/commands/`. The body carries the workflow recipe; the agent provides the stance and tools.

### `/fukan-design [topic-or-scope]`

**Agent:** `fukan-architect`.

**Argument:** path-shaped → consult that scope; free-text → design question.

**Workflow:**

1. Path argument → query the model for primitives in that scope (`(primitives :kind …)` filtered client-side by owner, or L0 Datalog when scope-by-owner is the point; plus `(neighborhood …)`).
2. Free-text argument → gather relevant model state (`(vocabulary)`, `(drift)`, `(violations)`, `(idioms)`).
3. Engage conversationally — frame proposals, present tradeoffs, cite the model's view explicitly.
4. Surface gaps as model-completeness questions rather than guessing.

**Output:** terminal conversation. No file artefacts.

### `/fukan-audit [scope]`

**Agent:** `fukan-reconciler`.

**Argument:** optional path; defaults to project-wide.

**Workflow:**

1. Query `(vocabulary)`, `(violations)`, `(drift)`, gap-detection queries (orphan triggers, unfulfilled contracts).
2. Sweep code in scope for primitives that should exist in the model but don't (uncovered code).
3. Per issue:
   - **Confident fix** → dispatch the matching focused workflow on the affected scope (`/fukan-conform-spec` for spec issues; `/fukan-align-code` for projection drift; `/fukan-distill-spec` for uncovered code).
   - **Judgment call** → report.
4. Refresh; re-query; iterate until stable.

**Output:** structured report —

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

### `/fukan-distill-spec <code-path>`

**Agent:** `fukan-reconciler`.

**Argument:** required code path (file or directory).

**Workflow:**

1. Read target code; identify candidate primitives.
2. Query live model for existing context (avoid duplicating).
3. Dispatch `allium:distill` with the code path + a brief on fukan conventions (altitude discipline; project's idioms).
4. Critique the proposal — altitude split, three-pillar partition, idiom conformance, projection-friendliness.
5. Fix directly or re-dispatch.
6. Write `.allium` / `.boundary` files.
7. Refresh; verify the distilled spec produces `:valid` projections for the code under analysis.

**Output:** written spec files + status report (what was distilled; what's still ambiguous).

### `/fukan-conform-spec [scope]`

**Agent:** `fukan-reconciler`.

**Argument:** optional path; defaults to project-wide.

**Workflow:**

1. Read specs in scope.
2. Query `(drift)`, `(idioms)`, `(constraints)`, `(violations)`.
3. Diagnose Fukan-vision violations:
   - Mixed altitudes — behaviour-only `.allium` content that should split to `.boundary`; Rule that should be `.boundary fn`.
   - Missing `.boundary` for closed modules.
   - Constraint violations.
   - Pre-fukan patterns (e.g. `external entity.provides` standing in for module API).
4. Classify confident-fix vs probe-allium-agent.
5. Apply confident fixes via Edit; dispatch `allium:tend` with refined direction for the rest.
6. Refresh; verify no new violations; projection validity at least preserved.

**Output:** applied changes + judgment-call report.

### `/fukan-align-code [scope]`

**Agent:** `fukan-reconciler`.

**Argument:** optional path; defaults to project-wide.

**Workflow:**

1. Query `(drift :projection-kind :clojure)` (the L2 view returns `:absent` projections by definition) to find spec primitives without realisation.
2. For each absent projection, assemble the Implementation Blueprint via `fukan eval` (Projector call when implemented; manual assembly from primitive + idioms + canonical address otherwise — see *open items*).
3. Read existing code at the canonical address.
4. Write code that realises the spec; respect single-canonical-address discipline.
5. Refresh; verify projection flips to `:valid`.

**Output:** code changes + status report.

`allium:*` is not invoked from this workflow — pure code-side work.

---

## 7. Verification discipline

Every write-side workflow ends with:

1. `fukan eval '(refresh)'` to rebuild the model.
2. Re-query the relevant slice (`(drift)`, `(violations)`, `(get-primitive …)`).
3. Confirm the intended change is reflected; if not, iterate.

A workflow that doesn't verify isn't done. This applies to `/fukan-audit`, `/fukan-distill-spec`, `/fukan-conform-spec`, and `/fukan-align-code`. Not applicable to `/fukan-design` (no writes).

---

## 8. Distribution & sequencing

**Phase 1 — fukan-on-fukan.** Ship the agents and commands in this repo's `.claude/`. Retire `spec.md` and `structurize.md` in the same change.

**Phase 2 — distribute to target projects.** Extend `bin/fukan init` (or add `install-agents`) to mirror the agents and commands into a target project's `.claude/`. Defer until phase 1 is validated by use.

**Phase 3 — refinement.** After lived experience: revisit the architect's tool restriction (does it ever genuinely need to read a doc?), the critique-loop iteration ceiling, and the boundary between `/fukan-audit` and the focused workflows.

---

## 9. Open items

- **Audit dispatch shape.** `/fukan-audit` confident-fixes by dispatching the focused slash commands on a narrowed scope. Whether this round-trips through the slash-command runtime or invokes the same reconciler with a workflow-mode hint is an implementation detail — design defaults to dispatching slash commands for composability and a single source of workflow truth.
- **Implementation Blueprint surface.** `/fukan-align-code` relies on Blueprint assembly. If the Projector isn't ready yet, the fallback is manual assembly from primitive metadata + project idioms + canonical-address rule — slower but unblocking.
- **Critique-loop iteration ceiling.** Defaults to 2 round-trips with `allium:*` before falling back to direct Edit or reporting failure. Tune with lived experience.
- **Architect design-output persistence.** The architect doesn't write to disk. If a design conversation produces a proposal worth keeping, the user copies it themselves; a future capture command could formalise this.
- **Compose-and-critique vs propose-and-handoff.** Current design has the reconciler review `allium:*` output and either fix directly or re-dispatch. An alternative — propose to the user and let them decide — is simpler but slower. Compose-and-critique chosen for autonomy; revisit if the critique loop misfires.
