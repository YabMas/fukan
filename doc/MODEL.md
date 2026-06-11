# Fukan — Model

**Status:** The substrate spec — *what the model is made of*.

**Companion to** [VISION.md](./VISION.md), [DESIGN.md](./DESIGN.md), and
[DECISIONS.md](./DECISIONS.md). The authoritative source is
`src/fukan/canvas/core/structure.clj`; this chapter is its prose.

---

## The substrate is the model

There is no separate model-map and no privileged kinds. The model is a single
**datascript db** of structure instances. A `defstructure` declares a structure as a
composition of slots plus the datalog laws that must hold of it; instances of those
structures, merged across all specs, *are* the model.

## Nodes, relations, and leaves

Everything reduces to three datom shapes:

- **Node** — an instance of some structure. `:structure/of <Tag>` records its kind
  (tags are namespace-qualified: identity = defining ns + name); `:entity/name` its
  name (derived from the binding var when not explicit); `:entity/doc` an optional
  docstring. A node owned by a module is reached via the owner's `:child`
  (ownership-on-owner — owned nodes carry no module back-reference).
- **Reified relation** — a slot whose target is another structure becomes a relation
  *entity*: `:rel/from`, `:rel/kind` (the slot keyword), `:rel/to`. Optional
  `:rel/label` (from an authored `[label target]` element) and `:rel/order`
  (authoring position, on sequence slots `[:* T]`/`[:+ T]`; a `[:set T]` slot
  records none and collapses duplicate targets).
- **Scalar leaf** — a slot whose target is a scalar type (`:Bool`, `:String`, …)
  stores its value directly as `:val/<slot>` on the node, with an auto-generated
  type-check law rather than a reified relation; a refined scalar (`[:enum …]`,
  `[:int {:min 1}]`) is checked through the registered type dialect. A scalar
  slot's optional `:payload` rides as a companion `:val/<payload>` datom on the
  same node.

## Value identity

A `^:value` structure is **content-deduped**: its `:entity/id` is a hash of its
content, so two structurally-equal values collapse to one node. Value nodes are
anonymous and ownerless — the canonical representation of nameless compound data
(list/record/shape descriptions). datascript dedups them on the content-keyed
`:entity/id`. A structure may declare a `(reader f)` so values author as native
data-literals, expanded per the target structure (symbol → leaf, `[X]` → list,
`{:f X}` → record).

## Laws and `check`

A law is a datalog constraint: `(law "desc" :offenders '[?x] :where '[…])`.
`(structure/check db)` runs every registered structure's laws and returns violations
(the offending bindings with the law's description).

- **Self-scoping.** By default a law is scoped to instances of its own structure
  (the engine injects the kind guard). `:scope :global` opts out — used by
  cross-cutting laws (e.g. correspondence) that quantify over other kinds.
- **Vocab-derived rules.** `check` derives datalog rules from the live vocabulary
  (`core/rules.clj`, pure) and injects them into every law's query: a kind rule per
  structure (`(Operation ?e)`), a relation rule per relation slot (`(calls ?a ?b)`),
  inclusion / realized-as rules, and fixed substrate rules (`in-module`, `named`).
  Laws therefore read in the vocabulary's own terms, not in raw `:structure/of` /
  `:rel/*` navigation.
- **Combinators.** The recurring law shapes — `(matched-by R …)`, `(has R …)`,
  `(has-any …)`, `(target R {k v})`, `(at-most-one R)` — are authored as
  `(law "desc" (combinator …))` and expand to datalog with negation routed through
  rules (the datascript empty-relation `not-join` gotcha lives in the kernel, once);
  the authored form rides the law as `:src` and round-trips through the print-dual.

## The registry

`defstructure` registers each structure (its slots, laws, value-ness, reader,
syntax hook) in a global table, keyed by the **namespace-qualified tag**, and
defines an instance macro mirroring defstructure's own shape: `(Structure name
"doc"? {slot → value}? nested…)` is a top-level def-emitting form (the symbol is
the var and the entity name); without the name symbol it is an anonymous
expression instance. References are ordinary var references; the global assembler
(`assemble-vars`) scans the interned vars into one db. `all-structures` and `vocab-rules` expose the
registry as data — and the registry is also **reflected onto the graph itself** on
every build (`lib.grammar/with-grammar`): Structure nodes, slots as
`:slot/<card>`-kinded labeled edges, laws as payload-carrying nodes — so the
language has no off-graph remainder and renders back as source (the print-dual,
`fukan.canvas.projection.grammar`).

## Assembly and cross-spec references

There is no per-spec db and no merge pass: `canvas_source` requires every
discovered canvas namespace and the **global assembler** walks all interned
instance vars into one db (nodes first, then relations, so lookup-refs resolve
across cycles). References between specs are ordinary var references — identity is
the qualified var name — so cross-namespace cycles are inexpressible by
construction. `union-dbs` remains only to fold an extractor's code db onto the
assembled design db.

## Acts through a lens — probe and projection

Acts on the graph are **complementary** — analysis (probe) and synthesis (projection)
— composing through a shared **focus** (sub-graph):

- **`evaluate-lens`** runs a Lens's selection `:query` (a datalog `:where`
  clause-vector binding `?n`) against the vocab-derived rules → the focus node-set
  (the sub-graph being those nodes and their induced relations). One expression
  handles both selection and traversal; `refine` narrows a focus by a further query
  (set-intersection) so a refined focus chains forward into a probe or projection.
- A **Probe** reads the graph through a lens and yields a **Finding** — a list of
  sub-graphs of interest. Observations carry `{:focus … :as … :note …}`. `check` is
  the canonical integrity probe.
- **`render-base` / `materialize-view`** (`model/materialize.clj`) are the projection
  surface: `render-base` is a multimethod on `[base kind]` (the projection base × the
  node's `:structure/of`), composing along references by re-dispatch under the same
  base; a *contextualization* projection renders through a base and frames its output.
  `compose` / `materialize-view` / `materialize-projection` compose the renders over a
  lens's focus.

## The build pipeline

`model/pipeline.clj`'s `build-model code-root`:

1. `canvas-source/build` — discover and load the `canvas/` design specs, assemble
   their instance vars into one db.
2. When `code-root` exists **and** an extractor is registered
   (`model/extraction.clj`), run it and fold the extracted code structures onto the
   same graph.
3. `lib.grammar/with-grammar` — reflect the registry onto the graph (the model's
   grammar is part of the model).

`(structure/check db)` over the result runs all laws — including the correspondence
laws — so model↔code drift surfaces as violations on one graph. The legacy
Allium/Boundary parse phases and the old Phase 4–6 analyzer are retired.

---

*See [DECISIONS.md](./DECISIONS.md) for why the substrate has this shape.*
