# Pilot port findings — Phase 1, Task 9

Ports of four existing Allium + Boundary specs to the canvas + lift library.
Goal: ergonomic stress-test of `function` and `record` lifts against real
domain modules. Gaps documented here drive Phase 2 lift priorities.

---

## Port 1 — `infra/server` (lifecycle smoke test)

**Slice chosen:** Entire module — `server.allium` + `server.boundary`.
Tiny (~55 lines combined). Good baseline.

### Original spec summary

`.allium` defines two value types (`ServerOpts`, `ServerInfo`), one guarantee
(`SingleServerInstance`), and two failure mode annotations. `.boundary` defines
three functions and an `exports:` closure.

### Canvas port

```clojure
(h/within-module "infra.server"
  (record "ServerOpts" ...)
  (record "ServerInfo" ...)
  (function "start_server" ...)
  (function "stop_server" ...)
  (function "get_port" ...))
```

### What read naturally

`record` and `function` map cleanly to `value` and `fn` respectively.
Two fields each, typed, optional via `:Optional<Integer>` approximated as
`:Integer?` — readable in canvas as `:Optional`.

### What was harder / gaps encountered

**Gap 1 — No guarantee/invariant lift.**
`guarantee SingleServerInstance` and the failure mode annotations have no
canvas equivalent. These are named behavioral commitments, not functions or
records. Dropped entirely. A `guarantee` or `invariant` lift is needed.

**Gap 2 — No exports/closure mechanism.**
`exports: ServerOpts ServerInfo` has no canvas equivalent. The canvas `record`
lift adds a Type to the store but there is no way to mark it as part of the
module's public surface. Structural closure information is silently dropped.

**Gap 3 — Optional type shape.**
`ServerOpts.port: Integer?` — the `optional` shape combinator exists in
`helpers.clj` but the `record` lift does not thread it through. Fields are
typed by keyword only (`:Integer`). To express optionality you'd need either
a richer field shape or a combinator in the lift body syntax.

---

## Port 2 — `constraint/evaluator` (constraint runtime)

**Slice chosen:** `evaluator.allium` + `evaluator.boundary`.

**Why evaluator over derivations/ast:** The evaluator is the richest
behavioral module — seven named invariants plus two cross-module-typed
functions. It stress-tests the lift library hardest. `derivations` is simpler
(one value, three invariants, one fn) and would surface fewer gaps.

### Original spec summary

`.allium` defines two value types (`Stratum`, `Binding`) and seven invariants
(`StratifiedFixedPoint`, `StratificationSafeNegation`, `NaiveFixedPoint`,
`UnificationSemantics`, `AggregationSemantics`, `ComparisonOperators`,
`Determinism`). `.boundary` defines two functions with cross-module-typed
parameters (`ast.ConstraintRule`, `derivations.EDB`) and an `exports:` clause.

### Canvas port

```clojure
(h/within-module "constraint.evaluator"
  (record "Stratum" ...)   ; no fields — opaque in allium
  (record "Binding" ...)   ; no fields — opaque in allium
  (function "evaluate_rules" ...)
  (function "query" ...))
```

### What was harder / gaps encountered

**Gap 4 — record lift requires at least one field (`:required true` on `field`).**
Both `Stratum` and `Binding` are opaque value types in the allium — their
internal structure is intentionally not specified. The `record` lift requires
at least one `(field ...)` form. You cannot express a named-but-structurally-
opaque value type. Workaround: use `h/declare-affordance` directly with a
stub shape, but this reads poorly and loses the record framing. A `value` lift
(opaque named type, zero fields allowed) is needed.

**Gap 5 — No cross-module type references in function signatures.**
`evaluate_rules(rules: List<ast.ConstraintRule>, edb: derivations.EDB)` —
the parameter types are qualified references to types in other modules.
The `function` lift's `takes` form accepts `[name :Keyword]` pairs where
the keyword is a local type name. There is no syntax for `module/Type` or
`:ast.ConstraintRule` references. Cross-module type references are
structurally expressible only as opaque keywords, losing the module
linkage information entirely.

**Gap 6 — Seven invariants, zero lift coverage.**
All seven `invariant` declarations in evaluator.allium are behavioral
commitments that live entirely outside the `function`/`record` vocabulary.
No lift exists for invariants. These are documented as Gap 1 in the server
port (same root gap, larger scale here).

**Gap 7 — No negation / aggregation / comparison semantics lift.**
The `ComparisonOperators` and `AggregationSemantics` invariants carry
enumerations (`:=`, `:!=`, `:count`, `:sum`, etc.) that are part of the
module's behavioral contract. No mechanism to express this in canvas.

---

## Port 3 — `vocabulary/allium/analyzer` (Allium analyzer)

**Slice chosen:** `analyzer.allium` + `analyzer.boundary`.

**Why analyzer:** Largest and most meta-interesting module in vocabulary/allium.
It has a `rule` (not just `invariant`), two value types with actual fields,
and functions with complex cross-module signatures. Maximum stress.

### Original spec summary

`.allium` defines two value types with fields (`ExposesIssue`, `EventShapeMismatch`),
one `rule` (`AnalyzeFile` with a formal `when:` clause), and eight invariants.
`.boundary` defines two functions with cross-module-typed parameters.

### Canvas port

```clojure
(h/within-module "vocabulary.allium.analyzer"
  (record "ExposesIssue" ...)      ; 7 fields — all String
  (record "EventShapeMismatch" ...) ; 5 fields — mixed types including List<Any>
  (function "analyze_file" ...)
  (function "extract_use_aliases" ...))
```

### What read naturally

`ExposesIssue` ports cleanly — all String fields, no optionals. The `record`
lift reads well for this kind of diagnostic record shape.

### What was harder / gaps encountered

**Gap 8 — `List<T>` and `Any` field types have no shape expression.**
`EventShapeMismatch.shapes: List<Any>` and `arities: List<Integer>?` —
the `list-of` shape combinator exists in helpers but the `record` lift
body syntax only accepts `(field name :Keyword)`. You cannot write
`(field shapes (h/list-of :Any))`. The lift body is keyword-only for
type references. A richer field shape syntax (accepting shape expressions,
not just keyword refs) is needed.

**Gap 9 — `rule` has no lift equivalent.**
`rule AnalyzeFile { when: AnalyzeFile(...) }` is a named behavioral rule
with a formal trigger clause. This is fundamentally different from a
function signature — it's a reactive computation trigger, not a callable.
No lift exists for rules. A `rule` lift (name + `when:` clause + doc) would
be needed to express this.

**Gap 10 — Optional fields are not expressible.**
`arities: List<Integer>?` and `type_seqs: List<Any>?` are optional list
fields. Even if list shapes were supported, optionality-of-lists has no
encoding. (Same root as Gap 3, repeated in a richer context.)

**Gap 11 — Eight invariants again have no lift coverage.**
Same as Gap 6 — the invariant surface of this module is the larger half of
its behavioral specification, and none of it is expressible.

---

## Port 4 — `validation/phase4` (Phase 4 runner)

**Slice chosen:** `phase4.allium` + `phase4.boundary`.

**Why phase4 overview over individual rules files:** The overview module tests
the runner pattern — one rule with a formal `when:`, one record type with
cross-module typed fields, and a boundary with seven enumerated sub-phase
entry points. Rules files (4a–4g) are all structurally similar to each other
and would surface the same gaps repeatedly.

### Original spec summary

`.allium` defines one value type (`Phase4Result` with two cross-module-typed
fields), one `rule` (`RunPhase4` with a formal `when:` clause), and six
invariants. `.boundary` defines nine functions (one with a `triggers:` clause
and a `returns:` clause, plus `gate_g2` and seven `rules_4X` sub-phase
entry points).

### Canvas port

```clojure
(h/within-module "validation.phase4"
  ;; Phase4Result — cross-module field types not expressible, approximated
  (function "run" ...)
  (function "gate_g2" ...)
  (function "rules_4a" ...)
  ;; ... through rules_4g
  )
```

### What was harder / gaps encountered

**Gap 12 — `record` with cross-module typed fields.**
`Phase4Result.model: model.Model` and `violations: List<agent.Violation>` —
both fields have cross-module types. The `record` lift only accepts keyword
type refs. Cross-module field types are silently lost (same root as Gap 5,
now in `record` context).

**Gap 13 — `fn` with `triggers:` and `returns:` clauses.**
`fn run(...) { triggers: RunPhase4; returns: post.result }` is a boundary
function that attaches to an Allium rule. The `function` lift has `(effect
:Name)` for side-effects, but no mechanism to reference a rule as the
behavioral anchor, and no `returns:` clause for post-condition labels.
This is a structural gap in the `function` lift vocabulary.

**Gap 14 — Seven structurally identical sub-phase functions.**
`rules_4a` through `rules_4g` are seven functions that differ only in a
trailing letter. The canvas port repeats seven nearly-identical `(function
...)` forms. No iteration / pattern-over-family lift exists. This is an
ergonomic gap rather than a structural one — expressible but painful.

**Gap 15 — `rule` again has no lift coverage.**
`RunPhase4` with `when: RunPhase4(model: model.Model)` — same as Gap 9.

---

## Gap Catalog Summary

| # | Gap | Root Cause | Affects |
|---|-----|-----------|---------|
| 1/6/9/11/15 | No `guarantee`/`invariant`/`rule` lift | Behavioral primitives have no canvas equivalent | Every module with behavioral spec |
| 2 | No `exports:` closure mechanism | Module surface closure not in substrate | All boundary files with `exports:` |
| 3/10 | Optional field shapes not expressible | `record` lift body is keyword-only | Any spec with `T?` fields |
| 4 | `record` lift requires >= 1 field | `:required true` on `field` form | Opaque value types |
| 5/12 | No cross-module type references | `takes`/`field` are keyword-only | All cross-module function signatures |
| 7 | No enumerated constant set lift | No `enum` or `variants` lift | Behavioral contracts with op sets |
| 8 | No `List<T>` shape in `record` fields | Field body accepts keyword refs only | Value types with collection fields |
| 13 | No `triggers:`/`returns:` in `function` | `function` lift lacks rule-anchor syntax | Boundary fns tied to Allium rules |
| 14 | No iteration over repeated similar fns | No `for-each` or family lift | Pattern-of-N functions (sub-phases) |

---

## Phase 2 Lift Priorities

Ranked by frequency of occurrence and structural importance across the four ports:

### Priority 1 — `invariant` / `guarantee` lift

Appeared in EVERY module (Gaps 1, 6, 9, 11, 15 — five separate instances).
Every Allium file specifies behavioral commitments as named `invariant` or
`guarantee` blocks. Without this lift, the canvas captures only the _surface_
of a module (functions, records) while the behavioral core is invisible.
This is the single largest gap by coverage.

Minimal form: `(invariant "Name" "doc string")` — named behavioral commitment,
attached to the enclosing module. No formal expression needed for Phase 2.
The formal `when:`/`then:` expressions in `rule` bodies can come later.

### Priority 2 — Richer field shape syntax in `record`

Gaps 3, 8, 10, 12 all trace to the same root: the `record` lift's `(field
name :Keyword)` body syntax can only encode named type keywords. Three
separate shape needs surfaced:
- Optional fields: `(field port :Integer?)` or `(field port (h/optional :Integer))`
- List fields: `(field shapes (h/list-of :Any))`
- Cross-module refs: `(field model :model.Model)` or `(field model [:model :Model])`

The `record` lift's `produces` block would need to accept shape expressions
(not just keywords) in the `field` form.

### Priority 3 — Zero-field `value` type (opaque record)

Gap 4 — `record` requiring at least one field blocks opaque value types like
`Stratum` and `Binding`, which are intentionally structureless in the spec.
A `value` lift (or a `record` lift that tolerates zero `field` forms) would
fix this. Low implementation cost, high correctness payoff.

---

### Secondary priorities (Phase 2+ candidates)

- **`rule` lift** — behavioral rules with `when:` trigger clauses. Significant
  structural investment; likely Phase 3.
- **Cross-module type references in `function` takes/gives** — requires a
  namespacing convention for type keywords in the lift body. Medium cost.
- **`triggers:`/`returns:` on `function`** — Gap 13. Ties a function to an
  Allium rule anchor. Needs `rule` lift first.
- **`exports:` closure** — Gap 2. Module surface closure mechanism.

---

## Most Surprising Finding

**The `record` lift's `:required true` on `field` is a category error for
value types.** Allium's `value` declaration and `record` lift are not the
same concept: Allium `value` types are frequently opaque (no exposed fields)
— they're named semantic tokens, not structural records. Requiring at least
one field conflates structural records with semantic value names. `Stratum`
and `Binding` are canonical examples: they are named concepts whose internal
structure the spec deliberately withholds. A `value` lift that produces a
named Type with zero fields is the right separation.

**Load-bearing for Task 10:** The gap between `invariant`/`guarantee`/`rule`
and the current lift library is not just a missing lift — it reveals that
the canvas currently models only the _callable surface_ of a module. The
behavioral half of every Allium spec (which is often the larger half) is
structurally invisible. Phase 2 must decide whether the canvas should be
extended to represent behavioral contracts or remain a surface-only tool.
This is the most consequential architectural question surfaced by the pilots.
