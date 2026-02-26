# Workflows vs APIs: Two Perspectives on System Design

## The Theme

There are two complementary ways to look at system structure:

**Workflow perspective** — a system is a composition of steps. You design the
flow (what happens, in what order, with what data), then implement the steps.
The graph of transitions is the primary artifact.

**API perspective** — a system is a composition of abstractions with contracts.
You design the shapes (what exists, what it accepts, what it returns), and
workflows emerge from composing those shapes. The landscape of contracts is the
primary artifact.

These are different entry points into the same design problem. The workflow
perspective asks "what needs to happen?" The API perspective asks "what are the
right building blocks?" Neither is complete on its own.

Sometimes the right design move isn't "add a step to the workflow" — it's
"reshape this API so the workflow becomes trivial." The API perspective catches
that. Conversely, once you have good abstractions, you still need to orchestrate
them — that's where workflows shine.

For AI agents, these map to different modes of assistance:
- "Help me understand and reshape the structure of this system" (API-oriented)
- "Help me implement this flow given these components" (workflow-oriented)

Fukan leans into the API perspective. It makes the landscape of abstractions,
contracts, and dependencies visible and navigable — for both humans and AI. The
intent is to stay in control of high-level design while collaborating with AI on
the details, by giving it tools to navigate and inspect high-level structure
without getting lost in low-level code.

## Where the Boundary Falls in Fukan

Fukan's own architecture illustrates where each perspective has natural
explanatory power.

### Workflow territory: the model build pipeline

`model/build.clj` is an 8-stage sequential pipeline:

    AnalysisData → folder-nodes → ns-nodes → var-nodes
    → merge+wire → prune → edges → signatures → contracts → Model

Each stage transforms data for the next. The language analysis layer extends this
upstream (kondo → schema discovery → schema nodes). These stages are sequential,
have clear input/output shapes, and compose linearly. New language analyzers slot
in as replacement stages.

This is where workflow thinking is natural. The build pipeline *is* a workflow.

### API territory: the projection layer

`projection/graph.clj`, `projection/details.clj`, `projection/path.clj`,
`projection/schema.clj` — the core of what makes Fukan useful.

These are not sequential stages. They're independent, composable query functions
called in varying combinations depending on context. The web layer calls all
three projections per request; the CLI calls subsets; a future agent API might
call just one. The SSE orchestration layer is thin glue precisely because the
projection contracts are well-shaped.

Nobody designed a "view workflow." They designed good query abstractions and the
orchestration became trivial. This is where API thinking is natural.

### The boundary

```
WORKFLOW (sequential, staged)          API (composable, on-demand)
─────────────────────────────          ──────────────────────────
model.build (8-stage pipeline)         projection.graph
model.languages.clojure                projection.details
infra.model/load-model                 projection.path
                                       projection.schema
                                       views.*
                                       cli.commands
                                       web/sse (thin orchestration)
```

The build pipeline is important but mechanical. The projections are where the
design leverage lives — where the choice of abstraction determines whether
everything downstream is simple or complicated.

## Mycelium: A Workflow-First Framework

[Mycelium](https://github.com/yogthos/mycelium) is a schema-enforced workflow
framework for Clojure by yogthos, built on the
[Maestro](https://github.com/yogthos/maestro) FSM engine. It shares many values
with Fukan:

- Malli schemas as first-class contracts
- Explicit boundaries between components
- Immutable data flow through pure functions
- Designed for AI-assisted development (bounded context per component)

Mycelium's unit of composition is the **cell** — a pure function with declared
input/output schemas, wired into a directed graph (workflow) with declarative
dispatch. Workflows can nest into larger workflows (fractal composition).

The key difference: Mycelium is workflow-centric. The workflow graph is the
primary artifact; cells serve the workflow. Fukan is abstraction-centric. The
contract landscape is the primary artifact; workflows emerge from composition.

These perspectives complement rather than compete. A system needs both: the right
abstractions (what to build) and the right orchestration (how to wire it).

## Directions to Explore

### Mycelium for Fukan's build pipeline

The model build pipeline is a natural Mycelium workflow. Each stage becomes a
cell with schema-validated inputs and outputs. Benefits: traceable execution,
pluggable language analyzers, compile-time validation of the pipeline. This is
also a case study for how well Mycelium scales to a real codebase.

### Fukan as a tool for seeing the boundary

If Fukan can visualize both workflow structure and API structure, it could help
answer: "which parts of this system are workflows and which are APIs?" This is a
design question that's currently answered by intuition. Making it visible in the
graph — showing where sequential flow dominates vs where composable queries
dominate — would be a unique capability.

### Agent-facing projection API

Fukan already computes bounded context (projections show only the relevant
subgraph). Exposing this as an API for AI agents gives them structural context
without drowning in code. Combined with Mycelium's manifest system (which
provides workflow context), agents get both views: what exists and how it flows.

### Visualizing Mycelium workflows

Mycelium workflows are directed graphs with typed nodes and edges — the same
structure Fukan already models. A Mycelium "language" for Fukan's analysis layer
would make workflows navigable with the same drill-down, schema-inspecting UI
that works for code structure.
