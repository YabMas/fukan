# Classification Stratum — Design Brief

**Date:** 2026-05-30
**Status:** Design agreed (BASHES dialectic). Uncommitted planning artifact — not part of any code commit.
**Trajectory:** Invention + Convergence. Value constraint: *surface the pattern* (primary), *most elegant* (secondary).

---

## The problem

`:entity/type` (family ∈ {`:Module` `:Affordance` `:Type` nil}) and `:affordance/role` (the kind-tag,
e.g. `:canvas/rule`, `:canvas/invariant`, `:canvas/function`) are a **denormalized index** over the
canonical tagapp datoms (`{:tagapp/node <ref> :tagapp/tag <kw>}`). They are emitted by `store/->datoms`,
explicitly flagged as transitional ("retained while consumers migrate"), and read by ~50–90 consumers
across lens, projection, drift, coverage, integrity, agent-API, and identity/addressing.

The naive cleanup — delete the attrs, rewrite 50 queries as `tagapp → registry → :family` joins — is a
**translation**: more brittle than what it replaces (the derivation hand-inlined at 50 sites), generalizes
to nothing, and the next derived classification ("is-public?", "DDD aggregate-root?") needs the same joins
all over again.

## The reframe

The family/role index is the first instance of a **derived-classification layer (a "stratum")** over the
Node+Relation+tagapp substrate. Build the stratum as a general mechanism; let the index **dissolve into it**
(become its first views). The legacy attrs vanish as a side-effect of adopting it.

## Success gate — dissolve vs. translate

A second classification must cost **one line of data and zero consumer changes**. If it needs new joins,
it's a translation in disguise. This is the explicit pass/fail, run before any legacy datom is deleted.

---

## The Substrate Truth (decision)

**The stratum is a refinement language:** one edge (`refines`), four derived relations, the registry
projected into the db as the language's data, validated by a checker the language writes about itself.

It is composition **(b) query-time rules + (c) tag-refinement hierarchy**, unified. Option **(a)
materialization** is demoted to a per-rule optimization escape hatch.

### Why (c)+(b), decided against forcing examples

- *DDD aggregate-root-refines-entity* is inherently hierarchical → only refinement (c) expresses it
  without a second mechanism. **Family is the depth-1 degenerate case of the same edge.**
- *The 50-consumer trap* is dodged: the `tagapp→family` join lives in **exactly one place** (the rule);
  consumers speak `(kind-of ?e ?k)`. Lineage stays single-sourced (Alvaro's concern).
- *(a) materialization* re-hits the enrich-time landmine (per-node unchecked materialization braided into
  the build broke `with-canvas` tests + the core→registry layering wall). Rejected as primary form; kept
  as a localized cache of one rule if profiling ever demands it.

### Materialized vs. query-time

**Query-time for the derivation; materialized for the *source*.** Per-node family is **never** stored.
What's written once, at build, is the **registry as ~10 reference datoms** (`:tagdef` entities carrying
`:tagdef/refines`). De-complecting: *materialize the vocabulary's small static refinement map; derive every
node's classification.*

### The surface — four words, all derived from one edge

| Word | Rule | Recursion? | Replaces | Used by |
|------|------|-----------|----------|---------|
| `(direct-kind ?e ?tag)` | `[?ta :tagapp/node ?e][?ta :tagapp/tag ?tag]` | no | raw `:affordance/role` reads | name+role disambiguation, role filters |
| `(refines* ?t ?k)` | reflexive-transitive closure of `:tagdef/refines` | **yes** | — (the engine) | rarely called directly |
| `(kind-of ?e ?k)` | `(direct-kind ?e ?t)(refines* ?t ?k)` | yes | `[?e :entity/type :Affordance]` | "is-a, transitively" |
| `(family-of ?e ?fam)` | `(kind-of ?e ?fam)(family-root? ?fam)` | yes | `:entity/type` *enumeration/bucketing* | coverage, identity, projection |

The **second argument is the open vocabulary** — `:family/affordance`, `:ddd/aggregate-root`,
`:access/public` are all just values. No new fn per classification ⇒ the dissolve test passes *at the API
boundary*, not only in storage. Agents see four stable relations, never rule syntax.

### Registry change

Drop the `:family` scalar from each tag-definition. Each tag-def carries `:refines <parent-tag>`. Introduce
`:family/affordance`, `:family/type`, `:family/module` as **real super-tags** (the lattice gets a visible top).

```
:canvas/invariant  :refines :family/affordance
:canvas/rule       :refines :family/affordance
:canvas/function   :refines :family/affordance
:canvas/record     :refines :family/type
:canvas/value      :refines :family/type
:canvas/module     :refines :family/module
```

### Two hardening invariants (Cohort Alpha)

`:entity/type` was a **function** (one family per node, by construction). `kind-of` is a **relation** (many
ancestors). A naive lattice silently breaks the partition `coverage`/projection enumerators rely on. So:

1. **Partition invariant, named and *checked*:** every tag's `refines` chain terminates at **exactly one**
   `:family/*` super-tag; the refinement graph is **acyclic**; no dangling parent. This is itself a
   `checker` in the validation vocabulary — the stratum **dogfoods**: a forked/cyclic/dangling `refines` is
   a located violation in `(integrity)`, not a silent double-count. The single-valuedness `:entity/type`
   gave by construction is *earned back as a validated invariant*.
2. **`family-of` is the single-valued projection** distinct from `kind-of` (the relation). Some consumers
   want *the* family (a function, to bucket on); `family-of` is total + single-valued *given* the checker
   holds. De-complects partition (`family-of`) from lattice (`kind-of`).

Both extra words derive from `refines` (one-line rules) — the dissolve test is untouched.

---

## The dissolve test, executed

```clojure
;; A second classification, in full — one vocabulary file, two data lines:
(refines :ddd/aggregate-root :ddd/entity)
(refines :ddd/entity         :family/affordance)
```

Consumers ask `(kind-of ?e :ddd/entity)`. Zero closure-rule edits, zero new fns, zero existing-consumer
edits. **Dissolve, not translate.**

---

## Build plan

### Phase 1 — recursion-free, ships on today's single-arity `q`
1. Registry: add `:family/*` super-tags + `:refines`; project `:tagdef` datoms into the db at build.
2. Write `direct-kind` (depth-0, no rules input needed).
3. Migrate the **load-bearing** consumer first: **`canvas_source` name+role disambiguation** → `direct-kind`.
   Plus `tar_pit`/`patterns` *role filters*.
   *Proves registry-as-datoms + the surface without touching the rules engine.*

### Phase 2 — needs the `q`/view surface widened to thread the `%` rules input ("solve it" item)
> datascript-the-library supports the `%` rules input incl. recursive rules. fukan's public sandbox `q` is
> currently single-arity `(q query)` and threads no rules — widen it, or expose `kind-of`/`family-of` as
> named view fns that close over the rules internally.

4. Write `refines*` + `kind-of` + `family-of`.
5. Write the **partition checker** (acyclic; exactly one `:family/*` root; no dangling parent) in the
   validation vocab → surfaces in `(integrity)`.
6. Migrate the **5 forcing consumers spanning the axes:**
   - `tar_pit` (`:entity/type :Type`/`:Affordance`) → `kind-of` *(kind-hardcoding lens)*
   - `coverage` (`d/datoms :aevt :entity/type` bucketing) → `family-of` *(enumeration + single-valued)*
   - agent-api L2 `affordance-element` + L0 public vocab → `direct-kind`/`kind-of` *(public surface)*
   - `identity` stable-id index (type+name+parent) → `family-of` *(addressing path)*
   - `canvas_source` projection enumerators → `family-of`

### Phase 3 — run the gate, then retire the legacy
7. Add the toy/real second classification (the two lines above); prove zero consumer change. **Explicit pass/fail.**
8. *Only then* drop `:entity/type`/`:affordance/role` emission from `store/->datoms`.

---

## What was traded away (named)

- **Generality:** the stratum covers *is-a hierarchies only*. A future *non-hierarchical* derived class
  (e.g. a computed metric "fan-in > 5") won't fit and earns its own word. Accepted: surface *the* pattern,
  not all patterns; cheaper to reverse than a speculative view-DSL is to maintain.
- **The AEVT index fast-path** for family enumeration — traded for single-sourced lineage; `family-of`
  materialization is the named escape hatch if it ever bites (~700 nodes, ~10 tag-defs — closure is tiny).
- **A general derived-attribute mechanism** (option (a) as a first-class form) — not built; demoted to
  one-rule optimization.

---

## Whiteboard

```
  SUBSTRATE (floor)          STRATUM (this design)            CONSUMERS
  ─────────────────          ────────────────────             ─────────
  node ──tagapp──► :canvas/invariant ──refines──► :family/affordance
                          ▲                              ▲
                   direct-kind (depth-0)          family-of (root, single-valued ← CHECKER)
                          │                              │
                          └──────── kind-of (transitive closure) ───────┘
                                          │
                  (kind-of ?e :ddd/entity)  ← second axis, two data lines, zero code

  registry  ──projected once at build──►  :tagdef/refines datoms  (materialize the SOURCE)
  per-node family                          NEVER stored            (derive the VALUE)
```

---

## Layering home

**Revised during Phase 1 implementation.** The original brief placed the stratum in the vocab/construct-kit
tier on the assumption it is "registry-aware." It is not: the stratum reads only **substrate schema**
(`:tagapp/*` now; `:tagdef/*` later — and `:tagdef/*` are projected into the db by `canvas_source`, so the
*query* takes no code dependency on the registry). The rules hardcode no kinds — they are **tag-agnostic**,
which is exactly `core/`'s contract. So the stratum lives in **`fukan.canvas.core.classification`** (core
tier), making it usable by every tier above, including construct-kit (which `construct/resolve-in-module`
needed). The partition checker (Phase 2) still lives in the validation vocab.

---

## Phase 1 — DONE (2026-05-30)

Recursion-free slice shipped, behaviour-preserving, in two commits:

1. **`feat(stratum)`** — `fukan.canvas.core.classification`: `rules` (the `direct-kind` Datalog rule) +
   `direct-kind` fn (per-entity, single tag or nil). Routed the **name+role convention** onto it:
   `canvas_source/find-intra-module-collisions` derives each colliding child's kind via `direct-kind`
   (no-tag → `:none`, preserving the prior `get-else` behaviour); `construct/resolve-in-module`'s by-name
   role filter uses the `direct-kind` rule.
2. **`refactor(lens)`** — `tar_pit` (getter/function/declarative-rule role filters — dropped the now-redundant
   `:entity/type :Affordance` clause too), `consistency` (per-child role lookup), `patterns`
   (affordance-signature role) all read `direct-kind`.

Verified: full suite 1066 tests, 0 failures (3 pre-existing `not yet implemented` placeholder errors in the
unrelated `distributed.cluster` trial scaffolding). `store/->datoms` untouched — `:entity/type` /
`:affordance/role` still emitted unchanged. Only the *role-filter reads* migrated; the
`:entity/type`-enumeration reads remain for Phase 2 (`kind-of` / `family-of`).

**Deferred to Phase 2** (needs recursion + the `q`/view surface widened to thread `%` rules): registry
`:refines` + `:family/*` super-tags, `refines*` / `kind-of` / `family-of`, the partition checker, and the
`:entity/type`-enumeration consumers (coverage, identity, tar_pit Type/Module enumerations, patterns
`affordance-eids`, canvas_source `project-affordances`).

---

## Phase 2 — DONE (2026-05-30)

Full fan-out shipped, behaviour-preserving, in four commits:

1. **`feat(stratum): transitive kind-of/family-of`** — the refinement lattice as data (`:tagdef/refines`,
   derived from explicit `:refines` or the family scalar — zero edits to existing tag-defs), family
   super-tags as parent-less roots, and the `refines*` / `kind-of` / `family-of` recursive rules.
   classification-test executes the depth-2 dissolve test and pins datascript recursive-rule support.
2. **`feat(stratum): partition checker`** — integrity check #5: acyclic, no dangling parent, ≤1 family
   root per tag (earns back the single-valued invariant). Real lattice (14 tagdefs, 10 refines) clean.
3. **`feat(stratum): of-kind/family-of fns + widen agent q`** — `of-kind`/`family-of`/`element-kind` fns;
   the agent L0 `q` auto-threads the rule set when a query declares `%`; agent-api's own reads migrated.
4. **`refactor(stratum): migrate all consumers`** — every `:entity/type`/`:affordance/role` reader (lens,
   coverage, drift, integrity, identity, canvas_source, defquery+check) now goes through the stratum.

**Key architectural decision:** `with-canvas` seeds every substrate with the tag-definition lattice up
front, so `kind-of`/`family-of` work on *any* canvas db (not just enriched production dbs). `element-kind`
bridges the legacy `:Module/:Affordance/:Type/:State` vocabulary, *derived* from the stratum — i.e.
`:entity/type` is now a derived value, exactly the Phase 3 shape.

**Datascript notes (the "solve it" items, solved):** recursive rules work; the reflexive base needs
distinct head vars (`[(refines* ?t ?anc) [?td :tagdef/tag ?t] [(identity ?t) ?anc]]`); inline keyword
literals are rejected as recursive-rule args, so the family is passed via `:in` (or baked into a `[(= …)]`
predicate, as defquery does).

Verified: full suite 1075 tests, 0 failures (3 pre-existing `not yet implemented` placeholders in the
unrelated `distributed.cluster` trial). `store/->datoms` still emits `:entity/type`/`:affordance/role` —
the sole remaining emitter, and the **Phase 3** drop target (run the dissolve gate, then delete emission).

---

## Phase 3 — DONE (2026-05-30) — the dissolution is complete

`store/->datoms` no longer emits `:entity/type`/`:affordance/role`; the dead `:entity/type` schema entry
is removed. **Tag-applications are the sole classification truth.** The real model builds with **zero**
`:entity/type` and **zero** `:affordance/role` datoms (529 affordances, 63 modules, integrity clean) —
family/kind/role derive entirely through the classification stratum.

Completing the drop required:
- `store` query helpers (`all-modules`/`affordances-in`/`children-of-module`) migrated onto
  `kind-of`/`element-kind` (they were excluded from the earlier consumer audit).
- Registering `:canvas/state` (a substrate kind `sub/state` creates) so state nodes classify, and adding
  `:State` to the family map + the partition-checker's allowed-family set.
- Migrating every remaining **test** reader onto the stratum (demo smoke tests, vocab tests,
  store/helpers/construction/integrity/agent-api/canvas-source/project-coverage). Hand-crafted test dbs
  now carry the classification spine (tagapps + tagdefs); low-level test affordances use a registered role.

**Surfaced gap (by design):** the stratum classifies only **registered** tags. Nodes built via low-level
helpers with unregistered/absent tags (e.g. `sub/affordance` with no `:role`) lose classification — the old
index classified via `:node-kind` regardless. Production always uses registered tags, so this only touched
low-level substrate tests.

**The dissolve gate** is locked in permanently as `classification-test/dissolve-test`: a depth-2 second
classification (`:ddd/aggregate-root refines :ddd/entity refines :family/affordance`) declared as refinement
data only, queried by the same `kind-of`, with zero consumer/rule changes. It fails on regression.

**The 7-commit arc:** classification stratum (direct-kind) → lens role-reads → transitive kind-of/family-of
→ partition checker → of-kind/family-of fns + agent-q widening → migrate all consumers → drop emission.

---

## Post-mortem reflection — (c) vs (b), and settling the surface (2026-05-30)

A design reflection after the arc landed: was refinement (c) over named-views (b) worth it? Honest findings:

- **Depth is free, not fixed at 2.** `refines*` is arbitrary-depth recursive. But the *real* lattice is
  100% depth-1 (every concrete tag → its family super-tag directly). The transitive machinery is
  currently exercised only at depth-1; the only depth-2 case is the synthetic dissolve-test.
- **At depth-1, (c) ≡ (b) + ceremony.** The (c)-specific tax is `refines`/`refines*`/`kind-of` + family
  super-tags + **the partition checker** (which guards a multi-valued/cyclic failure mode that only
  *exists* because the lattice is general; (b)'s scalar family is single-valued by construction).
  Everything else (`family-of`, `direct-kind`, `of-family`, `element-kind`) is inherent to dissolving the
  index — (b) needs it too.
- **What (c) buys is entirely future:** the methodology vocabularies VISION.md anticipates (DDD
  aggregate-root is-a entity, hexagonal ports/adapters, C4 layers) are inherently is-a hierarchies that
  (b) can't express without re-foundationing. The bet: pay cheap ceremony now, make that future a
  one-liner (proven by the dissolve gate).

**Decision: keep the foundation, tidy the surface (option 1).** The "why two operators everywhere?" feel
came largely from over-using `kind-of` for flat family questions during migration (~50 sites). Fixed:
everyday consumer surface is now **`direct-kind`** (exact kind/role) + **`family-of` / `of-family`** (the
family); **`kind-of` / `refines*` / `of-kind`** are the transitive foundation, kept for hierarchical
vocabularies but invisible at call sites. Consumer code: 0 `kind-of` (was ~50), 48 `family-of`/`of-family`,
16 `direct-kind` — (b)'s flat-feeling surface over (c)'s refinement foundation. If methodology-modelling
turns out *not* to be fukan's direction, the remaining step is to collapse to pure (b) (drop
refines/super-tags/checker); until then the foundation is cheap insurance.
