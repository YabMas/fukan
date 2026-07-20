# Fukan — Theory

**Status:** The theoretical foundations — what fukan's mechanisms are instances *of*.

**Companion to** [VISION.md](./VISION.md) (the why), [DESIGN.md](./DESIGN.md) (the
design principles), [MODEL.md](./MODEL.md) (the substrate spec), and
[DECISIONS.md](./DECISIONS.md) (the decision trace).

---

## One sentence

Fukan is a workbench for **theory presentations and theory morphisms** — the
algebraic-specification tradition running from Burstall–Goguen's *putting theories
together* to institutions — in which the one concrete logic every sentence is
written in is **Datalog over a typed relational model**.

Everything in fukan is an instance of one of two pillars:

- **The meta-theory: presentations & morphisms.** What a specification *is* (a
  signature plus sentences), what relates specifications (morphisms — maps that
  carry one theory's vocabulary into another's and induce proof obligations), and
  what it means for a model to satisfy one (⊨). This pillar answers what kind of
  *thing* every fukan construct is.
- **The object logic: Datalog.** The deductive-database tradition — stratified
  negation, semi-naive fixpoint evaluation, definitional extensions. Every law,
  demand, derived rule, and query compiles to this one logic (CozoScript over the
  typed-EAV view). Within it one sub-language is deliberately carved out: the
  **Kleene fragment** — regular relations (`:cat` / `:*` / `:+` / `:?` / `:alt`,
  the shape of SPARQL property paths) — allowed *inline* in a relation map;
  anything recursive must graduate to a *named* definitional extension
  (`defrelation`). The fragment line is a rule of the authoring surface, not a
  convention.

The pillars compose cleanly because the first never dictates a logic — institutions
are logic-independent by design — and the second is fukan's chosen instantiation.

An honesty note the frame requires: fukan today is **one** institution — Datalog
over the typed-EAV substrate. The type-dialect SPI parameterizes the *sort
universe* (base scalar sorts plus refinements), not the sentence language. "The
logic is a plug-point" is a door the frame leaves open, not a current capability;
claiming more would be decoration.

## The map

Every mechanism, with its reading. This table is the frame's contract with the
codebase: a mechanism that cannot name its row is suspect.

| Construct | Reading |
|---|---|
| the model db (Cozo, typed EAV) | a finite relational structure — the *model*, in the Tarskian sense |
| `defstructure` | a **theory presentation**: a signature fragment (a sort, its relations with cardinalities) plus axioms |
| `law` + `check` | **sentences** + the satisfaction relation ⊨; a violation is a counterexample witness |
| generated laws (type checks, cardinality, correspondence demands) | derived sentences of the same presentation — one sentence kind, never a second mechanism |
| derived `defrelation` | a **definitional extension** — a named (possibly recursive) rule, conservative over the presentation |
| relation character (`:isa`, `:transitive`) | subrelation **inclusion** / Kleene closure — a morphism in miniature, inside one theory |
| `correspond` | a **theory morphism**: a sort map with codomain restriction (`Operation :eq [Fn :public]`), relation maps with a direction (`:sub` ⊑, `:sup` ⊒, `:eq` ≡), a carrier correlation (`bridge`), and *generated* satisfaction laws — the morphism's proof obligations, run by `check` like any sentence |
| extraction | the semantics side of the seam: artifact → a model of the fact theory |
| `^:value` structures | **free (initial) types** — terms, identified up to structural equality, no junk and no confusion; plain structures are **loose sorts** — entities with individual identity. (CASL draws exactly this line: `free type` vs `sort`.) |
| the type-dialect SPI | the sort universe as a parameter: base sorts and predicate subsorts supplied by a plugin |
| inline path `E` vs named `defrelation` | the decidable Kleene fragment stays inline; recursion must be named — the fragment line, enforced |
| Lens / Projection / Check | a specification's three classical relations to a model: restriction (a view), interpretation (a rendering), satisfaction (a gate) |
| grammar reflection | the frame **internalized**: the meta-grammar (`Structure` / `Law` / `Vocabulary` / `Relation`) is a presentation of "presentation", so the grammar is data on the same graph it governs |
| the grouping ladder, refinement chains | theory **extensions** — a larger theory conservatively including a smaller one |

## The test

The frame earns its keep as a gate on growth. Every proposed mechanism must answer
one question before it exists:

> **Is it a presentation, a sentence, a definitional extension, or a morphism?**

If it is one of these, it should *look like* the existing instances of its row —
same declaration shape, same generated-law discipline, same reflection. If it is
none of them, the burden of proof is on the mechanism: most "none of the above"
proposals are one of the four wearing a costume, and the costume is debt. (The
correspondence DSL's history is the cautionary tale: six bespoke demand forms
existed precisely because the morphism had no codomain — restoring the missing
theoretical part collapsed the DSL, not the other way around.)

The companion discipline still binds ([DESIGN.md](./DESIGN.md)): the frame says
what a mechanism *is*, never that it should exist. Vocabulary and mechanism alike
grow only under concrete modelling pressure.

## Known deviations

Tracked here so the fundament carries no silent holes.

- **The signature is implicit and global.** Institutions begin with a category of
  signatures; fukan's registry is one flat tag namespace. Structure tags are
  namespace-qualified, but a relation *element's* tag is unqualified — its datalog
  rule name is global — so two vocabularies declaring the same relation name
  collide silently, and law scoping rides short-name rules. Vocabularies exist as
  reflected rendering units, not as signatures with identity and inclusions.
  **Being closed** — signatures-first-class is in progress.
- **~~The morphism is under-reflected.~~ Closed.** A correspondence now reflects
  as a `Morphism` node in the meta-grammar (`:from`/`:to` edges to its domain and
  codomain `Structure`s, the inclusion / restriction / bridge as queryable fields,
  `RelationMap` children for the relation maps), and a derived `defrelation`
  carries its defining rule on its `Relation` node — the morphism is data, like
  the presentations it connects.
- **One logic.** As noted above: the object logic is Datalog, unparameterized.
  Left open deliberately; not scheduled.

## Lineage

The short shelf behind the two pillars:

- Burstall & Goguen, *Putting Theories Together to Make Specifications* (1977) —
  specifications compose from small theories; the origin of pillar 1.
- Goguen & Burstall, *Institutions: Abstract Model Theory for Specification and
  Programming* (1992) — signatures, sentences, models, ⊨, and morphisms,
  logic-independently.
- Sannella & Tarlecki, *Foundations of Algebraic Specification and Formal Software
  Development* (2012) — the modern synthesis: presentations, free vs loose
  semantics, views, refinement.
- Ceri, Gottlob & Tanca, *What You Always Wanted to Know About Datalog (And Never
  Dared to Ask)* (1989) — pillar 2's charter.
- Kozen, *Kleene Algebra with Tests* (1997), and SPARQL 1.1 property paths — the
  shape of the inline path fragment and the public-call-graph quotient.
