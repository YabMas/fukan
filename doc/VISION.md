# Fukan — Vision

**Status:** Framing and direction — the *why*.

**Reading order:** Start here for motivation and framing. [DESIGN.md](./DESIGN.md) carries the design principles (three-tier layering, ownership, constraint language). [MODEL.md](./MODEL.md) is the authoritative substrate spec (kernel primitives, vocabulary mechanism). [DECISIONS.md](./DECISIONS.md) preserves the design-phase decision trace.

---

## What this chapter is about

Fukan today is a canvas-driven structural exploration tool. Its Model is built from canvas specs — Clojure data files authored at design-vocabulary altitude — and from Clojure implementation code, projected onto the same substrate. Humans and LLMs navigate the result as an interactive graph.

The canvas-first shift is not a feature addition. It is a re-foundation. The same pixels render in the browser; the same `clj -M:run` starts the server. But what populates the graph changes — from "this is what the code does, here is some spec annotation" to "this is the design vocabulary the system was authored in, here is how the implementation maps to it."

---

## Where fukan came from

Honest assessment of the prior state.

The previous approach used two spec languages co-located with implementation code: `.allium` files describing behavioural intent, and `.boundary` files describing module API surfaces. Fukan's build pipeline parsed these alongside Clojure source to produce a unified Model.

This worked for analysis but carried a tension: the spec languages were rich enough to express significant design vocabulary, but the coupling between spec files and implementation files made the design surface hard to reason about independently. The spec languages were also external to Clojure's tooling — no REPL participation, no namespace system, no standard editor support.

Phase 3 resolved the tension by moving to a canvas-first architecture. The `.allium`/`.boundary` files are archived in `.legacy-allium/`. The canvas — Clojure-embedded design declarations — is now the primary spec surface.

---

## The shift

Three sentences:

> Previously fukan was a code graph that knew about specs.
> The canvas chapter makes it a design-vocabulary graph that knows about code.
> The Model's content is determined by what the canvas says exists; code joins as a Phase 6 projection layer, with per-entity drift edges.

The reason this matters: as LLMs handle more low-level coding, the human's value migrates upward — to module boundaries, contracts, invariants, behavioural intent, architectural composition. A workbench for that level needs a Model whose primitives are *those things*, authored in a tool that integrates with the development environment natively.

The canvas gives that: design declarations in `.clj` files, on the classpath, participating in the REPL, queryable by agents, rendered in the graph viewer.

---

## The analysis-first strategic decision

Phase 3's strategic frame: *analysis substrate before authoring tools*.

The canvas exists primarily to be reasoned over; the graph viewer is fukan's current product surface. Phase 3 makes the canvas observable through that surface. Authoring (collaborative editing, LLM-assisted design conversations) is a later phase.

This ordering is deliberate. Before investing in authoring ergonomics, the substrate must demonstrate it can represent the full vocabulary of a real codebase — modules, functions, records, invariants, rules, getters, checkers — and make that vocabulary navigable in the graph. Phase 3 validated this across 62 modules of fukan-itself.

The bet paid off: with the substrate proven, subsequent work built on it without re-foundation — convention-resolved code projection with per-entity drift edges, a pluggable lens substrate for design-altitude analysis, and an agent query/authoring surface (`bin/fukan`, the `fukan-architect`/`fukan-reconciler` agents, the canvas-authoring system prompt). Authoring today is agent-driven through that surface and the REPL edit→`(refresh)`→query loop; an interactive *browser* authoring experience remains the open frontier (see *What is deferred*).

---

## What this enables for users

Concretely, in the canvas-first state:

**Navigable design-vocabulary graph.** A team writes canvas specs. Fukan renders the canvas as a navigable graph: modules connected by `:references` edges, affordances labelled by role (function, invariant, rule, getter, checker), record types with field structure. Each node is clickable; each relationship is followable. The explorer conveys design intent, not just code shape.

**Spec and code on the same graph.** Canvas specs project through Phase 0 (canvas ingestion) into the same Model that Phase 6 (Clojure target analyzer) writes code artifacts into. The graph shows where canvas-declared affordances have corresponding Clojure implementations and where they don't — drift is a first-class visible property.

**REPL-integrated workflow.** Canvas files are Clojure source files on the classpath. Editing a canvas spec and calling `(refresh)` in the REPL rebuilds the model and updates the graph on the next browser request. No separate compilation step, no external tooling, no round-trip to a different system.

**Architecture-neutral substrate.** The six substrate primitives (Module, Affordance, State, Type, Relation, Tag) carry no architectural vocabulary. Architectural meaning — function call, invariant, reactive rule, lifecycle accessor, validation entry point — lives in vocabulary lifts that projects opt into. Different architectural styles (monolith, CQRS, actor model) can coexist in the same substrate without substrate revision.

**Queryable by agents.** The same Model the graph renders is queryable via `bin/fukan`'s eval surface. Agents can ask "what affordances reference this type?" or "what invariants does this module declare?" without grepping source files. The canvas is the source of truth; the eval surface is the query layer over it.

---

## What has landed since

The canvas-first re-foundation has been extended substantially on the same substrate:

**Drift detection.** The Clojure target analyzer resolves canonical code addresses by convention, so `projects` edges now carry meaningful per-entity `:validity` (`:valid` / `:stale` / `:absent` / `:unknown`) instead of the uniform `:absent` of the early UUID-id scheme. Bidirectional drift between intent and reality is a first-class, queryable signal (`(canvas-drift)`).

**Lens substrate and agent surface.** A pluggable lens layer supports design-altitude analysis modes (new modes = drop a file), and the model is queryable by humans and agents through `bin/fukan` and the `fukan-architect` / `fukan-reconciler` agents.

## What is deferred

**Interactive browser authoring.** Canvas is authored today as `.clj` files edited in the REPL loop, and agent-assisted through the query/authoring surface. An interactive *browser* authoring experience — in-graph editing, real-time feedback during design — is the open frontier (the explorer rewrite).

**Vocabulary library expansion.** The current vocab libraries (`vocab.behavioral`, `vocab.lifecycle`, `vocab.validation`, plus the construction primitives) cover the fukan-itself corpus. Methodology libraries for other architectural styles (CQRS, actor model, event-driven microservices) arrive when real usage justifies them.

**Canvas-authored constraint depth.** The `defquery` mechanism and `fc/check` constraint runner exist and run in the build pipeline. Richer constraint *authoring at the canvas level* — projects expressing their own architectural laws as canvas constraints — remains lighter than the substrate allows.

---

## The pitch, refined

**Fukan is the workbench for the structural layer of a system — the layer humans own, the layer that survives across LLM sessions, the layer that should drive what the LLMs build.**

In the canvas chapter, "the structural layer" is expressed as canvas specs: Clojure data files using a deliberately minimal, architecture-neutral substrate extended by vocabulary lifts. The substrate has six primitives and ships zero architectural vocabulary; lift libraries carry architectural knowledge. The graph viewer renders the canvas alongside Clojure implementation reality. The eval surface makes the whole thing queryable by humans and agents alike.

Future phases add the authoring loop, more vocabulary libraries, richer drift analysis, and a constraint-authoring surface — all on the same substrate, without re-foundation.

---

*See [DESIGN.md](./DESIGN.md) for the three-tier layering principle, the ownership-on-owner substrate principle, and the build pipeline. [MODEL.md](./MODEL.md) is the authoritative substrate spec.*
