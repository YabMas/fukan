# Fukan — Design

**Status:** Design principles — *how* the system is shaped.

**Companion to** [VISION.md](./VISION.md) (the why), [THEORY.md](./THEORY.md) (the
theoretical foundations), [MODEL.md](./MODEL.md) (the substrate spec), and
[DECISIONS.md](./DECISIONS.md) (the decision trace).

---

## Purpose

This chapter records the design principles of the lean kernel: what the core ships,
how the shipped vocabulary tier layers on it, how models are ingested into one
graph, and the model↔code seam — extraction up, correspondence across. (The
downward direction — materializing the model toward an implementation — was cut
2026-07-15 to focus scope on verified modelling; its seam, the act grammar, stays
dormant.)

## The core ships mechanics only — no vocabulary

The single most load-bearing decision: `src/fukan/` ships only the `defstructure`
primitive and the ingestion/projection machinery. It ships **no domain vocabulary**
— no `Type`, no `Function`, no architectural kinds. This is the bottom-up half of
the premise (see VISION.md: *bottom-up language building, top-down design*) — in
the Lisp tradition of stratified languages, authoring a grammar is every project's
first modelling act:

- **fukan's own vocabulary** — the code grammar it models itself (and any Clojure
  project) with — is the shipped **`fukan.common` tier** (`common/`, its own
  classpath root outside `src/`): the structural primitives
  (`fukan.common.vocab.grouping`), the code grammar by element
  (`fukan.common.vocab.code.{kind,effect,operation,module,subsystem}`), and the
  pattern tier above it (`fukan.common.vocab.patterns.*`). It loads by *require*
  (the `fukan.common` index namespace), never by discovery.
- The **type dialect** (`fukan.common.typing.malli`) and the **Clojure extraction
  seam** (`fukan.common.extraction.*`) are plugins in the same tier — each realizes
  a kernel plug-point and owns its specialized vocabulary together with its
  mechanism.
- A **consuming project** depends on fukan, requires the vocabulary elements it
  uses, binds `*spec-dirs*` to its own spec directory, and authors its instance
  specs against the same grammar.

The core knows no kinds. This keeps the substrate a small, honest floor and forces
every modelling exercise to confront "what is the clearest vocabulary for *this*?"

Structure tags are **namespace-qualified** (identity = defining ns + name), so
co-loaded projects may share short names, and a free law self-scopes through the
qualified tag — ns-precise. The DOMAIN-ALTITUDE rule names (`(Operation ?s)`,
`(contains ?a ?b)`) are global, like vars in one Clojure runtime: two same-short-named
structures deliberately co-loaded union their kind rule (the documented co-load
allowance), and a relation *element's* name is global presentation identity — a second
namespace re-declaring it fails LOUDLY at registration (see
[THEORY.md](./THEORY.md), "Known deviations", for the signature reading).

## The `defstructure` surface

A structure is a *composition of slots* plus *datalog laws*.

- **Slots are one typed map**, cardinality as a quantifier: a bare target is one
  (`:reads Model`), `[:? T]` optional, `[:* T]` zero+ ordered, `[:+ T]` one+
  ordered, `[:set T]` unordered (order and duplicate targets excluded from value
  identity). A relation slot may take a UNION of target sorts
  (`[:* Operation Kind Module]`) — the generated target-type law checks the
  disjunction. A scalar target — a bare malli keyword (`:boolean` / `:int` /
  `:string`) — stores a leaf value with an auto type-check law; any other vector
  (`[:enum "a" "b"]`, `[:int {:min 1}]`) is a **refined scalar** — the core stores
  the type form verbatim and the generated law checks values through the registered
  type dialect (`fukan.canvas.core.typing`, the kernel's third plug-point); a slot
  whose target is another structure reifies a relation.
- **Slot options** ride the props position: `[:? {:payload :q} :string]`
  (`:payload` = a companion code-form stored as a sibling `:val/` datom); for
  cardinality one, lead with the props map. `{:form true}` marks the scalar value
  itself as declaration-position code data, so it is authored unquoted. `(reader f)` lets a value structure
  expand authoring data-literals (the malli dialect's `Schema` reads native malli
  forms); an inline `(syntax f)` lets a structure own instance-level sugar — a map → map
  rewrite of the authored slots map (`Operation` rewrites `:signature` into
  `:in`/`:out`).
- **Instances mirror defstructure** position-for-position: `(Structure name "doc"?
  {slot → value}? nested…)`, a top-level def-emitting form — the symbol is the var
  AND the entity name (`^{:name "…"}` metadata overrides). One `{slot → value}`
  map: a plural slot takes a vector of targets (authoring order is the sequence
  order, recorded as `:rel/order` — the bracket mirrors the quantifier), a
  labelled target is a `[label target]` pair, a payload slot takes
  `[value payload]`, reader literals pass as values. Entity instances always
  require the symbol; anonymity is reserved for `^:value` structures.
  Nested member instances trail where defstructure's laws sit, lift to sibling
  `def`s, and route by target-type into the container's slots.
- **`^:value` structures** are content-deduped, inline-anonymous nodes:
  structurally-equal values collapse to a single node (identity = a deterministic
  structural content key).
  Used for nameless compound data — list/record/shape descriptions — where an
  entity-style named stand-in would erase the structure.
- **Laws:** `(law "desc" {:offenders [?x] :where […] :rules […]? :scope …? :key …?})`
  is a datalog constraint — ONE unquoted map, the same declaration cell as every
  other form. Declaration positions—including `{:form true}` instance slots—never
  quote; quotes belong to evaluated runtime contexts such as the REPL and `q`.
  `:scope :global` opts a law out of its
  structure's self-scoping (needed for cross-cutting laws). The recurring shapes
  have **combinators** — `(law "desc" (matched-by R :from S? :when {k v}? :scope
  T?))`, `(has R :when …?)`, `(has-any R1 R2 …)`, `(target R {k v})`,
  `(at-most-one R)` — expanding to datalog that emits `not-join` directly (the
  Cozo query compiler lowers stratified negation correctly, so no negation-routing
  dance is needed). `(structure/check db)` runs every law and returns the
  violations.
- **Relations are ELEMENTS** (`defrelation`, sibling of `defstructure`) — three
  forms, one construct: BARE (`(defrelation :contains "doc")` — an OPEN primitive/genus
  claiming the name), an INCLUSION against a regular expression over relations
  (`(:sub :contains)` / `(:sup E)` / `(:eq E)` — the same (relation, direction,
  expression) triple a correspondence relation map uses), or DERIVED
  (`(defrelation :module-depends "doc" [?m ?n] […])` — a named custom-bodied
  datalog rule; multiple bodies = recursion). Bare relations and `:sup` heads are
  open to contributors; derived and `:eq` heads are closed views, and vocabulary compilation
  rejects additional contributors to those heads. Transitive closures are the
  COMPILER's: every binary relation's `R+` is minted unconditionally and injected
  only where referenced — nothing declares `:transitive`.

## Laws read at domain altitude — vocab-derived rules

Laws should read in the vocabulary's own terms, not in raw substrate patterns. The
core derives a set of **datalog rules from the live vocabulary** (`core/rules.clj`,
pure): a kind rule per structure (`(Operation ?e)`), a relation rule per relation
slot (`(calls ?a ?b)`), inclusion/realized-as/defrelation rules, plus the fixed
substrate rules (`named`, the `design`/`fact` strata); membership (`contains`,
`within`) rides vocab relation elements. `check` auto-injects these into every
law's query, so a law can say `(Operation ?s) (within ?s ?m)` instead of navigating
`:structure/of` and reified `:rel/*` triples by hand.

## Ownership-on-owner

Module ownership flows via `:child` relations on the **owner**, not via
back-references on the owned entity. Nested authoring routes members into the
container's slots automatically (`(Module m … (Operation f …))` emits the
`:child` relations). Owned entities carry no module back-reference; `within`
resolves membership by container name over the `contains` genus.

## Ingestion — many specs, one graph

Spec files under the configured spec-dirs (`*spec-dirs*`, default `["canvas"]`) are
**auto-discovered** (`canvas_source`): each is required — registering its vocabulary
and interning its instance `def`s — and the global assembler scans the interned vars
into one structure db. Adding a spec is a single file drop — no registry edit, no
per-spec build fn, no merge pass. (The `fukan.common` grammar tier is NOT
discovered — it loads by require, so fukan's own extractor never reads the grammar
as if it were modelled code.)

**References between specs are ordinary var references** (require + var capture;
`declare` for forward refs within a namespace). Identity is the qualified var name,
so cross-namespace reference cycles are inexpressible by construction.

## Grammar reflection — the language is on the graph

The structure registry is projected onto the model on every build
(`fukan.canvas.core.reflect/with-grammar` — kernel-native CORE machinery, not
vocab): each defstructure becomes a `Structure` node — slots as
`:slot/<card>`-kinded labeled edges whose scalar/refined targets reify as the type
dialect's content-deduped `Schema` values, laws as nodes carrying their datalog as
payload — one `Vocabulary` node per namespace (a presentation fragment: its owned
declarations plus DERIVED `:imports` edges, entailed from use), and each
`(correspond …)` as a decomposed `Correspondence` node with `RelationMap`
children. The
print-dual (`fukan.canvas.projection.grammar`) renders a reified structure back as
its authoring form — `(grammar)` in the REPL is the live language reference,
derived not maintained — and grammar drift (`unused-structures`: vocabulary no
instance inhabits) becomes an ordinary reading.

## The model↔code seam

Fukan's thesis (one assertable graph) is realized by projecting both specification
and implementation onto the same substrate, then checking them against each other.

- **Extraction (code → model), up.** `model/extraction.clj` is a vocabulary-blind
  plug-point: a project registers one extractor (`register-fact-extractor!`, wired
  by the composition root `infra/model.clj`); the builder runs it over a code-root
  and assembles the resulting facts with the authored facts in one pass. Fukan's
  Clojure extractor (`fukan.common.extraction.*`) reads clj-kondo analysis into the FACT
  vocabulary — `Ns` (a namespace) owning `Fn` (a function: decomposed signature,
  effects, the actual `:calls` graph, privacy/`^:export`/`^:test-support`
  metadata). Design and fact are DISTINCT tags — never one tag split by a
  provenance flag.
- **Correspondence (verify), across.** The design↔code link is a bridge presentation
  declared per design element, from outside the design vocabulary, in the language's
  extractor. Matching is an ordinary derived relation (`module-twin`,
  `operation-twin`); correspondence merely names it:
  `(correspond Module Ns {:carrier :module-twin :coverage :both})` and
  `(correspond Operation [Fn :public] {:carrier :operation-twin :coverage :both}
  (:delegates :sub :public-call) (:performs :sup [:cat [:* :calls] :performs]))`.
  Coverage is separate from the nested relation inclusions;
  it is not a bijection or a theory morphism. The
  identity component over shared sorts (`:in`/`:out` Schema values) is derived.
  Every demand law is GENERATED from the declaration — totality, surjectivity,
  structural adherence, call-realization, effect coverage — each with a stable key
  a worklist reader addresses (`law/violation-names`). Correspondence is its own
  concern: the design vocabulary stays pure and language-neutral, so a project
  loading it with a different extractor gets no Clojure constructs at all.
- **Materialize (model → code), down — CUT.** The downward projection
  (materializing a modelled node into implementation instructions, and the
  readings layer over it) was cut 2026-07-15 to focus scope on verified modelling.
  The act grammar below is its dormant seam.

## The act grammar — Lens, Projection, Check

`fukan.canvas.core.lens` owns the kernel's three ACTS on the graph (core is
unopinionated about the ELEMENTS a project models, opinionated about the acts it
performs on them):

- A **Lens** is the optional NAMING act for a focus — the `defrelation` of
  selections: one datalog `:select` (binding `?n`, evaluated with the vocab-derived
  rules by `evaluate-lens`), minted only when a selection is genuinely shared.
- A **Projection** re-presents the model from a focus it carries ITSELF: an inline
  `:select`, a `:through` Lens (a named shared focus), or neither — no narrowing is
  the maximal focus, the whole model. `projection-focus` is the one resolution;
  `refine` narrows a focus by a further query (set-intersection), so acts chain
  over a refined focus.
- A **Check** gates a Lens: a non-empty focus is a violation (`run-checks`), the
  use-side dual of the law substrate.

Selection and traversal are one expression — `(path ?from E ?to)` composes relation
paths (the same regular-expression language E used by relation inclusions and
correspondence relation maps), and `(via R Scope P)` transports a property along a
relation's compiler-minted closure. The grammar is dormant as a projection surface
(see the cut above) but live for inspection: the print-duals resolve focus through
`focus-nodes`.

## Conventions

- **`^:export`** marks vars reached only by dynamic dispatch — any var called from
  a law's `:where` clause (a datalog predicate) or a `(syntax …)` hook. Both lint
  configs honor `:exclude-when-meta #{:export}`.
- **Lint exemptions are mirrored** in `.clj-kondo/config.edn` and `.lsp/config.edn`
  (clojure-lsp doesn't honor clj-kondo per-namespace config).
- **clj-kondo CLI is ground truth.** The `defstructure` DSL is taught via `:hooks`;
  the per-structure instance entries are GENERATED (`clojure -M:kondo`). Editor
  false-positives on DSL bodies are expected without the hook cache; the canonical
  lint is `clojure -M:lint --lint src common canvas test`.

## REPL loop

The serving daemon is paused, so the loop is in-process (`clj -M:dev`):
`(go)` builds the model (canvas specs + the Clojure extractor over `src/`),
`(refresh)` reloads + rebuilds, `(status)` reports state, `(architecture)` projects
the system map, `(grammar)` prints the live language primer, `(correspondence)`
prints the design↔fact seam card, `(drift)` / `(encapsulation)` / `(type-drift)`
read the generated correspondence demands by their stable law keys, and `(check)`
prints violations with each offender quoted as its authored form. Build a db
directly with top-level instance `def`s + `assemble-vars`, query with `cq/q`, check
with `(s/check db)`. Front-door helpers speak only through NAMED surfaces — law
keys, defrelations, the print-duals — never inline substrate datalog.

---

*See [MODEL.md](./MODEL.md) for the substrate spec and [DECISIONS.md](./DECISIONS.md)
for why these shapes were chosen.*
