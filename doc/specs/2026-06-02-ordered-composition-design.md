# Ordered composition — sequence-bearing slots (design)

Status: design agreed 2026-06-02. A modelling-exploration cycle on the lean
kernel. **Uncommitted planning artifact** (gitignored).

## Why

Gap `#2`, the last surfaced gap: slots are unordered sets, so a sequence — a
grammar production's RHS, an AST node's children — can't be said. It is the only
gap that never *blocked* a model (grammar modelled `:produces` unordered and its
reachability law worked), and order is now *expressible* via index-wrapper values
(leaf-scalar `:pos`). So this is an **ergonomic** primitive over a workaround, not
an expressiveness gap — kept minimal accordingly.

## Decisions (settled with the user)

- **Vector authoring.** Order is authored with a Clojure vector — `(produces
  [A B C])` — leveraging vector semantics as the natural ordered literal.
- **`(ordered T)` cardinality.** A slot declares itself a sequence. This makes
  "ordered" a *schema* fact (enforced, not per-author-whim) and disambiguates a
  vector arg from the `[label target]` label form (an ordered slot reads its
  vector as a sequence, never as a label).
- **`:rel/order` storage.** The substrate is datoms, not vectors, so order is
  captured as a small integer index on each element's reified relation, assigned
  by vector position. Chosen over value-encoding (cons cells / indexed records)
  because it keeps BOTH access patterns cheap: "what are the elements" is the same
  relation query as today; "in order" is a sort by `:rel/order`. Value-encoding
  would force even basic element queries into a traversal — worse for an explorer.
- Ordered elements are **unlabeled** (order replaces labels; labelled-and-ordered
  is deferred).

## Mechanism (all in `canvas/core/structure.clj`)

- **Schema:** add `:rel/order {}` (a plain per-relation integer).
- **`(ordered T)`** parses via the existing generic `parse-slot` (`:card
  :ordered`, `:target T`) — no parse-slot change.
- **`clause->rels` (new, shared):** resolve one clause of an instance into
  relation specs `{:rk :label :order :tid}`:
  - ordered slot → splice vector args (`(mapcat #(if (vector? %) % [%]) args)`)
    and `map-indexed` → `:order` 0,1,…, `:label` nil;
  - other slot → today's per-arg `parse-clause-arg` (`:order` nil).
  Resolves each target via `resolve-target`; throws on an unresolved name.
- **`emit-relations!` (entity path)** and **`construct-value!` (value path)** both
  consume `clause->rels`; each transacts its relation carrying `:rel/order` when
  present (`order` is an int; 0 is truthy in Clojure, so `(cond-> … order (assoc
  :rel/order order))` is correct).
- **Value-identity:** `construct-value!`'s content key includes `:order` in each
  rel triple, so `[A B]` ≠ `[B A]` as values; the deterministic value `:rel/id`
  includes `:order` too (so the ordered relations of a deduped value don't clash).
- **Guard:** `defstructure` rejects `ordered` on a scalar slot (joins the
  `some`/`many` guard) — scalar storage is cardinality-one.
- **Laws:** `ordered` behaves like `many` — `relation-slot-laws`' `cond->` matches
  none of `:one`/`:some`/`:optional`, yielding just the target-type law. No change.

## Testing

Core (`structure_test.clj`), an ordered test slot:
- `(produces [A B C])` emits three `:produces` relations with `:rel/order` 0/1/2;
  recovering the sequence (sort by `:rel/order`) gives `[A B C]`.
- a 2-element ordered vector `[A B]` is a sequence, NOT a label (regression for
  the disambiguation).
- ordered value-identity: a `^:value` structure with an ordered slot — `[A B]`
  and `[B A]` are distinct nodes; `[A B]` authored twice dedups to one.
- `ordered` on a scalar slot throws at `defstructure` expansion.

End-to-end: retrofit `demos/grammar` `Symbol :produces` → `(ordered Symbol)`,
author a production RHS as a vector, assert `:rel/order` captures the sequence;
the order-agnostic reachability law (`derives`, querying `:rel/kind :produces`)
keeps passing unchanged.

## Out of scope

Labelled-and-ordered slots, ordered scalars, multi-clause order accumulation,
order-constraint laws — deferred to concrete need.
