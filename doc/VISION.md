# Fukan — Vision

**Status:** Framing and direction — the *why*.

**Reading order:** Start here for motivation. [DESIGN.md](./DESIGN.md) carries the
design principles (the mechanics-only core, per-project vocabulary, the build
pipeline). [MODEL.md](./MODEL.md) holds the substrate spec. [DECISIONS.md](./DECISIONS.md)
preserves the decision trace.

---

## What fukan is

Fukan is a structural exploration tool for the layer humans own as LLMs handle more
of the low-level coding — the *composition of a system's concepts and the laws that
must hold of them*. The question it explores: as LLMs write more of the code, how
do humans stay in control of high-level structure, and collaborate with LLMs at
that altitude?

The loop fukan is built around is **define → model → verify → project**:

> **Define** a system's structure — its concepts as a composition of slots, plus
> the datalog laws that must hold of it.
> **Model** abstractions over that structure (views, subjects, higher-level
> concepts) on the same substrate.
> **Verify** the whole as one assertable graph — laws run as checks; specification
> and implementation, projected onto the same graph, are checkable against each other.
> **Project** the model down toward an implementation specification.

Specification and implementation live on the **same** graph. That is the thesis:
intended structure and actual structure are not two artifacts to keep in sync by
discipline, but one graph whose internal consistency a machine can check.

## The premise: bottom-up language building, top-down design

Fukan's approach is rooted in the Lisp tradition of **stratified language
building**: you do not model a system in a fixed notation — you first grow the
language the domain wants, then express the system in it. The core ships no
vocabulary; authoring a grammar is every project's *first modelling act*, and
everything above — models, laws, probes, projections — reads in that grammar's own
terms (the core derives its datalog rules from the live vocabulary, and the grammar
itself is reflected onto the graph as data, like everything else).

The counterpart is deliberate **top-down design pressure**: the compositions, the
laws, the stated intent are high-altitude decisions a human commits to, and the
graph holds everything — including the LLM-written implementation — to them.

The premise is that these two motions together — a language grown bottom-up until
the domain reads in its own words, and design pressed top-down until the laws say
what must hold — yield expressions of a system powerful enough to verify against
its implementation, and to project an implementation from.

## Why it matters

As LLMs absorb the low-level work, the human's value migrates upward — to the
boundaries, the contracts, the invariants, the architectural composition. A tool
for that layer needs primitives that *are* those things, not a code graph that
merely annotates them. Fukan's primitive is the **structure**: a composition plus
the laws that constrain it. Everything — a function's signature, an architectural
concept, a workflow, fukan's own grammar and kernel — is expressed as a structure, queried as
one graph, and checked by running its laws.

The tool is REPL-native and agent-native by construction: models are Clojure data
on the classpath, the model is a datascript db, and the whole is queryable by a
human or an LLM with the same datalog.

## The foundation: `defstructure`

Fukan was radically pruned and rebuilt around a single primitive. A `defstructure`
declares a structure as a *composition of slots* plus the *datalog laws* that must
hold of it. The structure substrate **is** the model — there is no separate
model-map, no privileged kinds. The core ships only this primitive and the
ingestion/projection machinery; it ships **no domain vocabulary**. Every modelling
project authors its own grammar on the core.

## The near-term focus: architecture guard-rails

The enduring vision above spans the full altitude range — any structure, any domain.
The *near-term* tool narrows to the one altitude that pays rent today and is most
tractable: **code architecture.** Fukan's general question — how humans stay in
control of high-level structure as LLMs write the code — gets a concrete first
answer: hold the **architecture** of a real codebase as enforceable laws wired onto
the code itself. This is a sharpening of the thesis, not a retreat from it: the
non-code domain layer was the project reaching for a higher altitude; anchoring at
code architecture is choosing the altitude that is buildable and useful now.

**You grow the architecture vocabulary — the core ships none.** This pillar is
undiminished by the narrowing. There is no fixed catalog of rule types (the
ArchUnit / NDepend model); you author the architectural concepts and laws *your*
codebase actually has, on the bare primitive. The opt-in stdlib `lib.code`
(Module / Operation / Effect / …) is a *seed* to extend or replace, never a schema to
fit into. The acts below operate on whatever vocabulary you grew.

**The shape: two inputs → one graph → three acts.** This is "specification and
implementation on the same graph" made concrete.

- **Input — intent.** You author the architecture as grammar + laws: modules and
  their permitted dependencies, operations, effects, boundaries, encapsulation, and
  **data-shape definitions and ownership** — which module owns which type, and its
  shape. A data shape is in scope when it has a *code home*: an owning module and a
  realization as a type/schema/record. The free-floating domain ontology, modelled
  independently of any code, is the deferred altitude.
- **Input — reality.** Fukan's extractor lifts the real code onto the *same* graph
  (today: Clojure via clj-kondo — modules, operations, the call graph, privacy;
  grounding data-shape definition and ownership is the natural next extension).
- **Act — verify.** Laws gate the merged graph; architectural drift surfaces as
  violations. Repeatable, eventually CI-able. *Is the code still what I designed?*
- **Act — lens.** Focus the graph to a sub-graph and read it to surface design
  tensions — for you, and for an LLM collaborator to act on.
- **Act — project.** Re-present the model downward into implementation-instruction
  specs an LLM can build from.

The three acts share top billing — verification, design-tension reading, and
downward projection are equally the point.

**Still exercised by modelling.** Fukan-on-fukan remains the sharpening loop: the
self-model is the codebase fukan continuously checks itself against. The destination
is pointing the same instrument at a larger day-job codebase with the same
architectural idioms; the missing pieces for that — external-repo targeting, a clean
report surface — are ironed out when the time is ripe, not now. As before, fukan does
**not** ship a methodology/middle layer (DDD, hexagonal, C4): those arrive only if a
concrete case presses them out, and even then as primitive structures you opt into,
not a notation you must adopt.

## What is deferred

**The interactive browser explorer.** Rendering the graph as a navigable,
in-browser structural explorer is fukan's eventual vision and the origin of its
name. It is **deferred indefinitely**: the core is being exercised extensively
first, and the explorer is not on the near roadmap. Its code is parked under
`.paused/`. Do not propose reviving it as a next step.

**Modelling above code, and descent from it.** Modelling a domain or concepts at an
altitude *above* code — and deriving a clean code design *down from* that abstract
model — is fukan's eventual reach and a genuine vision. It is set aside for now: the
near-term tool anchors at code architecture and flows downward to implementation, not
upward into domain modelling.

**A methodology/middle layer.** Reusable architectural vocabularies arrive only
when real usage justifies them, and only after the refinement mechanism that would
underpin them is proven on a concrete need.

---

*See [DESIGN.md](./DESIGN.md) for the design principles, [MODEL.md](./MODEL.md) for
the substrate spec, and [DECISIONS.md](./DECISIONS.md) for the decision trace.*
