# Sprint 2 — Broader Porting Notes

Date: 2026-05-26
Scope: infra, project_layer, libs (six modules)

## Per-module status

### infra/model — CLEAN
Lifts: `invariant` (3), `function` (2), `getter` (2).
Shape grammar: cross-module `:model/Model` used throughout. `get_model` and
`get_src` map naturally to `getter` (zero-arg optional return). `refresh_model`
is `(gives (optional :model/Model))` — correctly expressed via shape grammar.
TODO comments: none. No escalations.

### project_layer/defaults — CLEAN
Lifts: `invariant` (1), `function` (1).
Shape grammar: cross-module `:registry/Registry` return type.
TODO comments: none. No escalations.

### project_layer/registry — ONE GAP
Lifts: `record` (3), `invariant` (2), `function` (4), `exports` (1).
Shape grammar: `IdiomRoute` all-optional String fields →
`(optional :String)` — clean. `Registry.idioms: List<IdiomEntry>` →
`(list-of :IdiomEntry)` — clean.

**GAP: `map-of` combinator wanted.**
`Registry.type_overrides` is `Map<String, Any>` in the source spec. No
`map-of` combinator exists. Approximated as `:Map` with inline TODO comment:
`(field type_overrides :Map)` with `;; TODO: map-of :String :Any`.
This is the only map-of gap encountered across all six ports.

### libs/coordinate — CLEAN
Lifts: `invariant` (4), `function` (1).
Shape grammar: pure String → String function, no complex shapes needed.
No value types; no `exports:` clause in source boundary.
TODO comments: none. No escalations.

### libs/allium/parser — CLEAN
Lifts: `record` (2), `invariant` (3), `function` (2).
Shape grammar: `ParsedAllium.declarations: List<Any>` → `(list-of :Any)` — clean.
`ParseFailure.reason: Any` — bare `:Any` — clean.
Return types: `parse_allium` and `parse_file` return `ParsedAllium | ParseFailure`
→ `(sum-of :ParsedAllium :ParseFailure)` — shape grammar handles this cleanly.
TODO comments: none. No escalations.

### libs/boundary/parser — CLEAN
Structurally parallel to libs/allium/parser. Identical lift pattern.
Lifts: `record` (2), `invariant` (3), `function` (2).
Shape grammar: `ParsedBoundary.declarations: List<Any>` → `(list-of :Any)`.
`boundary_version` is `:Integer` (not `:String` — boundary grammar uses integer).
Return types: `(sum-of :ParsedBoundary :ParseFailure)` — clean.
TODO comments: none. No escalations.

## Gaps to escalate

1. **`map-of` combinator** — `Registry.type_overrides: Map<String, Any>` cannot
   be expressed with current shape grammar. Only one instance across this sprint.
   Recommend adding `(map-of :K :V)` to `shape/parse` and `construction`. All
   other shapes were expressible with existing combinators.

## TODO comments left in port files

- `canvas/project_layer/registry.clj` line with `(field type_overrides :Map)`:
  has `;; TODO: map-of :String :Any — no map-of combinator exists yet.`

No `rule` lift TODO comments needed in this sprint: none of the six modules
contained `rule` declarations.

## Test counts

| State | Tests | Assertions |
|---|---|---|
| Before Sprint 2 | 67 | 119 |
| After Sprint 2  | 79 | 172 |
| Delta           | +12 | +53 |

## jj log

```
feat(canvas): port libs/boundary/parser spec   kxtkmnnw fcc46b49
feat(canvas): port libs/allium/parser spec     pxouupuo 6ad89f32
feat(canvas): port libs/coordinate spec        mxmrpxrz 7ac0aef6
feat(canvas): port project_layer/registry spec wzrnzmlu d6bc88bf
feat(canvas): port project_layer/defaults spec ysxtpuuu fd3bc351
feat(canvas): port infra/model spec            tnuplomz 5335dc76
```

---

# Sprint 2 Addendum — validation/ subsystem (8 modules)

Date: 2026-05-26
Scope: validation/rules_4a through rules_4g + validation/violation

## Per-module status

### validation/rules_4a — CLEAN
Lifts: `checker` (1), `invariant` (6).
All six structural invariants from the allium spec captured. The `checker` lift
bakes in `(Model) -> [Violation]` — one-liner per sub-phase entry point.
TODO comments: 5 rule declarations (deferred, no rule lift).
No escalations.

### validation/rules_4b — CLEAN
Lifts: `checker` (1), `invariant` (5).
Four event invariants + purity invariant. Structurally identical to 4a.
TODO comments: 4 rule declarations.
No escalations.

### validation/rules_4c — CLEAN
Lifts: `checker` (1), `invariant` (6).
Five binding invariants + purity. The `SignatureMatchVerifiable` invariant
captures the nuanced "uncertain at current fidelity" language.
TODO comments: 5 rule declarations.
No escalations.

### validation/rules_4d — CLEAN
Lifts: `checker` (1), `invariant` (4).
Three module-visibility invariants + purity.
TODO comments: 3 rule declarations.
No escalations.

### validation/rules_4e — CLEAN
Lifts: `checker` (1), `invariant` (3).
Two subsystem-visibility invariants + purity. The spec scope is tighter than
4a-4d; only 3 invariants total.
TODO comments: 2 rule declarations.
No escalations.

### validation/rules_4f — CLEAN
Lifts: `checker` (1), `invariant` (5).
Four closure invariants + purity. `ClosureScopeIsMVP` is explicitly scoped to
current coverage — captured faithfully.
TODO comments: 4 rule declarations.
No escalations.

### validation/rules_4g — CLEAN
Lifts: `checker` (1), `invariant` (5).
Four cross-module reference invariants + purity. Two shared-concept invariants
(`ContractsAreAlwaysVisible`, `ExternalEntityCountsAsVisible`) also appear in
4f — each module declares them independently, which is correct.
TODO comments: 4 rule declarations.
No escalations.

### validation/violation — ONE GAP (same as registry)
Lifts: `function` (5), `invariant` (4), `exports` (1).
Shape grammar: `(list-of :agent/Violation)` for filter functions — clean.
Cross-module `:agent/Violation` references handled by namespaced keyword.

**GAP: `map-of` combinator wanted (same as registry).**
`make_violation` takes `Map<String, Value>` — no `map-of` combinator exists.
Approximated as `:Map` with inline comment. This is the second occurrence of
this gap across Sprint 2.
No other escalations.

## Structural observations

1. **rules_4a–4g are maximally mechanical.** Every file is the same pattern:
   N invariants + 1 `checker`. The `checker` lift was purpose-built for this
   pattern and works perfectly. Zero friction.

2. **`rule` declarations are the only structural concept without a lift.**
   All 7 sub-phase files have between 3–5 rule declarations each (35 total
   across 4a–4g). All left as TODO comments. The inline comment format is
   consistent and captures: name, structural intent, violation kind + severity.

3. **`violation` is the one module with substantive function signatures.**
   The predicates and filters are all expressible in shape grammar. Only
   `make_violation`'s `Map<String, Value>` parameter hits the `map-of` gap.

4. **No `triggers:` / `returns:` annotations in these modules.** Unlike
   phase4.clj, none of the sub-phase boundary files use attach-form syntax —
   all functions are plain signature declarations.

## Gaps to escalate (Sprint 2 cumulative)

1. **`map-of` combinator** — second occurrence (`violation.make_violation`).
   Both instances are `Map<String, Value>` or `Map<String, Any>`. The gap is
   real but low-priority; approximating as `:Map` is lossless for structural
   purposes.
2. **`rule` lift** — 35 rule declarations across 7 files, all deferred.

## Test counts

| State | Tests | Assertions |
|---|---|---|
| Before Sprint 2 (all) | 67 | 119 |
| After Sprint 2 infra+proj+libs | 79 | 172 |
| After Sprint 2 validation/ | 95 | 229 |
| Delta (validation sprint) | +16 | +57 |

## jj log (Sprint 2 validation)

```
feat(canvas): port validation/rules_4a–4g + violation specs   vztqzvom 4fd5cc39
```
