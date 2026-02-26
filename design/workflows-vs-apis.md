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

## Integration Pattern: Integrant + Mycelium

Fukan uses [Integrant](https://github.com/weavejester/integrant) for system
lifecycle and [Mycelium](https://github.com/yogthos/mycelium) for workflow
execution. These are complementary layers with a clean seam between them.

### Three levels of state

| Level        | Managed by  | Scope              | Example                                |
|--------------|-------------|--------------------|-----------------------------------------|
| **System**   | Integrant   | Process lifetime   | Model, server, language registry        |
| **Workflow** | Mycelium    | Single execution   | Analysis data accumulating through build |
| **Request**  | Function args | Single request   | Projection parameters, view state       |

Integrant replaces the `defonce` atoms. Mycelium's accumulating data map handles
workflow-scoped state. Request-level state stays as function arguments —
projections take model + params, return data.

### The seam: Integrant config → Mycelium resources

When Integrant resolves `ig/ref`s and calls `init-key`, the config it passes in
is a map of live, initialized dependencies. That's exactly what Mycelium expects
as a resources map. The integration is:

1. **Integrant config** declares components and their dependencies
2. **`ig/ref`** resolves dependencies before `init-key` runs
3. **`init-key`** receives a map of live dependencies
4. **`run-workflow`** executes with those as resources; cells use them
5. **The result** becomes the component value in the system map
6. **Other components** reference it via `ig/ref`

```clojure
;; Integrant config
{:fukan/model  {:src "src"}
 :fukan/server {:model (ig/ref :fukan/model), :port 8080}}

;; init-key receives resolved config, passes it as Mycelium resources
(defmethod ig/init-key :fukan/model [_ {:keys [src]}]
  (let [result (myc/run-workflow
                 build-workflow
                 {:src-path src}    ;; Integrant config → Mycelium resources
                 {})]
    (:model result)))
```

Adding a new resource = adding it to the Integrant config. Any workflow that
needs it = `ig/ref` it. Cells receive it in the resources map. The wiring stays
in one place.

### Where each tool applies

**Integrant** manages what exists and how to start/stop it. It owns the system
shape, dependency wiring, and lifecycle (init, halt, suspend, resume).

**Mycelium** manages how things flow. It owns workflow execution, schema
validation between stages, and execution traces. It runs inside `init-key` or
inside request handlers — wherever a workflow is needed.

**Neither** applies to the projection layer. Projections are composable APIs
that receive the model as an argument. The request handler closes over the
Integrant system and passes the model to projections. No workflow, no lifecycle —
just functions with contracts.

### Dev workflow

Integrant's suspend/resume collapses the current refresh-vs-restart distinction
into a single operation:

```
(reset)
  → ig/suspend!                    ;; server pauses, holds port
  → reload code                    ;; clj-reload
  → ig/resume
    → init-key :fukan/model        ;; rebuilds via build workflow
    → resume-key :fukan/server     ;; swaps handler, unblocks
```

The server never restarts — it swaps the handler closure. The model rebuilds
because Integrant knows it needs re-initialization.

### The pattern scales

The same pattern works for future components:

```clojure
{:fukan/language-registry {:analyzers {...}}
 :fukan/model             {:src       "src"
                           :languages (ig/ref :fukan/language-registry)}
 :fukan/server            {:model (ig/ref :fukan/model), :port 8080}
 :fukan/agent-api         {:model (ig/ref :fukan/model)
                           :llm   (ig/ref :fukan/llm-client)}}
```

Each component declares what it needs. Integrant wires it. Internally, each
component can use Mycelium workflows, plain functions, or anything else. The
system shape doesn't change when an `init-key` body switches from function calls
to `run-workflow`.

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
