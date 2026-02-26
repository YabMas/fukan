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
1. Reload changed namespaces: `clj-nrepl-eval -p 7889 "(reload/reload)"`
   - clj-reload automatically detects changes and reloads in dependency order
2. Refresh browser to see changes
3. Do NOT restart server unless model/analysis changed

### When to Restart Server
Only call `(user/restart)` when:
- You changed fukan.analysis or fukan.model structure
- You need to re-analyze the source code (added new files)
- Something is broken and reload doesn't fix it

### NEVER DO
- Start nREPL in background with Bash
- Run multiple servers on different ports
- Kill servers via bash - use `(user/stop)` instead

---

## Context7 MCP

Always use Context7 MCP tools (`resolve-library-id` and `query-docs`) when you need:
- Library or API documentation
- Code examples for external libraries
- Setup steps for frameworks or tools

This ensures up-to-date, version-specific documentation instead of potentially outdated training data.

