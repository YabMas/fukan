# Fukan Agent Instructions

## Package-specific conventions

Some packages contain their own `CLAUDE.md` with package-specific conventions:

- `src/fukan/web/CLAUDE.md` - naming conventions for web module
- `src/fukan/web/views/CLAUDE.md` - view-spec implementation guide

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

