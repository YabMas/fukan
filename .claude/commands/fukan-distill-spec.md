---
name: fukan-distill-spec
description: Distill an Allium + Boundary spec from existing code, dispatching allium:distill and critiquing the result against the Fukan vision.
context: fork
agent: fukan-reconciler
argument-hint: <code-path>
---

Distill spec from code. `$ARGUMENTS` is the required code path. If empty, refuse and ask the user for a path.

**Code path:** $ARGUMENTS

## Workflow

1. **Read target code.** Open the file or sweep the directory; identify candidate primitives — modules, callables, types. Note party-facing seams (what crosses the wall) versus internal helpers.
2. **Query the live model for existing context.** Avoid duplicating what's already modelled: `(neighborhood …)` on the parent module, `(idioms)` for naming and address-resolution knobs, `(constraints)` for project-layer rules the proposal must respect.
3. **Dispatch `allium:distill`.** Pass the code path and a brief on fukan conventions: altitude discipline (behaviour in `.allium`, structural shape in `.boundary`); the project's idioms; underscore-to-kebab is mechanical; no Rule for mundane callables (getters, lifecycle, render plumbing) — those declare only a `.boundary fn`.
4. **Critique the proposal.** Run the five checks from the compose-and-critique loop in the agent system prompt:
   - **Altitude split** — does behaviour live in `.allium` and structural shape in `.boundary`, with no leaks across?
   - **Three-pillar partition** — is each wall-facing construct the right one of `rule` / `surface` / `fn`?
   - **Idioms** — does the proposal use the project's primitive choices and naming preferences from `(idioms)`?
   - **Projection-friendliness** — would generated artifacts land at canonical addresses derivable from owner, name, and idioms?
   - **Single-canonical-address** — does each new primitive resolve to a unique canonical code address per project idioms?
5. **Reconcile.** Confident misalignment → fix directly via Edit. Structural misalignment → re-dispatch `allium:distill` with refined direction. Ceiling: 2 round-trips before falling back to direct Edit.
6. **Write the spec.** Land `.allium` / `.boundary` files at the module path. Co-locate both files in the module directory; behavioural content in `.allium`, structural surface (`fn`, `exports:`, `subsystem`) in `.boundary`. Address derived from owner + name + project idioms; one canonical location, no parallel copies.
7. **Refresh and verify.** Run `fukan eval '(refresh)'`. Confirm the new primitives no longer appear in `(drift :projection-kind :clojure)` (the L2 view returns only `:absent` projections by definition; their disappearance is the proof). For primitive-specific checks, drop to L1: `(relations :kind :relation/projects :validity :valid :from <id>)`. If the slice didn't move, iterate from step 4.

## Output

Applied changes summary — files written, primitives introduced, projections that flipped to `:valid`.

Ambiguity report — primitives the agent couldn't confidently classify (rule-vs-surface judgement calls, entity-vs-value modelling, contract-of-one ceremony). Format:

```
## Applied
<file path>
  - <one-line description of what was distilled>

## Ambiguous
<code address>
  Primitive: <candidate>
  Question: <what couldn't be decided>
  Proposal: <suggested resolution>
```

If nothing distilled cleanly, say so and surface the blocking ambiguity.
