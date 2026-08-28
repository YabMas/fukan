# Fukan

Fukan is a structural exploration tool for codebases in the era of LLM-driven
development. The core question it explores: as LLMs handle more low-level coding,
how do humans maintain control over high-level structure and collaborate with LLMs
at that level of abstraction? You define a system's *structure* — its composition
of concepts plus the laws that must hold of it — model abstractions over that
structure, verify the whole as one assertable graph, and project it down toward an
implementation. Specification and implementation live on the **same** graph, so
intended structure and actual structure can be checked against each other.

The approach is **bottom-up language building, top-down design** — the Lisp
tradition of stratified languages: every project grows its own grammar on the one
primitive (the core ships none), and design presses down on it as laws the graph
enforces — against the model and against the LLM-written implementation alike.

The eventual vision is to render that graph as an interactive explorer in the
browser — but that is **deferred indefinitely** (see below). Today fukan is a
REPL-and-canvas tool exercised by modelling.

## The theoretical frame — measure work against it

`doc/THEORY.md` is the foundation every mechanism instantiates. Fukan's one semantic
object is a deductive presentation `P = (Σ, R, C)`: relational vocabulary, stratified
Datalog definitions, and denial constraints. Authored and extracted instances form
the finite EDB `M`; evaluation computes `Cl_P(M)`; `M ⊨ P` exactly when every offender
query is empty. Its map table is the contract with this codebase. The operational gate:

- **THE TEST.** Before any proposed mechanism exists, answer two questions: which
  kernel form does it contribute — **declaration, fact, rule, or denial** — and does
  it change semantics or merely elaborate notation? Semantic forms use the existing
  declaration/reflection/checking discipline. A derived form must eliminate completely
  into the four kernel forms. A model producer may add facts but not rules or laws.
- **Registration does not erase semantic effect.** Readers and inline syntax hooks are elaborators;
  extractors are model producers; type dialects and predicate ports are explicit semantic/compiler
  configuration because they can change satisfaction. Declaration lowering is closed and exhaustive;
  correspondence matching is ordinary Datalog, not a handler/comparator/callback registry.
- **Three semantic growth modes.** A fresh closed IDB view is definitional and
  conservative; an explicitly open relation head accepts accumulative rules; a new law
  is constraint refinement. Model growth is separate: it adds facts and may turn laws red.
- **No proof theory.** Fukan checks one concrete finite model. `correspond` lowers to
  definitional pairing/`realized-*` rules, not constraints; any correspondence CONSTRAINT would be
  an ordinary law model-checked in the joint model (a deferred layer — none authored yet), never a
  theory-morphism proof obligation over all target models. An unsupported law makes `check` fail closed.

The frame says what a mechanism *is*, never that it should exist — vocabulary and
mechanism grow only under concrete modelling pressure (see the standing discipline
below).

## ⚠ Current state — lean kernel rebuilt; in a modelling-exploration phase

The radical prune and the rebuild around a single structure-definition primitive
are **done**. `defstructure` is the heart of the kernel: *a structure = its
composition of Nodes/Relations + the datalog laws that must hold of it.* The
structure substrate **is** the model (no separate model-map).

**The lean kernel — `src/fukan/` (the only code on the classpath):**
- `canvas/core/structure.clj` — the `defstructure` primitive (the slot map + laws
  + combinators, `check` — which dispatches to the registered check-engine —
  value-identity, the reader/syntax hooks), and
  `canvas/core/{assemble,typing}.clj` — the global assembler and the type-dialect
  plug-point
- `canvas/core/rules.clj` — pure vocab-derived datalog rules (kind/relation/module
  rules) auto-injected into every law so laws read at domain altitude
- `canvas/core/lens.clj` — `evaluate-lens`/`projection-focus`: resolve a selection (a lens's, or a Projection's own inline `:select`) → its
  focus sub-graph; also OWNS the act grammar (`Lens`/`Projection`/`Check`)
- `cozo/` — the query engine. The model db **is a CozoDB**, and CozoScript (datalog)
  is what every query, law, and reader compiles to: `cozo/query.clj` — the kernel query
  primitive (`q`/`entity`), fukan's datalog subset → CozoScript. ⚠ INVARIANT: every clause compiles
  to DIRECT stored-relation access (`*t_str[e, 'attr', v]`), NEVER to a view — a view is a rule, a
  rule is materialized, and a materialized relation carries no key, so every join over it degrades
  to a scan. A unified `triple` view cost ~136x on multi-hop joins until 2026-08-25 (it also
  stringified eids, making join keys COMPUTED so no index could ever apply). Eids are native Ints.
  ⚠ THE SAME INVARIANT REACHES VOCAB RULES: a single-definition rule (`contains`, `calls`, a
  one-bodied defrelation) IS a view, so the compiler INLINES it at the call site
  (`query/inline-clauses`) instead of emitting it — and then ORIENTS each expansion against what
  the preceding clauses already bound (`order-expansion`), because Cozo executes a body largely in
  WRITTEN order: `*t_int[r, 'rel/from', a]` with `a` bound is a constrained probe, the same clause
  with `a` free is a scan of every `rel/from` datom. BOTH halves are load-bearing — measured on
  brian (898 ns / 10,673 fns), `ns-depends` ran 123.6s through the rules, **305.7s inlined but
  unoriented**, and 1.0s inlined + oriented. Multi-bodied heads (a union, a recursion, a
  compiler-minted closure) are genuine derivations and stay rules; and inlinability is decided over
  the rules IN SCOPE (caller `%` rules merged with the vocab's, deduped by FORM) — a name the caller
  REDEFINES gains a second definition and stops being a view, while one merely passed through again
  dedups to one and still inlines, which the readings depend on since they hand `q` the whole vocab
  rule set. Re-ordering may move a `not` ahead of its binder (Cozo binds by analysis, not by position
  — measured), but NEVER a PREDICATE: a comparison or a registered predicate PORT compiles to an
  EXPRESSION, and a function call reached before its argument is bound can fail outright
  (`starts_with` on an unbound var), which `check` swallows into an UNDECIDABLE law. `order-expansion`
  holds a predicate back until every var it mentions is bound;
  `cozo/law.clj` — compiles every defstructure law → CozoScript and registers the
  check-engine plug-point (so `structure/check` runs on Cozo); `cozo/build.clj` —
  the native model→CozoDB build; `cozo/{db,mirror,rules}.clj` — the db handle, datom
  loaders, and the shared CozoScript rule substrate
- `canvas/ingestion/canvas_source.clj` — ingestion: discover the instance specs under the
  configured spec-dirs (`*spec-dirs*`, default `["canvas"]`) and assemble their instance vars into
  one structure db (the `fukan.common` grammar itself loads by *require*, not discovery);
  `canvas/projection/{grammar,instance,architecture}.clj` — the inspection print-duals
  (grammar → defstructure forms; model nodes → authored instance forms) + the system map.
  (The DOWNWARD projection — model→artifact materialization + the readings/Findings — was CUT
  2026-07-15 to focus scope on verified modelling; the act grammar `Lens`/`Projection`/`Check`
  stays in `core/lens.clj` as the dormant seam for when the projection side is re-expanded.)
- `model/pipeline.clj` → `build-model`; `model/extraction.clj` — the code→model
  extractor plug-point (a registry slot, blind to the language)
- `infra/model.clj` (composition root — registers the project extractor + loads the dialect), `core.clj`

fukan's own **vocabulary** — the code grammar (Kind/Effect/Operation/Module/Subsystem) + its
model↔code correspondence — plus its Clojure extractor, its malli type dialect, and grammar
reflection — is NOT in `src/`. It is the **`fukan.common.*` library tier** at `common/fukan/common/**`
(its own shipped classpath root): `vocab/` (the grammar), `typing/` + `extraction/` (the
dialect/extraction plugins), and `reflect/` (reflection) — see "The fukan.common grammar tier" below.
It loads via the `fukan.common` index ns (a *require*), not by discovery. `src/fukan/` is kernel
mechanics + the build pipeline + the three plug-points (extraction, typing, render) only.

**Parked under `.paused/`** (off-classpath): only the **browser explorer / viewer**
(`web/`, top-level `projection/`, `infra/server`). The other once-parked subsystems
(agent surface, old Clojure analyzer, lens substrate, inspect/instruct, code
synthesis) were **removed** once the rebuild reborn their capabilities on the
substrate — they live in git history. The pre-canvas Allium/Boundary specs remain
in `.legacy-allium/` as the baseline to compare against once the transition closes.

**The browser explorer / viewer is DEFERRED INDEFINITELY.** It is fukan's eventual
vision, but it is *not on the near roadmap and should not be proposed as a next
step* — the core is being exercised extensively first. Do not re-suggest reviving it.

**Direction — exercise the core by modelling fukan ON itself, organized by element.** The
work now is authoring fukan's own structure directly on `defstructure` in `common/fukan/common/vocab/`,
pressure-testing the core in every way. The grammar was extracted into the reusable **`fukan.common`
tier** (2026-07-14) because a second project is about to consume it — fukan-modelling-fukan is now
just the *first consumer* (its self-model lives in `canvas/architecture/`, off the shipped surface).
The standing discipline still holds: do *not* spend time abstractly designing reusable/methodology
layers (DDD/hexagonal/C4) ahead of a concrete case — grow the vocab opportunistically *from*
modelling, never ahead of it. The tier extraction was pulled by a concrete second-project need, not
speculation.

## The fukan.common grammar tier (the kernel ships none)

The core (`src/fukan/canvas/`) ships only the `defstructure` primitive and the
ingestion/projection machinery. It ships **no domain vocabulary**. fukan's own
vocabulary — the grammar it models *itself* with — is the **`fukan.common.*` library tier** at
`common/fukan/common/**` (root `common/` + the `fukan/common/…` namespace tree), a **shipped**
classpath root (base `:paths`) *outside* the `src/` code-root, so fukan's own extractor never reads
the grammar as if it were modelled code. The tier loads via the `fukan.common` **index ns** (a
`require` that pulls in every element) — NOT by discovery; discovery (`*spec-dirs*`, default
`["canvas"]`) is reserved for a project's *instance* specs. A consuming project depends on fukan,
`(:require [fukan.common])`, and authors its own model against `fukan.common.vocab.*`. Organized
**by element**: each file is the complete story of one element — its `defstructure` + the
laws/correspondence about it. (The Clojure extraction that *populates* the code grammar is a
separate seam, `common/fukan/common/extraction/`; the type dialect is
`common/fukan/common/typing/malli.clj`.)

- `common/fukan/common/vocab/grouping.clj` — `Grouping` (the most abstract membership primitive) +
  `Connected` (a flow-node facet). The structural primitives the rest builds on.
- `common/fukan/common/vocab/code/{kind,effect,operation,module,subsystem,band}.clj` — the code
  grammar (Kind / Effect / Operation / Module / Subsystem / Band). **PURE DESIGN — language-neutral**,
  with ONE stated exception: `band` names the Clojure `Ns` sort (by full tag keyword, the documented
  spelling for a deliberate non-require), because a Band's whole point is that its evidence is the
  EXTRACTED call graph. A non-Clojure project gets vacuous laws rather than a load error; the honest
  fix is an extractor-neutral code-unit sort, and a second extractor is the trigger to build one. Each element
  file carries its structure and the laws that are its own slot semantics, and knows nothing about
  what language the code is written in.
- `common/fukan/common/vocab/patterns/plug_point.clj` — the PATTERN TIER: `PlugPoint`, one rung above
  the core code grammar. A pattern is a named CONFIGURATION drawn OVER the core elements (foundation
  reading: a presentation extension — it imports the core's sorts and adds its own sort + relations), and
  the dependency points strictly UPWARD: the pattern names its participants (`PlugPoint :owner`), the
  core never names the pattern — `Module` carries no pattern slots and does not require the pattern ns.
  The domain-altitude reading `(offers ?m ?p)` is a DERIVED relation in the pattern's fragment (the
  converse of `:owner`). The satisfy side is CUT until its semantics cycle (likely
  correspondence-recognized from registration facts, not authored).
  **The design↔code correspondence is NOT here** (since 2026-07-17): it maps design INTO a SPECIFIC
  language's FACT vocabulary. The codomains are real `defstructure`s in that language's extractor —
  `Operation ↦ Fn` (a Clojure function: `:calls` graph + `:private`/`:export`/`:test-support` metadata
  conventions), `Module ↦ Ns` (a namespace) — in
  `common/fukan/common/extraction/clojure/{operation,module}.clj`, declared from outside via the
  external `(correspond [Design ?d Fact ?f] match realization-map)` hook. The two strata are DISTINCT
  tags: the pairing is a genuine cross-tag relation, not the Operation-tag-with-a-provenance-flag graft
  it was before. Keeping the fact vocabulary here means the shipped, language-neutral vocab exports no
  Clojure constructs; a project loading the vocab with a different extractor correctly gets no
  `Fn`/`:calls` at all. A correspondence is ONE declaration — a HEAD (its identity, design sort first),
  a MATCH body (flat identity logic, ordinary Datalog; it may reference the ambient `corresponds` —
  recursion through the pairing, acyclic), and a REALIZATION MAP (total over the design sort's
  non-scalar slots; each entry a PURE code-graph path, the same-named atom `:in :in` for identity
  realization, `nil` for declared-unrealized):
  `(correspond [Operation ?op Fn ?fn] [(named ?op ?n) (named ?fn ?n) (contains ?m ?op)
  (contains ?ns ?fn) (corresponds ?m ?ns)] {:in :in :out :out :performs [:cat [:* :calls] :performs]
  :delegates [:cat :calls [:* [:cat [:not public] :calls]]]})` — the codomain restricts to Fn's public
  sub-sort through the `public` defrelation the `:delegates` entry names; `:delegates` routes delegation
  through non-public interior; `:performs` reaches effects to call-graph depth.
  `extraction/clojure/module.clj` holds the `Ns` codomain and the twin ROOT
  `(correspond [Module ?m Ns ?ns] [(named ?m ?mn) (named ?ns ?nn) [(name-match :qualified-suffix ?mn
  ?nn)]] {:child :child})`, using the configured `(name-match :qualified-suffix …)` predicate (canvas
  short-name is a separator-agnostic dotted suffix of the code ns); the Operation↦Fn pairing nests
  within it via the ambient `corresponds`.
  The declaration lowers EXCLUSIVELY to rules — a pairing rule feeding the open ambient `corresponds`,
  a compiler-minted `realized-<rel>` rule per entry, per-`^:value` reflexivity — definitional and
  conservative, no denials (THE TEST verdict: declarations → rules). A realization entry `R ↦ E` reads:
  an `R`-edge is realized by an `E`-path from the source's code witness to the target's code witness;
  a content-identified `^:value` node is its own witness, which is why entries never mention transport.
  Coverage is a set of READINGS over these rules — `(drift)` (unrealized, gated on extraction),
  `(encapsulation)` (unaccounted public functions, scoped to ADOPTED namespaces), ambiguous — NOT laws:
  `(check)` currently carries
  NO correspondence violations, and checks over `corresponds`/`realized-*` are a deferred law layer (the
  natural next arc). `public` appears in exactly two places — the `:delegates` realization path and the
  dev-side readings (policy) — never in match logic: an op realized by a private fn PAIRS, and
  realized-but-private is a future law's precise finding. The vocab laws reach shared vocab via datalog
  injection (no compile cycle, since the `fukan.common` index requires every element). A law that is a
  declaration's SLOT SEMANTICS rides the declaring structure itself: `Subsystem` carries the
  `:may-depend` conformance/acyclicity teeth **plus** the rehomed module-graph acyclicity +
  membership-totality demands (module-graph laws ride the clustering concept).

The type dialect and the extraction seam are NOT part of the code vocabulary — each is a
self-contained **plugin** in its own area (both realize a kernel plug-point; a plugin owns a
SPECIALIZED vocabulary + its mechanism together, not scattered into general vocab):
- `common/fukan/common/typing/malli.clj` (ns `fukan.common.typing.malli`) — the malli type DIALECT,
  one honest file: the whole dialect is malli top-to-bottom, so it lives under the malli name (not a
  generic `typing` layer — the neutral SPI is the kernel's `fukan.canvas.core.typing`). Three sections:
  the shape VOCABULARY (`Schema`/`SchemaChoice`/`SchemaField` — malli modelled as content-deduped
  `^:value` structures — + the authoring readers), the runtime BRIDGES (`render`/`valid?`), and the
  dialect wiring. The HOOK side of the `typing` SPI; requiring it self-registers the full dialect at load.
  `common/fukan/common/typing/` is the dialect AREA (room for a sibling realization if one ever appears).
- `common/fukan/common/extraction/` (ns `fukan.common.extraction.*`) — the Clojure EXTRACTION SEAM: `core.clj`
  (orchestration: clj-kondo `analyze` + `op-eid`, calling each element's builder) +
  `clojure/{effect,module,operation}.clj` (the per-element extraction builders). Mints no structures;
  the HOOK for the extraction plug-point; the composition root registers `extract-roots`.

`src/fukan/canvas/core/reflect.clj` (ns `fukan.canvas.core.reflect`) — GRAMMAR REFLECTION, kernel-native
**CORE machinery** (NOT the reusable `fukan.common` vocab): `reflect`/`with-grammar` reifies the registry
→ model db (every defstructure → a `Structure` node, slots as `:slot/<card>` edges, laws as `:val/form`
payload nodes, one `Vocabulary` per ns). It is grammar-AGNOSTIC (reifies whatever registry exists) and
the build ALWAYS runs it, so it belongs with the machinery, beside the act grammar in `core/lens.clj`.
The runtime never consults the reflected nodes — they exist only so the grammar is viewable as data (the
print-dual, `unused-structures`). The meta-grammar it mints — `Structure`/`Law`/`Vocabulary`/`Relation`/
`Correspondence` — is the tool's own vocabulary for describing grammars and bridge
presentations (same category as the act grammar), hence core. A `(correspond …)` reflects as a
`Correspondence` node with `:from`/`:to` edges to its design and codomain `Structure`s plus the pairing
`:val/match` and realization `:val/map` as scalar payloads; a derived
defrelation carries its rule body on its `Relation` node; and a `Vocabulary` is a presentation fragment —
its owned declarations plus DERIVED `:imports` edges (slot targets, law
rule-calls, (:sub …) genera — entailed from use, never authored). The reflection
ns-closure additionally follows each correspondence's codomain through the registry, so an imported
vocabulary reflects even with zero instances.
It reaches the type dialect only through the neutral SPI (`core/typing`), so it depends on no concrete
dialect (the composition root wires that). `doc/THEORY.md` names the theoretical frame all of this
instantiates (one deductive presentation over a finite relational model).

(The Lens-act `Coverage` law that a projection's focus once needed was DISSOLVED 2026-06-29: a
projection now carries its focus ITSELF — an inline `:select`, a named `Lens` only when a focus is
genuinely shared, none = the whole model — so the guarantee (a reading's selection cannot drift from
the reading) is structural, not a law.)

**The `canvas/principles/` layer was CUT (2026-07-13)** to focus scope on vocab-building +
verifiable models before re-widening ambition. Its genuine principle content (parse-don't-validate
TrustBoundary + totality, declared-effects/deep-modules/operation-surface demands and the
Boundary/Depth/latent-boundaries readings) is gone (git history preserves it); the foundation it had
bundled was kept — the two module-graph enforcement laws rehomed onto `Subsystem`, the correspondence
call-readers onto `operation.clj` (with the rest of Operation's correspondence), the
effect-correspondence reader onto `effect.clj`.

`Band` is `Subsystem`'s sibling and the difference is the EVIDENCE: a Subsystem clusters authored
Modules and checks `:may-depend` against `module-depends` (built from authored `:delegates`, so it
says nothing until a region is modelled op by op); a Band claims namespaces by NAME PREFIX and checks
the same declaration against `ns-depends`, which extraction produces the moment it runs. That makes a
large existing codebase declarable in an afternoon instead of an adoption project. Membership is
DERIVED, never authored — a namespace's band is readable from its own name and cannot drift from the
tree. Its coverage law ("every namespace belongs to a band") is GATED on at least one Band existing,
and the gate is what the rule MEANS rather than politeness to non-adopters: a project declaring no
bands asserts no partition, while one declaring a band asserts a partition, and a partition with a
hole in it is exactly the blindness the law exists to close (an unbanded package is an offender
NOWHERE — the cross-band law needs both ends banded before it fires).

The grouping ladder is a TREE, not a chain: `Grouping` (bare membership, `:child [:* Any]`) is
refined by two siblings that narrow the member sort — `Module` (a code namespace: a Grouping over
code elements, `:child [:* Operation Kind Module]` — the union is the membership constraint, its
generated target-type law the teeth) and `Subsystem` (a cluster of modules realizing a capability,
`:child [:* Module]`, with a declared `:may-depend` DAG it enforces ITSELF — conformance +
acyclicity are its slot-semantics laws — against the extracted code graph). There is **no convenience umbrella** — Clojure can't re-export the generated
instance-constructor macros, so consumers `require` the specific elements they use; structure
tags are verbose (`:fukan.common.vocab.code.operation/Operation`). Grow this vocab **only under
concrete design pressure** — never a methodology/middle layer designed abstractly ahead of real
cases. Methodology-shaped vocab (DDD/Wlaschin/APoSD idioms) is welcome once a concrete case
presses it out.

A `defstructure` is a composition of **slots** plus **laws**:

- Slots are ONE map of `rel → type-expr`; cardinality is a quantifier: a bare target
  is one (`:reads Model`), `[:? T]` optional, `[:* T]` zero+ ordered, `[:+ T]` one+
  ordered, `[:set T]` unordered (no `:rel/order`; order and duplicate targets are
  excluded from value identity). A scalar target — a bare malli keyword
  (`:string`/`:int`/`:boolean`) — stores a leaf value with an auto-generated
  type-check law; any other
  vector (`[:enum "a" "b"]`, `[:int {:min 1}]`) is a REFINED scalar: the core stores
  the type form verbatim and the generated law checks values through the registered
  type dialect (`fukan.canvas.core.typing`, the kernel's third plug-point;
  `fukan.common.typing.malli` registers `:valid?` at load). Never hand-write a membership/range law.
- Slot options ride the props position: `[:? {:payload :q} :string]` (`:payload` =
  a companion code-form stored as a sibling `:val/` datom); for cardinality one,
  lead with the props map: `[{:payload :q} :string]`. `{:form true}` stores the scalar
  value itself as declaration-position code data, authored unquoted. `(reader f)` expands authoring
  data-literals (e.g. the malli dialect's Schema expands native malli forms); a
  vocabulary-local inline `(syntax f)` hook (map → map) rewrites an instance's slots map before parsing
  (e.g. the code `Operation` rewrites `:signature` into `:in`/`:out`).
- INSTANCES mirror defstructure position-for-position: `(Structure name "doc"?
  {slot → value}? nested…)` — a top-level def-emitting form (the symbol is the var
  AND the entity name; `^{:name "…"}` metadata overrides, e.g. a name the var can't
  carry). One map of `slot → value`: a plural slot takes a VECTOR of targets
  (authoring order = `:rel/order`; the bracket mirrors the quantifier), a labelled
  target is a `[label target]` pair, a payload slot takes `[value payload]`, reader
  literals pass as values. Entity instances always require the symbol; only
  `^:value` structures are anonymous expressions. Nested member instances trail where
  defstructure's laws sit and route by target-type into the container's slots.
- `^:value` structures are content-deduped, inline-anonymous nodes (structurally
  equal values collapse to one node) — used for nameless compound data.
- `(law "desc" {:offenders [?x] :where […] :rules […]? :scope …? :key …?})` is a datalog
  constraint — ONE unquoted map, the same declaration cell as everything else (datalog in a
  declaration form is data by position, never quoted; quotes belong to evaluated contexts —
  the REPL, `q`. The old kwargs body throws). `:scope :global` opts a law out of
  self-scoping. The recurring shapes have COMBINATORS —
  `(law "desc" (matched-by R :from S? :when {k v}? :scope T?))`, `(has R :when …?)`,
  `(has-any R1 R2 …)`, `(target R {k v})`, `(at-most-one R)` — which expand to
  datalog emitting `not-join` directly (the Cozo query compiler lowers stratified
  negation correctly, so the combinators need no negation-routing dance; never
  hand-write these shapes). `(structure/check db)` runs every law → violations.
- `(is ?x Sort)` pins a clause's sort NS-PRECISELY — the precise dual of the bare kind-rule
  call: the sort symbol resolves at DECLARATION time through the declaring ns (requires-based,
  like an instance reference; a law may name the structure being defined; `::Sort` for a
  same-ns forward reference; the full tag keyword where a require would cycle — resolution
  rides the acyclic ns graph), and the query compiler LOWERS the resolved tag — a
  `:structure/of` triple for a direct tag, the kind-rule call for a realized concept. The bare
  rule call `(Sort ?x)` stays the deliberate CO-LOAD UNION (any same-short-named sort). Never
  hand-write a `[?x :structure/of <qualified-tag>]` guard in vocab laws/defrelations.
  Evaluated contexts (REPL `focus`, `q`) pass the qualified tag — a bare symbol resolves only
  in declaration forms. SPELLING converges to the most-resolved reader form available:
  `::Sort` same-ns, `::alias/Sort` through an existing ns alias; a FULL tag keyword is
  reserved for — and signals — a namespace deliberately NOT required at that site (kind.clj's
  require-cycle, the architecture print-dual's data-only vocab coupling).
- Correspondence is a CROSS-TAG BRIDGE PRESENTATION, declared EXTERNALLY via
  `(correspond [Design ?d Fact ?f] match realization-map)` (both concepts' own `defstructure`s stay
  pure identity — there is no inline correspondence form). The HEAD is the correspondence's identity
  (design sort first); the MATCH body is flat identity logic (ordinary Datalog, may reference the
  ambient `corresponds` — recursion through the pairing, acyclic); the REALIZATION MAP is TOTAL over
  the design sort's non-scalar slots, each entry a PURE code-graph path (the same-named atom `:in :in`
  is identity realization, `nil` is declared-unrealized). `Fact` is the CODOMAIN — a real `defstructure`
  whose slots are the extracted constructs (design `Operation` ↦ Clojure `Fn`; design `Module` ↦ `Ns`);
  the two strata are DISTINCT tags. The whole declaration lowers EXCLUSIVELY to rules (a pairing rule
  feeding the open ambient `corresponds`, a compiler-minted `realized-<rel>` per entry, per-`^:value`
  reflexivity) — definitional, conservative, no denials. Coverage classes are READINGS, not laws; no
  author-installed comparator or bridge callback exists. Registration keys by the sort pair (cross-ns
  collision throws; multiple correspondences per design sort are allowed).

The current catalog is the source — or just run `(grammar)` in the REPL: the
print-dual renders every vocabulary live. The files are under `common/fukan/common/vocab/**`.

A `defrelation` (in `core/structure.clj`, sibling of `defstructure`) declares a RELATION as an
ELEMENT — the relation itself, not a slot that happens to use it. Three forms, ONE construct
(since 2026-07-20 the `{:isa …}`/`{:transitive true}` character map and `defrelation-coproduct`
are RETIRED — an inclusion states its included relation, a direction, and a regular-relation
expression E over the same path language a correspondence realization entry (`rel → E`) draws on):

- **Bare** — `(defrelation :contains "doc")` — an OPEN primitive relation / genus: claims the name
  (global presentation identity), reflects, owns the doc; its edges come from slots of that name or from
  other relations' inclusions into it. **The kernel names no relation of its own:** the `contains`
  genus and the by-name `within` are vocab elements (both `vocab/grouping`).
- **Inclusion** — `(defrelation :child "doc" (:sub :contains))` — the relation stated as an
  inclusion, lowered GENERATIVELY to a rule: `(:sub atom)` — the included relation accumulates this
  one's edges (the old `:isa`); `(:sup E)` adds E to an OPEN head; `(:eq E)` defines a CLOSED head
  and rejects any additional contributor. E is an atom / `[:alt …]` (one rule per alternative —
  the old coproduct) / a regular path over atoms (`:cat`/`:+`/`:*`/`:?`), with two zero-width steps:
  `[:not pred]` filters the current position (may not end a path) and `[:inv r]` takes an inverse hop.
  Compound closure (`[:* C]`/`[:+ C]` over a compound `C`) is legal INSIDE a correspondence realization
  entry — it mints an auxiliary recursive rule automatically — but an inline `(path …)` context still
  rejects it.
- **Derived** — `(defrelation :module-depends "doc" [?m ?n] […])` — a named custom-bodied datalog
  CLOSED rule (head + bodies, unquoted — declaration forms never quote) for anything beyond the fragment;
  multiple bodies = recursion (base + step); other declarations may not feed its head. Prefer non-recursive: the rule pays the fixpoint on
  every check.

**Closures are the COMPILER's, not declarations:** `terms-of` emits every binary relation's `R+`
unconditionally, and per-query rule injection is reachability-scoped, so a query pays for a closure
only when it references it. Nothing declares `:transitive` anywhere — not elements, not slots.

A relation's tag is UNQUALIFIED (`:contains`) because its rule name is global — so the NAME is
global presentation identity: a second vocabulary re-declaring it THROWS at registration (the registry records
the declaring `:ns`; before 2026-07-20 this collision was silent replace-on-register). Anything
scoping by tag namespace falls back to that `:ns` (as the declarations golden does). `:delegates`/
`:calls` are still slot-only (not elements) — they belong to no declaring fragment, visible in reflection as
unowned `Relation` nodes. ⚠ REPL: MOVING a defrelation to another ns trips the collision guard on
`(refresh)` (the defonce registry keeps the old entry) — restart the REPL, like a removed defmethod.

## Spec locations

Laid out by **tier** (shipped library vs fukan's own use) and, within a tier, by **altitude**:

- `common/fukan/common/vocab/**` (ns `fukan.common.vocab.*`) — the shared VOCABULARY (the grammar):
  the structural primitives (`grouping`), the code grammar by element
  (`code/{kind,effect,operation,module,subsystem}`), and the pattern tier above it
  (`patterns/plug_point`). Loaded via the `fukan.common` index (require), NOT discovered.
- `common/fukan/common/typing/malli.clj` (ns `fukan.common.typing.malli`) — the malli type DIALECT plugin
  (its own area, not general vocab; realizes the `typing` SPI). `common/fukan/common/extraction/**`
  (ns `fukan.common.extraction.*`) — the Clojure EXTRACTION SEAM plugin (realizes the extraction SPI).
  Both in the `fukan.common` index; the whole tier is a shipped classpath root (`common/`), so a second
  project reuses it as a dependency.
- `src/fukan/canvas/core/reflect.clj` (ns `fukan.canvas.core.reflect`) — grammar REFLECTION (registry →
  model db). Kernel-native **CORE** machinery, NOT the reusable vocab: grammar-agnostic, the build always
  runs it, beside the act grammar in `core/lens.clj`.
- `canvas/principles/` — **CUT (2026-07-13).** The adopted-principles layer was removed to focus
  scope on vocab-building + verifiable models; its two module-graph enforcement laws rehomed onto
  `Subsystem`, its correspondence readers onto `operation.clj`/`effect.clj`. (git history preserves it.)
- `canvas/instruments/` — fukan as a *user of itself*: its use-side INSTANCES (Lens/Projection
  tool-definitions authored against the act grammar in `core/lens.clj`). Currently **PARKED —
  fukan ships none** (the dir is empty/absent). The DOWNWARD projection (materialization + the
  readings) was CUT 2026-07-15; the act grammar (`Lens`/`Projection`/`Check` + the selection
  engine) stays as the dormant seam, kept live only for inspection — the print-duals resolve
  their focus through `core/lens.clj`'s `focus-nodes`.
- `canvas/architecture/<area>/…` — fukan as a *built* system: one self-spec per `src/`
  module, grouped by area (`kernel`/`ingestion`/`cozo`/`projection`/`orchestration`), plus
  `subsystems.clj` (the capability clusters + the declared `:may-depend` DAG the
  architecture-quality laws enforce). Models ONLY fukan's `src/` — the vocab, dialect, and
  extractor are tools fukan *uses*, not part of its built design.
- `.legacy-allium/` — pre-canvas Allium/Boundary specs (read-only archive; not on
  the classpath; not loaded).

Instance-bearing spec files under the configured spec-dirs (`*spec-dirs*`, default `["canvas"]` →
`canvas/**/*.clj`, fukan's self-model) are **auto-discovered** — adding a spec is a single file drop
(no registry edit). The `fukan.common` grammar tier is not discovered; it loads by *require*.

## Cross-spec references

Instances are top-level `def`s holding values; references between specs are ordinary
**var references** (require + var capture; `declare` for forward refs in one ns) —
the global assembler resolves them, there is no merge/cross-ref pass. Structure tags
are namespace-qualified (identity = defining ns + name), so co-loaded projects may
share short names; the remaining edge is law SCOPING, which rides short-name rules.

## Conventions

**Ownership-on-owner.** Module ownership flows via `:child` relations on the
owner, not via back-references on the owned entity. Nested authoring routes members
into the container's slots automatically (`(Module m … (Operation f …))`).

**`^:export` for dynamically-invoked vars.** Vars reached only through dynamic
dispatch — any var called from a law's `:where` clause (a datalog predicate) or a
`(syntax …)` hook — carry `^:export`. Both
`.clj-kondo/config.edn` and `.lsp/config.edn` honor `:exclude-when-meta #{:export}`,
so the metadata alone suffices. Prefer `^:export` over a per-namespace exemption.

**Per-namespace lint exemptions live in BOTH `.clj-kondo/config.edn` and
`.lsp/config.edn`.** clojure-lsp doesn't honor clj-kondo's per-namespace config, so
namespace-wide `unused-public-var` exemptions must be mirrored in both files. The
only standing case is law-only test structures whose generated macro is never
called. When adding a namespace to one file's list, add it to the other.

**clj-kondo CLI is ground truth.** The defstructure DSL is taught to clj-kondo via
`:hooks` — one generic `hooks.fukan.structure/instance` hook that every per-structure
instance-constructor macro routes to. Those per-structure `:analyze-call` entries are
**generated, not hand-written**: `tasks.kondo` scans the defstructure forms and writes
`.clj-kondo/generated/config.edn` (merged into `.clj-kondo/config.edn` via
`:config-paths`). After adding or removing a structure, run `clojure -M:kondo` to
regenerate; the `tasks.kondo-test/generated-config-file-is-current` test guards drift.
Editor `not-a-function` / `unused-public-var` flashes on defstructure bodies are false
positives without the hook cache; the canonical full-classpath
`clojure -M:lint --lint src common canvas test` is authoritative (the `:lint` alias carries the
`.` root so `hooks.fukan.structure` resolves — a bare `-M` misses it now that `.` is off the base
`:paths`).

## REPL workflow

The serving daemon is paused, so the loop is **in-process** (`clj -M:dev`), over the
structure substrate which is the model:

- `(go)` — load the model (`build-model`, defaults to a `"src"` code-root so the
  Clojure extractor merges code onto the design graph).
- `(refresh)` — reload changed code + rebuild the held model. Use after editing a
  canvas spec or any `src/` code.
- `(reset)` — reload + rebuild from scratch. Use after adding a new canvas file or
  removing/renaming a var (a removed `defmethod`/`defn` lingers until a clean reset).
- `(status)` — report model state. `(drift)` — modelled Operations no function realizes, a READING of
  the `corresponds` pairing join (gated on extraction, not a law).
- `(show 'name)` — print a node as its AUTHORED form (the instance print-dual);
  `(focus '[(Operation ?n) …])` — render a datalog-selected slice as authored
  forms (the textual model explorer); `(check)` — violations with each offender
  quoted as its form.
- `(correspondence)` — the design↔fact seam as one card: each `(correspond …)`'s authored form plus its
  live coverage readings (unrealized/ambiguous) and a trailing unaccounted-public count.
- `(encapsulation)` / `(type-drift)` — the other correspondence READINGS: public functions no
  Operation models — scoped to ADOPTED namespaces (see below); paired Operations whose signature
  disagrees with the code's `:malli/schema`.
- `(leaves)` / `(frontier)` — the INCREMENTAL-ADOPTION instruments, for pointing fukan at an existing
  codebase one module at a time. `(leaves)` ranks the unadopted namespaces that depend on nothing else
  in the project by fan-in — where leaf-upward adoption starts, readable with NO model authored at all.
  `(frontier)` reports adopted code calling out into code the model does not yet claim: the blind spot
  (such a call has no `:delegates` edge — unauthorable, the slot only targets an authored Operation —
  and no `realized-delegates`, which needs both ends paired), so it is both the proof that a module
  called a leaf really is one, and the worklist for what to adopt next. Both are READINGS.
- `(undeclared-code-dependencies)` — the code module graph (through `realized-delegates`) vs the
  declared `:may-depend` DAG: cross-subsystem code calls no declared edge covers. A SIGNAL, not a law.
- Build a db directly: top-level instance `def`s + `(a/assemble-vars [#'x …])`, query with
  `d/q`, run `(s/check db)`.
- **REPL helpers speak only through NAMED surfaces** — stable law keys
  (`law/violation-names`/`violations-of`), defrelations, and the print-duals — never inline
  `:rel/*`/`:val/*` substrate datalog. Named surfaces fail LOUD when the grammar moves (an
  unknown law key THROWS; a relation collision throws at registration); inline substrate
  queries rot silently into always-empty helpers.
- **Never** use `remove-ns`, `require :reload`, or `(reload/reload)` directly.

nREPL runs on port 7889 (`clj -M:nrepl`).

## Build pipeline

`build-model code-root` (`model/pipeline.clj`): discover + ingest the project's instance specs
(`*spec-dirs*`, default `["canvas"]` → fukan's self-model; the `fukan.common` grammar tier is loaded
by *require* at the composition root, not discovered); when a `code-root` exists AND an extractor is
registered, merge the extracted code structures onto the same graph and re-resolve cross-refs.
`(structure/check db)` then runs all laws over the joint graph. Correspondence lowers to definitional
rules (`corresponds`/`realized-*`), NOT laws, so `(check)` currently carries no correspondence
violations; model↔code drift is READ off those rules (`(drift)`/`(encapsulation)`), and constraints
over them are a deferred law layer. The legacy Allium/Boundary parse phases
and the old Phase 4–6 analyzer are retired.

**Classpath tiers (`deps.edn`).** Base `:paths ["src" "common" "resources"]` is the *shipped* surface —
`fukan.*` (core) + `fukan.common.*` (grammar). The `.` root (fukan's self-model `canvas/`, `tasks/`,
`hooks/`) lives on the fukan-local aliases (`:dev`/`:test`/`:kondo`/`:lint`/`:nrepl`/`:run`), so a
consuming project inherits only core + grammar. **Consuming fukan from another project:** depend on
fukan, `(:require [fukan.common])` (or the specific `fukan.common.vocab.*` elements), bind
`fukan.canvas.ingestion.canvas-source/*spec-dirs*` to your own spec dir, and call `build-model` with
your own code-root (the default composition in `fukan.infra.model` registers the Clojure extractor +
malli dialect; write your own composition to vary either).

## Jujutsu workflow conventions

This repo uses Jujutsu (jj). Always check `jj st` before starting work. If `@` has
existing changes, run `jj new` to start clean. Commit per logical change:

```
jj desc -m "type(scope): short description"
jj new
```

Never use git commands directly — jj and git have different object models and
mixing them corrupts history.

## Key Files

- `dev/user.clj` — REPL helpers (`go`/`refresh`/`reset`/`status`/`drift`/`encapsulation`/
  `correspondence`/`undeclared-code-dependencies`/`leaves`/`frontier`) — thin PRINTERS now: the
  coverage + adoption reading QUERIES ship in the `fukan.common` Clojure tier (an external consumer
  needs them, and they name `public`/`Ns` — vocabulary the kernel tier must not)
- `src/fukan/infra/model.clj` — model lifecycle + the composition root (registers
  the Clojure extractor)
- `src/fukan/model/pipeline.clj` — `build-model` (canvas ingestion + extraction merge)
- `src/fukan/cli.clj` — the non-REPL entry: what the REPL cockpit does, for a PROGRAM. Two verbs,
  because a reader arrives with two questions — `clojure -M:fukan -m fukan.cli describe
  [--spec-dirs canvas] [--format prose|forms]` (what has this project DECLARED — the design as
  PROSE by default, or as the authored forms) and
  `… -m fukan.cli check --src src [--spec-dirs canvas] [--format edn|text]` (does the code still
  OBEY it — violations as DATA: offender eids resolved to names, rows kept as TUPLES so an
  edge-binding law carries both ends, `:vars` alongside so a consumer can label the columns).
  `describe` takes NO `--src`: a declared design is what the project SAID, and one that moved when
  the code moved would not be a declaration (it is also 40ms instead of 8s). Exit **0** satisfied /
  **1** unsatisfied / **2** UNDECIDABLE — a consumer must tell "your design is violated" from "the
  checker broke", or it waves through the branch that broke the checker. stdout is the report,
  stderr is the narration
- `src/fukan/canvas/projection/prose.clj` — the PROSE dual: the same declarations as SENTENCES.
  A form dual renders the AUTHOR's view (`{:may-depend [:* Band]}` is what you would write to
  change it); a reader who has to OBEY the design wants to be told the rule, not to infer it from
  a quantifier vector — and that reader is usually an agent now. It queries nothing: every fn is
  a pure function of `structure-form`/`instance-form` output, which is what keeps the two views
  from drifting into two designs. Law DESCRIPTIONS, never law bodies
- `src/fukan/canvas/projection/design.clj` — the DESIGN projection: grammar + instance duals
  composed into the document a reader arrives for, in either register. Its own contribution is
  the SCOPING, and neither exclusion is a heuristic: the reflection meta-grammar is in every model and authored by none, and
  an anonymous `^:value` node is already rendered inline by its owner. What remains is derived from
  the INSTANCES — a vocabulary appears because the project instantiated something from it, so a
  grammar merely loaded is never mistaken for a design
- `src/fukan/canvas/core/structure.clj` — the `defstructure` primitive + `check`
- `src/fukan/canvas/ingestion/canvas_source.clj` — canvas discovery, merge, cross-refs
- `common/fukan/common.clj` (ns `fukan.common`) — the grammar INDEX: one require that registers the
  whole `fukan.common.*` tier (vocab + typing + extraction)
- `common/fukan/common/vocab/` (ns `fukan.common.vocab.*`) — fukan's vocabulary: the code grammar by
  element + grouping. **Pure design, language-neutral — carries NO correspondence.** The membership
  relations are ELEMENTS here: the `contains` genus + `:child` + the derived by-name `:within`, all
  in `grouping.clj` (`code/module.clj` declares no relation element — membership is `:child` with a
  union target; the pattern tier's derived `offers` rides `patterns/plug_point.clj`). The
  module-dependency-graph relations + readers (`module-owns`/`module-depends`/`module-dependencies`)
  live with the architecture laws that consume them in `code/subsystem.clj`
- `common/fukan/common/extraction/clojure/` — the FACT vocabulary + the design↔Clojure bridge:
  `operation.clj` holds the `Fn` codomain and the essential correspondence
  `(correspond [Operation ?op Fn ?fn] match {:in :in :out :out :performs … :delegates …})`, whose
  realization map lowers to `realized-<rel>` rules, plus the adopted-scoped `unaccounted-public`
  reading; `module.clj` holds the `Ns` codomain and the twin ROOT
  `(correspond [Module ?m Ns ?ns] match {:child :child})`, plus the INCREMENTAL-ADOPTION readings —
  the `adopted` relation (the Ns half of a live pairing, which is what makes the frontier need no new
  declaration) and `ns-dependencies`/`adoption-frontier`/`adoption-candidates`. `ns-depends` is a DEFRELATION
  again since the substrate fix: the three-way join that once cost 58-69s on clojure-mcp against 0.70s
  for two Clojure-side pulls was the `triple` view plus un-oriented rules, not the planner
- `src/fukan/canvas/core/reflect.clj` (ns `fukan.canvas.core.reflect`) — grammar REFLECTION (registry → model db); kernel-native CORE machinery, not the reusable vocab
- `common/fukan/common/typing/malli.clj` (ns `fukan.common.typing.malli`) — the malli type DIALECT plugin, one file
  (shape vocab + bridges + wiring); realizes the `typing` SPI
- `common/fukan/common/extraction/` (ns `fukan.common.extraction.*`) — the Clojure EXTRACTION SEAM plugin: `core.clj`
  orchestration + `clojure/{effect,module,operation}.clj` (realizes the extraction SPI)
- `canvas/architecture/` — fukan-on-fukan's built-system self-specs (modules + subsystems +
  `:may-depend` DAG); the use-side instruments area (`canvas/instruments/`) is currently PARKED
