# LLM Agent Surface — Design Spec

**Status:** Brainstormed, pre-implementation. Ready for plan-out.
**Date:** 2026-05-20
**Audience:** Engineers and agents picking this up cold. Read this before the plan.
**Related:** [VISION.md](../VISION.md), [DESIGN.md](../DESIGN.md), [MODEL.md](../MODEL.md)

---

## 1. What this commits to

Fukan grows a programmatic surface for LLM agents — a way for agents to interact with the loaded Model that is more ergonomic and powerful than reading source files.

The surface is a thin CLI (`fukan`) that talks to the existing Fukan daemon over loopback HTTP and evaluates Clojure expressions in a sandboxed (SCI) environment. Agents see a small, layered, curated namespace; everything else inside the JVM is reachable only via the existing nREPL for human use.

The pitch in one sentence: *the same graph the human navigates in the browser, exposed to agents as a Datalog-queryable Model with an ergonomic vocabulary and a project-local extension mechanism.*

This is MVP-shaped: read-side access, drift derivation, spec-authoring scaffolding. Mutation primitives, blueprint-tool wrapping, and convenience commands are out of scope here.

---

## 2. Why

As LLMs handle more low-level coding, the human's value migrates upward — to module boundaries, contracts, invariants, behavioural intent. Fukan's existing browser-rendered graph is the workbench for that level. But agents working *with* humans on the same project read source files to derive the same understanding the graph already encodes.

That's wasted work, and it's the wrong abstraction layer. The agent should reach for Fukan first — for orientation, for finding drift, for picking what to work on next, for understanding the spec shape before authoring more spec.

The surface we're designing is what makes that reach ergonomic. **The CLI is for agents; humans continue to use the browser.** No human-pretty output paths are designed for or maintained.

### Use cases

- **B (near-term, motivating):** Fukan-on-Fukan. Agents working on Fukan itself use this surface during development.
- **A (validates B):** target projects pointing Fukan at their codebase + specs and giving their agents the same surface. Benchmarks Fukan's real-world effectiveness.
- The surface is identical between A and B. Only the loaded Model differs.

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────┐
│   Fukan JVM (existing)                                    │
│                                                            │
│   ┌──────────────────────────────────────────────────┐   │
│   │ Jetty / Ring (existing)                          │   │
│   │   /graph, /projector, /sidebar  ← browser        │   │
│   │   /agent/eval                   ← NEW            │   │
│   │   /agent/status                 ← NEW            │   │
│   └──────────────────────────────────────────────────┘   │
│                                                            │
│   ┌──────────────────────────────────────────────────┐   │
│   │ Hot Model (existing, single atom)                │   │
│   └──────────────────────────────────────────────────┘   │
│                                                            │
│   ┌──────────────────────────────────────────────────┐   │
│   │ Agent eval surface (NEW)                         │   │
│   │   - SCI evaluator                                │   │
│   │   - ns map bound to fukan.agent.api +            │   │
│   │     fukan.agent.system vars only                 │   │
│   │   - No Java interop, no IO, no shell-out         │   │
│   │   - Closed over the live Model atom              │   │
│   │   - Per-eval timeout                             │   │
│   └──────────────────────────────────────────────────┘   │
│                                                            │
│   ┌──────────────────────────────────────────────────┐   │
│   │ nREPL :7889 (existing) — humans only             │   │
│   │   Full Fukan internals; unchanged                │   │
│   └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
                          ▲
                          │ loopback HTTP, JSON
                          │
              ┌───────────┴────────────┐
              │   fukan CLI (NEW)      │
              │   small native/bb bin  │
              └────────────────────────┘
                          ▲
                          │ exec
                          │
              ┌───────────┴────────────┐
              │   Agent (any runtime)  │
              └────────────────────────┘
```

### Properties

- **Two eval surfaces, two audiences, two contracts.** The existing nREPL is the human dev surface; arbitrary Clojure, all internals reachable. The new SCI surface is the agent's only path in; locked to `fukan.agent.api` + `fukan.agent.system` from day 1, with no escape hatch.
- **CLI is a thin client.** No JVM startup tax per call; the CLI is a small native or Babashka binary that posts to loopback HTTP and prints the JSON response. The hot Model lives in the daemon.
- **Daemon lifecycle stays user-explicit.** `fukan status` shows what's loaded; `fukan eval '(refresh)'` rebuilds the Model. The CLI does not auto-start the daemon for MVP — if not running, it errors cleanly.
- **Single Model per daemon.** Pointing Fukan at a different target = different daemon. Multi-target support is deferred (see §10) but not architecturally precluded.

### Forward-compatibility for parallel sessions

None of the deferred parallelism flavours are precluded by this architecture:

- **Concurrent agent calls against one Model.** Free: Model is immutable after build; SCI is per-call; `(refresh)` is the only mutator (handled by atom swap).
- **Multiple Models loaded in one daemon** (Fukan-on-Fukan + target X + target Y). Bigger lift later — the Model atom becomes a registry keyed by target ID. Architecturally additive.
- **Isolated per-session SCI environments** (each agent has its own scratch state). Layered on top later via session IDs.

None of these are MVP work, and we don't pay for them now — but the design admits all three.

---

## 4. `fukan.agent.api` — the agent's layered Model interface

The bulk of the design work lives in this namespace. Everything else (CLI, HTTP, SCI bindings) is plumbing in service of this contract.

### Layering

```
┌─────────────────────────────────────────────────────────────────┐
│ L2  Views — recurring composite queries                          │
│     Built-in canon (small, illustrative) + project-local         │
│     additions (accreted by humans + agents over time).           │
│                                                                   │
│     Built-in MVP canon:    (drift)       (neighborhood id)        │
│     Project-local:         [grows via .fukan/agent-views.clj]     │
└─────────────────────────────────────────────────────────────────┘
                                ▲
                                │  built on
                                │
┌─────────────────────────────────────────────────────────────────┐
│ L1  Probes — Model vocabulary                                    │
│     Small fixed set aligned 1:1 to Fukan's domain primitives.    │
│     Daily driver. Consistent kw-args filtering pattern across.   │
│                                                                   │
│     Vocabulary:  (vocabulary)  (schema :kind k)                   │
│     Nodes:       (primitives & filters)  (get-primitive id)       │
│     Edges:       (relations & filters)                            │
│     Project:     (idioms)  (constraints)  (violations)            │
└─────────────────────────────────────────────────────────────────┘
                                ▲
                                │  built on
                                │
┌─────────────────────────────────────────────────────────────────┐
│ L0  Kernel — Datalog over the Model                              │
│                                                                   │
│     (q '[:find ... :where ...])                                   │
│                                                                   │
│     Reuses the existing constraint engine's Datalog AST —         │
│     same language constraints are written in.                    │
└─────────────────────────────────────────────────────────────────┘
```

**Principle:** higher layers are *convenience*, not *capability*. L1 is sugar over L0; L2 is named compositions of L0 + L1. There is no expressive power in L1 or L2 that isn't expressible at L0. The layering exists to teach the agent how to *think* about the Model and to make recurring questions cheap to ask.

### Layer reference

- **L0** — `(q '[:find ?p :where [?p :kind :rule]])`. Joins, aggregations, anything ad-hoc.
- **L1** — orthogonal kw-args filtering across the five "kinds of thing" the agent navigates:
    - **Vocabulary:** `(vocabulary)`, `(vocabulary :altitude :behaviour)`, `(schema :kind :rule)` — what primitive kinds and relation kinds exist; what attributes a given kind has; what relations it can participate in.
    - **Primitives (nodes):** `(primitives)`, `(primitives :kind :rule :owner "hex/core")`, `(primitives :address-prefix "Code.fukan.model.*")`, `(primitives :pred '(fn [p] ...))`. Returns summary maps; capped + paginated by default. `(get-primitive id)` returns full detail including attributes, source location, and docs.
    - **Relations (edges):** `(relations :from id)`, `(relations :kind :projects :validity :absent)`. Same filtering pattern.
    - **Project layer:** `(idioms)`, `(idioms :primitive-kind :surface)`, `(constraints)`, `(violations)`, `(violations :severity :error)`.
- **L2** — built-in MVP canon, kept small (so the agent isn't over-shaped by our notion of useful views):
    - `(drift)` — `(relations :kind :projects :validity :absent)` joined with `get-primitive` for the source side. Optional `:projection-kind`.
    - `(neighborhood id)` — a primitive + all its one-hop relations (both directions) + summary maps for the directly-connected neighbors. Local exploration without writing a query. Multi-hop traversal is the agent's job at L0 if needed.

### Output shape, illustrative

```clojure
;; (first (primitives :kind :rule))
{:id "rule:hex/core/r-mint"
 :kind :rule
 :altitude :behaviour
 :owner "module:hex/core"
 :address "Code.hex.core/mint"}

;; (get-primitive "rule:hex/core/r-mint") — adds:
{:source-location {:file "src/hex/core.allium" :line 42}
 :attributes {:triggers [...] :guarantees [...]}
 :doc "..."}

;; (first (relations :kind :projects :validity :absent))
{:from "rule:hex/core/r-mint"
 :to nil
 :kind :projects
 :projection-kind :clojure
 :validity :absent
 :expected-address "hex.core/mint"}
```

### Drift, derived (no convenience command needed)

```clojure
;; "What spec primitives have no Clojure realisation yet?"
(->> (relations :kind :projects :validity :absent)
     (map :from)
     (map get-primitive))

;; "Group absent projections by owning module"
(q '[:find ?m (count ?p)
     :where
       [?r :kind :projects]
       [?r :validity :absent]
       [?r :from ?p]
       [?p :owner ?m]])
```

The agent learns to derive drift, gaps, neighborhoods, etc. by composing L0 + L1. When a pattern recurs enough to be worth naming, it's promoted to L2 (see §6).

---

## 5. `fukan.agent.system` — operating Fukan

Split out from `agent.api` because the concern is different. `agent.api` queries the Model; `agent.system` operates Fukan itself.

Flat namespace; no layering.

- `(status)` — current state: target ID, primitive/relation counts, last-refresh time, violation count, agent-views load status, daemon uptime.
- `(refresh)` — blocks; rebuilds the Model from disk; re-loads `.fukan/agent-views.clj`; returns the new status.
- `(help)` — returns the surface catalog (both namespaces), grouped by ns and (for `agent.api`) by layer.
- `(help 'fn-name)` — returns docstring + signatures + examples + return-shape doc for a single fn.
- `(source 'fn-name)` — returns the implementation of an L1 or L2 fn. Used by agents to learn patterns by reading built-in code.

Not exposed: `(reset)` (would kill the agent's connection), arbitrary mutation, anything that touches state outside the loaded Model.

Both namespaces are referred-in to the SCI evaluator by default, so the agent writes `(status)` and `(primitives)` indifferently — no namespace prefixes required at the call site.

---

## 6. Persistence — agent-defined L2

The agent extends L2 by editing a single project-local file.

```
<target-project-root>/.fukan/agent-views.clj
```

### Conventions

- Lives **with the target project**, not with Fukan. Travels in git. PR-reviewable. Shared between humans and agents working on the same project.
- For Fukan-on-Fukan, the file lives at the Fukan repo root.
- The `.fukan/` directory is the home for any future project-local agent state.
- The CLI knows the target-project root because the daemon was started against it.

### Lifecycle

- **Daemon start:** file read; defs evaluated into the SCI environment alongside built-in L2.
- **`(refresh)`:** file re-read (it may have changed). Same mechanism agents use after editing specs.
- **Within a daemon's lifetime:** the SCI environment is reused across eval calls; no per-call reload tax.

### File shape

Plain Clojure defs written against L0 + L1. Same shape as built-in L2; the agent treats built-in canon as templates by reading `(source 'drift)`.

```clojure
(ns project.agent-views)

(defn unrealised-by-altitude
  "Absent projections, bucketed by the spec primitive's altitude."
  []
  (->> (relations :kind :projects :validity :absent)
       (map :from)
       (map get-primitive)
       (group-by :altitude)))
```

Once defined, project-local views are usable like built-in L2:

```clojure
(unrealised-by-altitude)
```

`(help)` lists them in a separate **project-local L2** bucket so the agent can distinguish canon from local accretion. `(source 'unrealised-by-altitude)` returns the file's source.

### Agent workflow for adding a view

1. Agent realises a query is recurring.
2. Composes the L0/L1 version inline in `fukan eval` to verify.
3. Edits `.fukan/agent-views.clj` to add the def.
4. Calls `(refresh)` — the new view is loaded.
5. Uses the new view; future sessions inherit it.

No special `def-util` primitive. The agent uses the same edit-and-refresh loop it uses for spec changes. The file *is* the persisted util library.

### Edge cases

- **Bad expression in the file:** `(refresh)` returns a structured error pointing to the offending def; built-in L2 and other good defs still load. `(status)` reflects partial load.
- **Name conflict with built-in L2:** project-local def wins; built-in stays reachable via `(source 'fn)`. A warning surfaces in `(status)`.
- **Attempt to redefine L1 / system fns:** refused with `:forbidden`. The lower layers are not extension points.

---

## 7. Onboarding — teaching the LLM the Model

Two distinct artifacts; never overlap:

- **Reference** = in-band, runtime, exhaustive: `(help)`, `(help 'fn)`, `(source 'fn)`. Always current because it reads from the live SCI environment.
- **Mental model** = static, prose, conceptual: a single primer document. Teaches how to *think* about the Model. No fn signatures (those drift).

### Primer location

The primer is owned and versioned in the Fukan repo. Target projects get a *reference* to it, not a copy that goes stale:

```
<fukan-repo>/AGENTS.md             ← canonical primer; source of truth
                                     also serves Fukan-on-Fukan directly

<target-project>/AGENTS.md         ← target's own AGENTS.md (may pre-exist
                                     for other tools); `fukan init` adds
                                     or updates a "## Fukan" section
                                     containing a brief note + a pointer to
                                     `fukan primer` for the full content
```

`AGENTS.md` is the emerging cross-tool convention surfaced by Claude Code, Cursor, Codex, and others. Falling back to it means every agent runtime finds the pointer without per-tool plumbing. For the full primer body, agents are expected to either fetch it via `fukan primer` (always current; reflects the running daemon's bundled version) or read the canonical copy in the Fukan repo if checked out locally. Avoiding a checked-in copy in each target project sidesteps the staleness problem — there is one source of truth.

`fukan init` is intentionally additive on existing `AGENTS.md`: if the file doesn't exist it creates one with a single Fukan section; if it does, it inserts or updates a `## Fukan` section without touching the rest.

### Primer outline

A tight document — concepts and worked examples, no exhaustive listings. Target 200–400 lines of markdown.

1. **What Fukan is** (one paragraph) — spec graph that knows about code.
2. **The Model in one minute** — three altitudes, primitives + relations + project layer, `projects` edges and their `:validity`.
3. **The two namespaces** — `system` (operating Fukan) and `api` (querying the Model).
4. **The L0 / L1 / L2 layering** — what each layer is for; principle that higher layers are convenience, not capability.
5. **Three worked examples** — orientation, derivation (drift, promoted to a view), edit-and-refresh loop.
6. **Persisted views** — `.fukan/agent-views.clj`; read built-in views as templates; PR them.
7. **The reference catalog is live** — `(help)`, `(help 'fn)`, `(source 'fn)`.
8. **Pointers** — `doc/VISION.md`, `doc/DESIGN.md`, `doc/MODEL.md`.

### Discoverability — three redundant mechanisms

1. **Convention auto-discovery:** `AGENTS.md` at the project root.
2. **CLAUDE.md pointer:** one line in the project's CLAUDE.md saying "Fukan is available — see AGENTS.md." Added by `fukan init` when bootstrapping a target project.
3. **CLI fallback:** `fukan primer` to stdout for agents that shell out but don't read files.

### In-band reference, illustrative

```
(help)
=> Groups by namespace + layer. Each fn: name + 1-line summary.
   JSON output; agent parses, decides.

(help 'primitives)
=> {:fn 'primitives
    :ns 'fukan.agent.api
    :layer :L1
    :doc "List Model primitives, filterable by kind/owner/address/pred. ..."
    :signatures [(primitives)
                 (primitives & {:keys [kind owner address pred limit offset]})]
    :examples [...]
    :returns "seq of primitive-summary maps; :truncated?/:total when capped"}

(source 'drift)
=> {:fn 'drift :layer :L2 :origin :built-in
    :source "(defn drift [...] (->> (relations ...) ...))"}
```

### Existing-agent integration (out of scope; flagged)

The project's `spec`, `allium:tend`, `allium:weed`, `allium:distill`, `allium:propagate` agents would benefit from a soft instruction to prefer Fukan when available — e.g., "before grepping for rules in module X, try `fukan eval '(primitives :kind :rule :owner ...)'`." That's prompt updates on those agents, not Fukan-side work; addressed in a separate pass after MVP.

---

## 8. Errors, safeguards, testing

### Error envelope

Single structured shape from every eval failure mode. JSON over the wire; CLI prints verbatim.

```clojure
{:ok? false
 :error/kind     :syntax | :unbound | :runtime | :timeout
                 | :exceeded-cap | :model-not-loaded | :forbidden
 :error/message  "..."
 :error/location {:line N :col M}        ; when available
 :error/symbol   'maybe-the-offending-var ; when relevant
 :error/data     {...}                    ; kind-specific detail
}
```

- `:syntax` — reader failure inside SCI.
- `:unbound` — references an unbound symbol; `:error/data` includes what *is* bound in scope.
- `:runtime` — exception during eval (with type + message).
- `:timeout` — eval exceeded the deadline.
- `:exceeded-cap` — query result blew the result cap; response *still* includes the first N results and a continuation hint.
- `:model-not-loaded` — daemon up but no Model loaded.
- `:forbidden` — attempted to redefine an L1/system fn, or another sandbox refusal.

Success envelope:

```clojure
{:ok? true
 :result <data>
 :elapsed-ms N
 :result-meta {:truncated? bool :total N}    ; when paginated
}
```

### Safeguards

- **Per-eval timeout.** SCI gets a deadline (default 5 s; daemon-configurable). Expiry → `:timeout`. Protects against pathological queries and infinite loops in stored views.
- **Result caps.** L1 listing fns have a default `:limit` (default 1000); excess returns first N + `:truncated? true :total N`. Explicit `:limit` and `:offset` for pagination. `q` results subject to the same cap.
- **Response body byte cap.** Hard ceiling (~8 MB) before the response is rejected with `:exceeded-cap`. Catches unbounded result blowing JSON size even if row count is reasonable.
- **Refresh atomicity.** `(refresh)` builds a new Model value then atom-swaps; in-flight readers see the pre-swap snapshot to completion. Concurrent `(refresh)` calls collapse to one.
- **SCI sandbox guarantees.** What eval **cannot** reach:
    - No `java.*` interop, no `clojure.java.io`, no `clojure.java.shell`, no `slurp`/`spit`.
    - No `System/exit`, no thread spawning, no `Thread/sleep` beyond a small ceiling.
    - No reach into other `fukan.*` namespaces — only `fukan.agent.api` + `fukan.agent.system` vars bound.
    - `def`/`defn` allowed only during the `agent-views.clj` load path; at eval-time, `def` is refused (`:forbidden`). Agents extend via file edits.
- **Daemon-not-running** is a CLI-level error, not an eval error; CLI emits a structured stderr message and exits non-zero before hitting the daemon.

### Testing — three layers

1. **L1 unit tests against fixture Models.** Hand-built small Models with known shape; assert each L1 fn returns expected primitive/relation sets under known filters. Covers the *vocabulary* contract.
2. **L0 / L1 / L2 equivalence tests.** For each built-in L2 fn, two property-style tests:
    - Result set matches the L0 Datalog form it claims to implement.
    - Result set matches the L1 composition shown in `(source 'fn)`.
    Catches L2 silently drifting from its lower-layer definition during refactors.
3. **End-to-end smoke.** Daemon up against a fixture target; exercise:
    - `fukan status` → expected shape.
    - `fukan eval '(primitives :kind :rule)'` → known primitives.
    - `fukan eval '(refresh)'` → completes; counts update.
    - `fukan eval '(System/exit 0)'` → `:forbidden`; daemon still up.
    - `fukan eval '(loop [] (recur))'` → `:timeout`; daemon still up.
    The sandbox-refusal cases are critical — they verify the security claims aren't aspirational.
4. **Agent-views loading tests.** Fixture `.fukan/agent-views.clj` with one good view + one syntactically broken view + one referencing an unbound var → `(status)` reports broken ones; good view is reachable; daemon healthy.

---

## 9. Deliberate omissions / explicit defers

Items repeatedly considered and **not** in MVP scope. Tracked so future decisions know what was left intentionally open.

- **No convenience commands.** No `fukan drift`, `fukan gaps`, `fukan blueprint`, etc. Drift and friends are L2 views, derivable in two lines of code. Convenience accretes via `agent-views.clj`, not the CLI.
- **No mutation primitives.** Spec edits happen by editing `.allium`/`.boundary` files directly. The agent's read surface against the rebuilt Model scaffolds the next edit.
- **No blueprint-driven generation tool.** `/projector` still exists as the HTTP endpoint; not wrapped as an agent tool for MVP. (Considered and dropped during scoping — the agent surface is upstream of code generation, not the consumer of blueprints.)
- **No MCP server.** A thin MCP wrapper around the CLI can be added later if it adds concrete value. The CLI stays canonical.
- **No `--target` flag.** Single Model per daemon for MVP; adding `--target` later is additive on both CLI and HTTP.
- **No CLI installer / packaging.** A `clj -M:agent-cli` alias or a Babashka script is enough for MVP.
- **No telemetry / eval logging surface.** Useful later to understand how agents use Fukan; not needed to ship.
- **No `(status)` history** beyond the current snapshot.
- **No API versioning header.** `(status)` will eventually expose a surface version; not yet.
- **No interactive tutorial.** Primer-as-document is sufficient.
- **No `pull` syntax** for shaped projections. `q` covers it.
- **No SSE / streaming.** Polling `(status)` is enough; if streaming becomes valuable, the existing `/sse` infrastructure can be reused.

---

## 10. What ships in MVP — checklist

- New namespaces: `fukan.agent.api` (L0 / L1 / L2 layered) and `fukan.agent.system` (flat).
- New HTTP endpoints on the existing daemon: `/agent/eval`, `/agent/status`.
- SCI-based evaluator with a frozen ns map, per-eval timeout, result + byte caps, structured error envelope.
- The `fukan` CLI (Babashka or native) with `status`, `eval '(...)'`, `primer`, and `init` subcommands.
- `.fukan/agent-views.clj` loader; status reporting of load errors.
- `AGENTS.md` primer (~200–400 lines) at the Fukan repo root as the canonical source. `fukan init` adds a "## Fukan" section to a target project's `AGENTS.md` (creating the file if absent) that points at `fukan primer` rather than embedding the body.
- Test suites covering L1 against fixture Models, L0/L1/L2 equivalence, sandbox refusals, and agent-views loading.

---

## 11. After this lands

- Update `spec`, `allium:tend`, `allium:weed`, `allium:distill`, `allium:propagate` agent prompts to prefer Fukan over file reading where Fukan is available.
- Watch agent usage in Fukan-on-Fukan; promote recurring patterns to built-in L2 only when they earn it.
- Validate against use case A by pointing Fukan at a non-trivial target project and letting an agent work against it. The shape of friction we encounter there shapes the next chapter (likely: parallel sessions, multi-target, surface versioning).
