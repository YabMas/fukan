# Sprint 2b Stage 1 — Emergence Corpus Inventory

Corpus file: `/tmp/fukan-emergence/corpus.edn`
Produced by: Stage 1 mechanical projection agent
Date: 2026-05-25

---

## Entity Counts

| Type | Count |
|------|-------|
| Module | 8 |
| Affordance (with shape — boundary `fn` declarations) | 25 |
| Affordance (without shape — `guarantee`, `invariant`, `rule`, `surface`) | 36 |
| **Affordance total** | **61** |
| Type (`:kind :atomic` — no declared fields) | 2 |
| Type (`:kind :record` — with declared fields) | 3 |
| **Type total** | **5** |
| Relation (`:kind :references`) | 28 |
| Relation (`:kind :triggers`) | 2 |
| **Relation total** | **30** |

**Grand total entities: 104**

No `:role` fields. No `:tags` fields. (Verified: only occurrences of those strings in the corpus are in the header comment on line 3.)

---

## Per-Module Inventory

### 1. `infra.server`

Source: `src/fukan/infra/server.allium` + `server.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| ServerOpts | Type (:record) | `port: Integer?` |
| ServerInfo | Type (:record) | `port: Integer` |
| SingleServerInstance | Affordance (no shape) | guarantee |
| start_server | Affordance (with shape) | `ServerOpts -> ServerInfo?` |
| stop_server | Affordance (with shape) | `() -> Unit` |
| get_port | Affordance (with shape) | `() -> Integer?` |

Relations from this module: none (all types are module-local).

---

### 2. `infra.model`

Source: `src/fukan/infra/model.allium` + `model.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| SnapshotIsolation | Affordance (no shape) | guarantee |
| SingleModelSource | Affordance (no shape) | guarantee |
| ModelServerDecoupled | Affordance (no shape) | guarantee |
| load_model | Affordance (with shape) | `FilePath -> model/Model` |
| get_model | Affordance (with shape) | `() -> model/Model?` |
| refresh_model | Affordance (with shape) | `() -> model/Model?` |
| get_src | Affordance (with shape) | `() -> FilePath?` |

Relations: 3 `:references :model/Model` (from load_model, get_model, refresh_model).

---

### 3. `constraint.evaluator`

Source: `src/fukan/constraint/evaluator.allium` + `evaluator.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| Stratum | Type (:atomic) | value block with no structural fields declared |
| Binding | Type (:atomic) | value block with no structural fields declared |
| StratifiedFixedPoint | Affordance (no shape) | invariant |
| StratificationSafeNegation | Affordance (no shape) | invariant |
| NaiveFixedPoint | Affordance (no shape) | invariant |
| UnificationSemantics | Affordance (no shape) | invariant |
| AggregationSemantics | Affordance (no shape) | invariant |
| ComparisonOperators | Affordance (no shape) | invariant |
| Determinism | Affordance (no shape) | invariant |
| evaluate_rules | Affordance (with shape) | `List<ast/ConstraintRule>, derivations/EDB -> derivations/EDB` |
| query | Affordance (with shape) | `List<ast/ConstraintRule>, derivations/EDB, ast/ConstraintAtom -> Set` |

Relations: 5 `:references` (ast/ConstraintRule x2, derivations/EDB x2, ast/ConstraintAtom x1).

---

### 4. `constraint.builtins`

Source: `src/fukan/constraint/builtins.allium` + `builtins.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| BuiltinCatalogue | Affordance (no shape) | invariant |
| in | Affordance (with shape) | `Any, Set<Any> -> Bool` |
| contains | Affordance (with shape) | `String, String -> Bool` |
| is_present | Affordance (with shape) | `Any -> Bool` |
| is_absent | Affordance (with shape) | `Any -> Bool` |

Relations from this module: none (all types are primitive/local).

---

### 5. `validation.phase4`

Source: `src/fukan/validation/phase4.allium` + `phase4.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| Phase4Result | Type (:record) | `model: model/Model, violations: List<agent/Violation>` |
| RunPhase4 | Affordance (no shape) | rule |
| SubPhaseOrdering | Affordance (no shape) | invariant |
| AggregateBeforeGate | Affordance (no shape) | invariant |
| GateG2HaltsOnError | Affordance (no shape) | invariant |
| GateG2IgnoresWarnings | Affordance (no shape) | invariant |
| MissingSubPhaseIsSilent | Affordance (no shape) | invariant |
| PurelyDerivedFromModel | Affordance (no shape) | invariant |
| run | Affordance (with shape) | `model/Model -> Phase4Result`; triggers RunPhase4 |
| gate_g2 | Affordance (with shape) | `model/Model, List<agent/Violation> -> Phase4Result` |
| rules_4a | Affordance (with shape) | `model/Model -> List<agent/Violation>` |
| rules_4b | Affordance (with shape) | `model/Model -> List<agent/Violation>` |
| rules_4c | Affordance (with shape) | `model/Model -> List<agent/Violation>` |
| rules_4d | Affordance (with shape) | `model/Model -> List<agent/Violation>` |
| rules_4e | Affordance (with shape) | `model/Model -> List<agent/Violation>` |
| rules_4f | Affordance (with shape) | `model/Model -> List<agent/Violation>` |
| rules_4g | Affordance (with shape) | `model/Model -> List<agent/Violation>` |

Relations: 1 `:triggers` (run -> RunPhase4); 16 `:references` (model/Model x9, agent/Violation x7 — covering run, gate_g2, and all seven sub-phase fns).

---

### 6. `validation.rules_4a`

Source: `src/fukan/validation/rules_4a.allium` + `rules_4a.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| AtMostOneCompositeParent | Affordance (no shape) | invariant |
| TopLevelModulesAreWarning | Affordance (no shape) | invariant |
| NoSubsystemCompositionCycles | Affordance (no shape) | invariant |
| ContainsPathsResolve | Affordance (no shape) | invariant |
| SubsystemNamesAreUnique | Affordance (no shape) | invariant |
| CheckIsPure | Affordance (no shape) | invariant |
| check | Affordance (with shape) | `model/Model -> List<agent/Violation>` |

Relations: 2 `:references` (model/Model, agent/Violation from check).

---

### 7. `vocabulary.allium.pipeline`

Source: `src/fukan/vocabulary/allium/pipeline.allium` + `pipeline.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| LoadSource | Affordance (no shape) | rule |
| DeterministicFileOrder | Affordance (no shape) | invariant |
| PathCanonicalisation | Affordance (no shape) | invariant |
| DefaultsRegisteredBeforeAnalysis | Affordance (no shape) | invariant |
| StubUnification | Affordance (no shape) | invariant |
| AmbiguousStubsLeftAlone | Affordance (no shape) | invariant |
| InlineLiftIdempotence | Affordance (no shape) | invariant |
| PipelinePurity | Affordance (no shape) | invariant |
| load_source | Affordance (with shape) | `String -> model/Model`; triggers LoadSource |

Relations: 1 `:triggers` (load_source -> LoadSource); 1 `:references` (model/Model from load_source).

---

### 8. `web.handler`

Source: `src/fukan/web/handler.allium` + `handler.boundary`

| Entity | Kind | Notes |
|--------|------|-------|
| PureDelegation | Affordance (no shape) | guarantee |
| PerRequestModel | Affordance (no shape) | guarantee |
| ViewTransport | Affordance (no shape) | surface (projected as shapeless Affordance per projection rule 7 extension — `surface` carries inline `@guarantee` annotations, no callable shape) |
| create_handler | Affordance (with shape) | `() -> Handler` |

Relations from this module: none (create_handler's output `Handler` is a local/opaque type).

---

## Projection Notes

**Stratum and Binding (constraint.evaluator):** Both are declared as `value { ... }` blocks whose body contains only a prose comment and no field declarations. Projected as `:kind :atomic` per rule 2 (value with no fields).

**ViewTransport (web.handler):** The `surface` keyword is not listed in the four projection rule 7 forms (`invariant`, `guarantee`, `rule`, `assertion`). It is structurally a named declaration with a prose body and inline `@guarantee` annotations — no callable shape. Projected as Affordance with `shape nil` and `formal-expression` holding the full surface body text. This is the closest rule-7-adjacent handling.

**`returns:` clauses (phase4.boundary fn `run`):** Skipped per rule 6.

**`exports:` blocks (server.boundary, evaluator.boundary):** Skipped per rule 8.

**`use` / cross-module aliases:** Used only to determine `:target` keywords in `:references` Relations (e.g. `use "../model/spec.allium" as model` + `model.Model` → `:model/Model`).
