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

The stratum is registry-aware → it **cannot** live in `core/` (core depends on nothing in `canvas/`). It
belongs at the **vocab/construct-kit tier** — likely a `strata`/`view` sibling to `construct`, reading the
registry + the substrate db. The partition checker lives in the validation vocab.
