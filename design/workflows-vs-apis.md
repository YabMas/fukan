# Workflows vs APIs: Two Perspectives on System Design

## References

This exploration was prompted by the Mycelium project and its surrounding ideas:

- **Blog post**: [Managing Complexity with Mycelium][blog] — yogthos' thesis on
  why bounded components with schema-enforced contracts are key to scaling
  AI-assisted development.
- **Mycelium**: [github.com/yogthos/mycelium][mycelium] (local: `~/Code/mycelium`)
  — a schema-enforced, composable workflow framework for Clojure, built on
  Maestro.
- **Maestro**: [github.com/yogthos/maestro][maestro] (local: `~/Code/maestro`) —
  a lightweight, purely functional state machine runner (~175 LOC) that provides
  Mycelium's execution engine.
- **Integrant**: [github.com/weavejester/integrant][integrant] — a data-driven
  system lifecycle framework for Clojure.

[blog]: https://yogthos.net/posts/2026-02-25-ai-at-scale.html
[mycelium]: https://github.com/yogthos/mycelium
[maestro]: https://github.com/yogthos/maestro
[integrant]: https://github.com/weavejester/integrant

---

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

---

## Mycelium and Fukan: Shared Values, Different Entry Points

[Mycelium][mycelium] is a schema-enforced workflow framework for Clojure by
yogthos, built on the [Maestro][maestro] FSM engine. It shares many values with
Fukan:

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

---

## System Lifecycle: Integrant

[Integrant][integrant] manages system lifecycle through a declarative config map
where components declare their dependencies via `ig/ref`. It provides:

- Automatic init/halt ordering (topological sort)
- Suspend/resume for REPL-driven development (handler hot-swap without server
  restart)
- Partial system init (useful for testing and CLI mode)
- The full system shape visible in one place

Fukan adopts Integrant to replace its current `defonce` atom pattern.

### Three levels of state

| Level        | Managed by    | Scope            | Example                                 |
|--------------|---------------|------------------|-----------------------------------------|
| **System**   | Integrant     | Process lifetime | Model, server, future components        |
| **Workflow** | (TBD)         | Single execution | Data accumulating through build stages  |
| **Request**  | Function args | Single request   | Projection parameters, view state       |

Integrant replaces the atoms for system-level state. Request-level state stays as
function arguments — projections take model + params, return data.

### Integration seam with Mycelium

When Integrant resolves `ig/ref`s and calls `init-key`, it passes in a map of
live, initialized dependencies. That's exactly what Mycelium expects as a
resources map:

1. **Integrant config** declares components and their dependencies
2. **`ig/ref`** resolves dependencies before `init-key` runs
3. **`init-key`** receives a map of live dependencies
4. If the init-key runs a Mycelium workflow, those dependencies become resources
5. **The result** becomes the component value in the system map

This means Integrant can be adopted now with plain function calls inside
init-keys, and individual components can migrate to Mycelium workflows later —
without changing the system shape. The `init-key` body is the only thing that
changes.

### The pattern scales

```clojure
{:fukan/language-registry {:analyzers {...}}
 :fukan/model             {:src       "src"
                           :languages (ig/ref :fukan/language-registry)}
 :fukan/server            {:model (ig/ref :fukan/model), :port 8080}
 :fukan/agent-api         {:model (ig/ref :fukan/model)
                           :llm   (ig/ref :fukan/llm-client)}}
```

Each component declares what it needs. Integrant wires it. Internally, each
component can use Mycelium workflows, plain functions, or anything else.

---

## Current Status

**Integrant** — adopting now. Replaces the two `defonce` atoms with a declarative
system config. Immediate benefits: explicit dependency wiring, suspend/resume dev
workflow, single `(reset)` replacing the refresh/restart distinction.

**Mycelium** — deferred. The conceptual alignment is clear (especially for the
build pipeline and future workflow-heavy features), but introducing it before
elaborate workflows naturally emerge in the app would be premature complexity.
The Integrant seam ensures a clean migration path: when workflows appear, they
slot into init-keys without restructuring.

**Focus** — get the right building blocks and patterns first. Integrant
establishes the system-level foundation. The API-oriented design (projections,
contracts, schemas) continues to drive Fukan's core. Workflow orchestration will
be introduced when the problem demands it.

---

## Directions to Explore

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
