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

---

# Sprint 2 Addendum — constraint/ subsystem (7 modules)

Date: 2026-05-26
Scope: constraint/ast, builtins, derivations, derivations_extra, phase5, sort, well_known

## Per-module status

### constraint/ast — CLEAN
Lifts: `value` (6), `invariant` (2), `function` (9), `exports` (1).
Shape grammar: intra-module cross-refs use `:ast/Term`, `:ast/ConstraintAtom`, etc.
Bool-returning `is_var` / `is_constant` are plain `function` with `(gives :Bool)`.
`vars_in_term` → `(set-of :ast/Term)`, `make_aggregation` body →
`(list-of :ast/ConstraintAtom)` — shape grammar handles all.
All six value types are opaque (PlainData invariant); no field declarations.
TODO comments: none. No escalations.

### constraint/builtins — CLEAN
Lifts: `invariant` (1), `function` (4).
`BuiltinCatalogue` invariant names the closed set; no predicate-catalog lift.
Bool-returning functions expressed as plain `function` with `(gives :Bool)`.
No `exports:` in source boundary.
TODO comments: none. No escalations.

### constraint/derivations — CLEAN
Lifts: `value` (1), `invariant` (3), `function` (1), `exports` (1).
`EDB` is opaque. `model_to_edb` takes `:model/Model` — cross-module ref auto-emits Relation.
`gives :derivations/EDB` — self-module namespaced return ref works correctly.
TODO comments: none. No escalations.

### constraint/derivations_extra — CLEAN
Lifts: `invariant` (3), `function` (1).
`depends_on_rules` is nullary — `(takes [])` — returns `(list-of :ast/ConstraintRule)`.
No exports in source boundary.
TODO comments: none. No escalations.

### constraint/phase5 — ONE TODO (deferred rule lift)
Lifts: `invariant` (6), `function` (1).
`run` has `triggers: RunPhase5` / `returns: post.model` — no anchor syntax in function lift;
noted in docstring. Cross-module `:model/Model` ref clean.
TODO comments: 1 rule declaration (RunPhase5 — deferred).
No escalations.

### constraint/sort — CLEAN
Lifts: `invariant` (1), `function` (4).
`SortGuardCatalogue` invariant names the closed set; no sort-guard-catalog lift.
Bool-returning guards expressed as plain `function` with `(gives :Bool)`.
No exports in source boundary.
TODO comments: none. No escalations.

### constraint/well-known — CLEAN
Lifts: `invariant` (7), `function` (5).
Nullary factories (`signal_gap`, `no_circular_refs`, `external_must_have_wrapper`)
use `(takes [])`. Parameterised factories take `:String`/`:Keyword` args.
All five functions return `:model/PredicateRegistration` — cross-module ref clean.
No exports in source boundary.
TODO comments: none. No escalations.

## Structural observations

1. **AST module is the richest single file in this sprint.** Six opaque value types
   + 9 functions + 2 invariants. All expressible: shape grammar handles intra-module
   namespaced refs (`:ast/Term` etc.) cleanly.

2. **`map-of` gap: NOT triggered.** Zero instances in constraint/. The constraint
   subsystem works entirely with typed scalars, sets, and lists — no map-shaped
   fields. Sprint 2 cumulative `map-of` count remains 2 (registry + violation).

3. **`rule` declarations.** Only 1 rule across all 7 constraint files (RunPhase5 in
   phase5.allium). Left as TODO comment. The constraint subsystem is
   behaviour-heavy via invariants, not rule-heavy.

4. **Bool-returning predicate-shaped functions are pervasive and clean.** `ast`,
   `builtins`, and `sort` all have multiple Bool-returning functions. The
   plain-`function`-with-`(gives :Bool)` decision from Sprint 2a is validated at
   scale — no friction, no ambiguity.

5. **Nullary functions (`(takes [])`) work cleanly** for factory functions and
   rule-constructors like `depends_on_rules`, `signal_gap`, `no_circular_refs`,
   `external_must_have_wrapper`.

## Gaps to escalate (Sprint 2 cumulative, constraint addendum)

No new gaps. Existing gaps from prior sprint sessions unchanged:
1. **`map-of` combinator** — 2 instances total (registry, violation). Constraint adds 0.
2. **`rule` lift** — 36 total across all ports (35 validation + 1 constraint).

## Test counts

| State | Tests | Assertions |
|---|---|---|
| Before Sprint 2 (all) | 67 | 119 |
| After Sprint 2 infra+proj+libs | 79 | 172 |
| After Sprint 2 validation/ | 95 | 229 |
| After Sprint 2 constraint/ | 109 | 301 |
| Delta (constraint sprint) | +14 | +72 |

## jj log (Sprint 2 constraint)

```
feat(canvas): port constraint/well-known spec      0a4c63b7
feat(canvas): port constraint/sort spec            062c24ce
feat(canvas): port constraint/phase5 spec          323208fc
feat(canvas): port constraint/derivations-extra spec  116971d1
feat(canvas): port constraint/derivations spec     4e0e27f7
feat(canvas): port constraint/builtins spec        85196db3
feat(canvas): port constraint/ast spec             480266a2
```

---

# Sprint 2 Addendum — vocabulary/ subsystem (8 modules)

Date: 2026-05-26
Scope: vocabulary/allium/{effect_canonicalise,expression,pipeline,renderers,tags},
       vocabulary/boundary/{analyzer,pipeline,tags}

## Per-module status

### vocabulary/allium/effect_canonicalise — CLEAN
Lifts: `invariant` (5).
Shape grammar: no value types, no function signatures. Pure invariant file.
TODO comments: none. No escalations.

### vocabulary/allium/expression — CLEAN
Lifts: `invariant` (5).
Shape grammar: no value types, no function signatures. Pure invariant file.
TODO comments: none. No escalations.

### vocabulary/allium/pipeline — ONE TODO (deferred rule lift)
Lifts: `invariant` (7).
Shape grammar: no value types, no exported functions.
TODO comments: 1 rule declaration (LoadSource — deferred).
Structural intent captured in inline comment: LoadSource(source_root: String),
5-step pipeline including stub-unification and inline-primitive lift.
No escalations.

### vocabulary/allium/renderers — CLEAN
Lifts: `invariant` (4).
Shape grammar: no value types, no function signatures. Pure invariant file.
TODO comments: none. No escalations.

### vocabulary/allium/tags — CLEAN
Lifts: `record` (1), `invariant` (3).
Shape grammar: `AlliumTagCatalogue` — four `List<String>` fields →
`(list-of :String)` — clean. No `map-of` gap.
TODO comments: none. No escalations.

### vocabulary/boundary/analyzer — ONE TODO (deferred rule lift)
Lifts: `record` (1), `invariant` (8).
Shape grammar: `BindingIssue` has 10 fields, 9 optional. `use_aliases:
List<String>?` → `(optional (list-of :String))` — nested shape grammar
works correctly. All optional scalar fields → `(optional :String)` /
`(optional :Any)` — clean.
TODO comments: 1 rule declaration (AnalyzeFile — deferred). Structural intent
captured: detects file shape (module-bound vs subsystem-bound), processes
declare-new / local-attach / foreign-attach fn forms, applies exports:.
No escalations.

### vocabulary/boundary/pipeline — ONE TODO (deferred rule lift)
Lifts: `invariant` (5).
Shape grammar: no value types, no exported functions.
TODO comments: 1 rule declaration (LoadSource — deferred).
Structural intent captured: LoadSource(model: model.Model, source_root: String).
No escalations.

### vocabulary/boundary/tags — CLEAN
Lifts: `record` (1), `invariant` (2).
Shape grammar: `BoundaryTagCatalogue` — two `List<String>` fields →
`(list-of :String)` — clean. No `map-of` gap.
TODO comments: none. No escalations.

## Structural observations

1. **Vocabulary is mostly invariant-heavy, record-light.** Six of eight files
   have no function signatures and no exported types. The dominant lift is
   `invariant`. This is the most invariant-dense subsystem across Sprint 2.

2. **`rule` declarations appear in pipeline files only.** Three of the eight
   files (allium/pipeline, boundary/pipeline, boundary/analyzer) have one
   `rule` declaration each. All left as TODO inline comments. Unlike validation/
   (35 rules) or constraint/ (1 rule), the vocabulary subsystem has exactly 3
   rule declarations total — all LoadSource/AnalyzeFile dispatch points.

3. **`map-of` gap: NOT triggered.** Zero instances. List-typed fields use
   `(list-of :String)` cleanly. Sprint 2 cumulative count remains 2.

4. **Nested shape grammar works for `BindingIssue.use_aliases`.** The field is
   `List<String>?` — `(optional (list-of :String))` is the correct form. This
   is the first occurrence of a nullable list-of in Sprint 2. No friction.

5. **`(optional :Any)` covers trigger/ex fields.** The `BindingIssue.trigger`
   and `.ex` fields have type `Any?` — expressed as `(optional :Any)`. Clean.

6. **No cross-module type references in vocabulary ports.** Unlike analyzer.clj
   (`:model/Model`, `:parser/ParsedAllium`), these files have no exported
   functions — no function signatures needed, so no namespaced type refs appear.
   The allium/pipeline.allium has a `use "../../model/spec.allium" as model`
   import but only for the rule's `when:` clause, which is deferred.

## Gaps to escalate (Sprint 2 cumulative, vocabulary addendum)

No new gaps. Existing gaps unchanged:
1. **`map-of` combinator** — 2 instances total. Vocabulary adds 0.
2. **`rule` lift** — 39 total across all ports (35 validation + 1 constraint + 3 vocabulary).

## Test counts

| State | Tests | Assertions |
|---|---|---|
| Before Sprint 2 (all) | 67 | 119 |
| After Sprint 2 infra+proj+libs | 79 | 172 |
| After Sprint 2 validation/ | 95 | 229 |
| After Sprint 2 constraint/ | 109 | 301 |
| Before Sprint 2 vocabulary/ | 591 | 3062 |
| After Sprint 2 vocabulary/  | 607 | 3128 |
| Delta (vocabulary sprint)   | +16 | +66 |

## jj log (Sprint 2 vocabulary)

```
feat(canvas): port vocabulary/boundary/tags spec       6279f5d9
  (contains all 8 vocabulary ports — absorbed into one commit by squash)
```

---

# Sprint 2 Addendum — agent/ subsystem (6 modules)

Date: 2026-05-26
Scope: agent/api, edb, query, sci, system, views_loader

## Per-module status

### agent/api — CLEAN
Lifts: `record` (14), `invariant` (5), `function` (12), `exports` (1).
Shape grammar: `Envelope.rows: List<Value>` → `(list-of :Value)`. Optional scalar
fields throughout (`label`, `validity`, `projection_kind`, `public`, `source_location`,
`sub_phase`). Nested optional: `ArtifactSummary.source_location: model.SourceLocation?`
→ `(optional :model/SourceLocation)` with cross-module ref auto-emitting Relation.
`VocabularyEntry.relation_kinds: List<RelationKindEntry>?` →
`(optional (list-of :RelationKindEntry))` — nested shape grammar, clean.
Cross-module type refs: `:model/SourceLocation`, `:query/QueryForm`, `:query/QueryRow`.
No `exports:` declared in source boundary — the subsystem boundary (agent.boundary)
carries the exports list; per-module boundary has none. `exports` macro added for all
14 value types anyway for completeness.
TODO comments: none. No escalations.

### agent/edb — CLEAN
Lifts: `value` (1), `invariant` (3), `function` (1), `exports` (1).
`EDB` is opaque (structureless value type). `PredicateCatalogue` invariant carries the
full predicate catalog in its docstring — no predicate-catalog lift needed, the
invariant body covers it. `model_to_edb` takes `:model/Model` cross-module ref.
TODO comments: none. No escalations.

### agent/query — CLEAN
Lifts: `value` (2), `record` (2), `invariant` (3), `function` (2), `exports` (1).
`QueryForm` and `QueryRow` are opaque value types. `ParsedQuery.in: List<Keyword>`
→ `(list-of :Keyword)` — clean. `QueryAtom.args: List<Value>` → `(list-of :Value)`.
`evaluate` takes cross-module `:edb/EDB`. `ParseFailureModes` invariant enumerates
the four typed ex-info variants — captured in docstring.
TODO comments: none. No escalations.

### agent/sci — CLEAN
Lifts: `record` (2), `invariant` (4), `function` (3), `exports` (1).
`EvalResult` has 6 fields, all optional except `ok`. `EvalOpts` is a single
optional field. `reset_ctx` is nullary with `(gives :Unit)`. `SandboxSafety` invariant
names the explicitly-denied operations. `SandboxFailureModes` enumerates `:runtime`
and `:timeout` with propagation semantics — captured in docstring.
TODO comments: none. No escalations.

### agent/system — CLEAN
Lifts: `record` (3), `invariant` (5), `function` (4).
`AgentStatus.views: views_loader.LoadReport` → `:views_loader/LoadReport`
cross-module ref. `help(fn_sym: Symbol?)` → `(optional :Symbol)` for optional arg.
`source` returns `(optional :SourceEntry)`. No `exports:` in source module boundary
(subsystem boundary carries them). No exports macro added — consistent with source.
TODO comments: none. No escalations.

### agent/views_loader — CLEAN
File path `views_loader.clj` → namespace `canvas.agent.views-loader` (underscore →
hyphen per standing convention). Module name in canvas is `"agent.views_loader"`
(matching the source coordinate).
Lifts: `record` (2), `invariant` (5), `function` (5), `exports` (1).
`LoadReport.loaded: List<Symbol>` → `(list-of :Symbol)`. `LoadError.error_form:
Value?` → `(optional :Value)`. `discover` and `auto_load` both take
`(optional :String)` for nullable target_src. `reset` is nullary with `(gives :Unit)`.
`ResetClearsBoth` invariant names the sci.reset_ctx coupling explicitly.
TODO comments: none. No escalations.

## Structural observations

1. **Agent is the most function-rich subsystem in Sprint 2.** api alone has 12
   function declarations (L0: 1, L1: 9, L2: 3) — the largest single-module function
   surface encountered across the entire sprint. Every function is expressible with
   existing shape grammar.

2. **No `rule` declarations anywhere in agent/.** Unlike validation/ (35 rules) and
   vocabulary/ (3 rules), the agent subsystem has zero rule declarations. Every
   behavioural commitment is an `invariant`. The `rule` deferred-lift count remains 39.

3. **Cross-module refs are rich and multi-directional.** api → query (QueryForm,
   QueryRow), api → model (SourceLocation), edb → model (Model), query → edb (EDB),
   system → views_loader (LoadReport). Every cross-module ref expressed as
   `:<module>/<Type>` and auto-emits a `:references` Relation. No friction.

4. **`map-of` gap: NOT triggered.** Zero instances. Sprint 2 cumulative count
   remains 2 (registry + violation only). Agent subsystem operates entirely with
   typed scalars, lists, sets, optional fields, and opaque maps (`:Map` for filters).

5. **`filters: Map<String, Value>?` recurs across L1 probes.** Eight L1 and L2
   functions take an optional filter map. Approximated uniformly as `(optional :Map)`
   — consistent with the approach used for `Map<String, Any>` elsewhere. This
   establishes a pattern: filter bags are `:Map`, structured domain types get
   field decomposition.

6. **Opaque value types via `value` vs structured via `record`.** `QueryForm`,
   `QueryRow`, and `EDB` are opaque (no declared fields — structureless value types).
   All other value types are structured (`record`). The split mirrors the spec
   language: structureless allium `value` → canvas `value`, fielded allium `value`
   → canvas `record`.

7. **`export` discipline clarified.** The agent.boundary subsystem boundary carries
   all exports; individual module boundaries have none. For api.clj the exports macro
   was still applied (it owns the types). For system.clj no exports macro was
   applied (types belong to their declaring modules). This matches the source
   structure.

## Gaps to escalate (Sprint 2 cumulative, agent addendum)

No new gaps. Existing gaps unchanged:
1. **`map-of` combinator** — 2 instances total. Agent adds 0.
2. **`rule` lift** — 39 total across all ports (35 validation + 1 constraint + 3 vocabulary + 0 agent).

## Test counts

| State | Tests | Assertions |
|---|---|---|
| Before Sprint 2 (all) | 67 | 119 |
| After Sprint 2 infra+proj+libs | 79 | 172 |
| After Sprint 2 validation/ | 95 | 229 |
| After Sprint 2 constraint/ | 109 | 301 |
| Before Sprint 2 agent/ (full suite) | 66 | 280 |
| After Sprint 2 agent/ (full suite)  | 137 | 464 |
| Delta (agent sprint) | +71 | +184 |

Note: the full suite (`clj -M:test -r '(fukan\.canvas\|canvas)\..*-test'`) includes
fukan.canvas.* substrate tests which account for the higher absolute numbers vs the
canvas-only counts in prior entries.

## jj log (Sprint 2 agent)

```
feat(canvas): port agent/views_loader spec   oqlyrtos
feat(canvas): port agent/system spec         urktpvzm
feat(canvas): port agent/sci spec            xlllvmuz
feat(canvas): port agent/query spec          mwqvrkqu
feat(canvas): port agent/edb spec            vxxwoowp
feat(canvas): port agent/api spec            xtwxmnov
```
