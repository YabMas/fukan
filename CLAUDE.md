# Fukan Agent Instructions

## Package-specific conventions

Some packages contain their own `CLAUDE.md` with package-specific conventions:

- `src/fukan/infra/CLAUDE.md` - infrastructure lifecycle and dependency direction
- `src/fukan/model/CLAUDE.md` - build pipeline, schema conventions, data shapes
- `src/fukan/model/languages/CLAUDE.md` - language analysis and schema discovery
- `src/fukan/projection/CLAUDE.md` - projection functions, edge types, IO pattern
- `src/fukan/web/CLAUDE.md` - naming conventions for web module
- `src/fukan/web/views/CLAUDE.md` - view-spec implementation guide

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
4. Start server: `clj-nrepl-eval -p 7889 "(user/start {:src \"src\"})"`
5. Web UI runs on **port 8080** by default: `http://localhost:8080`

### After Editing Code
Use `(refresh)` — it force-reloads all code and rebuilds the model:
```
clj-nrepl-eval -p 7889 "(refresh)"
```
Then refresh the browser. This is the right command for **all** code changes (views, schemas, model, projection). The handler fetches the model per-request, so changes take effect immediately.

Use `(restart)` only when you changed **infra** code (server.clj, model.clj, handler.clj routing):
```
clj-nrepl-eval -p 7889 "(restart)"
```

Use `(status)` to check what's running:
```
clj-nrepl-eval -p 7889 "(status)"
```

### NEVER DO
- Call `remove-ns` — loses `defonce` atoms, orphans running servers
- Call `(reload/reload)` directly — use `(refresh)` instead, which force-reloads
- Start nREPL in background with Bash
- Run multiple servers on different ports
- Kill servers via bash — use `(user/stop)` instead

---

## Context7 MCP

Always use Context7 MCP tools (`resolve-library-id` and `query-docs`) when you need:
- Library or API documentation
- Code examples for external libraries
- Setup steps for frameworks or tools

This ensures up-to-date, version-specific documentation instead of potentially outdated training data.

