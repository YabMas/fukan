---
name: fukan-audit
description: Audit the system's integrity against the fukan model; apply confident fixes, report judgment calls.
context: fork
agent: fukan-reconciler
argument-hint: [scope]
---

Audit the system's integrity against the fukan model. `$ARGUMENTS` is the optional scope (file or directory); empty → project-wide.

**Scope:** $ARGUMENTS

## Workflow

1. **Query the model.** Pull `(vocabulary)`, `(violations)`, and `(drift)` over the scope. Run gap-detection queries: signal gaps (Events with no subscribing Rule), unfulfilled contract operations (Operations with no fulfilling party), idiom violations. When a scope is given, filter each result client-side by owner — only `(violations :severity …)` and `(drift :projection-kind …)` accept built-in filters.
2. **Sweep code in scope.** Look for primitives that should exist in the model but don't — uncovered code the spec doesn't yet describe. Use `Glob` / `Grep` over the scope; cross-reference against `(primitives :kind …)` filtered client-side by owner.
3. **Classify each issue.**
   - **Confident fix** — dispatch the matching focused workflow on the affected scope: `/fukan-conform-spec` for spec issues, `/fukan-align-code` for projection drift, `/fukan-distill-spec` for uncovered code.
   - **Judgment call** — report only. Examples: rule-vs-surface decisions hinging on whether a downstream effect is domain-meaningful; contract-of-one ceremony where the right call depends on near-term plans; entity-vs-value modelling choices.
4. **Refresh and re-query.** Run `fukan eval '(refresh)'`; re-run the queries from step 1. Iterate until the only remaining items are report-only.

## What each query surfaces

- `(violations)` — broken project constraints (architectural laws, naming preferences); each carries a severity.
- `(drift)` — `:absent` and `:stale` projections; spec primitives whose code realisation is missing or out-of-date.
- Orphan triggers — events with no Rule subscribing; usually a missing Rule or a stale signal.
- Unfulfilled contracts — Contracts with no fulfilling Surface; either a missing implementation or an over-eager Contract.
- Idiom violations — addresses or identifiers that don't match the project's `(idioms)`.
- Uncovered code — implementation that lacks a corresponding spec primitive; candidate for distillation.

Concern categories used in the report: `altitude-split`, `projection-drift`, `uncovered-code`, `orphan-trigger`, `unfulfilled-contract`, `idiom-violation`, `constraint-violation`, `closure-gap`. Use the closest match; one-off concerns can take a free-form label.

## Report format

Concern categories used below — see *What each query surfaces* above for the canonical labels.

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

If nothing is wrong in a file, don't list it. If nothing is wrong at all, say so.
