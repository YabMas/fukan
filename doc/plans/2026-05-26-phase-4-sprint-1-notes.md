# Phase 4 Sprint 1 — Substrate hardening notes

**Date:** 2026-05-26
**Status:** Complete

## What landed

Four tasks across four commits:

```
T3 (shape queryability): feat(canvas/substrate): extract input/output and field type names to first-class queryable datoms
T2 (stable id contract): feat(canvas/identity): stable id contract with alias mechanism
T1 (module-qualified resolution): fix(canvas/projection): module-qualified cross-module reference resolution
```

Plus this notes doc.

**Final test state:** 238 canvas suite tests / 785 assertions; 609 full project tests / 1664 assertions. All green.

**Functional verification (REPL):**
- `build-model` produces 623 primitives + 575 edges + 472 artifacts (matches Phase 3 baseline).
- `[?a :affordance/output-types :Phase4Result]` returns `[run, gate_g2]`.
- 31 affordances queryable as taking `:model/Model`.
- `identity/resolve-id db "infra.server/start"` resolves to `"infra.server/start_server"`.

## Two surprises worth tracking

### Surprise 1: 31 intra-module duplicate names in canvas ports

Task 1's stricter intra-module duplicate detection surfaced **31 cases where a single canvas port declares two entities with the same name**. All are in the validation subsystem: each `rules_4X.clj` and `validation/violation.clj` declares both a `(rule "X" ...)` AND an `(invariant "X" ...)` with identical names. Examples:

- `validation.rules-4a/AtMostOneCompositeParent` — both rule and invariant
- `validation.violation/ViolationShape` — both rule and invariant
- `validation.rules-4c/AttachReturnsRequiresTriggers` — both rule and invariant
- ... 28 more

**These are genuinely two distinct entities** at the substrate level — a `:canvas/rule` Affordance with a `(when …)` clause AND a `:canvas/invariant` Affordance with a `(holds-that …)` body. They share a name but have different roles and formal expressions.

**Current behavior:** Task 1 ships `build-canvas-db` with a *warn-not-throw* variant for intra-module duplicates. The strict throwing variant (`detect-intra-module-duplicates-for-test`) is implemented and tested with synthetic data but not used in production pipeline; throwing would block startup.

**Real fix (deferred to Phase 4.5 or Phase 5):** Decide whether the substrate should allow same-name entities within a module when they have different roles. Three options:

1. **Disallow.** Rename the colliding rules/invariants to differentiate (e.g. `RuleX`/`InvariantX` or `XRule`/`XInvariant`). 31 renames across the validation subsystem.
2. **Allow + scope resolution by name+role.** Reference resolution becomes `(module, name, role)` instead of `(module, name)`. Substrate stays the same; resolution gets richer.
3. **Promote the convention.** Allium had the same pattern (same name for related rule + invariant). Document it explicitly as a canvas convention. Resolution disambiguates via context (which is what currently happens silently).

Option 1 is simplest; Option 2 is most principled; Option 3 is most permissive. **Recommendation deferred** — this is a design conversation worth having explicitly rather than picking now under sprint pressure.

### Surprise 2: cross-module ref resolution uses segment-matching, not full-name match

The original Task 1 spec said: namespace of `:model/Model` should match a Module with `:entity/name "model"`. But canvas modules in fukan are named with dot-paths like `"infra.server"`, `"vocabulary.allium.analyzer"`, `"model.spec"`, etc. References use SHORT namespaces (`:model/Model`, `:ast/ConstraintRule`, `:registry/Registry`).

The Task 1 agent implemented **segment-matching resolution**: `:model/Model` matches any Module whose dot-separated name contains `"model"` as a segment. So `:model/Model` finds `Model` in `model.spec`. `:ast/ConstraintRule` finds it in `constraint.ast`. `:registry/Registry` finds it in `project_layer.registry`. Works for all 25 distinct reference targets in the current corpus.

**Limitation:** in a project where two modules share a segment (e.g. `accounts.users` and `users.accounts`), `:users/...` would match both — ambiguous. The agent's algorithm picks the first match (back to a documented form of first-match-wins, just scoped narrower).

**Real fix (deferred):** Either teach the canvas convention that reference keywords MUST use full module names (`:model.spec/Model` rather than `:model/Model`) — requires backfilling all canvas content — OR keep segment-matching and accept ambiguity in projects where modules share segments.

For fukan-itself, segment-matching is fine. For Phase 4 Sprint 3's stress-test against a non-fukan paradigm, we'll see if the heuristic holds up.

## What's ready for Sprint 2

Task 1 closed Phase 3 Q3 (module-qualified resolution). Task 2 closed Phase 3 Q5 (stable id contract). Task 3 closed the broader shape-queryability gap that Q5's design conversation surfaced.

The substrate now supports the queries Sprint 2's `triggers:`/`returns:` work needs to be able to express. Plus the alias mechanism is in place if Sprint 2 produces renames worth aliasing.

Sprint 2 begins with two settled decisions: triggers/returns as symmetric first-class structural elements (Relation + attribute respectively); the `tuple-of` combinator decision pending evidence from a grep of canvas content.
