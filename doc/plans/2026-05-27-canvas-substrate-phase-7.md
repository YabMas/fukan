# Canvas + Substrate Implementation Plan — Phase 7

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn drift findings into actionable implementation instructions. Phase 6 detects where canvas (design) and `src/` (code) diverge; Phase 7 generates the structured handoff that tells an implementing LLM exactly what code to write to close each gap. The product surface fukan has been pointing at since the original Phase 5 reframe: **canvas author writes design; fukan tells implementing LLM exactly what to write in `src/`.**

**Phase 7's strategic frame:** Phase 6's drift output is decision-ready about *what's missing*; Phase 7 makes it actionable about *what to write*. The split matters — Phase 6's bidirectional framing leaves the "which side moves" judgment to the canvas-author LLM. When that LLM decides "close in code," Phase 7 produces the precise instruction: target path, symbol name, expected signature, semantic intent, structural template. Then a separate **implementing LLM** (general-purpose, given the instruction) writes the actual code body. The architectural separation respects each LLM's strength — fukan's canvas reasoning stays in fukan; Clojure code synthesis delegates to a capable coding LLM.

**Two-layer architecture (the load-bearing distinction this plan respects):**

- **Layer A — Project-lens projection.** The generic Model element (from canvas) projects through a project-configured language lens to produce a deterministic low-level code specification: target path, namespace, symbol name, structural template, prose where structure leaves semantic intent. The Model substrate is language-agnostic; each project (fukan-on-fukan, or any external project using fukan) registers its own lens defining how `Type → schema`, `Function → fn-shape`, `Invariant → predicate`, etc. The fukan-on-fukan Clojure lens is the reference implementation; the registry mechanism is pluggable.
- **Layer B — Scenario-aware instruction.** Wraps a raw projection from Layer A with situation-specific framing: drift-close ("you're closing a gap; don't disturb neighbors"), cold-write ("you're writing from scratch; here are the conventions + matching neighbors"), refactor (Phase 8 candidate). Same projection, different prose envelope.

The original draft of this plan conflated the two layers into per-instruction-type generators. The amendment section at the bottom of this doc captures the reframe; the forward-reading sections below have been edited to reflect it.

**Architecture inheritance from Phase 6:**

- `canvas/inspect/drift.clj` — produces structured drift findings (446 today against fukan-itself)
- Each finding names canvas side (`:stable-id`) + code side (`:expected-code-path`, `:expected-symbol`, `:canvas-kind`)
- The bidirectional framing means findings are *not* prescriptive — they say "here's a gap"; Phase 7 says "here's how to close it in code if you choose to"
- The existing `target/clojure/analyzer.clj` + `address.clj` already do the canonical-address derivation Phase 7 needs (file path, symbol name, namespace) — Phase 7 consumes their output rather than re-deriving

**Tech stack:** Same. Clojure 1.11, Datascript. New: probably no new deps; the work is data-transformation (drift findings → instruction structures) plus a handoff protocol to an implementing LLM via the existing Agent tool dispatch.

**Naming note:** "implementation instructions" / "code instructions" / "implementation specs" — all candidate terms. The plan uses **implementation instructions** throughout. Sprint 1 may rename if a sharper term emerges.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-27-phase-6-verification.md` — Phase 6 outcomes; what Phase 7 builds on
- `doc/plans/2026-05-27-drift-trial-run-findings.md` — the trial run's evidence about what drift output feels like; informs what instruction output should feel like
- `doc/plans/2026-05-27-drift-signals-design.md` — drift signal categories + amendments
- `doc/canvas-authoring-system-prompt.md` — the LLM-facing activation Phase 7 extends with instruction-generation awareness
- `src/fukan/canvas/inspect/drift.clj` — the drift output Phase 7 consumes
- `src/fukan/target/clojure/{analyzer,address,projector}.clj` — the projection machinery; Phase 7 reads its canonical-address output

**Scope of this plan:** Phase 7 only.

**Subsequent phases (out of scope here):**

- Phase 8 — Closing-the-loop automation: dispatch the implementing LLM automatically from drift findings; verify drift cleared post-write; iterate. Phase 7 builds the human-in-the-loop version; Phase 8 builds the human-out-of-the-loop version.
- Continued lens additions (methodology coherence, coupling/dependency, FCIS, DDD) as drop-a-file enhancements
- The Phase 6 trial run's surfaced concerns — compound-shape comparator, daemon `--src` footgun, `(status)` artifact-count staleness — pulled into Phase 7 sprints where they're prerequisite

---

## What "code specification" and "implementation instruction" mean concretely

Per the two-layer architecture, these are two distinct things that Sprint 1 + 3 build:

### The code specification (Layer A output — project-lens projection)

A **deterministic low-level code spec** produced by projecting one generic Model element through the project's registered language lens. For fukan-on-fukan (Clojure lens), one projection per Model element kind:

| Model element kind | Clojure-lens projection produces |
|---|---|
| `Type` (record-shaped) | Malli `[:map [:field-name :field-type] ...]` schema; alias-normalized; target path + def name + namespace |
| `Type` (value/opaque) | `(def Name :tag/<name>)` or equivalent opaque marker; target path + def name |
| `Affordance` (function role) | `(defn name [args] body)`; arglist derived from canvas `takes`; return-type hint from `gives`; body is `(throw (ex-info "Not implemented" {…}))` stub; docstring from canvas |
| `Affordance` (getter role) | Zero-arg `(defn get-name [] (Optional<T>))`; structure baked in |
| `Affordance` (checker role) | `(defn check [model] [Violation...])`; structure baked in |
| `Affordance` (invariant role) | Predicate fn `(defn <holds-that> [model] …)`; body is prose-only (semantic intent from canvas docstring) |
| `Affordance` (rule role) | Predicate fn `(defn <kebab-rule-name> [model] …)`; symmetric with invariant |
| `Affordance` (event role) | Event-schema `(def Name [:map [:payload-field :type] ...])` |
| `Affordance` (handler role) | `(defn on-<event-name> [payload] …)`; emits-target list as prose context |

The spec is **structured data plus rendered template**. Where structure is determinate (signatures, schemas, named fns), the template renders unambiguous Clojure. Where structure leaves semantic intent (fn bodies, invariant property logic), the spec carries prose from the canvas declaration's docstring + structural surroundings.

A given Model element projects through the lens to the **same code spec** regardless of scenario — the spec is intrinsic to the Model element + the lens config, not to whether the canvas-author is closing a drift gap or writing from scratch.

### The implementation instruction (Layer B output — scenario-aware wrapper)

A code spec rendered through a scenario context, producing the **full prompt the implementing LLM consumes**. Same code spec, different prose envelope per scenario:

- **drift-close scenario.** The LLM is closing a known gap. Wraps the code spec with: "Canvas declared X at <path>. Code has no matching artifact. Here's the spec. Here's what's already in the file (neighbor fns + imports). Don't disturb unrelated content."
- **cold-write scenario.** The LLM is writing from scratch (no existing src/ for this canvas module). Wraps with: "Canvas declares this module + these elements. Here are the specs for each. Here are matching-pattern neighbors in other modules to reference for style. Here are the canvas vocabulary conventions you should know."
- **refactor scenario** (Phase 8 candidate). Code exists but its shape diverges. Wraps with the delta + neighbor-preservation guidance.

Each instruction is **structured data plus rendered prose** — the structured data carries the spec; the prose carries the scenario context. The implementing LLM consumes both.

The trust/weigh discipline carries forward: drift findings are facts (trust); code specs derived from Model elements through the lens are facts (trust); instructions wrapping specs with scenario context are facts (trust). The interpretive judgment is "should I close this gap?" — which Phase 6 already framed via bidirectional `:offenders` shape. Phase 7 doesn't add interpretation; it adds *actionability* on findings the canvas-author already chose to act on.

---

## File structure (Phase 7)

**Likely new namespaces** (per the two-layer architecture):

```
src/fukan/canvas/project/             ; LAYER A — project-lens projections (Model element → code spec)
  core.clj                            ; the lens contract (per-Model-element projection contract)
  registry.clj                        ; project-lens registry; fukan-on-fukan Clojure lens registers here
  render.clj                          ; structured projection → markdown spec rendering

  clojure/                            ; the Clojure lens (fukan-on-fukan's reference projection set)
    type_to_malli.clj                 ; Type (record-shaped) → Malli :map schema
    value_to_def.clj                  ; Type (value/opaque) → def
    function_to_defn.clj              ; Affordance (function/getter/checker) → defn
    invariant_to_predicate.clj        ; Affordance (invariant) → predicate fn
    rule_to_predicate.clj             ; Affordance (rule) → predicate fn
    event_to_schema.clj               ; Affordance (event) → schema def
    handler_to_defn.clj               ; Affordance (handler) → defn

src/fukan/canvas/instruct/            ; LAYER B — scenario-aware instructions (spec + situation context)
  core.clj                            ; the scenario contract
  registry.clj                        ; scenario registry
  render.clj                          ; structured instruction → markdown rendering for the implementing LLM

  drift_close.clj                     ; scenario: closing a drift gap
  cold_write.clj                      ; scenario: writing canvas content from scratch
  ; refactor.clj                      ; (deferred to Phase 8) scenario: code exists but shape diverges

src/fukan/canvas/architect/
  handoff.clj                         ; dispatch protocol for handing instructions to implementing LLM

doc/plans/
  2026-05-27-project-lens-design.md         ; Sprint 1 Task 1 output (Layer A design)
  2026-05-27-scenario-handoff-design.md     ; Sprint 1 Task 2 output (Layer B + handoff design)
  2026-05-27-phase-7-verification.md        ; Sprint 5 output
```

**Likely modified files:**

```
src/fukan/canvas/inspect/drift.clj            ; possible :context enrichment for instruction generation
src/fukan/canvas/lens/                        ; possible new "shape-drift compound" handling carried in
src/fukan/agent/api.clj                       ; expose instruction generation under :trust or new :act tier
.claude/agents/fukan-architect.md             ; add instruction-generation mode
doc/canvas-authoring-system-prompt.md         ; add Phase D — Instruct + dispatch
AGENTS.md                                     ; add instruction surface
```

**Files NOT touched:**

- Canvas substrate primitives — settled in Phase 4
- The vocab libraries — Phase 7 may notice lift candidates from instruction patterns but doesn't ship vocab unless rule-of-three within authoring evidence
- Phase 5 + 6 helpers (integrity, coverage, drift, the three lenses) — they're inputs to Phase 7, not modified
- `.legacy-allium/`

---

# Sprint 1 — Project-lens + scenario-handoff design (Tasks 1–2)

Two design docs before any code. Mirrors Phase 5 + 6 Sprint 1 shape. Task 1 designs Layer A (the project-lens); Task 2 designs Layer B + the handoff protocol.

---

## Phase 7, Task 1: Project-lens design (Layer A)

**Files:**
- Create: `doc/plans/2026-05-27-project-lens-design.md`

### What the design doc must cover

For each Model element kind that needs a Clojure projection (function/getter/checker/invariant/rule/event/handler — affordances; record + value — types):

1. **What the projection produces.** Concrete sample of the structured code-spec map for one Model element. What fields are always present; what's optional.
2. **Inputs.** What canvas-db queries the projection needs (the originating declaration's name, role, docstring, takes/gives/holds-that/etc.); what address-mapping output it reuses from `target/clojure/address.clj`.
3. **Structural template.** Where the structure is determinate, what does the rendered Clojure look like? E.g. for `record → Malli :map`, show the literal `(def Name [:map [...] ...])` rendering. For `invariant → predicate`, the bare `(defn name [model] ...)` with body left as prose.
4. **Prose envelope.** Where structure leaves semantic intent (fn bodies, invariant property logic), what prose travels with the spec? Pulled from canvas docstring + structural surroundings; should be self-contained.
5. **Alias normalization.** Canvas type-name keywords (`:Integer`, `:String`, …) translate to Malli keywords (`:int`, `:string`, …) via the alias table from Phase 6 Sprint 3. This task documents the canonical mapping; extends the table if Phase 7 needs more entries.

### Critical design questions to settle

- **Lens-registry shape.** How does fukan-on-fukan's Clojure lens register its per-Model-element projections? How would an external project (TypeScript, Python, …) register an alternative lens? **Default proposal: a multimethod keyed on `[lens-id Model-element-kind]` plus a per-project registry entry. The Clojure lens registers in `src/fukan/canvas/project/clojure/registry.clj`; an external project would ship its own analogue.**
- **Per-projection contract.** What's the consistent map shape every projection returns? E.g.:
  ```clojure
  {:projection-kind :clojure/type-to-malli
   :model-element-id "<canvas stable-id>"
   :target {:path "src/fukan/<...>.clj" :namespace "fukan.<...>" :symbol "<name>"}
   :template "<rendered Clojure string or nil>"
   :prose "<semantic intent>"
   :context {:related-elements [...] :canvas-source-ref "..."}}
  ```
- **Trust tier?** Code specs derived from generic Model elements through a deterministic lens are facts (trust). Confirm `:severity :info` (informational, never "error").
- **Address-mapping reuse.** `target/clojure/address.clj` already maps canvas module + entity name → file path + symbol. The Clojure projections should call its existing fns rather than re-deriving. Document the reuse path.

### Which projections ship in Phase 7

Default: ship 6 of the 9 Clojure-lens projections in Phase 7. Sprint 1 picks; suggested:
- Ship: `type → malli`, `value → def`, `function → defn`, `invariant → predicate`, `event → schema`, `record → schema` (alias for type→malli when shape is record)
- Defer: `getter → defn` (special-case of function; ship if trivial), `handler → defn` (semantics overlap function), `rule → predicate` (symmetric with invariant)

### Steps

- [ ] **Step 1: For each candidate projection, sketch the structured + rendered shape** against a real canvas declaration in fukan-itself. Use the matching `src/` file as the target the projection points at.
- [ ] **Step 2: Settle the lens-registry shape.** Confirm multimethod-keyed-on-[lens-id, element-kind], or propose alternative.
- [ ] **Step 3: Settle the per-projection contract.** Lock the map shape.
- [ ] **Step 4: Pick 5-7 projections to ship in Phase 7.** Defer the rest.
- [ ] **Step 5: Pause for user review.** Load-bearing design conversation.
- [ ] **Step 6: Commit.**

```bash
jj desc -m "doc(canvas): Phase 7 project-lens design (Layer A)"
jj new
```

---

## Phase 7, Task 2: Scenario + handoff design (Layer B)

**Files:**
- Create: `doc/plans/2026-05-27-scenario-handoff-design.md`

### What the design doc must cover

Layer B wraps Layer A's code specs with scenario context, then defines the handoff to the implementing LLM. Three sub-designs:

1. **Scenario contract.** What's the consistent shape every scenario produces? Suggested:
   ```clojure
   {:scenario-id :drift-close
    :code-spec <Layer-A projection map>
    :scenario-context {:what-exists-in-target-file "..." :neighbor-patterns [...] ...}
    :rendered "<full markdown the implementing LLM reads>"}
   ```
2. **Which scenarios ship.** Default: **drift-close + cold-write** in Phase 7. Refactor (shape-drift handling) defers to Phase 8 because it depends on compound-shape comparator + delta logic.
3. **Handoff protocol.**
   - **Dispatch shape.** Default: Agent-tool dispatch from `fukan-architect` to a general-purpose implementing LLM subagent. Keeps the loop closed; implementing LLM is a child subagent.
   - **Implementing-LLM brief.** What goes in the dispatched prompt: the scenario's rendered output (the full instruction) + targeted context (neighbor fns from the target file, related canvas declarations, project conventions). **Don't dump the canvas db.**
   - **Verification protocol.** After the implementing LLM commits, re-run drift; confirm the finding cleared. If not closed, dispatch a second iteration with the new drift output as feedback. **Default: max 2 iterations per instruction.**
   - **Multi-instruction batching.** Default: one instruction per dispatch in Phase 7. Phase 8 candidate.

### Per-scenario design

**drift-close scenario.** Input: one drift finding + the Layer-A spec derived from it. Output: instruction prompt framed as "you are closing a known gap." Includes:
- The code spec (template + prose envelope)
- Neighbor context (what's already in the target file; imports; sibling defs)
- The drift finding itself (so the LLM understands why this is being asked)
- Discipline: don't disturb unrelated content; preserve existing imports/aliases

**cold-write scenario.** Input: a canvas module declaration (or a chosen subset of its entities) + the Layer-A specs. Output: instruction prompt framed as "you are writing this canvas module's implementation from scratch." Includes:
- The Layer-A specs for each entity
- Project conventions (from CLAUDE.md + canvas vocab docs)
- Matching-pattern neighbors from elsewhere in `src/` (e.g. "look at `src/fukan/infra/server.clj` for how a module of this shape is typically organized")
- Discipline: follow the project conventions even where the spec is silent

### Steps

- [ ] **Step 1: Lock the scenario contract.** Map shape.
- [ ] **Step 2: Specify drift-close + cold-write scenarios** concretely (sample instructions for each, derived from real fukan-on-fukan situations).
- [ ] **Step 3: Specify the handoff protocol** (dispatch, brief shape, verification, retry).
- [ ] **Step 4: Pick the trial-run target.** Default: `canvas/distributed/*` + partial `src/fukan/distributed/*` from Phase 6. Many drift findings remain there from Phase 6's deliberate omissions — ready material for the closing loop.
- [ ] **Step 5: Pause for user review.**
- [ ] **Step 6: Commit.**

```bash
jj desc -m "doc(canvas): Phase 7 scenario + handoff design (Layer B)"
jj new
```

---

# Sprint 2 — Pre-implementation hardening (Tasks 3–4)

Two items needed before Sprint 3's instruction-generation work.

---

## Phase 7, Task 3: Compound-shape comparator for shape-drift

**Files:**
- Modify: `src/fukan/canvas/inspect/drift.clj` — extend `check-shape-drift`'s comparator
- Test: `test/fukan/canvas/inspect/drift_test.clj` — add compound-shape cases

The Phase 6 Sprint 4 trial-run surfaced this: today's shape-drift comparator flattens compound shapes to head-only (`set-of :NodeId` → `:NodeId`; `[:set :NodeId]` → `:set`). 4 of 5 shape-drift findings in the Phase 6 trial were this artifact.

Phase 7 needs accurate shape-drift output BEFORE instruction generation runs — otherwise instructions for `update-record-fields` will be noisy.

Implementation: extend the comparator to recurse into compound shapes (`set-of`, `list-of`, `map-of`, `optional`, `sum-of`, `tuple-of`, `record-of`) and compare structurally. Both canvas and code-side shapes flatten the same way before comparison; the alias table normalizes leaves.

- [ ] **Step 1: TDD against synthetic compound cases.** Canvas `set-of :NodeId` vs code `[:set :NodeId]` → no finding (equivalent shapes). Canvas `set-of :NodeId` vs code `[:vector :NodeId]` → finding with `:type-mismatch {:nodes {:canvas :set :code :vector}}`.
- [ ] **Step 2: Implement** the recursive comparator + a normalizer that maps Malli compound shapes (`[:set …]` etc.) to canvas-compound-vocab equivalents.
- [ ] **Step 3: Re-run shape-drift against fukan-itself.** The 4 noisy cytoscape findings should collapse to whichever ones are genuinely real after compound normalization.
- [ ] **Step 4: Commit.**

```bash
jj desc -m "fix(canvas/inspect/drift): compound-shape comparator handles set-of/list-of/map-of recursively"
jj new
```

---

## Phase 7, Task 4: Other prereqs (surfaced in Sprint 1)

**Files:** TBD per Sprint 1 findings.

Sprint 1's design conversations may surface additional small prerequisites — drift output enrichment for instruction context, helper extraction, schema tweaks. Keep small and additive. Likely surface items:

- `:context` enrichment on drift findings (related-fns, related-types, originating canvas docstring) so instruction generators don't re-query
- Address-derivation gaps (any new ones surfaced by trying to generate instructions)
- Substrate concerns from Phase 6 verification (rule+invariant collision in primitives) if they bite instruction generation

- [ ] **Step 1: Implement Sprint-1-named surface items.**
- [ ] **Step 2: Tests + per-commit hygiene.**

---

# Sprint 3 — Build the two layers (Tasks 5–N)

The substantive sprint. Two substrates + their occupants. Mirrors Phase 5's lens-substrate sprint shape but builds **two** substrates (project-lens + scenario) since Phase 7's architecture is two-layered.

Sprint 3 structure:

- **Task 5 — Layer A substrate**: `src/fukan/canvas/project/{core,registry,render}.clj`. ~3 small namespaces.
- **Tasks 6 to M — Clojure-lens projections**: one task per Model-element projection Sprint 1 Task 1 selected. Each is a `(def projection …)` in `src/fukan/canvas/project/clojure/<name>.clj`. ~5-7 tasks.
- **Task M+1 — Layer B substrate**: `src/fukan/canvas/instruct/{core,registry,render}.clj`. ~3 small namespaces.
- **Tasks M+2 to N — Scenario wrappers**: one task per scenario Sprint 1 Task 2 selected. Each is a `(def scenario …)` in `src/fukan/canvas/instruct/<name>.clj`. Default 2 (drift-close, cold-write).

Each task lands as: one namespace + tests + registry registration + an agent api `(help)` surface entry under `:trust` (with `:severity :info`).

---

## Phase 7, Task 5: Layer A substrate (project-lens core + registry + render)

**Files:**
- Create: `src/fukan/canvas/project/core.clj` — lens contract; `valid-projection?`, `validate-projection`
- Create: `src/fukan/canvas/project/registry.clj` — per-`[lens-id, model-element-kind]` projection registration; `all-projections`, `projection-for`
- Create: `src/fukan/canvas/project/render.clj` — structured projection → markdown spec rendering
- Tests for each

Mirrors the Phase 5 lens substrate pattern: small (~30-50 LOC per namespace), explicit, mechanical.

**Projection contract** (per Sprint 1 Task 1's settled shape; placeholder here):

```clojure
{:projection-kind  :clojure/type-to-malli         ; required; namespaced keyword
 :lens-id          :clojure                       ; required; which lens produced this
 :model-element-kind :Type                        ; required
 :model-element-id "<canvas stable-id>"           ; required
 :target           {:path "src/fukan/<...>.clj"
                    :namespace "fukan.<...>"
                    :symbol "<name>"}
 :template         "<rendered Clojure string or nil>"
 :prose            "<semantic intent>"
 :context          {:related-elements [<stable-id> ...]
                    :canvas-source-ref "<canvas/.../file.clj:line>"}}
```

**Registry shape**: multimethod-keyed on `[lens-id model-element-kind]` (or whatever Sprint 1 Task 1 settled). Auto-discovery from `fukan.canvas.project.<lens>.*` namespaces; explicit `require` + `defmethod` registration per projection.

- [ ] **Step 1: Lens contract.** Spec + validators.
- [ ] **Step 2: Registry.** Multimethod + per-lens namespace discovery.
- [ ] **Step 3: Render.** Structured → markdown.
- [ ] **Step 4: Tests for each.**
- [ ] **Step 5: Commit.**

```bash
jj desc -m "feat(canvas/project): project-lens substrate (Layer A core + registry + render)"
jj new
```

---

## Phase 7, Tasks 6 to M: Clojure-lens projections

One task per Sprint-1-selected projection. Default 5-7 tasks; suggested order (cheapest to hardest):

- Task 6: `value-to-def` (canvas `value` → `(def Name :tag/<name>)` or equivalent opaque marker)
- Task 7: `type-to-malli` (canvas record-shaped Type → Malli `[:map ...]` schema; uses the alias table from Phase 6)
- Task 8: `event-to-schema` (canvas event → schema def; mirrors type-to-malli for payload)
- Task 9: `function-to-defn` (canvas function → defn; arglist from takes, return-hint from gives, body as exception stub)
- Task 10: `invariant-to-predicate` (canvas invariant → predicate fn; semantic intent in prose is the only non-trivial bit)
- Task 11: `rule-to-predicate` (symmetric with invariant; defer if rule-of-three pressure)
- Task 12: `handler-to-defn` / `getter-to-defn` (special cases of function; defer if scope pressure)

Each task:

- Implements `(def projection …)` plus the `defmethod` registration in `src/fukan/canvas/project/clojure/<name>.clj`
- Tests cover: positive case (model element → expected projection), edge cases (missing context, unusual shapes), rendered-template snapshot
- Runs against real canvas declarations from fukan-itself; documents a sample projection
- One commit per projection (per-commit hygiene)

---

## Phase 7, Task M+1: Layer B substrate (scenario core + registry + render)

**Files:**
- Create: `src/fukan/canvas/instruct/core.clj` — scenario contract; `valid-scenario?`, `validate-scenario`
- Create: `src/fukan/canvas/instruct/registry.clj` — scenario registration; `all-scenarios`, `scenario-by-id`
- Create: `src/fukan/canvas/instruct/render.clj` — structured instruction → markdown rendering for the implementing LLM
- Tests for each

Mirrors the Layer A substrate, just shorter — scenarios are fewer than projections in Phase 7.

**Scenario contract:**

```clojure
{:scenario-id      :drift-close              ; required
 :description      "..."                     ; required; one-line summary
 :prompt-fragment  "..."                     ; required; situation-framing prose for the implementing LLM
 :build-context    (fn [code-spec opts] ...) ; required; takes Layer-A projection + opts → scenario-context map
 :render           (fn [code-spec scenario-context opts] ...)} ; required; produces the full rendered instruction
```

The instruction this layer produces (registered through the registry):

```clojure
{:scenario-id     :drift-close
 :code-spec       <Layer-A projection map>
 :scenario-context {:what-exists-in-target-file "..." :neighbor-patterns [...] ...}
 :rendered        "<full markdown the implementing LLM reads>"}
```

- [ ] **Step 1: Scenario contract.** Validators.
- [ ] **Step 2: Registry.**
- [ ] **Step 3: Render.**
- [ ] **Step 4: Tests.**
- [ ] **Step 5: Commit.**

```bash
jj desc -m "feat(canvas/instruct): scenario substrate (Layer B core + registry + render)"
jj new
```

---

## Phase 7, Tasks M+2 and M+3: drift-close + cold-write scenarios

Two tasks. Each ships one scenario:

- Task M+2: `drift-close` — input is a drift finding + the projection derived from it; output is the full instruction prompt framed as "closing a known gap"
- Task M+3: `cold-write` — input is a canvas module (or subset of entities) + their projections; output is the full instruction prompt framed as "writing this module's implementation from scratch"

Each task:

- Implements `(def scenario …)` in `src/fukan/canvas/instruct/<name>.clj`
- Tests cover: input → expected rendered output; scenario-context derivation correctness; integration with at least one Layer-A projection
- Runs against a real fukan-on-fukan situation (e.g. drift-close against one of the 443 missing-implementation findings; cold-write against `canvas/distributed/*`)
- One commit per scenario

---

## Phase 7, Task N: Agent api integration

**Files:**
- Modify: `src/fukan/agent/api.clj` — expose `(spec model-element-id)` (Layer A) and `(instruct drift-finding scenario)` (Layer B) under `:trust` with `:severity :info`
- Tests for the agent surface

The agent api gains two new fns:

- `(spec model-element-id)` — invokes the Clojure lens projection for the named Model element; returns the structured code spec
- `(instruct drift-finding scenario-id)` — invokes the scenario wrapper; returns the full instruction (structured + rendered)

Both fns appear in `(help)` under `:trust`. Marked `^:export`.

- [ ] **Step 1: Implement the wrappers.**
- [ ] **Step 2: Help-surface tests.**
- [ ] **Step 3: Commit.**

```bash
jj desc -m "feat(agent/api): expose project-lens specs + scenario instructions under :trust"
jj new
```

---

# Sprint 4 — Agent integration + trial run (Tasks N+1 to N+2)

Same shape as prior phase Sprint 4s.

---

## Phase 7, Task N+1: Extend system prompt + `fukan-architect` for instruction generation + handoff

**Files:**
- Update: `doc/canvas-authoring-system-prompt.md` — add Phase D (Instruct) to the authoring loop; add discipline for instruction review + dispatch
- Update: `.claude/agents/fukan-architect.md` — instruction-generation mode + handoff dispatch
- Update: `AGENTS.md` — add instruction surface to the trust-tier primer

**Phase D — Instruct + Dispatch** (new authoring-loop phase added after Phase C Reflect):

1. Read drift findings (Phase C output).
2. For each finding the LLM decides to close in code, invoke `(generate-instruction finding)`.
3. Review the instruction's structured + rendered output. Catch obvious issues (wrong target path, missing context, oddly-shaped signature).
4. Dispatch the implementing LLM with the rendered instruction + targeted context.
5. After the implementing LLM commits, re-run drift. Confirm closure.

The system prompt's failure-mode list grows: e.g. "treating instructions as gospel" (the canvas-author should still review; the generator is mechanical, not omniscient).

- [ ] **Step 1: Draft the Phase D additions.**
- [ ] **Step 2: Update the agent definition.**
- [ ] **Step 3: Update AGENTS.md.**
- [ ] **Step 4: Commit per artifact.**

---

## Phase 7, Task N+2: Trial run — close some `canvas/distributed/*` drift gaps via the loop

**Files:**
- Create: real implementations at `src/fukan/distributed/*` matching the canvas declarations
- Create: `doc/plans/2026-05-27-instruction-trial-run-findings.md`

The trial extends Phase 6's trial-run subsystem. Phase 6's trial left ~30 drift findings in `distributed.*` (deliberate omissions). Phase 7's trial:

1. Pick a handful of those findings (e.g. the 3 omitted cluster invariants + the 3 omitted election handlers).
2. For each, the canvas-author LLM (running as `fukan-architect`) generates the instruction.
3. The canvas-author dispatches the implementing LLM (general-purpose subagent) with the instruction.
4. The implementing LLM writes the code, runs `clj -M:test`, commits.
5. Drift re-run. Confirm closure.
6. Document the loop's feel — instructions clear or confusing? implementing LLM had what it needed? verification protocol worked?

- [ ] **Step 1: Dispatch the trial.**
- [ ] **Step 2: Observe the close-the-loop dynamic.**
- [ ] **Step 3: Document findings.**
- [ ] **Step 4: Commit.**

---

# Sprint 5 — Verification (Task Final)

## Phase 7, Task Final: Phase 7 verification report

**Files:**
- Create: `doc/plans/2026-05-27-phase-7-verification.md`

Standard verification template, mirroring Phase 5 + 6.

- [ ] **Section 1: What was attempted vs. built.**
- [ ] **Section 2: Did the instruction substrate work?** Substrate stays small? Generators drop-in?
- [ ] **Section 3: Did the instructions produce useful output?** Per generator: representative samples; signal-to-noise.
- [ ] **Section 4: Did the handoff loop succeed?** The trial run's evidence. Did the implementing LLM close gaps with the instructions alone? Did the verification protocol confirm closure?
- [ ] **Section 5: What did the trial reveal about gaps?** Phase 8+ candidates.
- [ ] **Section 6: Decision.** Three outcomes:
  1. Loop works → Phase 8 (automation) can begin.
  2. Works with caveats → Phase 7.5.
  3. Loop didn't close gaps reliably → reset; rethink approach.
- [ ] **Section 7: Phase 8+ implications.**
- [ ] **Section 8: Carried-forward concerns.**

```bash
jj desc -m "doc(canvas): phase-7 verification + phase-8 brief"
jj new
```

---

## Subsequent phases (sketches; each gets its own plan after Phase 7)

**Phase 8 — Closing-the-loop automation.** Phase 7 keeps the canvas-author in the loop reviewing each instruction before dispatch. Phase 8 closes that loop: drift findings auto-trigger instruction generation; instructions auto-dispatch to the implementing LLM; verification auto-confirms closure; canvas-author observes the result rather than driving each step. Multi-instruction batching ships here.

**Phase 8+** — symmetric canvas-side instructions (when the LLM decides the canvas should move, not the code); cross-module change instructions; refactoring instructions (for shape-drift); test-scaffolding generation; survey scoping + coupling lens (carried from Phase 5/6).

---

## Self-review notes

- **Sprint 1's design docs are the load-bearing artifacts of Phase 7.** Both have pause points before downstream sprints begin.
- **The instruction substrate must stay small.** core + registry + render = three short namespaces. If the substrate grows in Sprint 3, surface the reason — likely a substrate design smell.
- **Don't conflate instruction generation with code generation.** Phase 7 produces structured handoffs the implementing LLM uses. Phase 7 does not write Clojure function bodies; the implementing LLM does.
- **Resist scope creep on stub templates.** "Richer stubs would be more useful" pulls toward fukan-as-code-generator. Phase 7's bet is that *clear instructions + a capable implementing LLM* beats *rich templates + a constrained dispatcher.* Watch for evidence either way during the trial run.
- **Trust/weigh discipline carries forward.** Instructions derived from facts are facts; severity `:info` reflects "no error; here's how to close the gap if you choose to."
- **Phase 7 is about CLOSING DESIGN-CODE LOOPS.** Hard to verify without a real authoring exercise (Sprint 4 trial run). Plan for subjective evidence.

---

## Open questions for the user (before Sprint 1 begins)

**Layer A (project-lens) questions:**

1. **Lens-registry shape.** Default: multimethod keyed on `[lens-id model-element-kind]`, plus a per-project registry of `defmethod` entries. The Clojure lens registers in `src/fukan/canvas/project/clojure/registry.clj`; external projects register their own. Push back if a different mechanism reads better.
2. **Clojure-lens projection priority.** Of the 9 candidate Model-element projections, which 5-7 ship in Phase 7? Default: `value-to-def`, `type-to-malli`, `event-to-schema`, `function-to-defn`, `invariant-to-predicate`, `rule-to-predicate`. (Defer: `handler-to-defn`, `getter-to-defn` as special-cases of function; `update-record-fields` shape-drift handling waits on Sprint 2's compound-shape comparator.)
3. **Tier verdict.** Recommended default: trust-tier with `:severity :info` (informational; the LLM acts or doesn't act). Push back if a new tier (e.g. `:act`) reads more naturally.
4. **Stub-template ambition.** Default: bare signature + exception body + prose envelope. Richer templates risk being wrong; bare templates leave more work for the implementing LLM but stay safe. Preference?

**Layer B (scenario + handoff) questions:**

5. **Scenario priority.** Default: ship `drift-close` + `cold-write` in Phase 7. Defer `refactor` (shape-drift handling) to Phase 8 because it needs Sprint 2's compound-shape comparator AND new delta-instruction shape. Push back if you want a different starter set.
6. **Symmetric (canvas-side) scenarios.** When drift's bidirectional framing says "canvas may be the side to move," does Phase 7 generate canvas-edit instructions too? Default: defer to Phase 8. Push back if you want canvas-edit scenarios in Phase 7.
7. **Dispatch shape.** Default: Agent-tool dispatch from `fukan-architect` to a general-purpose implementing LLM. Push back if you want a different shape (e.g. external workflow, CI agent).
8. **Multi-instruction batching.** Default: one instruction per dispatch in Phase 7. Phase 8 candidate. Push back if you want batching in Phase 7.

**Trial-run question:**

9. **Trial-run target.** Default: continue `canvas/distributed/*` work — Phase 6's deliberate omissions are ready material for closing-loop trial. Push back if you want a different target.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–2 | Project-lens design (Layer A) + scenario-handoff design (Layer B) — two pause points |
| 2 | 3–4 | Compound-shape comparator + Sprint-1-surfaced prereqs |
| 3 | 5–N | Layer A substrate + 5-7 Clojure-lens projections + Layer B substrate + 2 scenarios + agent api integration |
| 4 | N+1 to N+2 | Architect agent extension + close-the-loop trial run |
| 5 | Final | Phase 7 verification + Phase 8 brief |

**Estimated calendar:** Sprint 1 ≈ 2 sessions (design + 2 pauses). Sprint 2 ≈ 1-2 sessions (compound-shape comparator is the meaningful piece). Sprint 3 ≈ 5-7 sessions (two substrates + 5-7 projections + 2 scenarios + agent api). Sprint 4 ≈ 2 sessions (integration + trial). Sprint 5 ≈ 1 session. **Total: 11-14 working sessions.**

---

## Amendment — 2026-05-27 two-layer architecture clarification

After review, the user surfaced a load-bearing distinction the original draft was conflating: **code specification is fundamentally projection of the generic Model through a project-configured language lens; the instruction layer wraps the resulting raw spec with scenario context.**

The original draft's per-instruction-type generators mixed two responsibilities into one fn. The amendment splits them.

### Two layers

**Layer A — Project-lens projection** (the generic-Model → project-specific-code-spec layer)

The Model substrate is language-agnostic (canvas vocab carries no Clojure or Malli assumptions). Each project that uses fukan registers a **lens configuration** defining how its generic Model elements project into code for that project's language + conventions. For fukan-on-fukan (Clojure):

| Model element | Projects to |
|---|---|
| `Type` (record-shaped) | Malli `[:map [:field-name :field-type] ...]` schema |
| `Type` (value/opaque) | `def Name :tag/name-or-equivalent` |
| `Affordance` (function role) | `(defn name [args] body)` |
| `Affordance` (getter role) | `(defn get-name [] (Optional<T>))` |
| `Affordance` (checker role) | `(defn check [model] [Violation...])` |
| `Affordance` (invariant role) | predicate fn named by `holds-that` clause |
| `Affordance` (rule role) | predicate fn named by kebab(rule-name) |
| `Affordance` (event role) | event-schema def |
| `Affordance` (handler role) | `(defn on-event-name [payload] ...)` |

Projection of one Model element through the lens produces a deterministic **low-level code specification**: target path, namespace, symbol name, structural template where the structure is determinate, prose where structure leaves semantic intent (fn bodies, invariant property logic). The totality is "pretty nailed down" — the implementing LLM has unambiguous structure plus targeted prose.

**This layer is reusable across scenarios.** Whether the canvas-author is closing a drift gap or writing from scratch, the projection of `canvas function get_self_role in distributed.cluster` is the same: same path (`src/fukan/distributed/cluster.clj`), same `(defn get-self-role [] …)` shape, same docstring source, same arglist derived from the canvas `takes`/`gives`.

**The lens is pluggable.** External projects using fukan register their own language lens. The fukan-on-fukan Clojure lens is the reference implementation; the registry mechanism (in `src/fukan/project_layer/`) already has `:root-prefix` as a seed of this pluggability.

**Layer B — Scenario-aware instruction** (the project-spec + situation-context layer)

Takes a raw projection from Layer A and wraps it with situation-specific framing. Same projection, different prose envelope, different "what to pay attention to" guidance:

- **drift-close scenario**: "You're closing a gap. Here's the spec; here's what's already in the file; canvas declared this but code is missing. Don't disturb neighbors."
- **cold-write scenario**: "You're writing from scratch. Here's the spec; here are the canvas conventions; here are matching-pattern neighbors elsewhere you should reference for style."
- **refactor scenario** (Phase 8 candidate): "Code exists but its shape diverges from canvas. Here's the delta; preserve unrelated behavior."

The scenario IS the context that turns a raw spec into useful framing for the implementing LLM.

### Where the existing machinery fits

`src/fukan/target/clojure/{address,projector,blueprint}.clj` already does **half of Layer A** — specifically the canonical-address mapping (Model entity → file path + symbol) and the source-side direction (code → Model artifacts for the analyzer). What's new for Phase 7 is the **reverse direction of Layer A**: generate the structural code spec FROM a Model element, given the lens config. Phase 7 reuses the address mapping; the structural-template generation is the new work.

`src/fukan/project_layer/defaults.clj` already has the `fukan-on-fukan` registry hook. Layer A's pluggability extends this — the project-lens registry adds the per-Model-element projection fns alongside the existing root-prefix.

### Amended file structure

```
src/fukan/canvas/project/             ; LAYER A — project-lens projections (pluggable per project)
  core.clj                            ; the lens contract (per-Model-element projection contract)
  registry.clj                        ; project-lens registry; the fukan-on-fukan Clojure lens registers here
  render.clj                          ; structured projection → markdown spec rendering

  clojure/                            ; the Clojure lens (fukan-on-fukan's reference projection set)
    type_to_malli.clj                 ; Type (record-shaped) → Malli :map schema
    value_to_def.clj                  ; Type (value/opaque) → def
    function_to_defn.clj              ; Affordance (function/getter/checker) → defn
    invariant_to_predicate.clj        ; Affordance (invariant) → predicate fn
    rule_to_predicate.clj             ; Affordance (rule) → predicate fn
    event_to_schema.clj               ; Affordance (event) → schema def
    handler_to_defn.clj               ; Affordance (handler) → defn

src/fukan/canvas/instruct/            ; LAYER B — scenario-aware instructions (situation context over projections)
  core.clj                            ; the scenario contract
  registry.clj                        ; scenario registry
  render.clj                          ; structured instruction → markdown rendering for the implementing LLM

  drift_close.clj                     ; scenario: closing a drift gap
  cold_write.clj                      ; scenario: writing canvas content from scratch
  ; refactor.clj                      ; (deferred to Phase 8) scenario: code exists but shape diverges
```

### Amended Sprint 1 task scopes

The two design docs reshape:

- **Task 1: Project-lens design.** What's the lens contract (per-Model-element projection contract)? What does the fukan-on-fukan Clojure lens register for each Model element type? How is the lens pluggable from outside fukan (so a TypeScript project could register its own)? What's the address-mapping reuse path through the existing `target/clojure/` machinery?
- **Task 2: Scenario + handoff design.** What scenarios ship first (drift-close, cold-write)? How does scenario context wrap the projection? What's the handoff protocol from canvas-author LLM to implementing LLM (the original Task 2's content folds in here)?

### Amended Sprint 3 task shape

Sprint 3 builds **two substrates and their occupants**:

- **Project-lens substrate (Layer A core)**: contract + registry + render. Same shape as Phase 5's lens substrate (small, mechanical).
- **Per-Model-element projections (Clojure lens)**: one task per element type. Type→Malli; Function→defn; Invariant→predicate; Event→schema; etc.
- **Scenario substrate (Layer B core)**: contract + registry + render.
- **Per-scenario wrappers**: one task per scenario. drift-close + cold-write for Phase 7.

Sprint 1 picks which Model elements + scenarios ship in Phase 7 vs defer.

### Updated open questions

Existing open questions in the plan stay relevant; add:

- **Project-lens pluggability shape.** How does an external project register its language lens? Sprint 1 Task 1 settles. Default: a `defmethod`-style multimethod keyed on `[language Model-element-kind]`, plus a registry entry that the project ships.
- **Scenario priority.** Of the three named scenarios (drift-close, cold-write, refactor), which ship first? Default: drift-close + cold-write in Phase 7; refactor deferred to Phase 8.
- **Layer naming.** "Project-lens" vs "language-lens" vs "code-spec lens" — sharper terminology may emerge during Sprint 1.

### Why this layering matters

The original draft's "per-instruction-type generator" risked locking Clojure-specific assumptions into the instruction layer. The amended two-layer design keeps:

- The Model generic (canvas substrate stays language-agnostic)
- The project-lens pluggable (fukan usable beyond fukan-itself)
- The scenarios composable (any projection × any scenario = a coherent instruction)

The bet stays the same — clear instructions + capable implementing LLM > rich templates + constrained dispatcher — but the architecture now expresses where each piece of clarity comes from.
