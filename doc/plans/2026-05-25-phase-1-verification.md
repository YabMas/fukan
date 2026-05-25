# Phase 1 Verification Report — Canvas + Substrate

**Date:** 2026-05-25
**Status:** Complete
**Decision:** (2) Layered language works with caveats

> **Amended 2026-05-25** to integrate open-question resolutions from the
> post-report conversation. The substrate held up *more* than the original
> framing of Section 3 credited (Affordance already accommodates behavioral
> declarations). Section 6 is restructured: most of the original open questions
> were resolved, deferred, or dropped, and a smaller set of genuine Phase 2
> design questions takes their place.

---

## 1. What was attempted vs. what was built

The Phase 1 goal (from the redesign doc): build a minimum viable substrate +
canvas library + first lift library, port real specs, and validate that the
*layered-language hypothesis* holds ergonomically. The hypothesis: a deliberately
minimal, architecture-neutral substrate + lift libraries that carry architectural
vocabulary = a canvas where LLM architects can author at design-vocabulary
altitude.

Eight implementation tasks were completed in sequence:

| Task | What it delivered | Key files |
|------|-------------------|-----------|
| T1 | Datascript dependency | `deps.edn` |
| T2 | Six substrate primitives as Clojure records | `src/fukan/canvas/substrate.clj` |
| T3 | Datascript-backed store (transact + query) | `src/fukan/canvas/substrate/store.clj` |
| T4 | Substrate-construction helpers (`arrow`, `record-of`, `within-module`) | `src/fukan/canvas/helpers.clj` |
| T5 | `defconstructor` macro — form grammar + `produces` block | `src/fukan/canvas/defconstructor.clj` |
| T6 | `defquery` + Datalog name-resolution expansion | `src/fukan/canvas/defquery.clj` |
| T7 | `fc/check` — constraint runner + structured violation output | `src/fukan/canvas/check.clj` |
| T8 | First lift library: `function`, `record` | `src/fukan/canvas/library/monolith.clj` |

Four pilot ports were completed in Task 9, extending the original plan's
minimum of two:

| Port | Source module | Canvas pilot file |
|------|--------------|-------------------|
| Lifecycle smoke test | `infra/server` | `src/fukan/canvas/pilot/server.clj` |
| Constraint runtime | `constraint/evaluator` | `src/fukan/canvas/pilot/constraint_evaluator.clj` |
| Allium analyzer | `vocabulary/allium/analyzer` | `src/fukan/canvas/pilot/vocabulary_analyzer.clj` |
| Phase 4 runner | `validation/phase4` | `src/fukan/canvas/pilot/validation_phase4.clj` |

The canvas test suite (27 tests, 42 assertions) passes clean as of this report.

---

## 2. Verification of the layered-language hypothesis

### What worked

The substrate's six-primitive decomposition held up across four ports without
requiring revision. Module, Affordance, State, Type, Relation, Tag provided
sufficient vocabulary to represent the callable surface of all four modules
without forcing new primitives. The `defconstructor` form-grammar mechanism
worked cleanly: the required/optional/repeatable declaration model handled
`function` and `record` lifts naturally, and the diagnostic error on unknown
forms ("`returns` is not a body form of `function`") is demonstrably useful.

`function` and `record` as lifts read at the right altitude. An author writing:

```clojure
(function "analyze_file"
  "Add this file's kernel content to model."
  (takes [model :Model] [ast :ParsedAllium] [coordinate :String])
  (gives :Model))
```

is thinking in module-design terms, not substrate plumbing. The lift
abstraction holds: the substrate construction inside `produces` is invisible
to the author.

The separation of lift-library vocabulary from substrate is also principled
in practice. When `function` needed a new form (`effect`), it was addable
without touching the substrate. When `record` needed a `field` form, same.
The composition boundary was clean.

### What was awkward

Three categories of ergonomic friction surfaced:

**1. Lift body syntax is keyword-only for types.** Both `(field name :Keyword)`
and `(takes [name :Keyword])` accept only bare keywords for type references.
This means `List<Integer>` becomes `:List`, `Optional<String>` becomes
`:String`, and `model.Model` becomes `:Model`. The type information is
structurally lost, not just unrendered. Authors writing the ports made this
tradeoff explicitly visible via comments, which is the right failure mode —
but the gap is real.

**2. The `record` lift requiring at least one field is a category error.**
Allium's `value` types (e.g. `Stratum`, `Binding` in `constraint/evaluator`)
are intentionally opaque: named concepts whose internal structure the spec
withholds. Forcing a `(field ...)` declaration on them conflates structural
records with semantic value names. The workaround (dropping to
`h/declare-affordance` directly) is worse than having no record lift at all —
it loses the named-type framing.

**3. The `function` lift's `effect` form is a non-solution for behavioral
anchoring.** Gap 13 in the findings doc identifies `fn run(...) { triggers:
RunPhase4; returns: post.result }` — a boundary function that attaches to an
Allium rule. The `effect` form handles simple side-effect tagging; it doesn't
provide a mechanism to reference a rule as the behavioral anchor. The two
things look similar at the surface but are structurally different.

### What's missing: structural vs. ergonomic

**Structural gaps** — the lift library lacks primitive coverage for entire
categories of spec declaration:

- No `invariant`/`guarantee` lift (Gaps 1, 6, 11, 15 across three ports)
- No `rule` lift with `when:` clause (Gaps 9, 15)
- No `exports:`/closure mechanism for module surface (Gap 2)
- No cross-module type reference syntax in `takes`/`field`/`gives` (Gaps 5, 12)
- No `triggers:`/`returns:` clause on `function` (Gap 13)

**Ergonomic gaps** — the substrate and lift mechanism are structurally capable
but the syntax is rough:

- `record` requires ≥1 field (Gap 4) — a `value` lift fixes this
- Keyword-only field types (Gaps 3, 8, 10) — lift body needs shape-expression syntax
- No iteration over structurally-identical function families (Gap 14)

The structural gaps are the hard part; the ergonomic gaps are lift-library
implementation work.

---

## 3. The behavioral-coverage finding

This is the most important architectural finding of Phase 1, and the findings
doc buries it slightly. State it directly:

**Across four pilot ports of substantial Allium specs, the canvas + monolith
library expressed the modules' callable surface (functions, value types) but
expressed ZERO of the modules' 25 named behavioral declarations — invariants,
guarantees, and rules.**

The count is not an approximation. From the four ported modules:

- `infra/server`: 1 guarantee, 2 failure-mode annotations → 0 expressed
- `constraint/evaluator`: 7 invariants → 0 expressed
- `vocabulary/allium/analyzer`: 1 rule + 8 invariants → 0 expressed
- `validation/phase4`: 1 rule + 6 invariants → 0 expressed

Total named behavioral declarations in the ported specs: 25. Canvas expressions
of those declarations: 0. That's 0%.

**The substrate already accommodates this content.** The redesign doc's
"Affordance: the heavy lifter" section spells out three Affordance categories;
the third — *rule, invariant, behavioural assertion* — is an Affordance with
no shape and a `formal-expression` attribute. The substrate primitive was
designed for behavior. What Phase 1 didn't deliver was a *lift* in the monolith
library for constructing those Affordances. An `invariant` lift is small:

```clojure
(defconstructor invariant
  "A named behavioral commitment of the enclosing module."
  (form holds-that "What must remain true." :shape :prose)
  (produces [name doc forms]
    (h/declare-affordance name
      :role :invariant
      :formal-expression (first (:holds-that forms)))))
```

That's the minimal form. Substantially all 25 dropped declarations across
the four ports become trivially expressible with this lift plus a sibling
`guarantee`. The gap is therefore *lift-library scope*, not substrate
capability — a meaningfully smaller architectural concern than "the canvas
can't model behavior."

### Canvas-supersedes-Allium

The intended trajectory is that canvas supersedes `.allium` as fukan's
primary design surface. Behavioral content must therefore be expressible in
canvas — not as a stretch goal but as a base requirement. The Phase 1
behavioral-coverage finding sharpens the Phase 2 brief in one direction:
a behavioral-lift sprint is a prerequisite to broader porting, not an
optional enhancement. The good news, restated: it's a sprint, not a
substrate-revision project.

### If behavior must be expressible in canvas, what does that mean structurally?

At minimum: `invariant` and `guarantee` lifts (named behavioral commitments,
no formal expression required for Phase 2). These are straightforward: a named
Affordance with no shape, a role of `:invariant`, and a docstring is
sufficient to register the behavioral commitment in the substrate.

Beyond minimum: a `rule` lift with a `when:` clause. Rules carry formal
trigger expressions — this pushes toward a grammar for formal expressions
inside lift bodies, which is real design work. The redesign doc's
`formal-expression` slot on Affordance anticipates this; Phase 1 never
exercises it.

Full formal rule bodies (evaluating Datalog or custom rule expressions) are
Phase 3+ work. Phase 2's `rule` lift can be named-and-documented without
requiring executable formal expressions, and that alone covers the majority
of what the ports needed.

---

## 4. Decision

**(2) — Layered language works with caveats.**

The substrate architecture is sound. Four ports completed without requiring
primitive revision. The `defconstructor` mechanism is ergonomically correct
for lift authoring. The `function` and `record` lifts read at the right
altitude. `fc/check` and `defquery` work and compose correctly.

The caveats are real but addressable in lift-library space:

- The lift library scope is significantly too narrow for Phase 2 broad porting.
  Behavioral declarations (invariants, guarantees, rules) are the majority of
  the spec surface that's currently inexpressible. Without at minimum an
  `invariant`/`guarantee` lift, porting any Allium file is guaranteed to drop
  its most important content.
- Several ergonomic gaps in existing lifts (keyword-only field types, required
  fields on `record`) need fixing before porting more specs is informative
  rather than misleading.

The decision does NOT trigger a brainstorming reset. The substrate design from
the redesign doc is validated structurally. What needs work is the lift library,
not the substrate primitives or the `defconstructor`/`defquery` mechanism.

The path forward is Phase 2 with a tightly-scoped lift-library expansion
sprint before broader porting begins.

---

## 5. Phase 2 implications

### Before broader porting: required lift-library work

The findings doc's priority ranking holds. Tightened:

**P0 — Fix the `record` lift's field-required constraint.** This is a one-line
fix (remove `:required true` from the `field` form declaration in
`src/fukan/canvas/library/monolith.clj`) or introduce a separate `value` lift.
Either way, it's a prerequisite for correct porting of any Allium file with
opaque value types, which is most of them.

**P1 — `invariant`/`guarantee` lift.** Required before porting any module with
behavioral commitments, which is every module. The substrate primitive
(Affordance with no shape + `formal-expression`) already exists from Task 2;
the lift just constructs it. Minimal form is ~5–10 lines per lift definition
(see Section 3 for the sketch). Zero formal-expression-grammar machinery is
required initially — a prose `:holds-that` form is sufficient to register
the named behavioral commitment and surface it in the substrate. Grammar
for executable formal expressions is RQ2 (see Section 6).

**P2 — Richer field shape syntax in `record`.** The keyword-only `(field name
:Keyword)` syntax must accept shape expressions to correctly express
`List<T>`, `Optional<T>`, and cross-module type refs. This is a `defconstructor`
form-grammar question: does `:shape :field+` accept arbitrary s-exprs or only
keywords? The lift body's `produces` block would need to handle the expression
case. Medium complexity; a prerequisite for meaningful porting of any module
with non-trivial data shapes.

**P3 — Cross-module type references.** Currently silently dropped in both
`takes`/`gives` (Gap 5) and `record` fields (Gap 12). A namespacing
convention for type keywords (`:module.SubType` or `[module :SubType]`) is
needed. The substrate's Relation mechanism is the right representation (a
`:references` Relation from the Affordance/Type to the referenced Type entity).
This is a lift-library design question, not a substrate question.

### Does any gap require substrate-level change?

No. The findings doc is clear on this, and the port evidence supports it.

All nine root-cause gaps are addressable in lift-library + canvas-API space:

- Behavioral lifts (`invariant`, `guarantee`, `rule`) → new lift definitions
  using existing `defconstructor` + `declare-affordance` with role attributes
- Field shape syntax → `defconstructor` form-grammar extension (`:shape :field+`
  accepting expressions, not just keywords)
- Cross-module type refs → lift-library convention + Relation substrate (already
  exists)
- `exports:` closure → a module-level `exports` form or a new
  `within-closed-module` macro variant

The substrate held up. Its six primitives were sufficient for everything the
ports needed to represent. No case required a new primitive, a new structural
reference, or a revision to existing primitive semantics. This is the most
important positive finding of Phase 1.

### Does the redesign doc need revision?

Minor updates only. The doc's Section "Affordance: the heavy lifter" anticipates
three Affordance categories (shaped/function, lifecycle hook, rule/invariant/
behavioral assertion). Phase 1 validated the first category and ignored the
third. The doc's design is correct; Phase 1 just didn't implement it fully.

One thing the redesign doc does not address and should: the ergonomic gap between
the substrate's `formal-expression` slot on Affordance and the lift-body grammar
that would populate it. The doc defers "rule bodies" without flagging that they
require their own grammar inside lift `produces` blocks. Phase 2 design needs
to address this explicitly.

---

## 6. Resolved open questions and remaining design work

The five open questions Phase 1 originally surfaced were resolved, deferred,
or dropped in the post-report review on 2026-05-25.

### Resolved or deprioritized

- **Behavior in canvas vs. `.allium` long-term** — *resolved*. Canvas is
  intended to supersede `.allium`. The substrate's Affordance primitive
  (no shape + `formal-expression`) already accommodates behavioral
  declarations; Phase 2 builds the lifts that construct those Affordances.
  No substrate work required. (Was OQ1.)
- **Lift definitional rules vs. system constraints** — *resolved*. The
  substrate (Affordance + Tag + Relation + `defquery`/`fc/check`) has the
  composability to express the same predicate as either a baked-in lift
  rule or a system-level constraint. The boundary is empirical and
  refactorable; patterns will emerge from use. Where logic is *definitional*
  to a lift's identity (a function MUST have `:gives`), folding it into the
  lift means the substrate is never constructible in a violating state.
  System constraints handle the can-be-violated case. Different reachability,
  same expressive power. (Was OQ4.)
- **GUI canvas authoring round-trip** — *deprioritized*. Not a Phase 2
  concern. If a future GUI authoring surface is needed, the `defconstructor`
  internal grammar map gives a starting point; the question becomes load-
  bearing only when that work begins. (Was OQ2.)
- **`defconstructor` compile-time vs. runtime validation** — *deprioritized*.
  Both are achievable; runtime validation is sufficient for now. Secondary
  to the core goal of getting the lift vocabulary right. (Was OQ5.)

### Remaining design questions for Phase 2

**RQ1 — `fc/check` semantics: predicate-as-violation vs predicate-as-compliance.**
The current implementation registers constraints as "fire when the Datalog
query finds matches" (predicate-as-violation). The alternative is "fire when
the query finds no matches" (predicate-as-compliance), which is more natural
for invariant-style constraints. Both have valid use cases. Phase 1 didn't
exercise constraints, so the question can be deferred — but the first
`invariant` lift that wants to auto-register a constraint forces the
decision. Settle empirically when the use case lands rather than abstractly
now. (Was OQ3.)

**RQ2 — Grammar for `formal-expression` on behavioral Affordances.**
A minimal `invariant` lift can take a prose `:holds-that` form (free text).
But expressive invariants — Datalog over the substrate, set-comprehension
constraints, equality assertions, cross-Affordance references — need a
*grammar* inside the lift body's `produces` block. The redesign doc's
`formal-expression` slot anticipates this but does not specify the spec
language. This is the deepest piece of Phase 2+ design work, and the size
of it determines whether canvas-supersedes-Allium is a one-quarter project
or a multi-quarter one.

**RQ3 — Migration path from `.allium` files to canvas.**
With behavioral lifts in place, every existing `.allium` file becomes
portable to canvas form. The analyzers that read `.allium` files project
into the same substrate the canvas writes to, so analysis-pipeline
compatibility is not the question. The design-authoring question is:
should existing `.allium` files be auto-translated to canvas form on a
tooling pass, deprecated in place while remaining readable, or maintained
indefinitely alongside as a secondary surface? This intersects with RQ2 —
auto-translation is only well-defined once the formal-expression grammar
exists.

---

## Appendix: Phase 1 artifact inventory

| Artifact | Lines | Tests |
|----------|-------|-------|
| `substrate.clj` | 55 | `substrate_test.clj` — 7 tests |
| `substrate/store.clj` | 68 | `store_test.clj` — 3 tests |
| `helpers.clj` | 60 | `helpers_test.clj` — 3 tests |
| `defconstructor.clj` | 107 | `defconstructor_test.clj` — 3 tests |
| `defquery.clj` | 65 | `defquery_test.clj` — 4 tests |
| `check.clj` | 47 | `check_test.clj` — 2 tests |
| `library/monolith.clj` | 41 | `monolith_test.clj` — 2 tests |
| Pilot ports (4 × ~70 lines) | ~286 | `pilot_test.clj` — 3 tests |
| **Total** | ~729 | **27 tests, 42 assertions** |

Canvas test suite status: **0 failures, 0 errors** as of 2026-05-25.
