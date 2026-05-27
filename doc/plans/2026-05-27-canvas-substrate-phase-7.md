# Canvas + Substrate Implementation Plan — Phase 7

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn drift findings into actionable implementation instructions. Phase 6 detects where canvas (design) and `src/` (code) diverge; Phase 7 generates the structured handoff that tells an implementing LLM exactly what code to write to close each gap. The product surface fukan has been pointing at since the original Phase 5 reframe: **canvas author writes design; fukan tells implementing LLM exactly what to write in `src/`.**

**Phase 7's strategic frame:** Phase 6's drift output is decision-ready about *what's missing*; Phase 7 makes it actionable about *what to write*. The split matters — Phase 6's bidirectional framing leaves the "which side moves" judgment to the canvas-author LLM. When that LLM decides "close in code," Phase 7 produces the precise instruction: target path, symbol name, expected signature, semantic intent, structural template. Then a separate **implementing LLM** (general-purpose, given the instruction) writes the actual code body. The architectural separation respects each LLM's strength — fukan's canvas reasoning stays in fukan; Clojure code synthesis delegates to a capable coding LLM.

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

## What "implementation instruction" means concretely

A structured, LLM-consumable description of *what code to write at what location*. Sprint 1 Task 1 settles which instruction types ship vs which defer. Candidates derived from Phase 6's drift output shape:

**From `missing-implementation` findings (the umbrella):**

1. **Add function.** Canvas declared a function with `takes`/`gives`/`triggers`/`emits`/`effects`; code has no corresponding fn. Instruction: target path, fn name, arglist, return-type hint, semantic intent (rendered from canvas declaration's docstring + structural surroundings), stub-template.
2. **Add record (Malli schema).** Canvas declared a record with named fields + types; code has no corresponding `def`. Instruction: target path, schema name, `[:map ...]` body derived from canvas fields, alias-normalized.
3. **Add value (opaque type).** Canvas declared a value with no fields. Instruction: target path, def name, opaque-marker comment, possibly a `(def Foo :tag/foo)` or schema-shaped fallback.
4. **Add event.** Canvas declared an event with payload shape. Instruction: target path, event-schema def, payload `[:map ...]`.
5. **Add invariant predicate.** Canvas declared `(invariant X (holds-that "Y"))`; code has no fn `Y`. Instruction: target path, predicate fn name `Y` (from `holds-that`), `[model]` arglist, return-type `Boolean`, semantic intent rendered from invariant docstring + property name.
6. **Add rule predicate.** Canvas declared `(rule X (when X (params)))`; code has no fn `kebab(X)`. Symmetric with invariants.
7. **Add getter.** Canvas declared `(getter X :T)`; code has no fn `kebab(X)`. Instruction: zero-arg fn returning Optional<T>.
8. **Add handler.** Canvas declared `(handler X (on Event) (emits …))`; code has no fn `kebab(X)`. Instruction: handler signature, event-payload arg, emits-target list.

**From `shape-drift-on-records` findings:**

9. **Update record fields.** Canvas record's field set differs from code's `[:map …]`. Instruction: target path, schema name, structured delta — fields to add (with types), fields to remove, fields whose type to change.
10. **Update record fields (canvas-side).** Symmetric direction: when the LLM decides code is correct, regenerate canvas to mirror. (May or may not ship in Phase 7 — Sprint 1 Task 1 decides.)

Each instruction is **structured data** plus **rendered prose** for the implementing LLM. The LLM doesn't reverse-engineer from prose alone; it reads the structure for unambiguous fields and the prose for semantic intent.

The trust/weigh discipline carries forward: drift findings are facts (trust); instructions derived from facts are still facts (trust). The interpretive judgment is "should I close this gap?" — which Phase 6 already framed via bidirectional `:offenders` shape. Phase 7 doesn't add interpretation; it adds *actionability* on findings the canvas-author already chose to act on.

---

## File structure (Phase 7)

**Likely new namespaces:**

```
src/fukan/canvas/instruct/
  core.clj                       ; instruction-shape contract (spec/schema)
  registry.clj                   ; per-drift-kind generator registry; mirrors lens registry pattern
  render.clj                     ; structured-instruction → markdown rendering for LLM consumption

  add_function.clj               ; one file per instruction type
  add_record.clj
  add_value.clj
  add_event.clj
  add_predicate.clj              ; covers add-invariant-predicate + add-rule-predicate
  add_getter.clj
  add_handler.clj
  update_record_fields.clj

src/fukan/canvas/architect/
  handoff.clj                    ; dispatch protocol for handing instructions to implementing LLM

doc/plans/
  2026-05-27-instruction-design.md          ; Sprint 1 output
  2026-05-27-handoff-protocol-design.md     ; Sprint 1 output
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

# Sprint 1 — Instruction shape + handoff design (Tasks 1–2)

Two design docs before any code. Mirrors Phase 5 + 6 Sprint 1 shape.

---

## Phase 7, Task 1: Instruction-shape design doc

**Files:**
- Create: `doc/plans/2026-05-27-instruction-design.md`

### What the design doc must cover

For each of the 10 candidate instruction types listed above:

1. **What it generates.** Concrete sample of the structured-instruction map for one drift finding. What fields are always present; what's optional.
2. **Inputs.** What drift-finding fields feed in; what canvas-db queries the generator needs (e.g. to fetch the originating canvas declaration's docstring + structural surroundings); what analyzer-output it reads for the canonical address.
3. **Rendered prose shape.** The markdown the implementing LLM reads. Same finding's prose rendering. Should be self-contained — implementing LLM doesn't need to query fukan to write the code.
4. **Stub template.** When the generator produces a structural template (e.g. `(defn get-self-role [] ...)`), what's the template shape? When the body is too constrained to template usefully (e.g. invariant predicates that need actual logic), the instruction skips the template and emits prose-only.
5. **Phase 7 priority.** Ship vs defer.

### Critical design questions to settle

- **Is this trust tier or a new tier?** Drift findings are trust-tier. Instructions derived FROM drift findings — are they still decision-ready facts? Or is the LLM's stub-template a recommendation (weigh-tier)? **Default proposal: trust-tier with `:severity :info`** (informational; the action is take-it-or-modify, never "this is an error"). But this is a partition question worth examining.
- **Stub-template ambition level.** Bare signatures + an exception body? Or richer scaffolding (related fns referenced; tests outline; common patterns)? The trade-off: richer templates risk being wrong; bare templates leave more work for the implementing LLM but stay safe. **Default proposal: bare signature + exception body + structured `:context` field naming related canvas entities.**
- **Symmetric (canvas-side) instructions?** When drift's bidirectional framing says "canvas may be the side to move," does Phase 7 generate canvas-edit instructions too? **Default proposal: defer to Phase 8.** Phase 7 handles only `:code-side/*` instructions; symmetric canvas-side instructions are a follow-on.

### Steps

- [ ] **Step 1: For each candidate instruction type, sketch the structured + rendered shape against a real drift finding from fukan-itself.** Pick representative findings from each category (function, invariant, record, event, etc.) — use real `:stable-id`s as the source material.
- [ ] **Step 2: Settle the tier question.** Confirm or push back on "trust-tier with `:info` severity."
- [ ] **Step 3: Settle stub-template ambition.** Confirm or refine the "bare signature + exception body" default.
- [ ] **Step 4: Settle symmetric instructions** (defer vs ship).
- [ ] **Step 5: Pick 4-6 instruction types for Phase 7 priority.** Defer the rest.
- [ ] **Step 6: Pause for user review.** Load-bearing design conversation.
- [ ] **Step 7: Commit.**

```bash
jj desc -m "doc(canvas): Phase 7 instruction-shape design"
jj new
```

---

## Phase 7, Task 2: Handoff protocol design

**Files:**
- Create: `doc/plans/2026-05-27-handoff-protocol-design.md`

### Context

Phase 7's product surface separates fukan from the implementing LLM. fukan reasons about canvas + drift + instructions. The implementing LLM writes the code. The handoff is the structured instruction + enough context for the LLM to act without further fukan queries.

The handoff design settles:

- **Dispatch shape.** Does the canvas-author LLM (running as `fukan-architect`) dispatch the implementing LLM via the Agent tool? Or does fukan emit the instruction structure for an external workflow (the human, an editor agent, a CI system) to pick up? **Default proposal: Agent-tool dispatch from `fukan-architect`** — keeps the loop closed; implementing LLM is a child subagent.
- **Implementing-LLM brief.** What goes in the dispatched prompt? The structured instruction + the rendered prose + pointers to: relevant existing code (matching neighbor fns, related records), the canvas docstring source, possibly a few lines of context from `src/`. **Don't dump the canvas db.** The implementing LLM needs targeted context.
- **Verification protocol.** After the implementing LLM lands its commit, how does fukan verify the gap closed? Re-run drift; confirm the finding is gone. If drift still shows the finding (or new findings appeared), what's the loop? **Default proposal: one re-run; if not closed, the implementing LLM is dispatched a second time with the new drift output as feedback.**
- **Multi-instruction batching.** Should fukan generate one instruction per dispatch, or batch (e.g. "implement all missing fns in `distributed.cluster`")? **Default proposal: one instruction per dispatch in Phase 7.** Batching is a Phase 8 candidate once single-instruction loops are evidenced.

### Steps

- [ ] **Step 1: Sketch the dispatch protocol.** Per-turn structure (canvas-author asks for instruction → fukan produces it → canvas-author reviews → canvas-author dispatches implementing LLM → implementing LLM commits → drift re-run → confirm).
- [ ] **Step 2: Specify the implementing-LLM brief.** What context to pass; what NOT to pass (don't bloat).
- [ ] **Step 3: Specify the verification protocol.** Re-run drift; closed-vs-not-closed determination; retry loop.
- [ ] **Step 4: Pick trial-run target shape.** Phase 6's `canvas/distributed/*` + partial `src/fukan/distributed/*` is the natural continuation — many drift findings remain there from Phase 6's deliberate omissions, ready material for the closing loop.
- [ ] **Step 5: Pause for user review.**
- [ ] **Step 6: Commit.**

```bash
jj desc -m "doc(canvas): Phase 7 handoff protocol design"
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

# Sprint 3 — Build instruction generation (Tasks 5–N)

The substantive sprint. Implements the instruction substrate (`core`/`registry`/`render`) + one generator per Sprint-1-selected instruction type.

Mirrors Phase 5's lens-substrate sprint shape:

- **Substrate** (Task 5): core + registry + render namespaces. ~3 small namespaces.
- **Generators** (Tasks 6 through N): one per instruction type. Each is a `(generate finding) → instruction-map` fn registered in the registry.

Each generator task lands as:
- One namespace under `src/fukan/canvas/instruct/`
- Tests covering: positive case (finding → expected instruction), edge cases (missing context, unusual shapes), rendered-prose snapshot
- Registry registration
- Agent api `(help)` surface entry under the appropriate tier

---

## Phase 7, Task 5: Instruction substrate (core + registry + render)

**Files:**
- Create: `src/fukan/canvas/instruct/core.clj` — instruction-shape contract; `validate-instruction`
- Create: `src/fukan/canvas/instruct/registry.clj` — per-drift-kind generator registration; `all-generators`, `generator-for`
- Create: `src/fukan/canvas/instruct/render.clj` — structured → markdown rendering
- Tests for each

Mirrors the Phase 5 lens substrate pattern: small (~30-50 LOC per namespace), explicit, mechanical.

**Instruction contract:**

```clojure
{:instruction-kind  :code-side/add-function     ; required
 :severity          :info                       ; trust-tier default
 :drift-finding     {<original drift-finding-map>}    ; provenance
 :target            {:path "src/fukan/<...>.clj"
                     :namespace "fukan.<...>"
                     :symbol "<symbol-name>"}
 :signature         {<kind-specific structured signature>}
 :rationale         "<one-paragraph prose>"
 :stub-template     "<rendered Clojure template string, or nil>"
 :context           {:related-fns    [<stable-id> ...]
                     :related-types  [<stable-id> ...]
                     :canvas-source  "<canvas/.../file.clj:line>"}
 :rendered          "<full markdown the implementing LLM reads>"}
```

The registry is keyed by drift `:check` value (`:inspect.drift/missing-implementation` + `:canvas-kind`) so the dispatch is mechanical:

```clojure
(generate-instruction finding)
  => (apply-generator (lookup-generator-for finding) finding)
```

- [ ] **Step 1: Lens-substrate-style contract.** Spec + `validate-instruction`.
- [ ] **Step 2: Registry.** Auto-discovery from `fukan.canvas.instruct.*` namespaces (mirror lens registry — explicit `require` + `conj`).
- [ ] **Step 3: Render.** Structured-instruction → markdown.
- [ ] **Step 4: Tests for each.**
- [ ] **Step 5: Commit.**

```bash
jj desc -m "feat(canvas/instruct): instruction substrate (core + registry + render)"
jj new
```

---

## Phase 7, Tasks 6 through N: Per-instruction-type generators

One task per instruction type Sprint 1 Task 1 selected. Each task:

- Implements `(def generator …)` in `src/fukan/canvas/instruct/<instruction_name>.clj`
- Registers with the registry
- Adds tests with synthetic drift findings + snapshot tests for rendered prose
- Runs against real findings against fukan-itself; documents a sample

Suggested order (cheapest to hardest):

- Task 6: `add-record` (simplest — canvas record fields map directly to `[:map ...]`)
- Task 7: `add-value` (very simple — opaque type def)
- Task 8: `add-event` (similar to record)
- Task 9: `add-function` (signature derivation from canvas takes/gives; semantic intent from docstring)
- Task 10: `add-getter` (special case of function)
- Task 11: `add-invariant-predicate` (signature is `(fn [model] ...)`; semantic intent is the only non-trivial bit)
- Task 12: `add-rule-predicate` (symmetric)
- Task 13: `update-record-fields` (depends on compound-shape comparator from Sprint 2 Task 3)

Sprint 1 settles which to ship vs defer. **Default: ship 5-6, defer 2-3.**

Per-commit hygiene: **one commit per generator.** Each generator is one logical change.

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

1. **Tier verdict for instructions.** Recommended default: trust-tier with `:severity :info` (no error; here's how to close). Push back if a new tier (e.g. `:act`) reads more naturally.
2. **Stub-template ambition level.** Recommended default: bare signature + exception body + structured `:context` field. Richer templates risk being wrong; bare templates leave more work for the implementing LLM but stay safe. Preference?
3. **Symmetric (canvas-side) instructions.** Recommended default: defer to Phase 8. Phase 7 ships only `:code-side/*`. Push back if you want both directions in Phase 7.
4. **Instruction-type priority.** Of the 10 candidates, which 5-6 ship first? Default: add-record, add-value, add-event, add-function, add-invariant-predicate, update-record-fields. (Excludes: add-getter as special-case of function; add-handler; add-rule-predicate as symmetric with invariant; symmetric canvas-side instructions.)
5. **Dispatch shape.** Recommended default: Agent-tool dispatch from `fukan-architect` to a general-purpose implementing LLM. Push back if you want a different shape (e.g. external workflow, CI agent).
6. **Multi-instruction batching.** Recommended default: one instruction per dispatch in Phase 7. Phase 8 candidate. Push back if you want batching in Phase 7.
7. **Trial-run target.** Recommended default: continue `canvas/distributed/*` work — Phase 6's deliberate omissions are ready material. Push back if you want a different target.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–2 | Instruction-shape design + handoff protocol design (two pause points) |
| 2 | 3–4 | Compound-shape comparator + Sprint-1-surfaced prereqs |
| 3 | 5–N | Instruction substrate (core/registry/render) + per-instruction-type generators (5-6 to ship; 2-3 deferred) |
| 4 | N+1 to N+2 | Architect agent extension + close-the-loop trial run |
| 5 | Final | Phase 7 verification + Phase 8 brief |

**Estimated calendar:** Sprint 1 ≈ 2 sessions (design + 2 pauses). Sprint 2 ≈ 1-2 sessions (compound-shape comparator is the meaningful piece). Sprint 3 ≈ 4-6 sessions (substrate + 5-6 generators). Sprint 4 ≈ 2 sessions (integration + trial). Sprint 5 ≈ 1 session. **Total: 10-13 working sessions.**
