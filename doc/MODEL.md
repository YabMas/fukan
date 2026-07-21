# Fukan — Model

**Status:** The substrate spec — *what the model is made of*.

**Companion to** [VISION.md](./VISION.md), [DESIGN.md](./DESIGN.md), and
[DECISIONS.md](./DECISIONS.md). The authoritative source is
`src/fukan/canvas/core/structure.clj`; this chapter is its prose.

---

## The substrate is the model

There is no separate model-map and no privileged kinds. The model is a single
**Cozo db** of structure instances — CozoScript (datalog) is what every query, law,
and reader compiles to, over a typed-EAV view. A `defstructure` declares a
structure as a composition of slots plus the datalog laws that must hold of it;
instances of those structures, merged across all specs, *are* the model.

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
- **Scalar leaf** — a slot whose target is a scalar type (`:boolean`, `:string`, …)
  stores its value directly as `:val/<slot>` on the node, with an auto-generated
  type-check law rather than a reified relation; a refined scalar (`[:enum …]`,
  `[:int {:min 1}]`) is checked through the registered type dialect. A scalar
  slot's optional `:payload` rides as a companion `:val/<payload>` datom on the
  same node.

## Value identity

A `^:value` structure is **content-deduped**: its `:entity/id` is a hash of its
content, so two structurally-equal values collapse to one node. Value nodes are
anonymous and ownerless — the canonical representation of nameless compound data
(list/record/shape descriptions); the build dedups them on the content-keyed
`:entity/id`, across strata too (a design signature's `Schema` and its extracted
twin's are ONE node). A structure may declare a `(reader f)` so values author as
native data-literals, expanded per the target structure (symbol → leaf, `[X]` →
list, `{:f X}` → record).

## Laws and `check`

A law is a datalog constraint: `(law "desc" {:offenders [?x] :where […]})` — one
unquoted map (declaration forms never quote). `(structure/check db)` runs every
registered structure's laws and returns violations (the offending bindings with
the law's description; a `:key` makes a law addressable by worklist readers, which
throw on an unknown key).

- **Self-scoping.** By default a law is scoped to instances of its own structure
  (the engine injects the kind guard). `:scope :global` opts out — used by
  cross-cutting laws (e.g. the generated correspondence demands) that quantify
  over other kinds.
- **Vocab-derived rules.** `check` derives datalog rules from the live vocabulary
  (`core/rules.clj`, pure) and injects them into every law's query: a kind rule per
  structure (`(Operation ?e)`), a relation rule per relation slot (`(calls ?a ?b)`),
  inclusion / realized-as / defrelation rules, and the fixed substrate rules
  (`named`, the `design`/`fact` strata); membership (`contains`, the by-name
  `within`) rides vocab relation elements, and every binary relation's transitive
  closure `R+` is compiler-minted, injected only where referenced. Laws therefore
  read in the vocabulary's own terms, not in raw `:structure/of` / `:rel/*`
  navigation.
- **Combinators.** The recurring law shapes — `(matched-by R …)`, `(has R …)`,
  `(has-any …)`, `(target R {k v})`, `(at-most-one R)` — are authored as
  `(law "desc" (combinator …))` and expand to datalog emitting `not-join` directly
  (the Cozo query compiler lowers stratified negation correctly); the authored form
  rides the law as `:src` and round-trips through the print-dual.
- **Measures.** An inline `(measure ?out (agg ?var) body…)` clause aggregates
  inside a law or query body; a defrelation head may itself carry an aggregate
  (`[?m (count ?op)]`), making the derived relation a measure.

## The registry

`defstructure` registers each structure (its slots, laws, value-ness, reader,
syntax hook) in a global table, keyed by the **namespace-qualified tag**, and
defines an instance macro mirroring defstructure's own shape: `(Structure name
"doc"? {slot → value}? nested…)` is a top-level def-emitting form (the symbol is
the var and the entity name); without the name symbol it is an anonymous
expression instance. `defrelation` registers a relation ELEMENT under its
unqualified tag (the rule name is global, so the name is signature identity — a
second namespace re-declaring it throws). External declarations hook a concept
from outside: `(correspond Design …)` registers a correspondence morphism against
the design tag; `register-syntax!` an authoring hook. `all-structures` and
`vocab-rules` expose the registry as data — and the registry is also **reflected
onto the graph itself** on every build (`fukan.canvas.core.reflect/with-grammar`):
`Structure` nodes with slots as `:slot/<card>`-kinded labeled edges and laws as
payload-carrying nodes, a `Vocabulary` node per namespace (a signature: owned
relations + derived `:imports`), a decomposed `Morphism` node per correspondence —
so the language has no off-graph remainder and renders back as source (the
print-dual, `fukan.canvas.projection.grammar`).

## Assembly and cross-spec references

There is no per-spec db and no merge pass: `canvas_source` requires every
discovered spec namespace (`*spec-dirs*`, default `["canvas"]`) and the **global
assembler** walks all interned instance vars into one db (nodes first, then
relations, so lookup-refs resolve across cycles). References between specs are
ordinary var references — identity is the qualified var name — so cross-namespace
cycles are inexpressible by construction. `union-dbs` remains only to fold an
extractor's code db onto the assembled design db.

## The act grammar — Lens, Projection, Check

`fukan.canvas.core.lens` owns the act structures and their evaluation engine
(dormant as a projection surface since the downward cut, live for inspection):

- **`focus-nodes`** runs datalog `:where` clauses (binding `?n`) with the
  vocab-derived rules → the focus node-set; the sub-graph is those nodes and their
  induced relations. `(path ?from E ?to)` composes relation paths (the shared
  regular-expression language E); `(via R Scope P)` transports a property along a
  relation's closure.
- **`evaluate-lens`** runs a Lens's own stored `:select`; a prose-only lens yields
  `nil` (not evaluable — a Maybe, never a throw). **`projection-focus`** resolves a
  Projection's focus three ways: its inline `:select`, its `:through` Lens, or —
  absent both — the whole model. **`refine`** narrows a focus by a further query
  (set-intersection), so acts chain over a refined focus.
- **`run-checks`** evaluates every `Check`: a Check gates a Lens, and a non-empty
  focus is a violation — the use-side dual of `structure/check`'s law violations.

## The build pipeline

`model/pipeline.clj`'s `build-model code-root`:

1. `canvas-source/build` — discover and load the project's instance specs
   (`*spec-dirs*`), assemble their instance vars into one db. (The `fukan.common`
   grammar tier loads by require at the composition root, not by discovery.)
2. When `code-root` exists **and** an extractor is registered
   (`model/extraction.clj`), run it and fold the extracted fact structures
   (`Ns`/`Fn`) onto the same graph, stamping the fact stratum at the merge.
3. `reflect/with-grammar` — reflect the registry onto the graph (the model's
   grammar is part of the model).

`(structure/check db)` over the result runs all laws — including the generated
correspondence demands — so model↔code drift surfaces as violations on one graph.

---

*See [DECISIONS.md](./DECISIONS.md) for why the substrate has this shape.*
