# Phase 7 Sprint 1 — Scenario + handoff design (Layer B)

**Date:** 2026-05-27
**Status:** Draft for user review (pause point before Sprint 2 dispatch)
**Companion doc:** doc/plans/2026-05-27-project-lens-design.md (Sprint 1 Task 1 — Layer A)

---

## Strategic frame

The Phase 7 plan's load-bearing amendment partitions the work into two layers. **Layer A** is the project-lens — given a generic Model element and a registered language lens, it produces a deterministic low-level code specification (target address, structural template, prose envelope). Layer A is intrinsic to the Model element + lens; it does not know whether the canvas-author is closing a known gap or writing a module from scratch. **Layer B is where situation enters.** The same Layer A spec is wrapped with a scenario context that names what the implementing LLM is being asked to do, what's already around the gap, what discipline to keep, and what to ignore. drift-close says "you're closing a known gap; preserve neighbors." cold-write says "you're writing this module fresh; here are the matching neighbors elsewhere and the conventions you should follow."

Layer B closes Phase 7's product surface: **canvas-author writes design; fukan tells the implementing LLM exactly what to write in `src/`.** The canvas-author LLM (running as `fukan-architect`) picks which gap to close, asks Layer B to render the instruction, reviews it, dispatches a general-purpose implementing LLM with the rendered instruction as its prompt + a small targeted context bundle, then re-runs drift to verify closure. Phase 7 keeps the canvas-author in the loop (one instruction reviewed per dispatch); Phase 8 closes the loop further by auto-dispatching from drift findings.

This document specifies (1) the scenario contract every scenario must satisfy, (2) the concrete shape of the two scenarios that ship in Phase 7 — `drift-close` and `cold-write` — each sketched against a real fukan-on-fukan situation, (3) the handoff protocol that connects `fukan-architect` to the implementing LLM, (4) the trial-run target Sprint 4 will exercise, and (5) the open questions for user review before Sprint 2 begins.

---

## The scenario contract

Confirmed largely as drafted in the Phase 7 plan, with two refinements specified below.

### Per-scenario declaration shape

Every scenario ships as a single `(def scenario …)` form in `src/fukan/canvas/instruct/<name>.clj` registered against `src/fukan/canvas/instruct/registry.clj`:

```clojure
{:scenario-id      :drift-close                          ; required; namespaced :code-side/<name>
 :description      "Close a known canvas↔code drift gap" ; required; one-line summary surfaced in (help)
 :prompt-fragment  "..."                                 ; required; situation-framing prose template
 :build-context    (fn [layer-a-spec opts] ...)          ; required; produces scenario-context map
 :render           (fn [layer-a-spec scenario-context opts] ...)} ; required; produces full rendered instruction
```

The `:scenario-id` namespace is `:code-side/*` for Phase 7. All Phase 7 scenarios dispatch the implementing LLM to write code; symmetric `:canvas-side/*` scenarios (where the canvas is the side that moves) defer to Phase 8 entirely.

### Per-instruction return shape (what scenarios produce)

```clojure
{:scenario-id      :drift-close
 :code-spec        <Layer-A projection map; see companion doc Section "Per-projection return shape">
 :scenario-context {:what-exists-in-target-file "..."   ; scenario-specific keys
                    :neighbor-patterns          [...]
                    :discipline-prose           "..."
                    :drift-finding              {...}}
 :rendered         "<full markdown the implementing LLM reads>"}
```

**Refinement 1 — the `:rendered` string is canonical.** The structured `:code-spec` and `:scenario-context` are carried for verifiability + downstream tooling (Phase 8's auto-dispatch will parse them), but the `:rendered` field is what the implementing LLM consumes. If a future Layer B render path produces a different artifact (e.g. a `.md` file on disk + a path), the scenario's `:render` fn returns that; `:rendered` carries either the inline markdown or the path.

**Refinement 2 — `opts` is open-shape.** Each scenario's `:build-context` and `:render` take an `opts` map for caller-supplied overrides (e.g. drift-close may take `:include-related-element-defs? true` to fold in sibling definitions; cold-write may take `:neighbor-count 3` to control how many matching neighbors get cited). Defaults live in the scenario definition; callers override per-invocation. Sprint 3 lands the concrete `opts` keys per scenario.

### Validators (Sprint 3 Layer-B substrate)

`src/fukan/canvas/instruct/core.clj` will ship `valid-scenario?` / `validate-scenario` (over the declaration) and `valid-instruction?` / `validate-instruction` (over the produced map). Shape mirrors the lens-substrate validators from Phase 5.

### Why this shape

The contract keeps three things explicit. The **Layer A spec** is carried verbatim so the implementing LLM (and a downstream verifier) can see what projection was used; it round-trips with `inspect/drift.clj`'s stable-ids. The **scenario context** is the new information Layer B contributes — what makes this instruction situation-aware. The **rendered prose** is the LLM-facing surface; everything else is verifiable structure underneath.

---

## drift-close scenario

### What it does

Takes one drift finding + the Layer-A projection of the named gap → produces an instruction framed as "you are closing a known gap." The implementing LLM reads the rendered output, edits the target file to add the missing implementation, and runs tests. The canvas-author reviews drift afterwards to confirm closure.

### Input

A drift finding's `:offenders[N]` entry plus the Layer-A projection of the named `:stable-id`:

```clojure
;; from (canvas-drift) output
{:check             :missing-implementation
 :severity          :warning
 :primitive-id      "distributed.cluster/get_self_role"
 :offenders [{:stable-id          "distributed.cluster/function/get_self_role"
              :expected-code-path "src/fukan/distributed/cluster.clj"
              :expected-symbol    "get-self-role"
              :canvas-kind        :getter}]}
```

Plus the Layer A projection of that stable-id (the companion doc's getter / function projection).

### `:build-context` — what the scenario adds

drift-close fetches **neighbor context** from the target file. The implementing LLM needs to know what's already in the file so it doesn't disturb existing imports, sibling defs, or unrelated content. The context-builder:

1. Reads `:expected-code-path` from disk (`slurp`). Returns `:target-file/missing` if absent (cold-write candidate, not drift-close).
2. Parses the ns form (top-level `(ns …)`). Extracts: namespace symbol, docstring, `:require` clauses.
3. Walks the top-level forms. Extracts the names of existing `def` / `defn` / `defn-` / `defrecord` declarations + their first-line docstrings (the "sibling summary").
4. Records the **insertion point** — convention: append at end of file, just before the closing parenthesis of any trailing block; preserve trailing newline. The scenario's render output names the insertion point explicitly so the LLM doesn't have to guess.
5. Carries the drift finding itself in `:drift-finding` so the LLM understands the why.

Returns:

```clojure
{:what-exists-in-target-file
 {:ns-symbol     "fukan.distributed.cluster"
  :ns-docstring  "Implementation surface for canvas.distributed.cluster — partial."
  :requires      []
  :sibling-defs  [{:symbol "NodeId" :first-line "An opaque, stable identity for a cluster member."}
                  {:symbol "Term"   :first-line "A monotonically increasing logical clock."}
                  {:symbol "Node"   :first-line "A known member of the cluster."}
                  {:symbol "Cluster" :first-line "One node's view of the cluster."}
                  {:symbol "get-current-term"   :first-line "The most recent Term this node has observed."}
                  {:symbol "get-current-leader" :first-line "The id of the leader for the current term, if one is known."}
                  {:symbol "get-node"   :first-line "Look up a known cluster member by id."}
                  {:symbol "members"    :first-line "Return all known cluster members as Node values."}]
  :insertion-point "end-of-file"}
 :discipline-prose
 "You are closing a known gap. Do not disturb unrelated content. Preserve existing imports. Add the new definition at the end of the file."
 :drift-finding   <the offender entry verbatim>}
```

### `:render` — concrete instruction for `distributed.cluster/function/get_self_role`

The canvas declares the getter on line 82 of `canvas/distributed/cluster.clj`:

```clojure
(getter "get_self_role"
  "This node's current role within the cluster."
  :NodeRole)
```

The trial run deliberately omitted this; drift surfaces stable-id `distributed.cluster/getter/get_self_role` (note the canvas-kind disambiguation; the actual stable-id is whatever canvas-source emits — verify in Sprint 3). The full rendered instruction:

```markdown
# Implementation instruction — drift-close

## What you're doing

You are closing a known **canvas↔code drift gap** in fukan. The canvas
declared a getter; the code-side counterpart is absent. Your job: add the
implementation to the target file. Do not disturb unrelated content.

## The gap (canvas → code)

- **Canvas declaration:** `canvas/distributed/cluster.clj`
- **Stable-id:** `distributed.cluster/getter/get_self_role`
- **Canvas-kind:** getter
- **Target file:** `src/fukan/distributed/cluster.clj`
- **Expected symbol:** `get-self-role`
- **Severity:** :warning (drift is fact-of-discrepancy; resolution is judgment)

## The code spec (Layer-A projection)

Project the getter as a zero-arg `defn` returning `Optional<NodeRole>`.

\`\`\`clojure
(defn get-self-role
  "This node's current role within the cluster."
  {:malli/schema [:=> [:cat] [:maybe :NodeRole]]}
  []
  (throw (ex-info "get-self-role: not yet implemented"
                  {:canvas-id "distributed.cluster/getter/get_self_role"})))
\`\`\`

Prose from canvas: "This node's current role within the cluster." `NodeRole`
is one of `:follower`, `:candidate`, `:leader` (see `NodeRole` in the same
file). The getter returns `nil` when this node's role is unknown (per the
`optional`/`:maybe` convention).

## Neighbor context (what's already in the file)

The target file already declares:

- `NodeId`, `Term`, `NodeRole`, `Node`, `Cluster` (all type schemas)
- `get-current-term`, `get-current-leader` (sibling getters; same shape)
- `get-node`, `members` (function stubs)

The ns form is `(ns fukan.distributed.cluster "...")`. No `:require` clauses.
Insertion point: end of file.

**Match the existing sibling style.** The other getters take a `cluster`
argument explicitly (the trial keeps state-passing pure rather than closing
over node state). Follow the same convention.

## Discipline

- Do **not** disturb unrelated content.
- Preserve the file's existing imports and ns form exactly.
- Add the new `defn` at end of file.
- After writing, run `clj -M:test` to confirm the file still loads.
- Commit the change with a jj message of the form
  `feat(distributed/cluster): close drift — get-self-role`.

## Output format

Write the edit directly to `src/fukan/distributed/cluster.clj` using the
`Edit` tool. Do not produce a diff — the file is yours to modify. After
writing + committing, report:

- The symbol you added
- The exact insertion point used
- The `jj` commit id

## Why this is being asked

The canvas-author has reviewed the drift finding and judged that the canvas
side is correct (the getter should exist) and the code side should move.
Your role is to make the code match the canvas. If you encounter ambiguity
the spec doesn't resolve, prefer matching the sibling getters' style over
inventing a new convention.
```

### Key insight from sketching this instruction

The **neighbor context section is the single largest difference** between this scenario and cold-write — drift-close fetches what's already in the file because the target file exists, and uses that as the convention anchor. The implementing LLM doesn't need to be told the project's conventions in the abstract; it needs to be told "look at the file you're editing — match its existing style." This is the targeted-context discipline: pass the file's existing siblings (names + first-line docstrings), not the canvas db, not the full project conventions doc.

A second insight: the **stub template is structurally complete** (compiles, loads, throws when called). The implementing LLM's job is to replace the throw with real logic, not to invent the signature. This is the explicit Phase 7 bet — clear instruction + capable LLM beats rich template.

---

## cold-write scenario

### What it does

Takes a canvas module declaration (or a chosen subset of its entities) → produces an instruction framed as "you are writing this module's implementation from scratch." The implementing LLM creates the target file (with ns form) and writes all the declared implementations. The canvas-author reviews drift afterwards to confirm closure of all entities in the module.

### Input

A module's stable-id (e.g. `"distributed.cluster"`) + the Layer-A projections of every entity declared in it (or a subset specified by the caller). For the trial target, this would be:

- `distributed.cluster/type/NodeId` → value-to-def
- `distributed.cluster/type/Term` → value-to-def
- `distributed.cluster/type/NodeRole` → value-to-def
- `distributed.cluster/type/Node` → type-to-malli
- `distributed.cluster/type/Cluster` → type-to-malli
- `distributed.cluster/invariant/AtMostOneLeaderPerTerm` → invariant-to-predicate
- `distributed.cluster/invariant/TermMonotonicity` → invariant-to-predicate
- `distributed.cluster/invariant/MajorityRequiredForLeadership` → invariant-to-predicate
- `distributed.cluster/getter/get_current_term` → getter-to-defn (Phase 7.5 if not shipped)
- `distributed.cluster/getter/get_current_leader` → getter-to-defn
- `distributed.cluster/getter/get_self_role` → getter-to-defn
- `distributed.cluster/function/get_node` → function-to-defn
- `distributed.cluster/function/members` → function-to-defn

### `:build-context` — what the scenario adds

cold-write fetches three context bundles:

1. **Project conventions** — pointer (not embedded). Names the canvas-authoring system prompt + CLAUDE.md as the references; the implementing LLM has Read access and can pull them on demand. Mentions specific load-bearing conventions inline (e.g. "Records project as Malli `[:map …]` schemas marked `^:schema`; events project as schemas marked `^:event`").

2. **Matching-pattern neighbors** — a short list of existing `src/fukan/*` modules with the same Model-element-kind mix as the target. The scenario's neighbor-selection heuristic:
   - For each canvas-declared entity kind in the target module, find a sibling module under `canvas/` that ships the same mix.
   - For each such sibling, the corresponding `src/` file (if it exists and is non-trivial) becomes a neighbor recommendation.
   - Cap at 3 neighbors total. Prefer neighbors in the same canvas subsystem (e.g. for `distributed.cluster`, prefer `distributed.election` over `infra.server`).
   - If <2 neighbors are available (rare; means the canvas-element-kind mix is novel), fall back to type-only matches (e.g. "look at `src/fukan/infra/server.clj` for record + function organization").

3. **The Layer A projections themselves** — every entity in the module (or selected subset) is rendered through Layer A; the result map is folded into the scenario context as `:projections`. The render step emits one section per projection in the rendered instruction.

Returns:

```clojure
{:module-id          "distributed.cluster"
 :module-canvas-path "canvas/distributed/cluster.clj"
 :target-file        "src/fukan/distributed/cluster.clj"
 :target-already-exists? false                        ; or true; affects discipline prose
 :projections        [<Layer-A spec> <Layer-A spec> ...]
 :conventions-pointer
 {:canvas-authoring-prompt "doc/canvas-authoring-system-prompt.md"
  :project-claude-md       "CLAUDE.md"
  :load-bearing-conventions
  ["Records project as Malli `[:map …]` schemas marked `^:schema`."
   "Events project as schemas marked `^:event`."
   "Function-shaped affordances kebab-case; types stay PascalCase."
   "Invariants project as `defn name [model] ...` predicate fns."
   "Field-level optionality renders as `{:optional true}` in the `:map`."
   "Cross-module type refs like `:cluster/NodeId` resolve via the Malli registry."]}
 :neighbors
 [{:canvas-path "canvas/infra/server.clj"
   :src-path    "src/fukan/infra/server.clj"
   :why         "Records + functions + a getter — same kind-mix as your target."}
  {:canvas-path "canvas/infra/model.clj"
   :src-path    "src/fukan/infra/model.clj"
   :why         "Module with kebab-case fn naming + invariants commented for property-test deferral."}]
 :discipline-prose
 "You are writing the implementation of canvas.distributed.cluster from scratch. The canvas is the design; your job is the code-side projection. Follow the project conventions even where the spec is silent. When the spec gives you signature + intent but not body logic, draw on the neighbor modules for style and on the canvas docstrings for semantic intent."}
```

### `:render` — concrete instruction for `canvas/distributed/cluster.clj`

The rendered instruction (abridged — the full version repeats the spec-per-entity sections):

```markdown
# Implementation instruction — cold-write

## What you're doing

You are writing the implementation file for **canvas.distributed.cluster**
from scratch. The canvas declares 5 types, 3 invariants, 3 getters, and 2
functions — 13 entities total. Your output: one file at
`src/fukan/distributed/cluster.clj` that projects each canvas declaration
into the matching Clojure form.

## The canvas

- **Module canvas path:** `canvas/distributed/cluster.clj`
- **Target file:** `src/fukan/distributed/cluster.clj` (does not yet exist)
- **Project conventions:** read `doc/canvas-authoring-system-prompt.md` and
  `CLAUDE.md` for the full set. Load-bearing conventions for this task:
  - Records project as Malli `[:map …]` schemas marked `^:schema`.
  - Function-shaped affordances kebab-case; types stay PascalCase.
  - Invariants project as `defn name [model] ...` predicate fns whose body
    is a `(throw …)` stub carrying canvas-id metadata. The semantic intent
    lives in the docstring; replace the stub with property logic when
    real implementation lands (not now — leave the stub for this trial).
  - Cross-module refs (e.g. `:cluster/NodeId` inside a sibling module)
    resolve via the Malli registry; render them as keywords here.

## Matching-pattern neighbors

When the spec gives you a signature but not a body, look at these for style:

1. **`src/fukan/infra/server.clj`** — records + functions + a getter; mirrors
   the kind-mix of your target. Note how it handles optional fields (`port`
   with `{:optional true}` plus default in destructuring), Malli schema
   metadata on the defn (`{:malli/schema [:=> ...]}`), and the
   `defonce`-based state pattern (do **not** copy the `defonce` — your
   target is pure state-passing; see the spec sections below).

2. **`src/fukan/infra/model.clj`** — kebab-case fn naming throughout.
   Invariants commented for property-test deferral (similar to what you
   should produce here for the 3 cluster invariants).

## The code specs (Layer-A projections, per entity)

### 1. `NodeId` (value → opaque marker)

\`\`\`clojure
(def ^:schema NodeId
  [:any {:description "An opaque, stable identity for a cluster member.
   Distinct from the transport-layer address; survives restarts and rebinds."}])
\`\`\`

### 2. `Term` (value → opaque marker)

\`\`\`clojure
(def ^:schema Term
  [:any {:description "A monotonically increasing logical clock. Every
   leadership epoch carries a unique term. Comparable; never reused."}])
\`\`\`

### 3. `NodeRole` (value → opaque marker)

\`\`\`clojure
(def ^:schema NodeRole
  [:any {:description "One of: Follower, Candidate, Leader."}])
\`\`\`

### 4. `Node` (record → Malli :map)

\`\`\`clojure
(def ^:schema Node
  [:map {:description "A known member of the cluster."}
   [:id   :NodeId]
   [:role :NodeRole]])
\`\`\`

### 5. `Cluster` (record → Malli :map)

\`\`\`clojure
(def ^:schema Cluster
  [:map {:description "The cluster's view from one node."}
   [:self           :NodeId]
   [:members        [:set :NodeId]]
   [:current_term   :Term]
   [:current_leader {:optional true} :NodeId]])
\`\`\`

### 6. `AtMostOneLeaderPerTerm` (invariant → predicate)

\`\`\`clojure
(defn at-most-one-leader-per-term
  "Across the cluster, at most one node may hold the Leader role for
   any given Term."
  [model]
  (throw (ex-info "at-most-one-leader-per-term: not yet implemented"
                  {:canvas-id "distributed.cluster/invariant/AtMostOneLeaderPerTerm"
                   :invariant-name "AtMostOneLeaderPerTerm"
                   :holds-that "at-most-one leader per term"})))
\`\`\`

[… repeat for TermMonotonicity, MajorityRequiredForLeadership, three
getters, two functions, each with its full Layer-A template + prose …]

## Discipline

- Write the ns form first: `(ns fukan.distributed.cluster "<docstring>")`.
- Order matches the canvas: types first, invariants next, getters, then
  functions. (Match the canvas declaration order to make drift comparison
  easy to read.)
- Follow the project conventions even where the spec is silent.
- Where the spec gives you signature + prose but no body, leave the
  stub `(throw (ex-info …))` in place. This is a deliberate Phase 7
  convention — bodies land in subsequent rounds.
- After writing, run `clj -M:test` to confirm the file loads.
- Commit with `feat(distributed/cluster): cold-write — 13 entities`.

## Output format

Write the new file directly to `src/fukan/distributed/cluster.clj`. Report
the file path, the entity count, and the `jj` commit id.

## Why this is being asked

The canvas-author has declared the `distributed.cluster` module's design
and is now requesting the code-side projection. The spec is settled; your
job is the mechanical translation, respecting project conventions and
preserving intent.
```

### Key insight from sketching this instruction

The **conventions pointer + matching neighbors are doing more work than the project conventions doc would alone.** "Look at `src/fukan/infra/server.clj` to see how a module of this shape is typically organized" is a more targeted prompt than "read CLAUDE.md and follow the conventions." Capable code-synthesis LLMs are very good at imitating an exemplar; they're less good at applying a list of rules. The discipline of cold-write is **pick the right exemplar, don't enumerate the rules.**

A second insight: **the per-entity sections inflate the instruction's length quickly.** A 13-entity module's rendered instruction is ~1000 lines. The default `opts` should let callers pass a subset (e.g. `:include-entity-ids [...]` to render only the named entities; the implementing LLM then closes one section at a time). Phase 7 default: render all entities by default; the canvas-author can call `(instruct module-id :code-side/cold-write {:include-entity-ids […]})` to scope down.

A third insight: **the canvas-author's review step is non-trivial for cold-write.** The 13-entity instruction is long enough that the canvas-author may want to break it into parts. Phase 7 keeps the human-reviewable single-instruction contract; if the trial run in Sprint 4 surfaces "this is too much to review in one go," Phase 8's multi-instruction batching reshape inverts that — small slices auto-dispatched, canvas-author audits results instead of pre-reviewing.

---

## The handoff protocol

### Per-turn protocol (Phase 7 — human-in-the-loop)

1. **Pick a target.** The canvas-author LLM (running as `fukan-architect`)
   either reads `(canvas-drift)` output and picks a single offender entry,
   or names a canvas module to cold-write.

2. **Generate the instruction.** Invokes `(instruct <target> :code-side/<scenario>)`:
   - `(instruct drift-offender :code-side/drift-close)` — for a drift gap
   - `(instruct module-id :code-side/cold-write)` — for a whole module
   - The fn returns the structured `{:scenario-id :code-spec :scenario-context :rendered}` map. The `:rendered` field is the prompt the implementing LLM will read.

3. **Review the instruction.** The canvas-author reads the rendered output
   end-to-end. Catches obvious issues — wrong target path, missing context,
   oddly-shaped signature, a referenced sibling that no longer exists,
   prose that doesn't read clearly. **If anything is off, the canvas-author
   does not dispatch.** Either reports back to the user (Phase 7 stance:
   the canvas-author is a thinker, not a fix-er) or fixes the source canvas
   declaration so the next regeneration is clean.

4. **Dispatch the implementing LLM.** Uses the Agent tool (Anthropic's
   sub-agent dispatch primitive) with:
   - **system prompt:** brief operator framing ("You are a Clojure
     implementing assistant. You receive an instruction; you write code.
     You have Read, Write, Edit, Bash tools.")
   - **user message:** the `:rendered` string from step 2 (the instruction)
   - **tool grants:** Read, Write, Edit, Bash (with permission scoped to
     `clj -M:test` + `jj` for commits)
   - **no canvas-db access.** The implementing LLM does not call
     `bin/fukan eval`. The instruction is self-contained.

5. **Implementing LLM acts.** Writes/edits the target file, runs tests,
   commits with a jj message. Returns a short report — what was written,
   what tests passed, the commit id, any unresolved questions.

6. **Verify closure.** Canvas-author re-runs `(canvas-drift)` (or a scoped
   `(canvas-drift :module-coord "distributed.cluster")` once that affordance
   exists — recommendation 5 from the Phase 6 trial-run findings). Confirms
   the named finding(s) cleared. The new finding count is reported back to
   the user.

7. **Retry once if needed.** If the finding persists, the canvas-author
   dispatches the implementing LLM a SECOND time with the new drift output
   appended as feedback. **Max 2 iterations per instruction.** If iteration
   2 still doesn't close the gap, the canvas-author surfaces the situation
   to the user — the instruction itself is suspect (Layer A defect or
   Layer B scenario defect), not the implementing LLM.

### Implementing-LLM brief shape (what context to pass + what NOT to pass)

**Pass:**

- The full `:rendered` instruction string. This is the contract.
- A pointer (not the file) to `CLAUDE.md` if the scenario references it.
  The implementing LLM has Read access and can pull on demand.
- For drift-close: the existing target file is on disk; the implementing
  LLM reads it. Don't pre-read and embed.
- For cold-write: the matching-neighbor file paths (cited inline in the
  rendered instruction). Implementing LLM reads them on demand.

**Don't pass:**

- The canvas db. The implementing LLM has no `bin/fukan` access; everything
  it needs is in the rendered instruction.
- The full `(canvas-coverage)` output. Most of it is irrelevant; the one
  finding being closed is named explicitly in the instruction.
- The list of all drift findings. Same reasoning — the one being closed
  is named.
- The Layer A projection map (structured form) AS WELL AS the rendered
  instruction. Pass one; pass the rendered one. The structured form is
  for verification, not for the implementing LLM's consumption.
- The project's full convention docs verbatim. Pointers + the load-bearing
  conventions inline in the instruction. Read-on-demand for the rest.

**The discipline:** capable code-synthesis LLMs need targeted info — what
to write, what's around it, what conventions to follow. They do not need
the canvas substrate vocabulary, the model schema, or the survey lens
output. Phase 7's bet is that **the rendered instruction is the minimum
sufficient context**, and Sprint 4's trial run will tell us where that bet
holds and where it breaks.

### Verification protocol

After the implementing LLM returns:

1. Canvas-author re-runs `(canvas-drift)` scoped to the affected module
   (or globally if scope-filter doesn't land in Sprint 2).
2. For drift-close: confirm the named offender's `:stable-id` is no longer
   in the output.
3. For cold-write: confirm every projection the instruction covered
   no longer appears as a missing-implementation finding. Shape-drift may
   appear (the implementing LLM may project a field in a shape that
   triggers Phase 7 Sprint 2's compound-shape comparator differently) —
   that's a real signal, surface to the user, don't retry.
4. If iteration 1 didn't close the named gap(s), construct the retry brief:
   - Original `:rendered` instruction
   - The implementing LLM's report from iteration 1 (what it claimed to do)
   - The drift output post-iteration-1 (proof the gap persists)
   - Plus a one-paragraph "the gap persists; here's what drift still says — please reconcile"
5. Dispatch iteration 2. If iteration 2 still doesn't close, escalate.

### Retry policy — confirmed at 2 iterations

The user default ("max 2 iterations per instruction") holds. Phase 8 will
likely refine this with richer retry policies (e.g. iteration 2 attempts a
narrower fix; iteration 3+ escalates to canvas-author re-review). Phase 7
keeps it simple: dispatch, verify, one retry on failure, escalate.

### Multi-instruction batching — confirmed deferred

The user default ("one instruction per dispatch in Phase 7") holds. The
canvas-author may, in practice, dispatch multiple instructions in sequence
during a session — but each one is its own review→dispatch→verify cycle.
Phase 8 candidate: batched dispatch (the implementing LLM receives several
instructions in one prompt and handles them in sequence within one
sub-agent session). Defer.

### Symmetric (canvas-side) scenarios — confirmed deferred

The user default ("symmetric scenarios defer to Phase 8 entirely") holds.
Phase 7 ships `:code-side/drift-close` and `:code-side/cold-write` only. A
future `:canvas-side/drop-declaration` (close a drift gap by retracting
the canvas side) or `:canvas-side/rename` (canvas declared a name the code
diverged from for good reasons) belong in Phase 8 alongside the
closing-the-loop automation.

---

## Trial-run target

**Confirmed: `canvas/distributed/*` + extending `src/fukan/distributed/*`.**

The Phase 6 trial-run findings doc records 30 surviving drift findings
inside `distributed.*` post-trial — exactly the deliberate omissions left
to exercise the closing loop. Specifically:

| Module | Surviving findings | Best-fit scenario |
|---|---|---|
| `distributed.cluster` | get_self_role + 3 invariants + 2 shape-drift | drift-close (per finding) or cold-write (rewrite module) |
| `distributed.election` | 3 missing events + 2 missing handlers + 3 missing invariants | drift-close per finding |
| `distributed.log` | "mostly absent" — 12+ findings | cold-write |

**Sprint 4 trial plan:**

1. **Pick 3 specific drift-close trials.**
   - `distributed.cluster/getter/get_self_role` — simplest case, isolated.
   - `distributed.election/handler/on_election_started` (or similar)
     — handler stub, more complex signature.
   - `distributed.election/invariant/<one>` — the property-test deferral
     case; tests that the prose envelope is sufficient.

2. **Pick 1 cold-write trial.** Recommended: `distributed.log` — sparsest
   today, so cold-write produces visible closure.

3. **For each trial:**
   - Canvas-author generates the instruction.
   - User-reviewed (Sprint 4 keeps human in the dispatch loop).
   - Dispatch the implementing LLM.
   - Verify closure via re-run drift.
   - Document the loop's feel — instructions clear? implementing LLM had
     what it needed? Verification correct? Retry needed?

4. **Outcomes to record (in `doc/plans/2026-05-27-instruction-trial-run-findings.md`):**
   - Per-trial closure rate (closed in 1 iteration / 2 iterations / not closed).
   - Per-trial signal-to-noise of the rendered instruction.
   - Whether neighbor context made the difference (drift-close) or whether
     the matching-neighbor recommendation made the difference (cold-write).
   - Whether the canvas-author's review caught anything; if so, what.
   - The shape of any escalations.

The Phase 6 trial-run findings already lay the groundwork — the surviving
distributed.* findings are clean, deliberate, and varied enough to exercise
both scenarios across multiple Model-element kinds.

---

## Open questions for the user

1. **Scenario id namespace — `:code-side/*` vs `:drift-close` flat.** The
   doc above uses `:code-side/drift-close` to make room for Phase 8's
   symmetric `:canvas-side/*` scenarios. Push back if you'd rather keep
   ids flat (`:drift-close`) and add the side later.

2. **`:rendered` field vs `:render-path`.** Phase 7 renders inline strings;
   if instructions grow long (cold-write at ~1000 lines), a future
   refactor may write to disk and return a path. Recommend inline-string
   for Phase 7; revisit if Sprint 4 trial shows the prompt-size friction.

3. **`opts` keys for drift-close.** Recommend:
   - `:include-related-element-defs? (default false)` — fold sibling
     defs verbatim into the rendered output (rather than just first-line
     summaries). Useful when the implementing LLM needs to see exact
     existing field shapes for type-consistency.
   Push back if you want a different set.

4. **`opts` keys for cold-write.** Recommend:
   - `:include-entity-ids [...]` (default nil = all) — scope the
     instruction to a subset of the module's entities.
   - `:neighbor-count (default 3)` — how many matching neighbors to cite.
   Push back if you want a different set.

5. **Neighbor-selection heuristic for cold-write.** Recommend:
   "same Model-element-kind mix in canvas sibling modules, capped at 3,
   preferring same canvas subsystem." Push back if you'd rather have a
   different selection rule (e.g. recency, file-size, vocab-coverage).

6. **Where do conventions live in the prompt — inline list vs pointer?**
   The cold-write instruction above includes a small inline list of
   load-bearing conventions plus a pointer to `CLAUDE.md` for the rest.
   Push back if you'd rather have **only** the pointer (let the
   implementing LLM Read the full file on demand) or **only** the inline
   list (embedded, no read).

7. **Dispatch via Agent tool — does the canvas-author have it today?**
   The current `fukan-architect` agent definition restricts tools to
   `Bash(fukan eval *|fukan status|fukan primer)`. For Phase 7's
   handoff protocol, the agent needs the Agent tool grant (to dispatch
   the implementing LLM) plus possibly Read access (to read the rendered
   instruction back to itself). Sprint 4 Task N+1 expands the agent's
   tool grant. Confirm the tool grant scope.

8. **Verification protocol — global vs scoped drift.** The doc assumes
   `(canvas-drift :module-coord "distributed.cluster")` lands (Phase 6
   trial-run recommendation 5). If it doesn't, verification runs against
   global drift and filters client-side. Push back if you want the
   scope-filter blocked into Sprint 2 hardening as a prerequisite.

9. **Retry brief shape.** Recommend the retry brief carries the original
   `:rendered` + iteration-1 report + post-iteration-1 drift output +
   a short reconciliation paragraph. Push back if you want a different
   shape (e.g. summary-only without the original).

10. **Trial-run target — confirm or adjust.** Recommend the 3
    drift-close + 1 cold-write split described in the trial-run section.
    Push back if you want different specific findings (or more / fewer
    trials), or if you'd rather pick a different subsystem entirely.

11. **Layer B namespace path.** The Phase 7 plan suggests
    `src/fukan/canvas/instruct/`. The verb-keyword reads. Push back if
    you prefer a different name (`/scenario/` is a candidate; less
    action-y).

12. **What happens when cold-write's target file already exists?** Recommend
    the scenario detects this in `:build-context` (sets
    `:target-already-exists? true`) and adapts its discipline prose
    ("Append to the existing file rather than create it; preserve the
    existing ns form") — effectively becoming a multi-entity drift-close.
    Push back if you'd rather have cold-write refuse-with-message in that
    case (force the canvas-author to switch scenarios).
