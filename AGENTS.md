# Fukan — agent primer

> This is your mental-model guide. For the **live catalog** of fns,
> signatures, and examples, call `(help)` from inside
> `fukan eval '(...)'`. Don't memorise signatures here — they may
> drift; `(help)` is current.

## 1. What Fukan is

Fukan is a canvas-driven structural exploration tool. Its primitives are
modules, affordances, states, types, relations, and tags — not functions
and call edges. You write canvas specs to describe what a system is
meant to do; fukan reads them, builds a unified structural model, and
makes that model queryable. Code joins as a projection layer: every
spec primitive that should have a Clojure realisation gets a `projects`
edge carrying a `:validity` value — `:valid`, `:stale`, `:absent`, or
`:unknown`. Drift between intent and reality shows up as `:absent`
projections. The graph is rebuilt on every `(refresh)`. The browser
renders the same model the agent queries; humans use the browser,
agents use this surface.

**Canvas specs are in `canvas/<subsystem>/<module>.clj`.** Do not reach
for `.allium` or `.boundary` files — those are archived in
`.legacy-allium/` and no longer loaded by the build pipeline. If you
need to find where a concept is specified, look in `canvas/`.

## 2. The canvas vocabulary

Canvas specs use these construction forms. All are Clojure macros/fns
defined in `src/fukan/canvas/`.

**Always available (`construction.clj`):**

| Form | What it declares |
|------|-----------------|
| `(function name doc (takes ...) (gives ...))` | A synchronous callable |
| `(record name doc (field name type) ...)` | A named record type |
| `(value name doc)` | An opaque named type |
| `(exports Name1 Name2 ...)` | Module API closure — marks listed declarations as exported |

**Opt-in vocabularies:**

| Namespace | Form | What it declares |
|-----------|------|-----------------|
| `vocab.behavioral` | `(invariant name doc (holds-that "..."))` | A named timeless behavioral commitment |
| `vocab.behavioral` | `(rule name doc (when TriggerName (param :Type) ...))` | A reactive declaration with trigger |
| `vocab.lifecycle` | `(getter name doc :ReturnType)` | A zero-arg `Optional<T>` accessor |
| `vocab.validation` | `(checker name doc)` | A `(Model) -> [Violation]` validation entry point |

**Name+role convention.** A canvas module may declare multiple entities with the same name provided they have distinct `:affordance/role` values. The canonical example is the rule + invariant pair in `canvas/validation/*` — the same behavioral commitment expressed reactively (`(rule "X" ...)`, role `:canvas/rule`) and timelessly (`(invariant "X" ...)`, role `:canvas/invariant`). Reference resolution uses the `(name, role)` tuple; role is unambiguous from context. The canvas builder warns once per collision and never throws.

**Shape expression grammar (type positions):**

| Expression | Meaning |
|-----------|---------|
| `:Keyword` | Atomic type |
| `:ns/Name` | Cross-module type reference |
| `(optional :T)` | Optional value |
| `(list-of :T)` | Ordered list |
| `(set-of :T)` | Unordered set |
| `(sum-of :A :B)` | One-of sum type |
| `(map-of :K :V)` | Key-value map |

## 3. The Model in one minute

The Model has six substrate primitives and a projection layer.

**Six primitives (architecture-neutral):**

| Primitive | What it represents |
|-----------|-------------------|
| Module | A containment unit — the design boundary |
| Affordance | A named capability or behavioral declaration on a Module |
| State | A named data slot on a Module |
| Type | A data shape (record or atomic) |
| Relation | A typed directed edge between entities |
| Tag | An extensible classification label |

**Affordances** carry the design vocabulary: the `function` lift produces
an Affordance with role `:fukan.canvas.monolith/exposed-call`; `invariant`
produces one with role `:canvas/invariant`; `rule` with `:canvas/rule`;
`getter` with `:canvas/getter`; `checker` with `:canvas/checker`.

**Implementation is not a fourth altitude.** Code is a *projection* of
the canvas — a materialisation that loses information from the spec
source. A projection that no longer matches is `:stale` or `:absent`.
The kernel relation that carries this is `projects`.

## 4. The two namespaces

Both referred-in by default — no ns/fn prefix needed in `fukan eval`:

- **`fukan.agent.system`** — operating Fukan: `status`, `refresh`,
  `help`, `source`. Call these to orient yourself and to drive
  the rebuild cycle.

- **`fukan.agent.api`** — querying the Model: layered interface (L0 /
  L1 / L2) described in the next section.

## 5. The L0 / L1 / L2 layering of `fukan.agent.api`

| Layer | What lives here | Used for |
|-------|-----------------|----------|
| **L0** | `q` — Datalog over the Model | Joins, aggregations, anything ad-hoc |
| **L1** | `primitives`, `get-primitive`, `relations`, `vocabulary`, `schema`, `idioms`, `constraints`, `violations` | Daily driver; filter by kw-args |
| **L2** | `drift`, `neighborhood` (built-in) + whatever the project has added in `.fukan/agent-views.clj` | Recurring questions, named |

**Principle.** Higher layers are *convenience*, not *capability*. L1
is sugar over L0; L2 is named compositions. The agent learns L1
first, reaches for L0 when needed, and promotes recurring patterns
to L2.

### L0 — `q`

Datalog form: `[:find ?vars :where clauses]`. The EDB contains one
fact per primitive attribute and one fact per edge attribute. Useful
when you need joins across multiple primitives or arbitrary
aggregations that L1 filters can't express.

```clojure
(q '[:find ?p :where [?p :primitive/kind :primitive/rule]])
```

### L1 — probes

`(primitives)` and `(relations)` return an *envelope*:
`{:rows [...] :truncated? bool :total N}`. Extract rows with `:rows`
before mapping over them. `(get-primitive id)` returns a full
primitive map or `nil`.

Filters are keyword args. Current primitive filters: `:kind`, `:label`.
Current relation filters: `:kind`, `:from`, `:to`, `:validity`,
`:projection-kind`. Pass `:limit` to any listing fn to cap results.

`(vocabulary)` surfaces the primitive kinds and relation kinds present
in the loaded Model. `(schema :kind k)` lists the attributes observed
on all primitives of kind `k` plus which relation kinds they participate
in.

`(idioms)`, `(constraints)`, `(violations)` expose the project layer.
Pass `:severity :error` to `violations` to filter down.

### L2 — views

`(drift)` — absent projections joined with their source primitive.
Accepts optional `:projection-kind` filter. Returns a vector of maps
with `:from`, `:to`, `:kind`, `:validity`, and `:primitive` (the
full source primitive).

`(neighborhood id)` — a primitive + all its one-hop outgoing and
incoming edges + summary maps for the directly-connected neighbors.
Multi-hop traversal is the caller's job at L0.

## 6. Three worked examples

### (a) Orientation: what is module `infra.server`?

```clojure
;; What kinds of primitives are in the model?
(vocabulary)
;; => {:primitive-kinds [...] :relation-kinds [...]}

;; Find container primitives
(primitives :kind :primitive/container)

;; Inspect one in full
(get-primitive "infra.server")

;; What does this module connect to?
(neighborhood "infra.server")
```

Start with `(vocabulary)` to see what's in the model. Use
`(primitives :kind k)` to list; use `(get-primitive id)` to drill.
Use `(neighborhood id)` to see what a node connects to without
writing a query.

### (b) Derivation: what affordances have no Clojure realisation yet?

```clojure
;; L2 shorthand — already does the join for you:
(drift)

;; Filter to a specific projection kind:
(drift :projection-kind :clojure)
```

### (c) The edit-and-refresh loop

1. Call `(drift)` to find what spec primitives have no realisation.
2. Open the relevant canvas spec file on disk — `canvas/<subsystem>/<module>.clj`
   — and edit it to add, change, or remove a declaration.
3. Call `(refresh)` to rebuild the model. It blocks and returns the
   new status.
4. Re-query with `(drift)` or the same L1/L0 form. Iterate.

Edits always happen on disk. The loop is: **edit canvas file → `(refresh)` → query**.
There is no mutation surface inside `fukan eval`; all spec changes
land in source files.

## 7. Persisted views — `.fukan/agent-views.clj`

When a query recurs across sessions, name it and commit it to the
project-local views file. For a project analysed by Fukan, this
file lives at `<target-project-root>/.fukan/agent-views.clj`. For
Fukan-on-Fukan it lives at `<fukan-repo-root>/.fukan/agent-views.clj`.

Add a plain Clojure `defn` that composes L0 + L1:

```clojure
(ns project.agent-views)

(defn unrealised-by-kind
  "Absent projections grouped by the source primitive's kind."
  []
  (->> (:rows (relations :kind :relation/projects :validity :absent))
       (map #(-> % :from :id))
       (map get-primitive)
       (group-by :kind)))
```

Then call `(refresh)`. The fn loads into the project-local L2 bucket.
`(help)` lists it. `(source 'unrealised-by-kind)` returns the source.

Read the built-in canon first:

```clojure
(source 'drift)
;; => {:name drift :ns fukan.agent.api
;;     :source "(defn drift [& {:keys [projection-kind]}] ...)"}
```

Use that as your template. The file is versioned with the project and
shared between agents and humans.

### Edge cases to know

- If a def in the file has a syntax error, `(refresh)` returns a
  structured load report — other good defs still load.
- If a project-local def shadows a built-in L2 name, the local version
  wins; `(status)` reports the shadowing.
- Attempting to shadow an L1 or system fn is refused with `:forbidden`.

## 8. The reference catalog is live

```clojure
;; Full surface, grouped by namespace and layer:
(help)

;; Detail on a single fn — docstring, arglists, example:
(help 'primitives)

;; Source of a built-in view as a template:
(source 'drift)
(source 'neighborhood)
```

Use these aggressively. The primer you're reading now is static; the
`(help)` output is current. If a signature here and in `(help)` disagree,
`(help)` is right.

## 9. Constraints on what eval can do

The eval sandbox is SCI (Small Clojure Interpreter) with a frozen ns
map. It cannot reach beyond `fukan.agent.api` and `fukan.agent.system`.

**Hard limits:**

- No Java interop (`java.*`), no file IO (`clojure.java.io`, `slurp`,
  `spit`), no shell-out (`clojure.java.shell`).
- No thread spawning, no `System/exit`.
- Per-eval timeout — default 5 s. Long-running loops die with
  `:timeout` error kind. Design queries to terminate.
- Result row cap — default 1000 rows per listing fn. Paginate with
  `:limit` and `:offset`. `q` results are subject to the same cap.
- Response body byte cap — ~8 MB hard ceiling. Large result sets
  return `:exceeded-cap`; reduce scope with filters.
- `def` and `defn` work at eval time AND inside `.fukan/agent-views.clj`
  (a single shared SCI context backs both). But ad-hoc defs at eval time
  clutter the shared namespace and don't survive a daemon restart —
  prefer file-based views, which are persistent, reviewable, and
  shareable across humans and other agents on the project.

**If you need something not currently exposed:** propose adding it to
`fukan.agent.api` rather than reaching around via interop. The surface
is the contract; circumventing it undermines the model.

### Error envelope

Every eval response is either:

```clojure
{:ok? true  :result <data> :elapsed-ms N}
;; or, when truncated:
{:ok? true  :result <data> :elapsed-ms N :result-meta {:truncated? true :total N}}
```

or:

```clojure
{:ok? false
 :error/kind    :syntax | :unbound | :runtime | :timeout
                | :exceeded-cap | :model-not-loaded | :forbidden
 :error/message "..."}
```

`:model-not-loaded` means the daemon is up but no model has been built.
Call `(refresh)` to build one. `:unbound` includes what *is* in scope
so you can see what names are available.

## 10. Pointers

- `doc/VISION.md` — why Fukan exists; the canvas-first direction; what this chapter commits to
- `doc/DESIGN.md` — system design: three-tier canvas layering, ownership-on-owner principle, build pipeline, project layer
- `doc/MODEL.md` — Model substrate: six primitives, thirteen relations, vocabulary mechanism, constraint language
- `canvas/` — the canvas spec tree (62 modules); the design surface for fukan-itself
- `src/fukan/canvas/` — canvas machinery (core, construction, vocab libraries)

When working on Fukan itself, `src/fukan/agent/api.clj` and
`src/fukan/agent/system.clj` are the source of truth for what the
surface exposes. Read them when `(help)` is insufficient.
