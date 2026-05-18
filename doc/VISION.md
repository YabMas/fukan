# Fukan — Next Chapter Vision

**Status:** Forward-looking design framing — the *why* of the next chapter.

**Reading order:** Start here for motivation and framing. [DESIGN.md](./DESIGN.md) carries the application-design specification (protocols, altitudes, pipeline, project layer). [MODEL.md](./MODEL.md) is the authoritative substrate spec (kernel, vocabulary mechanism, constraint language). [DECISIONS.md](./DECISIONS.md) preserves the design-phase decision trace.

---

## What this chapter is about

Fukan today is a code-graph explorer that knows how to read specifications. The next chapter inverts that: fukan becomes a **specification-graph explorer that reads code as projection of the spec**. The Allium specification language stops being decorative content layered onto a code-shaped Model and becomes the **shape of the Model itself**; code joins as a projection layer whose alignment with the spec is continuously checked.

This is not a feature addition. It is a re-foundation. The same pixels render in the browser; the same `clj -M:run` starts the server. But what the graph *means* changes — from "this is what the code does, here is some spec context" to "this is what the system is, here is how each piece is realised."

---

## Where fukan is today

Honest assessment.

The current Model — defined in [`src/fukan/model/spec.allium`](../src/fukan/model/spec.allium) and [`src/fukan/model/schema.clj`](../src/fukan/model/schema.clj) — pivots on three node kinds (`Module`, `Function`, `Schema`) and three code-shaped edge kinds (`function_call`, `dispatches`, `schema_reference`). The Allium analyzer contributes to this Model by:

- Folding `entity` / `value` / `variant` declarations into `Schema` nodes carrying a `TypeExpr`
- Attaching `guarantee` declarations as a list on `Boundary`
- Attaching `invariant` declarations as a list on `Module`, with bodies kept as opaque strings

Allium's richer constructs — rules, surfaces, contracts, actors, deferred specs, derived values, transitions, projections, named triggers — are **dropped at the door**. The Model has nowhere to put them. The result is a code-graph with thin spec annotation: useful for code navigation, but not the workbench for high-level structure that the project's stated purpose calls for. ("Projections" here is Allium's own behavioural-spec clause within `.allium`, not the kernel's cross-altitude `projects` relation introduced in this chapter — the two are unrelated despite the lexical overlap.)

This is the foundation we're flipping.

---

## The shift

Three sentences:

> Today fukan is a code graph that knows about specs.
> The next chapter makes it a spec graph that knows about code.
> The Model's vocabulary is determined by what the spec says exists; code is the projection layer underneath, accountable to the spec via convention-resolved `projects` edges.

The reason this matters: as LLMs handle more low-level coding, the human's value migrates upward — to module boundaries, contracts, invariants, behavioural intent, architectural composition. A workbench for that level needs a Model whose primitives are *those things*, not whose primitives are call graphs and var definitions.

The current Model can describe what code does. The new Model describes what the system *is meant to do* — and, via convention-driven projection edges, where intent and reality diverge.

---

## The three altitudes

The next chapter recognises that "specification" is not one thing. Different concerns live at different altitudes, and conflating them produces a language that's clean for nothing in particular.

| Altitude | What it specifies | Files |
|---|---|---|
| **Behaviour** *(highest — abstract intent)* | Rules, events, top-level invariants — what happens | `*.allium` |
| **Structure** | Types, operations, surfaces, contracts; the binding between contract operations and rules; composition of modules into subsystems | `*.allium` (partial) + `*.boundary` (binding + composition) |
| **Infra** *(lowest spec altitude — declarative deployment commitment)* | Endpoints, storage, wire formats, transports, deployment topology | `*.infra` *(later)* |

Altitudes and files are not 1:1. `.allium` already covers Behaviour in full and partial Structure (operations inside contracts, surfaces, types). What `.allium` cannot grammatically express is the binding between Operations and the Rules they invoke, and composition of modules into subsystems — `.boundary` is the file format that fills those Structure-altitude gaps. `.infra` adds a lower altitude entirely.

Each file format is opt-in. A project that writes only `.allium` produces a complete, valid Model. Adding `.boundary` fills in binding and composition. Adding `.infra` (later) adds deployment commitments.

The altitudes respect one direction: **strict one-up reference**. Lower altitudes reference the altitude immediately above; never downward, never skipping. `.boundary` (Structure) references `.allium` Behaviour content. `.infra` (Infra) references Structure. Concrete commitments at lower altitudes are accountable to the abstract intent at higher ones, not the reverse — that is what keeps each altitude clean for its concern.

**Implementation is not a fourth altitude.** It is what *materialises* any of the three: code realising Behaviour rules, infrastructure realising Infra commitments, documentation projecting spec content. Spec is the source; code, infrastructure, tests, and docs are projections of it — chosen materialisations that lose information from the source. Drift is a projection that has ceased to be valid.

In this chapter, we commit to Behaviour in full (`.allium`), Structure in full (`.allium` partial coverage + `.boundary` for binding and composition), and bidirectional code linkage via the Clojure Target language extension: its Analyzer produces convention-resolved `projects` edges to `Code.*` artifacts (surfacing spec-to-code drift on every rebuild), and its Projector produces Implementation Blueprints on demand for LLM-driven code generation from spec. The Infra altitude (`.infra`) and its corresponding Infra Artifact cases stay architecturally seamed; the Documentation flavour of the Artifact ontology likewise awaits its producing analyzer (see the *What this defers* section below).

### Why three altitudes and not one big language

Allium tried to be one big language. It mostly succeeded for behaviour and for the structure of individual contracts, but it left two things open: composition of modules into subsystems (filesystem-derived rather than declared), and the binding between contract operations and the rules they invoke (no grammar connecting them). It also explicitly excluded deployment specifics. Those exclusions are correct — Allium's clarity comes from what it refuses to model. The next chapter respects those exclusions and provides them their own homes.

This is also why none of the new file formats is "an extension of Allium." `.boundary` does not extend Allium; it speaks at the same Structure altitude Allium partially covers, filling the binding and composition gaps. `.infra` speaks at a lower altitude entirely. They are siblings of Allium's outputs, not subclasses.

---

## The project layer

Allium is intentionally flexible: `provides` actions and contract operations both express "an action at a boundary" but with different protocols; `exposes` declares public visibility but doesn't pin down whether reads happen via direct field access or via wrapper operations. Two engineers (or two LLM sessions) describing the same system with different primitives can both be technically correct.

The **project layer** is where a project makes its choices explicit — which primitives it uses for which situations, and the way it applies them. The same content serves three audiences: human readers orienting to "how this project does things," LLMs designing or extending spec needing patterns to conform to, and the build pipeline checking that the Model in fact conforms. The primary purpose is making the project's design vocabulary explicit; validation and generation are useful consequences.

The layer is not a fourth language at a different altitude — it annotates the three spec languages rather than peering with them. It hosts both soft preferences (naming styles) and hard architectural laws (e.g. "no Rule in `Hex::Core` writes to a `Hex::Adapter` Container"); severity is per-entry. Mechanics — entry shape, the two sub-loci (projection inputs + constraints), composition — live in [DESIGN.md](./DESIGN.md). Projection inputs ship in declarative form from day 1; constraints author against the constraint language's Datalog AST until surface-syntax tokenisation lands. Composition mechanics across methodology-shipped + project-shipped baselines (severity overrides, profiles, bundles) wait until lived experience forces their shape.

---

## What this enables for users

Concretely, after this chapter lands:

**Navigable spec graph.** A team writes `.allium` and `.boundary` files. Fukan renders the spec as a navigable graph: behaviours connected to triggers, surfaces connected to actors and contracts, types connected by field references and variant relationships, operations bound to rules via `.boundary` declarations. Each node is clickable; each relationship is followable. The explorer is the workbench for reasoning about the spec — meaningful even before any code exists.

**Architectural exploration as a first-class activity.** Because `.boundary` owns composition (and filesystem layout no longer drives it), restructuring the system is a one-file edit, not a refactor. Topology becomes a *design surface*, not a side-effect of where files happen to live. Alternative decompositions live on git branches — or in workspace-scoped overrides once that mechanism arrives — rather than as competing `.boundary` files in one Model: the build pipeline enforces a single composite parent per module, so "three decompositions side-by-side" is a multi-branch workflow, not a multi-file one.

**Gap detection within the spec.** A `Trigger` produced by a Surface but consumed by no Behaviour is a detectable gap. A Surface that demands a Contract no module fulfils is a detectable gap. A Behaviour that mutates a Type owned by a Module nothing depends on is a detectable structural oddity. These were invisible before; the next-chapter Model surfaces them.

**Spec-to-code drift detection — at every rebuild.** Every spec primitive that should have a Clojure realisation (Entity → malli schema, Operation → function, Rule → function, Invariant → function, Event → schema) produces a `projects` edge with per-edge validity. Missing implementation = absent edge = red marker. Code at the expected address = valid edge = green marker. Missing tests are the same red marker against the `test` projection. The mapping is convention-driven — no code-side annotations, no out-of-band binding files; one root-prefix knob, mechanical transliteration, strict single-canonical-address discipline. Drift between intent and reality refreshes on every model rebuild — fast enough to feel live as the project evolves; on-demand evaluation between rebuilds is a post-MVP optimisation. (See DESIGN.md and MODEL.md §7.6 / §10.3.)

**On-demand code generation from spec.** The same projection mechanic that detects drift runs in reverse: clicking a red `absent` drift marker summons the Clojure Target language extension's Projector, which assembles an Implementation Blueprint — an ephemeral, per-projection artifact bundling the canonical address, artifact kind, expected signature, type renderings, surrounding model context, and applicable project idioms. The Blueprint plus a generic system prompt drives an LLM to produce the code that should land at the canonical address. Spec is the source; the Blueprint is the contract between spec and target language; generated code closes the loop. The Blueprint is regenerated fresh per request, never persisted — it always reflects current project projection inputs and current spec. Generation is part of MVP, not a future chapter. (See MODEL.md §7.7 and DESIGN.md *Implementation linkage*.)

**The project layer as a first-class artifact, in two sub-loci.** Project design vocabulary becomes explicit rather than tacit, carried by project-layer entries. Two sub-loci coexist: *projection inputs* encode how kernel concepts project concretely in this project's target language (address-resolution knobs, type-translation overrides, and idioms — per-primitive-kind, per-projection-kind, and per-address-match patterns); *constraints* encode Model-shape rules — from soft naming preferences ("surface names follow Subject + Verb") through hard architectural laws ("no Rule in `Hex::Core` writes to `Hex::Adapter` state"). New contributors and LLMs working in the project read the same entries — orientation for humans, generation context for agents (driving the Blueprint), validation against the Model where machine-checkable.

**Forward compatibility for further projection targets.** The `projects`-edge mechanism is the carrier for *all* cross-altitude correspondences, framed by a single principle: **spec is the source; code, infrastructure, tests, and docs are projections of it** — chosen materialisations that lose information from the source. Drift is a projection that has ceased to be valid. Code projection lights up in MVP; infrastructure projection (when `.infra` joins, comparing spec deployment commitments to observed live reality) and documentation projection (when a docs analyzer joins) hook in additively against the same substrate, with no further substrate work required.

---

## What this defers

Honest about what's not in this chapter.

**The old code-graph.** The previous `Function` / `Schema` node kinds and `function_call` / `dispatches` / `schema_reference` edges are gone, replaced by `projects` edges from spec primitives to `Code.*` artifacts. The Clojure Target language extension's Analyzer itself stays — but produces convention-resolved projection edges, not the old code-graph. Code that doesn't match any spec-primitive's expected projection appears as unprojected `Code.Function` / `Code.DataStructure` nodes (visible, but not bound to spec). Continuity-of-old-visualisation is intentionally not preserved.

**`.infra` itself.** Designed-for but not built. We commit only to leaving the architectural seam open. The `Infra(Endpoint | Resource)` Artifact cases and the `endpoint` / `resource` projection_kind values stay deferred alongside it — they come back together when `.infra` joins.

**Documentation as a projection target.** `Documentation(Page | Diagram)` Artifact cases and the corresponding projection_kind values await a documentation analyzer. Docs-as-input (Container.description, Rule.description, etc., already carried on kernel primitives) is unaffected — that's substrate, not `projects`.

**Project-layer composition mechanics, and a polished surface form for constraints.** Projection inputs are declarative day 1; constraints author against the Datalog AST until path-sugar tokenisation lands. What waits is *composition* — severity overrides for imported constraints, multiple profiles (CI vs dev), bundle composition within a Vocabulary, transitive imports, versioning. Per-entry registration is committed; layered composition over Vocabulary-shipped + project-shipped baselines waits for lived experience to force its shape.

**Stress-test methodologies (DDD / Hex / C4).** Validated the substrate at design time; not committed support targets. Re-join only if a real project pulls them.

This is a meaningfully narrower defer-list than the previous draft: implementation analysis, spec-to-code drift detection, *and on-demand code generation from spec* are all *in* MVP, carried by the Clojure Target language extension's two operations (Analyzer + Projector). The user-facing pitch holds in full — *workbench for spec-driven development with bidirectional spec↔code: analysis surfacing drift, generation producing code from spec via the Implementation Blueprint* — not waiting on a later chapter to make good.

---

## The pitch, refined

**Fukan is the workbench for the structural layer of a system — the layer humans own, the layer that survives across LLM sessions, the layer that should drive what the LLMs build.**

In this chapter, "the structural layer" expands from *code-shape with spec annotations* to *spec-shape across three altitudes (Behaviour, Structure, Infra) with the project's design vocabulary made explicit through the project layer, and implementation reality joined now via the Clojure Target language extension's two operations — projection edges surfacing drift on every rebuild, and on-demand Implementation Blueprints driving spec-to-code generation.* Spec↔code becomes a present-tense bidirectional capability — not a future promise. Future chapters add `.infra`, more methodologies, more target languages, project-layer composition mechanics, and richer drift analyses on the same substrate.

---

*See [DESIGN.md](./DESIGN.md) for the technical specification of the Model, the three boundary protocols, the layer responsibilities, the build pipeline, and the project-layer mechanics.*
