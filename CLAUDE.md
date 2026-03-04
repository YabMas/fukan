# Fukan Agent Instructions

## Package-specific conventions

Some packages contain their own `CLAUDE.md` with package-specific conventions:

- `src/fukan/infra/CLAUDE.md` - infrastructure lifecycle and dependency direction
- `src/fukan/model/CLAUDE.md` - build pipeline, schema conventions, data shapes
- `src/fukan/model/analyzers/implementation/CLAUDE.md` - implementation language analyzers
- `src/fukan/model/analyzers/specification/CLAUDE.md` - specification language analyzers
- `src/fukan/projection/CLAUDE.md` - projection functions, edge types, IO pattern
- `src/fukan/web/CLAUDE.md` - naming conventions for web module
- `src/fukan/web/spec.allium` - ViewTransport boundary contract
- `src/fukan/web/views/CLAUDE.md` - view-spec implementation guide

---

## Functional Core, Imperative Shell — enforced by Allium

The system follows a **functional core, imperative shell** architecture. Allium specs are the enforcement mechanism: if logic can be expressed as an Allium spec (values, rules, invariants), it belongs in the functional core. If it can't — because it involves IO, mutable state, lifecycle, or side effects — it belongs in the thin imperative shell.

**Functional core** (Allium-specced):
- `model/` — build pipeline, structural invariants
- `projection/` — pure computation over the model
- `web/views/` — rendering contracts and interaction rules
- Pure command logic in `cli/`

**Imperative shell** (boundaries specced, internals governed by tests + conventions):
- `infra/` — defonce atoms, lifecycle functions, port binding
- `web/handler.clj` + `web/sse.clj` — HTTP routing, SSE streaming
- IO edges in `model/analyzers/` — subprocess invocation, runtime reflection

**Design pressure:** When new logic arrives, the first question is "can I express this as an Allium rule?" If yes, it goes in the core. If not, ask *why* — often the answer is that pure logic is tangled with IO and can be factored out. The shell's job is to wire IO to the core, not to contain domain logic.

**Boundary contracts:** Every crossing point between shell and core is described by an enriched `external entity` declaration. These capture:
- **What the boundary provides** — contract signatures (`analyzes:`, `provides:`, `handles:`)
- **How it can fail** — named failure modes (`failures:`)
- **What it guarantees** — observable promises (`guarantee:`)

The shell's *internal mechanics* are not specced. Ring middleware, atom implementation — these are declarative config or mechanical plumbing where a spec would just duplicate already-clear code. The enriched boundaries ensure that if you "lose sight" of all unspecced code, you still know what every shell component promises, how it can fail, and what invariants hold across the crossing.

**Boundary spec locations:**
- `model/spec.allium` — SourceAnalyzer, SchemaDiscovery, ProjectResources, ModelLifecycle
- `web/spec.allium` — ViewTransport

**Litmus test for new boundaries:** If the boundary describes something you could write a black-box test for without knowing the implementation, it belongs as an enriched external entity. If you need to know internal structure to verify it, it doesn't.

---

## contract.edn Convention

`contract.edn` defines a module's **external boundary** — the functions that callers outside the module use. It is not an inventory of all public vars.

- **In contract:** Functions called by other modules (the module's external API).
- **Not in contract:** Functions that are `defn` (public) only because they're called cross-namespace *within* the module. These are internal implementation details that happen to need Clojure-level visibility.
- **Should be `defn-`:** Functions only called within their own namespace.

When reviewing, a public var missing from the contract is only a warning if it's called from outside the module. If it's only called within the module, it's correctly absent.

---

## Schema Design Guidelines

Schemas are as much documentation as they are validation. They are rendered in the web sidebar where they become the primary way someone understands the data flowing through the system. Write schemas with a reader in mind — someone browsing the sidebar should be able to grasp a data shape without reading the source code.

### Shape the data, don't just type-check it

A schema should reveal structure. Prefer types that tell a reader *what the data looks like* over types that merely pass validation. `{:nodes {:string → map}}` validates but communicates nothing; `{:nodes {:NodeId → Node}}` tells you what's inside.

### Model variants explicitly

When data varies by kind, express the variants in the schema rather than describing them in prose. A discriminated `:or` renders each variant as a separate block in the sidebar, making the shape immediately scannable.

```clojure
;; Good — each variant is visible with its own description
(def ^:schema NodeData
  [:or {:description "Kind-specific properties, discriminated by :kind."}
   [:map {:description "Container: directory or namespace."}
    [:kind [:= :container]] ...]
   [:map {:description "Function: var definition."}
    [:kind [:= :function]] ...]])

;; Bad — structure hidden behind prose
(def ^:schema NodeData
  [:map {:description "Container data has :doc and :contract. Function data has ..."} ...])
```

### Every schema needs a description

The top-level `:description` is the first thing someone reads when drilling into a type. Write it as a one-line definition of what this data represents.

### Name schemas for domain concepts, not convenience

A named schema should represent a concept that appears across the system or that someone would want to drill into. If a type is used once and is simple, inline it.

```clojure
;; Good — NodeId appears in Node, Edge, Model, projections
(def ^:schema NodeId [:string {:description "..."}])

;; Unnecessary — only used once, adds indirection without value
(def ^:schema Port [:int {:description "TCP port number."}])
```

### Describe fields that carry domain meaning

Standard fields (`:name`, `:id`, `:label`) are self-explanatory. Fields with domain-specific semantics need per-field descriptions.

```clojure
[:io-type {:description "Whether this node represents an input or output schema boundary."}
 [:enum :input :output]]
```

### Use `:any` only at true boundaries

`:any` is acceptable for Malli schema forms (which are recursive arbitrary data), opaque library types, or genuinely polymorphic data. Always describe what it actually holds.

```clojure
[:signature {:optional true
             :description "Malli function schema [:=> [:cat inputs...] output]."}
 :any]
```

### Function schemas reference domain types

`:malli/schema` annotations on functions should use named schema keywords so they render meaningfully in the sidebar.

```clojure
;; Good
{:malli/schema [:=> [:cat :Model :Request] :SSEResponse]}

;; Bad
{:malli/schema [:=> [:cat :map :map] :any]}
```

### Use keyword refs, not var refs

Reference other schemas by keyword (`:NodeId`), not by Clojure var (`NodeId`). Var refs get expanded at def-time, so the named reference is lost in the rendered output.

### Maximize type specificity for documentation

#### Eliminate `:any` — it is a documentation failure

`:any` tells the reader nothing. Before using it, exhaust these alternatives in order:

1. **Use a named domain type** if one exists (`:NodeId`, `:FilePath`, `:Model`).
2. **Define a new named type** if the data has known structure — even partial structure is better than none.
3. **Use a runtime-validated wrapper** with `[:fn ...]` when the type is an opaque external object (e.g., http-kit `AsyncChannel`). The wrapper gives the reader a name, the predicate catches type errors in dev, and the description + external docs link tells them where to learn more.
4. **Use `:any` as absolute last resort** — only for genuinely recursive/polymorphic data where no structural claim is possible (e.g., arbitrary Malli schema forms that can be any valid Malli expression). Even then, the `:description` must explain what the value actually is and link to external docs where the reader can learn more.

#### Use the most specific type available

- **Never use `:string` when a domain type exists.** If a string is a `NodeId`, use `:NodeId`. If it's a file path, use `:FilePath`. Generic `:string` hides domain semantics.
- **Never use `:map` when structure is known.** Function options, session state, server config — if you know the keys, spell them out. `:map` as a type says "I didn't bother."
- **Never use `:fn` without a function schema.** `[:fn]` says nothing. Use `[:=> [:cat ...] ...]` to describe the signature.
- **Constrain numeric ranges.** Use `[:int {:min 1 :max 65535}]` for ports, not bare `:int`.

#### Reuse domain types across layers

If `ProjectionOpts` uses `NodeId` for `:view-id`, and `EditorState` has the same concept, both should use `:NodeId`, not bare `:string`.

#### Wrap opaque library types — but only when it adds meaning

Create named wrapper schemas for opaque types **when the wrapper communicates something the underlying type doesn't**:

```clojure
;; Good wrapper: AsyncChannel tells you what :any actually is, plus has runtime validation
(def ^:schema AsyncChannel
  [:fn {:description "HTTP-kit AsyncChannel..."} #(instance? org.httpkit.server.AsyncChannel %)])

;; Bad wrapper: GraphData = CytoscapeGraph adds no meaning, just indirection
(def ^:schema GraphData :CytoscapeGraph) ;; remove this, use :CytoscapeGraph directly
```

The test: if removing the wrapper and using the inner type directly loses no information for the reader, the wrapper is noise. If the wrapper gives the reader a domain concept they wouldn't have from the inner type alone, keep it.

---

## Testing (MUST FOLLOW)

Tests are spec-driven: invariant predicates encode Allium spec rules, generators produce valid inputs, and tests verify invariants hold. **Run tests after every implementation change.**

### Running Tests

```
clj -M:dev:test
```

Run this after completing any code change — model, projection, view, or schema modifications. All tests must pass before committing.

### When Implementing Changes

1. **Write or update tests alongside code.** If you change behavior covered by an existing test, update the test. If you add new behavior, add a test.
2. **Run the full suite** after implementation is complete. Fix failures before committing.
3. **If a test fails**, read the violation map — it describes exactly which invariant broke and why. Fix the implementation, not the test, unless the spec itself changed.

### Test Structure

Tests live in `test/` mirroring `src/`:

| Test file | What it covers |
|-----------|---------------|
| `test/fukan/model/build_test.clj` | Model build pipeline — generative (defspec) + self-analysis integration |
| `test/fukan/projection/graph_test.clj` | Graph projection — generative + self-analysis integration |
| `test/fukan/projection/details_test.clj` | Entity details — example-based |
| `test/fukan/projection/path_test.clj` | Breadcrumb paths — example-based |
| `test/fukan/web/views/graph_test.clj` | View rendering — generative + example-based |

### Test Support

| File | Purpose |
|------|---------|
| `test/fukan/test_support/invariants/model.clj` | Model invariant predicates (from model.allium) |
| `test/fukan/test_support/invariants/projection.clj` | Projection invariant predicates (from projection.allium) |
| `test/fukan/test_support/invariants/views.clj` | View invariant predicates (from views.allium) |
| `test/fukan/test_support/generators.clj` | Custom generators for valid test data |
| `test/fukan/test_support/registry.clj` | Malli schema registry for generators |

### Writing New Tests

- **Generative tests** (`defspec`) for functions with rich input spaces (build-model, entity-graph, render-graph). Use generators from `test_support/generators.clj` and invariant predicates from `test_support/invariants/`.
- **Example-based tests** (`deftest`) for functions with simple inputs or where specific scenarios matter (details, path, edge cases).
- **Integration tests** that run against Fukan's own source code catch regressions that random generation won't reach. Add these for new pipeline stages.
- When adding a new Allium spec invariant, add a corresponding predicate in the relevant `invariants/` file and a defspec that exercises it.

---

## Guardrails

- Don't use re-exports to fix dependency issues — have the downstream namespace compose from available data.
- Don't introduce intermediate composite data shapes (e.g., `view-data`) — the handler should compose projection + view directly.
- Don't add artificial limits (e.g., drill-down depth) when natural termination is guaranteed.
- Schema registry should throw on duplicate key registration to catch name collisions early.

---

# Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.

---

## Development Workflow (MUST FOLLOW)

### Starting Development
1. Check for existing nREPL: `clj-nrepl-eval --discover-ports`
2. If nREPL exists on port 7889 in this directory, USE IT - do not start another
3. If no nREPL, ask user to start one (do NOT start in background)
4. Start system: `clj-nrepl-eval -p 7889 "(go)"`
5. Web UI runs on **port 8080** by default: `http://localhost:8080`

### After Editing Code
Use `(reset)` — it halts the system, force-reloads all code, and restarts:
```
clj-nrepl-eval -p 7889 "(reset)"
```
Then refresh the browser. This is the right command for **all** code changes (views, schemas, model, projection, infra). The handler fetches the model per-request, so view/projection changes take effect immediately after reload.

Use `(refresh-model)` to rebuild the model without restarting the server:
```
clj-nrepl-eval -p 7889 "(refresh-model)"
```

Use `(status)` to check what's running:
```
clj-nrepl-eval -p 7889 "(status)"
```

### NEVER DO
- Call `remove-ns` — orphans running servers
- Call `(reload/reload)` directly — use `(reset)` instead
- Start nREPL in background with Bash
- Run multiple servers on different ports
- Kill servers via bash — use `(halt)` instead

---

## Context7 MCP

Always use Context7 MCP tools (`resolve-library-id` and `query-docs`) when you need:
- Library or API documentation
- Code examples for external libraries
- Setup steps for frameworks or tools

This ensures up-to-date, version-specific documentation instead of potentially outdated training data.

