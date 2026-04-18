---
name: structurize
description: Audit .allium and .boundary specs against spec agent guidelines; apply confident fixes, report judgment calls
context: fork
agent: spec
argument-hint: [path]
---

Audit Allium and Boundary specifications against the guidelines in your system prompt.

**Scope:** $ARGUMENTS

If the scope above is empty, audit all `.allium` and `.boundary` files under `src/`. Otherwise treat it as a file or directory path.

## What to check

For each spec file in scope, check against the guidelines in your system prompt:

1. **Rule intent and the litmus test.** Would deleting each rule cause something observably wrong over time? Flag rules that are really getters, setters, or request/response plumbing.
2. **Three-pillar partitioning.** Is each wall-facing construct the right one of `fn` / `surface` / `rule`? Look for: rules modelling shape, surfaces re-declaring signatures, `fn`s that should have a surface for domain guards.
3. **Contracts vs. `.boundary fn`.** Nominal vs. parametric. Contracts-of-one are ceremony; missing contracts for polymorphic dispatch are a real gap.
4. **Invariants and guarantees taxonomy.** Right construct chosen for each prose/expression assertion? Look for: prose `@invariant` placed at module level instead of inside a contract, expression-bearing conditions written as prose when they could be `invariant`s.
5. **What crosses the wall.** Values on `exposes` (good); entities on `exposes` (usually wrong — should be `exposes entity.field` through a surface).
6. **Legacy `external entity.provides` pattern.** If a module is using this to stand in for its own API, flag as a `.boundary fn` migration.
7. **Language-agnostic specs for model/projection/view.** No `defmulti`, `defmethod`, `protocol`, or other Clojure-specific terms; use "dispatch point", "handler", "polymorphic dispatch". Analyzer boundary specs are allowed to be concrete.
8. **Underscore-to-kebab mapping.** Spec identifiers use underscores (`schema_reference`), not kebab-case.

## How to act

Classify each divergence as:

- **High-confidence fix** — apply directly via Edit. Examples: dropping a rule that is clearly a getter and replacing with a `surface.exposes` line, fixing identifier casing, removing redundant constructs already covered by the taxonomy, migrating an internal module's `external entity.provides` to a `.boundary fn`, removing language-specific terms from language-agnostic specs.
- **Judgment call** — report only. Examples: rule vs. surface decisions that hinge on whether a downstream effect is domain-meaningful, contract vs. `.boundary fn` decisions for roles that currently have one fulfiller, entity-vs-value modelling choices.

## Report format

```
## Applied
<file path>
  - <one-line description of fix>
  - ...

## Reported
<file path>:<line>
  Guideline: <section name from system prompt>
  Issue: <one-line description>
  Proposal: <one-line suggestion>
```

If nothing is wrong in a file, don't list it. If nothing is wrong at all, say so.
