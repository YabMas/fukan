---
name: fukan-conform-spec
description: Conform existing Allium + Boundary specs to the Fukan way using live-model state (drift, idioms, constraints, violations).
context: fork
agent: fukan-reconciler
argument-hint: [scope]
---

Conform specs in scope to the Fukan way. `$ARGUMENTS` is the optional scope; empty → project-wide.

**Scope:** $ARGUMENTS

## Workflow

1. **Read specs in scope.** Open the `*.allium` and `*.boundary` files under the scope (project-wide when empty). Note the current altitude split, `exports:` closure, and any pre-fukan patterns at a glance.
2. **Query the live model.** Pull `(drift)`, `(idioms)`, `(constraints)`, and `(violations)`. None accept `:owner`; filter the result against the scope client-side. Useful slicers: `(drift :projection-kind :clojure)`, `(violations :severity :error)`. The L2 `drift` view returns absent projections only; drop to L1 with `(relations :kind :relation/projects :validity :valid :from …)` when a specific projection's validity needs confirming.
3. **Diagnose Fukan-vision violations.** Triage what the queries surface and what the spec reading reveals:
   - **Mixed altitudes** — behaviour-only `.allium` content that should split to `.boundary` (e.g., a Rule modelling a getter; a top-level `@invariant` on a module that's really a guard on a single contract Operation).
   - **Missing `.boundary`** — closed modules without a `.boundary` file, or `exports:` clauses that should exist but don't.
   - **Constraint violations** — items from `(violations)`: naming preferences, architectural laws, project-layer rules.
   - **Pre-fukan patterns** — `external entity.provides` standing in for module API; ad-hoc shapes the project's idioms supersede.
   - **Three-pillar mis-partitioning** — wrong choice among `rule` / `surface` / `fn`. Examples: a Rule that's really a getter or render plumbing; a Surface that re-declares an `fn` signature without adding party context or guards; an `fn` with behavioural weight that should carry a Rule via `triggers:`.
4. **Classify each divergence.**
   - **Confident fix** — apply directly via Edit. Examples: extract a `.boundary fn` from an `.allium` Rule that is plumbing; fix identifier casing; migrate `external entity.provides` to a `.boundary fn`; add missing `exports:` for a closed module.
   - **Dispatch `allium:tend`** — call the agent with a focused brief (scope, target altitude, applicable idioms) when the change is structurally non-trivial — re-shaping a Contract, re-partitioning a Surface, splitting a Rule across altitudes.
5. **Critique each `allium:tend` proposal.** Run the compose-and-critique loop from the agent system prompt — altitude split, three-pillar partition, idiom conformance, projection-friendliness, single-canonical-address. Treat output as a proposal, not a result; iterate or fall back to direct Edit per the ceiling.
6. **Refresh and verify.** Run `fukan eval '(refresh)'`. Verify no new violations: `(violations)` returns nothing in the affected slice that wasn't there before. Verify projection validity is at least preserved: each primitive that was `:valid` before remains `:valid` — `(relations :kind :relation/projects :validity :valid :from <id>)` per touched primitive; `(drift)` should not list new `:absent` entries the change introduced.

## Output

Applied changes summary + judgment-call report. Same shape as `/fukan-audit`:

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

Concern categories: `altitude-split`, `closure-gap`, `constraint-violation`, `pre-fukan-pattern`, `three-pillar-partition`, `idiom-violation`. Use the closest match; one-off concerns can take a free-form label.

If nothing diverged in a file, don't list it. If nothing diverged at all, say so.
