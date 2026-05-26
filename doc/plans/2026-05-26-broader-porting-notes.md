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
