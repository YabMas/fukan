# Fukan — Theory

**Status:** The theoretical foundations — what fukan's mechanisms are instances *of*.

**Companion to** [VISION.md](./VISION.md) (the why), [DESIGN.md](./DESIGN.md) (the
design principles), [MODEL.md](./MODEL.md) (the substrate spec), and
[DECISIONS.md](./DECISIONS.md) (the decision trace).

---

## One sentence

Fukan is a **language-growing deductive-modelling workbench**: vocabulary
declarations form a relational presentation; authored and extracted instances
form one finite database; Datalog derives its views; laws are denial constraints;
and verification means that every violation query is empty.

The formal centre is one object, a deductive presentation:

```text
P = (Σ, R, C)

Σ  relational vocabulary     — unary kinds, binary relations, scalar attributes
R  intensional definitions   — stratified Datalog rules and aggregates
C  constraints               — offender queries, read as denials
```

Given finite ground facts `M`, evaluation computes the stratified least-fixed-point
closure `Cl_P(M)`. Satisfaction is:

```text
M ⊨ P    iff    offenders_c(Cl_P(M)) = ∅    for every c in C
```

This is the whole semantic loop. Define extends `P`; model adds facts to `M`;
verify computes the closure and checks the denials; lens and projection query or
render that same closure. A violation is a counterexample witness.

Fukan has no proof calculus. It establishes that one concrete finite model
satisfies the loaded presentation; it does not establish semantic consequence
between presentations or prove that every model of one presentation satisfies
another.

## The foundation

The primary formal home is the deductive-database tradition: EDB facts, IDB
rules, stratified fixed-point semantics, queries, and integrity constraints. The
algebraic-specification tradition supplies the discipline for composing small
presentation fragments, but does not add a second semantic mechanism. Fukan has
one loaded presentation and one evaluator.

The implementation stores the model as typed EAV, but that is a representation,
not the author-facing logic. At the authoring level a structure kind is a unary
predicate, a relation slot is a binary predicate, and scalar slots are attributes
whose type and cardinality declarations generate constraints. Sorts are therefore
predicates constrained over one relational universe, not disjoint carrier sets of
an independently implemented many-sorted logic.

The supported sentence language is the Datalog subset compiled by the Cozo
engine, including stratified negation, regular-path expansion, and stratified
aggregation, together with an explicit set of configured semantic built-ins.
Scalar validators and explicitly configured predicate ports belong to that
configuration: they affect satisfaction and are not mere harness wiring. Every
accepted law must be evaluable; unsupported laws make `check` fail rather than
silently count as satisfied.

## The map

Every live mechanism lowers to one of four semantic forms or to notation over
them. This table is the frame's contract with the codebase.

| Construct | Formal reading |
|---|---|
| the loaded registry | one deductive presentation `P = (Σ, R, C)` |
| a namespace `Vocabulary` | a provenance-bearing presentation fragment; its `:imports` are derived dependencies, not an independent signature category |
| `defstructure` | a declaration bundle: one kind predicate, slot predicates/attributes, generated cardinality and target constraints, and an instance constructor |
| a structure instance | finite EDB facts in `M`; named instances have qualified-var identity |
| extraction | a trusted model producer: artifact → finite fact fragment |
| the build | construction of one joint finite structure from design facts, extracted facts, and shared canonical values |
| `law` | a denial constraint represented by its offender query |
| `check` | the satisfaction decision above; empty offenders means satisfied, non-empty means refuted, inability to evaluate is an error |
| bare `defrelation` | declaration of an **open** relation head to which later fragments may contribute rules |
| derived `defrelation` | a fresh **closed IDB view**; under the closed-head discipline it is a conservative definitional extension |
| `(:sup E)` relation inclusion | a definition that contributes `E` to an open relation head |
| `(:eq E)` relation inclusion | a closed definition of a relation by `E`; other contributors to the head are rejected |
| `(:sub R)` relation inclusion | an accumulative extension: this relation contributes its edges to the open head `R` |
| inline path `E` | the deliberately small regular-path surface fragment; compound recursion graduates to a named `defrelation` |
| `measure` | stratified aggregation, lowered to an auxiliary rule head |
| `correspond` | a **bridge presentation** over design and fact vocabularies: two typed queries and a realization map, lowering to definitional pairing and `realized-*` rules; any constraints are deferred to ordinary laws authored over those rules |
| grammar reflection | reification of the normalized presentation as facts in a meta-vocabulary |
| Lens / Projection | queries and readback over the closure; they add no new model semantics |

`^:value` structures deserve a deliberately modest reading: they are canonical
structural values — ground record-like terms identified by structural content.
This gives sharing and no unintended duplication among authored values. It does
not by itself establish constructor generation, no-junk, no-confusion, or an
initial-algebra semantics, so fukan does not call them free types.

## Composition and correspondence

Presentation fragments compose by contributing declarations, rules, and
constraints to the one loaded `P`. Their namespaces record provenance and their
derived `:imports` edges make dependencies inspectable. Today rule resolution is
global, so these fragments are modules of one presentation, not independent
signatures joined by institutional signature morphisms.

Design and extracted code are likewise not independently built models later
amalgamated by a universal construction. The builder constructs one joint model
over their combined vocabulary in one pass. Structurally equal `^:value` nodes
are shared across the two strata by canonical identity.

`correspond` adds an ordinary bridge fragment to that presentation. It is not a
second matching language: a correspondence is a HEAD naming the two sorts, a MATCH
body — flat identity Datalog defining the pairing relation

```text
corresponds ⊆ Design × Fact
```

which may itself reference the ambient `corresponds` recursively — and a REALIZATION
MAP total over the design sort's non-scalar slots, each entry a regular code-graph
path `E`. The fragment lowers EXCLUSIVELY to definitional rules: a pairing rule
contributing to the open `corresponds` head, one `realized-<rel>` rule per entry
conjugating the fact-graph path with the pairing on both witnesses, and one
reflexivity rule per `^:value` sort (a content-identified value is its own witness).
This is a conservative extension — it adds derived views, not denials.

Conformance — coverage, adherence, effect realisation — is therefore NOT part of the
`correspond` declaration. Those are ordinary laws (denials) authored separately over
`corresponds` and the `realized-*` rules; at present none are authored, so a `check`
carries no correspondence obligations and the constraint layer is a deliberate
deferral. This keeps the definition/assertion split sharp: the declaration says what
realises what, and a later law says what must hold of it.

Whatever those laws come to check, they are checked in the concrete joint model.
Calling the bridge a theory morphism would require more: explicit sentence
translation and a result that the target presentation entails every translated
source axiom for all of its models. Fukan neither needs nor claims that result. The
bridge-presentation reading preserves the useful shape of the current authoring form
without granting it stronger mathematics than it implements.

## The canonical authoring model

The whole authoring surface has one operational reading:

> Authors declare predicates and rules with Clojure data, add facts with instance
> forms, and forbid witnesses with laws. Every convenience form normalizes to
> those operations.

The forms have disjoint jobs:

| Form | Canonical contribution |
|---|---|
| `defstructure` | a constructible kind predicate, its slot predicates/attributes, and generated well-formedness denials |
| a structure/value instance | finite ground facts |
| `defrelation` | an open relation head, inclusion, or closed derived Datalog view |
| `law` | a denial constraint whose query returns counterexample witnesses |
| `correspond` | a pairing relation (head + match) and a realization map, lowering to definitional pairing / `realized-*` rules; conformance is deferred to ordinary laws |

Identity is equally explicit: ordinary structures construct named entities, so
their instance form always starts with a binding/name symbol; only `^:value`
structures construct anonymous, content-identified values. Both elaborate to
ground facts—the distinction determines identity, not a second model language.

The slots map is the canonical instance representation. An inline `(syntax f)`
hook may provide vocabulary-local sugar, but it is a deterministic map-to-map
elaborator and the map form always remains valid. A `{:form true}` scalar slot
marks declaration-position code data, so authors write Datalog forms unquoted
there just as they do in a law; quoting belongs to evaluated runtime calls.

Normalized readback follows the same boundary. `structure-form` produces a valid
`defstructure`; `correspondence-form` produces the separate valid top-level
`correspond`. Sugar need not round-trip textually, but normalized output must be
accepted input at every layer.

## Growing the language

[VISION.md](./VISION.md) names the premise: *bottom-up language building,
top-down design*. It has four precise parts, all terminating at the fixed
deductive kernel.

### Semantic growth — extend the presentation

Growing vocabulary changes `P` in one of three declared ways:

1. **Definitional extension.** Introduce a fresh closed IDB predicate. Old facts,
   old derived relations, and old query answers are unchanged; the new name is
   eliminable into its definition.
2. **Accumulative extension.** Add a rule to a head explicitly declared open.
   Existing derived answers may grow. Relation genera such as `contains` are
   extension points of this kind.
3. **Constraint refinement.** Add a law. The closure is unchanged, but the class
   of acceptable models shrinks. This is top-down design pressure in its exact
   semantic form.

Adding facts is not language growth; it is model growth and may turn existing
constraints red. Keeping those two axes separate prevents authoring notation or
model ingestion from silently changing the meaning of the vocabulary.

### Syntactic growth — elaborate into the kernel

Instance macros, law combinators, `(is …)`, readers, and inline syntax hooks are derived
forms governed by an elaboration:

```text
elab_Σ : Surface → {declarations, rules, constraints, facts}
```

An elaborator must be deterministic and total on accepted forms, run before
assembly, inspect only the loaded vocabulary, and leave no semantic remainder.
Its correctness criterion is semantics preservation: evaluating the surface form
means evaluating its elaboration. A form that needs a new evaluator, host callback,
or model-dependent expansion is not notation; it is a proposed extension of the
semantic kernel and must be named and judged as such.

This is the formal integration of the Lisp premise. Steele's *Growing a Language*
supplies the design aim — users participate in building the language from small
words. Languages-as-libraries and macro elaboration supply the implementation
discipline — grown surface words lower into a small stable core. Algebraic
specification describes the semantic result: a larger presentation assembled
from smaller fragments. No additional pillar is required.

### Machinery has explicit boundaries

The old declaration-versus-registration rule is refined by semantic effect:

- `reader`, inline `syntax`, and law-combinator machinery are surface elaborators;
- extractors are model producers;
- the type dialect and predicate ports are semantic built-ins or compiler backend
  configuration because they can affect satisfaction;
- declaration lowering is closed and exhaustive: unknown declaration kinds fail.

The core may keep genuine configuration behind registries, but registration does
not make a semantic dependency into mere wiring. Vocabulary growth does not
install evaluator handlers, correspondence comparators, or bridge callbacks;
those meanings are expressed with the fixed kernel and ordinary Datalog. The
effective logic configuration must be fixed for a build and visible to diagnostics.

### Reflection — reify and read back

Reflection is not the host Lisp's homoiconicity. Clojure source is already data;
fukan additionally reifies its normalized presentation into the model:

```text
reify   : Kernel → Model_meta
readback(reify(k)) = normalize(k)
```

The first property to maintain is coverage: every kernel declaration has a
meta-model representation. The second is the round trip above, modulo explicit
normalization. The print-dual is readback, not unquotation. This sharper account
keeps the valuable self-description claim without conflating three distinct
operations: Lisp syntax-as-data, registry reification, and source rendering.

## The admission test

Every proposed feature must answer two questions:

1. **Which kernel form does it contribute — declaration, fact, rule, or denial?**
2. **Does it change semantics, or only elaborate notation?**

A semantic contribution should use the existing declaration shape, reflection,
and checking discipline. A derived form must eliminate completely into existing
kernel forms. A model producer may add facts but not rules or laws. A semantic
built-in must be explicit, total on its declared domain, and fixed for the build.

If a proposal answers none of these, the burden of proof is on the mechanism.
This gate concentrates freedom on the two axes fukan values — vocabulary
authoring and model building — while keeping evaluation, checking, and reflection
constrained enough to make that freedom dependable.

## Known boundaries

- **One global presentation.** Structure tags are qualified and relation-element
  collisions are loud, but rule calls still resolve through one global namespace
  and slot-only relations have no declaring `Vocabulary`. Namespace fragments are
  useful modules, not yet independent formal signatures.
- **Configured built-ins.** Scalar validation and registered predicate ports do not
  compile entirely to CozoScript. They are explicit semantic configuration of
  the current evaluator. Moving them to materialized relations or compiled rules
  would narrow this boundary further.
- **One logic.** The sentence language is not a plug-point. Generalizing to
  multiple institutions is neither implemented nor required by the current
  verified-modelling aim.

## Lineage

The short shelf behind the foundation:

- Ceri, Gottlob & Tanca, *What You Always Wanted to Know About Datalog (And
  Never Dared to Ask)* (1989) — the semantic centre: EDB, IDB, recursion,
  fixed-point evaluation, and querying.
- Burstall & Goguen, *Putting Theories Together to Make Specifications* (1977)
  — the modular-presentation discipline: build larger languages from small
  specification fragments.
- Tobin-Hochstadt, St-Amour, Culpepper, Flatt & Felleisen, *Languages as
  Libraries* (2011) — extensible surface languages elaborating into a stable
  host kernel behind a phase boundary.
- Steele, *Growing a Language* (1998) — the motivating Lisp vision: a language
  should be designed so its users can grow it.
- Goguen & Burstall, *Institutions: Abstract Model Theory for Specification and
  Programming* (1992) — the precision standard for signatures, sentence
  translation, model reduct, and satisfaction under change of notation; retained
  as a guard against calling a model-level correspondence a theory morphism.

CASL free types, Kleene algebra with tests, SPARQL property paths, and classical
definition criteria remain useful comparative references where a concrete
mechanism calls for them. They are not additional foundations of the current
kernel.
