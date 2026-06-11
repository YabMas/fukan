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

## The current phase

Fukan is in a **modelling-exploration** phase: authoring a wide variety of models
directly on `defstructure` — fukan's own self-model and a corpus of standalone
demos — to pressure-test the core in every way. We are deliberately **not** building
a reusable methodology/middle layer (DDD, hexagonal, C4 vocabularies) yet. The core
is kept *able* to grow such a layer later (via the refinement mechanism), but that
is proven only when a concrete need forces it. The focus today is exploring
modelling itself.

## What is deferred

**The interactive browser explorer.** Rendering the graph as a navigable,
in-browser structural explorer is fukan's eventual vision and the origin of its
name. It is **deferred indefinitely**: the core is being exercised extensively
first, and the explorer is not on the near roadmap. Its code is parked under
`.paused/`. Do not propose reviving it as a next step.

**A methodology/middle layer.** Reusable architectural vocabularies arrive only
when real usage justifies them, and only after the refinement mechanism that would
underpin them is proven on a concrete need.

---

*See [DESIGN.md](./DESIGN.md) for the design principles, [MODEL.md](./MODEL.md) for
the substrate spec, and [DECISIONS.md](./DECISIONS.md) for the decision trace.*
