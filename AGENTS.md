# Fukan — agent primer

> This is your mental-model guide. For the **live catalog** of fns,
> signatures, and examples, call `(help)` from inside
> `fukan eval '(...)'`. Don't memorise signatures here — they may
> drift; `(help)` is current.

## 1. What Fukan is

Fukan is a spec graph that knows about code. Its primitives are
behavioural rules, surfaces, contracts, types, modules, and subsystems —
not functions and call edges. You write `.allium` files to describe what
the system is meant to do; you write `.boundary` files to declare what
crosses each module wall. Fukan reads both, builds a unified structural
model, and makes that model queryable. Code joins as a projection layer:
every spec primitive that should have a Clojure realisation gets a
`projects` edge carrying a `:validity` value — `:valid`, `:stale`,
`:absent`, or `:unknown`. Drift between intent and reality shows up as
`:absent` projections. The graph is rebuilt on every `(refresh)`. The
browser renders the same model the agent queries; humans use the browser,
agents use this surface.

## 2. The Model in one minute

The Model has three spec altitudes and a projection layer underneath.

**Three altitudes, top to bottom:**

| Altitude | Files | What lives here |
|----------|-------|-----------------|
| Behaviour (highest) | `*.allium` | Rules, events, invariants — what happens and under what constraints |
| Structure | `*.allium` (partial) + `*.boundary` | Types, operations, surfaces, contracts; module walls; composition into subsystems |
| Infra (lowest spec altitude) | `*.infra` (deferred) | Endpoints, wire formats, deployment topology |

**Implementation is not a fourth altitude.** Code, tests, and docs are
*projections* of the three spec altitudes — chosen materialisations that
lose information from the spec source. A projection that no longer
matches its spec source is `:stale` or `:absent`. The kernel relation
that carries this is `projects`.

**Kernel primitives:** `Container`, `Actor`, `Behaviour`, `Rule`,
`Boundary`, `Operation`, `Intent`, `Clause`, `Event`. Nine primitives.
Each has an addressable id. Container is the universal structural unit —
modules, entities, surfaces, contracts, and aggregates are all Containers
with different vocabulary tags.

**Kernel relations:** thirteen directed edges —
`triggers`, `observes`, `reads`, `writes`, `creates`, `destroys`,
`emits`, `realises`, `specialises`, `uses`, `exposes`, `provides`,
`projects`. The `projects` relation is how code joins the model.

**Project layer:** two sub-loci — *projection inputs* (address-resolution
knobs for the Clojure target, type-translation overrides, idioms) and
*constraints* (architectural laws, naming preferences, with severity).
Exposed via `(idioms)`, `(constraints)`, `(violations)`.

Full detail: `doc/MODEL.md` (kernel substrate), `doc/DESIGN.md` (protocols,
pipeline, project-layer mechanics), `doc/VISION.md` (why).

## 3. The two namespaces

Both referred-in by default — no ns/fn prefix needed in `fukan eval`:

- **`fukan.agent.system`** — operating Fukan: `status`, `refresh`,
  `help`, `source`. Call these to orient yourself and to drive
  the rebuild cycle.

- **`fukan.agent.api`** — querying the Model: layered interface (L0 /
  L1 / L2) described in the next section.

## 4. The L0 / L1 / L2 layering of `fukan.agent.api`

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

## 5. Three worked examples

### (a) Orientation: what is module `fukan/model`?

```clojure
;; What kinds of primitives are in the model?
(vocabulary)
;; => {:primitive-kinds [...] :relation-kinds [...]}

;; Find container primitives
(primitives :kind :primitive/container)
;; => {:rows [{:id "container:fukan/model" :kind :primitive/container :label "..."} ...]
;;     :truncated? false :total N}

;; Inspect one in full
(get-primitive "container:fukan/model")
;; => {:id "..." :kind :primitive/container :label "..." :description "..." ...}

;; What does this container connect to?
(neighborhood "container:fukan/model")
;; => {:primitive {...} :outgoing [...] :incoming [...] :neighbors [...]}
```

Start with `(vocabulary)` to see what's in the model. Use
`(primitives :kind k)` to list; use `(get-primitive id)` to drill.
Use `(neighborhood id)` to see what a node connects to without
writing a query.

### (b) Derivation: what rules have no Clojure realisation yet?

```clojure
;; L2 shorthand — already does the join for you:
(drift)
;; => [{:from {:endpoint/primitive "rule:fukan/model/r-merge"}
;;      :to nil
;;      :kind :projects
;;      :validity :absent
;;      :primitive {:id "rule:..." :kind :primitive/rule :label "..."}}
;;     ...]

;; Filter to a specific projection kind:
(drift :projection-kind :clojure)

;; If you want only rules, compose at L1:
(->> (:rows (relations :kind :projects :validity :absent))
     (map #(-> % :from :endpoint/primitive))
     (map get-primitive)
     (filter #(= :primitive/rule (:kind %))))

;; Or with full aggregation at L0:
(q '[:find ?m (count ?p)
     :where
       [?r :kind :projects]
       [?r :validity :absent]
       [?r :from ?p]
       [?p :owner ?m]])
```

Both L1 and L0 forms produce the same data. Use `(drift)` for the
common case; drop to L1 when you need to filter or transform before
acting; use L0 when the question needs joins or aggregations that L1
filters can't express.

### (c) The edit-and-refresh loop

1. Call `(drift)` to find what spec primitives have no realisation.
2. Open the relevant spec file on disk — `src/.../*.allium` or
   `src/.../*.boundary` — and edit it to add, change, or remove
   a declaration.
3. Call `(refresh)` to rebuild the model. It blocks and returns the
   new status.
4. Re-query with `(drift)` or the same L1/L0 form. Iterate.

Edits always happen on disk. The loop is: **edit → refresh → query**.
There is no mutation surface inside `fukan eval`; all spec changes
land in source files.

## 6. Persisted views — `.fukan/agent-views.clj`

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
  (->> (:rows (relations :kind :projects :validity :absent))
       (map #(-> % :from :endpoint/primitive))
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

## 7. The reference catalog is live

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

## 8. Constraints on what eval can do

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
- `def` and `defn` are refused at eval time (`:forbidden`). Define
  persistent views in `.fukan/agent-views.clj` and reload with
  `(refresh)`.

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

## 9. Pointers

- `doc/VISION.md` — why Fukan exists; the spec-graph-knows-about-code
  inversion; what this chapter commits to
- `doc/DESIGN.md` — system design: protocols, pipeline, project-layer
  mechanics, Implementation Blueprint (code gen from spec)
- `doc/MODEL.md` — Model substrate: nine primitives, thirteen
  relations, vocabulary mechanism, constraint language
- `doc/specs/2026-05-20-llm-agent-surface-design.md` — design spec
  for this agent surface: layering rationale, safeguards, deferred
  items
- `doc/plans/2026-05-20-llm-agent-surface.md` — implementation plan
  for this surface

When working on Fukan itself, `src/fukan/agent/api.clj` and
`src/fukan/agent/system.clj` are the source of truth for what the
surface exposes. Read them when `(help)` is insufficient.
