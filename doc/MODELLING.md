# Fukan — Modelling

**Status:** How to reason about a modelling problem with the primitives — which
construct answers which question, and why.

**Companion to** [THEORY.md](./THEORY.md) (what the mechanisms are instances of),
[DESIGN.md](./DESIGN.md) (the authoring surface), [MODEL.md](./MODEL.md) (the
substrate spec), and [DECISIONS.md](./DECISIONS.md) (the decision trace).

This document deliberately does NOT list the constructs or their spellings.
`grammar-primer`, `vocabulary-primer` and `correspondence-card` render those from
the live registry and cannot drift; DESIGN.md owns the surface. What lives here is
the part no projection can generate: which one to reach for.

---

## The frame

Every modelling question in fukan is the same question at two arities. **A sort is
a unary predicate. A relation is a binary predicate.** Nothing else is a thing.
So "how do I model X" always reduces to: *which predicate, and defined how* —
stored as facts, or derived by a rule from other predicates.

A sort has no representation. It is a set of entities, not a record type. That is
what makes every rule below fall out rather than needing to be remembered.

## Products are slots. Sums have three spellings.

A structure's slots are a product — the fields an instance carries. There is no
choice to make there.

A **sum** — "any of these sorts may appear here" — has three spellings that all
lower through `pin-clause` into the same generated target-type law. They are not
alternatives on the merits; they differ in **who must be edited when a case is
added**:

| Spelling | Declared at | Adding a case means editing |
|---|---|---|
| `{:child [:* Fn Method]}` | the use site | every slot that names the union |
| `(eq [(or-join …)])` | the union | the union's own declaration |
| `(sub Callable)` on each case | the member | nothing but the new case |

Enumerate at the use site when the union is local and small — one slot, two or
three targets, no law wants to range over it. Name it with `(eq …)` when several
places need the same union and the cases are known. Reach for `(sub …)` when new
cases must be able to join **without editing anything that already exists** —
which is the only thing the other two cannot do.

`(eq …)` is a closed sum; `(sub …)` is an open one. That is the whole distinction,
and it is the expression problem rather than a question about hierarchy.

## Data lives on cases, never on the sum

An `(eq …)` sort has no instances of its own — nothing carries its tag — so the
kernel refuses to let it declare slots, a reader, or `^:value`. This is not a
restriction to work around. A derived sort re-cuts entities that already exist;
there is nothing for a slot value to hang on.

So when a payload belongs to a member of a union, it belongs to a **stored**
sort, and the union is what ranges over it. If you find yourself wanting to put a
field on the sum, the sum is standing where a case should be.

## A genus states obligations, not fields

The two law-generation sites pin their subject differently, and the asymmetry is
the design:

- **Slot laws** pin the owner as a literal `:structure/of` triple, so a genus's
  slot laws never reach a species. Slot inheritance works by re-generating those
  laws under the species' own tag — the slot list is copied down.
- **Free `(law …)` forms** take their scope from `pin-clause`, which for a genus
  is its kind rule. A genus's free laws therefore reach **every** species.

Representation does not inherit; obligation does. So a genus cannot tell you what
a member holds — it tells you what a member must **satisfy**, and each case may
satisfy it by whatever structure it likes. That is the half worth using: two sorts
can both answer "has exactly one signature" without sharing a single slot.

Corollary: **do not put a shared slot on a genus you intend to specialise.**
Restating an inherited slot is refused — on the slot NAME, so tightening a target
or a cardinality is refused too — and a species that needs a narrower version has
nowhere to put it. Put the shared *constraint* in a genus law and let each case
declare its own slot.

## Never retrofit a genus onto a sort that has laws

A kind predicate is open. Declaring `(sub G)` adds a rule body to `G`'s
membership, so **every law already scoped to `G` silently widens** to the new
species. The mechanism is right — a law about a sort is a law about its members —
but the laws were written when `G` meant something narrower, and nothing in the
diff shows them changing.

So when an existing concrete sort needs to become one case of several, introduce
the genus **above** it rather than refining it. `Fn` keeps its rule, every law
scoped to `Fn` stays exactly as narrow as its author meant, and the laws that
should range over both get rescoped to the new genus one at a time, visibly.

## One genus per sort

A sort names at most one genus, because slot inheritance from two parents has no
resolution order. Where a sort genuinely belongs to two unions, make at least one
of them an `(eq …)` sort defined over the members — a closed sum imposes nothing
on its cases, so it can overlap freely with anything.

## A genus is still instantiable

A stored sort that happens to be a genus keeps its constructor: nothing marks a
sort abstract. If a genus should have no direct instances, either give it derived
membership — `(eq …)` interns no constructor, which is abstractness by
construction — or state the intent as a law over the genus.

---

## The short version

1. Slots for products. For sums, ask who gets edited when a case is added.
2. Data on cases, never on the sum.
3. Shared constraints go in a genus law; shared slots do not go in a genus.
4. Introduce a genus above a concrete sort; never refine one that has laws.
5. Whatever the primer can render, let it render — this file is for the choices.
