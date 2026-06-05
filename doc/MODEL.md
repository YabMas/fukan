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

- **Node** — an instance of some structure. `:structure/of <Tag>` records its kind;
  `:entity/name` its name; `:entity/doc` an optional docstring. A node owned by a
  module is reached via the owner's `:module/child` (ownership-on-owner — owned
  nodes carry no module back-reference).
- **Reified relation** — a slot whose target is another structure becomes a relation
  *entity*: `:rel/from`, `:rel/kind` (the slot keyword), `:rel/to`. Optional
  `:rel/label` (from an authored `[label target]` clause) and `:rel/order` (for
  `ordered` slots, position). A not-yet-resolved cross-module target is held as
  `:rel/to-ref [module name]` and resolved post-merge.
- **Scalar leaf** — a slot whose target is a scalar type (`:Bool`, `:String`, …)
  stores its value directly as `:val/<slot>` on the node, with an auto-generated
  type-check law rather than a reified relation. A scalar slot's optional `:payload`
  rides as a companion `:val/<payload>` datom on the same node.

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
  structure (`(Stage ?e)`), a relation rule per relation slot (`(calls ?a ?b)`), and
  fixed substrate rules (`in-module`, `named`). Laws therefore read in the
  vocabulary's own terms, not in raw `:structure/of` / `:rel/*` navigation.

## The registry

`defstructure` registers each structure (its slots, laws, value-ness, reader) in a
global table. `with-structures` / `within-module` author instances against the
registered macros; `within-module` emits `:module/child` automatically.
`all-structures` and `vocab-rules` expose the registry as data — the latter is what
`check` and `evaluate-lens` inject. The registry is a **single global namespace**:
co-loaded projects cannot share a tag name (a standing finding; per-project
namespacing deferred).

## Merge and cross-module references

Each spec builds its own per-spec db; `canvas_source` merges them into one
(`merge-dbs`). The merge is **schema-driven**: `db->entity-maps` carries every
identity-bearing entity across without per-attribute knowledge — scalar attrs as
maps (cardinality-many accumulated as sets), ref-typed attrs translated to identity
lookup-refs in a second pass — so per-db eids never leak across the boundary.
`resolve-cross-refs` then resolves every deferred `:rel/to-ref` against the merged
db (`[module]` → that Module node; `[module name]` → that module's named child),
throwing on an unresolved reference.

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

1. `canvas-source/build` — discover, load, build, and merge the `canvas/` design
   specs (resolving cross-refs).
2. When `code-root` exists **and** an extractor is registered
   (`model/extraction.clj`), run it, merge the extracted code structures onto the
   same graph, and re-resolve cross-references.

`(structure/check db)` over the result runs all laws — including the correspondence
laws — so model↔code drift surfaces as violations on one graph. The legacy
Allium/Boundary parse phases and the old Phase 4–6 analyzer are retired.

---

*See [DECISIONS.md](./DECISIONS.md) for why the substrate has this shape.*
