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
  the shape of SPARQL property paths) — ONE expression language with three inline
  homes: a correspondence relation map, an inclusion element, and a law/lens
  `(path ?from E ?to)` clause. Recursion beyond simple closure (closure over a
  compound) must graduate to a *named* definitional extension (`defrelation`).
  The fragment line is a rule of the authoring surface, not a convention.

The pillars compose cleanly because the first never dictates a logic — institutions
are logic-independent by design — and the second is fukan's chosen instantiation.

An honesty note the frame requires: fukan today is **one** institution — Datalog
over the typed-EAV substrate. The type-dialect SPI parameterizes the *sort
universe* (base scalar sorts plus refinements), not the sentence language. "The
logic is a plug-point" is a door the frame leaves open, not a current capability;
claiming more would be decoration.

A second honesty note, stating a choice rather than a hole: fukan has **no proof
theory**. The only judgment anywhere is ⊨, decided by evaluation over a finite
structure — laws, generated demands, and a morphism's proof obligations alike are
*model-checked*, never derived. The workbench's claims are about one concrete
model, and evaluation decides them; a ⊢ is not missing, it is not the instrument.

## The map

Every mechanism, with its reading. This table is the frame's contract with the
codebase: a mechanism that cannot name its row is suspect.

| Construct | Reading |
|---|---|
| the model db (Cozo, typed EAV) | a finite relational structure — the *model*, in the Tarskian sense |
| instance forms + the assembler | a **finitely presented model**: ground atoms authored position-for-position against the presentation; identity = the qualified var name |
| `defstructure` | a **theory presentation**: a signature fragment (a sort, its relations with cardinalities) plus axioms |
| `law` + `check` | **sentences** + the satisfaction relation ⊨; a violation is a counterexample witness |
| generated laws (type checks, cardinality, correspondence demands) | derived sentences of the same presentation — one sentence kind, never a second mechanism |
| the authoring surface — instance macros, law combinators, `(reader f)` / `(syntax f)` hooks, `bridge` strategies | **derived forms**: notation elaborating at authoring time into the four kernel things — eliminable and non-creative, or it isn't notation (see "Growing the language") |
| derived `defrelation` | a **definitional extension** — a named (possibly recursive) rule, conservative over the presentation |
| relation inclusion `(defrelation :r "…" (:sub E))` | a subrelation **inclusion** — the SAME (relation, direction, expression) triple a correspondence relation map states. Within one theory it lowers *generatively* (a rule: the included relation accumulates the edges); at the correspondence seam the same triple lowers as a *checked* law — the keystone's two halves ("a theory morphism IS a derived datalog rule; satisfaction IS a scoped check") |
| `correspond` | a **theory morphism**: a sort map with codomain restriction (`Operation :eq [Fn :public]`), relation maps with a direction (`:sub` ⊑, `:sup` ⊒, `:eq` ≡), a carrier correlation (`bridge`), and *generated* satisfaction laws — the morphism's proof obligations, run by `check` like any sentence |
| extraction | the semantics side of the seam: artifact → a model of the fact theory |
| the design⊕code merge (`union-dbs`) | **model amalgamation** over the signature sum — the one-graph thesis under its formal name: the assembled design model and the extracted fact model amalgamate into one structure both theories constrain |
| `^:value` structures | **free (initial) types** — terms, identified up to structural equality, no junk and no confusion; plain structures are **loose sorts** — entities with individual identity. (CASL draws exactly this line: `free type` vs `sort`.) |
| the type-dialect SPI | the sort universe as a parameter: base sorts and predicate subsorts supplied by a plugin |
| inline path `E` vs named `defrelation` | the **regular** (Kleene) fragment stays inline; recursion beyond it must be named — the fragment line, enforced. (Over a finite model everything is decidable — the line is regular vs general recursion, the shape of regular path queries.) Transitive closure belongs to the fragment, so it is the **compiler's**: every binary relation's `R+` is minted unconditionally and injected only where referenced — nothing declares "transitive" |
| `measure` clauses | **stratified aggregation** — pillar 2's standard extension, sentence language not sugar: an inline aggregate lifts to an auxiliary rule head at compile time |
| Lens / Projection / Check | a specification's three classical relations to a model: restriction (an induced substructure), interpretation (a rendering), satisfaction (a gate) |
| grammar reflection | the frame **internalized**: the meta-grammar (`Structure` / `Law` / `Vocabulary` / `Relation` / `Morphism` / `RelationMap`) is a presentation of "presentation" — and of "morphism" — so the grammar and its seams are data on the same graph they govern; a `Vocabulary` reflects as a signature with owned relations and derived `:imports` |
| the grouping ladder, refinement chains | theory **extensions**, in two modes the frame distinguishes: **definitional** — a defined relation (`:sup`/`:eq`, a derived rule): conservative, eliminable — and **accumulative** — an open genus fed by downstream inclusions (`:child` ⊑ `:contains` *adds edges* to `contains`): non-conservative by design. An open head is Datalog's native extension point; a bare `defrelation` genus is the declaration of openness, a defined relation is closed |

## The test

The frame earns its keep as a gate on growth. Every proposed mechanism must answer
one question before it exists:

> **Is it a presentation, a sentence, a definitional extension, a morphism — or a
> derived form?**

If it is one of the first four, it should *look like* the existing instances of
its row — same declaration shape, same generated-law discipline, same reflection.
If it is a derived form — notation — it must **eliminate**: expand into the four
with no remainder and no meaning of its own ("Growing the language" below carries
the criterion). If it is none of the five, the burden of proof is on the
mechanism: most "none of the above" proposals are one of the five wearing a
costume, and the costume is debt. (The correspondence DSL's history is the
cautionary tale, re-read by the criterion: six bespoke demand forms were
*creative* notation — meaning with no kernel expression, because the morphism had
no codomain. Restoring the missing theoretical part collapsed the DSL, not the
other way around.)

The companion discipline still binds ([DESIGN.md](./DESIGN.md)): the frame says
what a mechanism *is*, never that it should exist. Vocabulary and mechanism alike
grow only under concrete modelling pressure.

## Growing the language

[VISION.md](./VISION.md) names the premise — *bottom-up language building,
top-down design*, the Lisp tradition of stratified languages. This section gives
the premise the same formal standing as the rest of the frame. The claim:
**"growing a language" is the dynamics of pillar 1.** Burstall–Goguen's *putting
theories together* describes what a grown language *is*; Steele's *Growing a
Language* describes the act of growing one. No third pillar. Growth happens in two
places, is observed in a third, and is safe because of a fixed point:

**Growth in theories — the library.** A vocabulary is a presentation; growing the
language is extending a *diagram* in the category of theories. The grouping
ladder, the code grammar, the pattern tier above it, a project's own vocabulary —
each is a node in that diagram, and the reflected `Vocabulary`'s derived
`:imports` edges are the diagram *internalized*: the growth structure is data on
the graph it grew. The two extension modes (definitional vs accumulative — see
the map) are the two Lisp growth moves under their formal names: defining a new
word (closed, conservative, eliminable) and opening a generic that later
vocabularies feed (an open genus — Datalog's open head as the declared extension
point).

**Growth in notation — derived forms.** Everything the authoring surface adds
above the kernel's four things — the instance macros mirroring `defstructure`,
the law combinators, `(reader f)` / `(syntax f)` hooks, `bridge` strategy
keywords — is a **derived form**: notation elaborating at authoring time into
presentations, sentences, extensions, or morphisms. The classical theory of
definitions supplies the discipline a macro system needs: a derived form must be
**eliminable** (it expands away without remainder) and **non-creative** (it
expresses nothing the kernel forms cannot). Steele's actual thesis — grown words
must be indistinguishable from primitives — is the generated-law discipline ("one
sentence kind, never a second mechanism") extended from semantics to syntax. A
**phase line** rides with it: derived forms elaborate strictly before assembly,
and may consult only the *signature* (the registry), never the *model* — notation
cannot peek at satisfaction. (The REPL's defonce-registry caveats — the collision
guard tripping on a moved `defrelation` — are leaks across this phase line: a
named cost of interactive growth, not a mystery.)

**Growth observed — homoiconicity.** Reflection is quotation: the meta-grammar is
a presentation of "presentation", so the grown language — vocabularies, morphisms,
the imports diagram — is data on the same graph it governs. The print-dual is
unquotation, and two properties make "code is data" a property rather than a
slogan: **adequacy** — every registry element reflects — and **faithfulness** —
reflect-then-print round-trips to the authored form (the print-dual round-trip
tests and the declarations golden are this theorem's suite).

The premise's other half — top-down design — needs no new seat: design pressure
*is* sentences, and holding an implementation to them *is* the morphism's
obligations.

**The fixed point — the logic never grows.** Lisp keeps `eval` small and pushes
all growth into libraries and macros; fukan keeps the institution fixed and pushes
all growth into theories and derived forms — `eval : logic :: library : theories
:: macro : derived forms`. Expressive freedom lives exactly on the
vocab-and-model axis; everything else stays rigid to fund it. This is the honesty
note above read as a positive law, not an apology.

## Known deviations

Tracked here so the fundament carries no silent holes.

- **~~The signature is implicit and global.~~ Closed, with one declared residual.**
  A relation element's name is now *signature identity*: re-declaring it from a
  second namespace throws at registration (the silent replace-on-register is gone).
  The reflected `Vocabulary` is a genuine signature: the sorts it defines, the
  relation elements it declares (ownership rides the element's recorded `:ns`), and
  its inclusions — `:imports` edges *derived* from actual use (slot targets, law
  rule-calls, `:isa` genera, correspondence codomains; entail, don't store). The
  declared residual: rule *names* stay global — laws resolve `(contains …)` against
  one shared rule namespace, like vars in one Clojure runtime. With collisions loud,
  that is a constraint, not a hole; per-signature name *resolution* stays tied to
  the relations-first-class north star (slot-only relations like `:calls` belong to
  no signature yet — visible in the reflected graph as unowned `Relation` nodes).
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
- Steele, *Growing a Language* (OOPSLA 1998) — the growth premise; formalized here
  as the dynamics of pillar 1.
- Tobin-Hochstadt, St-Amour, Culpepper, Flatt & Felleisen, *Languages as
  Libraries* (PLDI 2011) — the modern formal home of language growing: derived
  forms elaborating into a small kernel, behind a phase discipline.
- Suppes, *Introduction to Logic* (1957) — the criteria of definition,
  eliminability and non-creativity; the derived-form gate.
