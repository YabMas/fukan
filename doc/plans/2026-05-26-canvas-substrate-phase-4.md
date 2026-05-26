# Canvas + Substrate Implementation Plan — Phase 4

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settle Phase 3's deferred items, fix real bugs surfaced by broader porting, and make canvas pleasant to author. **No UI work.** Phase 5 picks up the browser-side authoring loop once Phase 4 settles "the rest."

**Phase 4's strategic frame:** Phase 3 produced a canvas-native analysis substrate end-to-end (62 modules ported, pipeline integrated, legacy retired). It also produced known hardening items: 57 duplicate-name collisions resolved by first-match (a real correctness bug), the `triggers:`/`returns:` annotations still captured only in docstrings (now at ~4 corpus instances; previous threshold was 2), the stable id scheme is naive and would break under rename, and authoring ergonomics for LLM co-authors are still rough. Phase 4 closes these.

**Architecture:** All work happens in existing canvas tree namespaces (`src/fukan/canvas/core/`, `src/fukan/canvas/construction.clj`, `src/fukan/canvas/vocab/`, `src/fukan/canvas/projection/`). No new top-level structure. The substrate may gain one or two attributes (e.g. for stable ids or for triggers/returns); no primitive changes.

**Tech stack:** Same as Phase 3 — Clojure 1.11, Datascript, clojure.test, nREPL.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-26-phase-3-verification.md` — Phase 3 outcomes and the open questions Phase 4 inherits.
- `doc/plans/2026-05-26-broader-porting-notes.md` — Sprint 2 evidence on what gaps emerged during broader porting.
- `doc/plans/2026-05-26-graph-integration-design.md` — Section 7.1 (cross-module reference resolution) and Section 7.2 (stable IDs).
- `doc/plans/2026-05-25-emergence-comparison.md` — Section 4 on the deferred `rule` decision (now closed in Phase 3) and what other Phase 2 evidence carries forward.
- `DESIGN.md`, `CLAUDE.md`, `AGENTS.md` — current state after Phase 3 Sprint 5 update.

**Scope of this plan:** Phase 4 only. Phase 5 (browser authoring loop, collaborative editing, LLM-assisted design conversations through the viewer) gets its own plan after Phase 4's verification.

---

## File structure (Phase 4)

**Likely files to modify:**

```
src/fukan/canvas/projection/canvas_source.clj       ; Sprint 1 — module-qualified ref resolution
src/fukan/canvas/core/substrate.clj                  ; Sprint 1 (possibly) — stable-id support if needed
src/fukan/canvas/core/substrate/store.clj            ; Sprint 1/2 — schema additions as needed
src/fukan/canvas/construction.clj                    ; Sprint 2 — triggers/returns extension to function
src/fukan/canvas/vocab/behavioral.clj                ; Sprint 2 — adjustments if needed
src/fukan/canvas/core/shape.clj                      ; Sprint 2 — tuple-of combinator if warranted
src/fukan/canvas/core/check.clj                      ; Sprint 4 — better violation diagnostics
src/fukan/canvas/core/defquery.clj                   ; Sprint 4 — named-entity resolution
bin/fukan                                             ; Sprint 4 — possibly enhanced agent CLI
```

**Likely files to create:**

```
src/fukan/canvas/identity.clj                        ; Sprint 1 — stable-id generation + resolution
test/fukan/canvas/identity_test.clj
src/fukan/canvas/vocab/<paradigm>/EXAMPLES.md       ; Sprint 4 — one EXAMPLES doc per vocab library
doc/plans/2026-05-26-architect-explorer-workflow.md ; Sprint 4 — the workflow pattern formalized
doc/plans/2026-05-26-phase-4-verification.md        ; Sprint 5 — verification + Phase 5 brief
```

**Files NOT touched in Phase 4:**

- `canvas/` content (no port refactoring; the 62 canvas ports were ratified in Phase 3).
- `src/fukan/model/`, `src/fukan/infra/`, `src/fukan/web/` — implementation code (no changes except possibly via `bin/fukan`).
- `.legacy-allium/` — archive stays as-is.
- Plan docs in `doc/plans/` — historical record.

---

# Sprint 1 — Substrate hardening: identity + queryability (Tasks 1–4)

Two related correctness items Phase 3 left open:

- **Identity** — cross-module reference resolution is by-name first-match; 57 duplicate names exist in the canvas tree. Tasks 1-2 fix this with module-qualified resolution + a stable-id contract.
- **Queryability** — structural facts that should be Datalog-queryable are stored as `pr-str` strings inside the `:affordance/shape` attribute. Asking "find all functions returning `:Phase4Result`" currently requires parsing strings. Task 3 extracts input/output type names (plus record-field type names) as first-class queryable datoms alongside the full shape map.

Sprint 1 is the substrate-side hardening pass. Everything that follows (triggers/returns, stress-test, ergonomics) benefits from the queryability extension being already in place.

---

## Phase 4, Task 1: Module-qualified name resolution

**Files:**
- Modify: `src/fukan/canvas/projection/canvas_source.clj` — change resolution logic
- Possibly modify: `src/fukan/canvas/core/substrate/store.clj` — new query helpers
- Test: `test/fukan/canvas/projection/canvas_source_test.clj`

**Context.** Phase 3 Sprint 3 Task 8 resolved cross-module references like `:model/Model` by finding the first entity in the unified db with `:entity/name "Model"`. This is wrong: if two modules both declare entities named "Model", references silently route to one of them. The 57 duplicate names in fukan's canvas tree make this a real correctness issue even though the current resolution happens not to break anything by accident.

The fix: namespaced reference keywords (`:model/Model`) should resolve by **module-qualified name**, not by bare entity name. The keyword's namespace identifies the module; the keyword's name identifies the entity within that module.

- [ ] **Step 1: Test the failing case.** Add a test that constructs two canvas modules each with an entity named "X". Reference `:moduleA/X` from somewhere. Confirm resolution picks the X from moduleA, not from moduleB.

- [ ] **Step 2: Implement module-qualified resolution.** In `canvas_source/build-canvas-db`'s reference-resolution logic, when resolving a namespaced keyword `:M/N`, find the Module entity named `M` first (or fail), then find the child entity of `M` named `N`. Use the `:module/child` graph to scope the search.

- [ ] **Step 3: Update duplicate-name detection.** With module-qualified resolution, duplicates across modules are no longer a problem. Duplicates WITHIN a module ARE still wrong. Update the detection to fail-fast on intra-module duplicates and warn (or silence) on cross-module duplicates.

- [ ] **Step 4: Run the full suite.** All 208 canvas tests + new tests should pass. Phase 3's first-match behavior happens to work for fukan-itself, so existing tests don't regress.

- [ ] **Step 5: Commit**

```bash
jj desc -m "fix(canvas/projection): module-qualified cross-module reference resolution (closes Phase 3 Q3)"
jj new
```

---

## Phase 4, Task 2: Stable id contract

**Files:**
- Create: `src/fukan/canvas/identity.clj` — id generation + lookup + alias support
- Modify: `src/fukan/canvas/projection/canvas_source.clj` — use `identity/stable-id`
- Test: `test/fukan/canvas/identity_test.clj`

**Context.** Phase 3 chose `module-name/entity-name` strings as graph node IDs. Working but fragile: renaming an entity in canvas (or in source) breaks any persisted reference to its old ID (URL-based selection, bookmarks, external systems). The simplest evolution mechanism: support id aliases — declare that an old ID still resolves to a current entity.

Phase 4 doesn't need to implement URL-based selection or external persistence. What it needs is **the contract** — define how stable IDs work and add the alias mechanism — so Phase 5+ can build on it.

- [ ] **Step 1: Spec the contract.** A short design note (~30 lines, inline in the namespace docstring) covering:
  - The id format (`module-name/entity-name`, with `module-name/state/name` and `module-name/type/name` variants for typed-clarity).
  - Aliasing mechanism: declare an old id maps to a current entity. Where do aliases live? (Decision: a per-canvas-port `(alias "old-id" "new-id")` form, OR a top-level `canvas/aliases.edn` file.)
  - Resolution: `identity/resolve-id` returns the current id for any input (canonical id or alias).
  - Stable across rename: only the canvas author choosing to add an alias declares stability; without an alias, renames lose old references.

- [ ] **Step 2: Implement `identity/stable-id` and `identity/resolve-id`.** Both are tiny — pure functions over the canvas db.

- [ ] **Step 3: Implement aliasing.** Pick the mechanism from Step 1's design. If per-port: a new lift `(alias "old-id" "new-id")` in `vocab.lifecycle` or `construction`. If top-level edn: a single `aliases.edn` file at canvas/ root that `canvas-source/build` reads.

- [ ] **Step 4: Update `canvas_source/project`** to call `identity/stable-id` for every entity it projects.

- [ ] **Step 5: Test the rename scenario.** Construct a canvas with entity "X"; add alias mapping "old-id" → "X"; confirm `resolve-id "old-id"` returns "X"'s current id.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(canvas/identity): stable id contract with alias support (closes Phase 3 Q5)"
jj new
```

---

## Phase 4, Task 3: Extract shape type names to first-class queryable datoms

**Files:**
- Modify: `src/fukan/canvas/core/substrate/store.clj` — schema additions + `->datoms` extensions
- Possibly modify: `src/fukan/canvas/core/shape.clj` — add `type-names` traversal helper
- Test: extend `test/fukan/canvas/core/store_test.clj`

**Context.** Affordance shapes are stored as `(pr-str <shape-map>)` strings on the `:affordance/shape` attribute. The full shape data is recoverable via `edn/read-string` but Datalog queries can't filter on its contents directly. Same for Type records — field types are nested inside the `:fields` collection.

Task 3 extracts *flat type-name sets* as separate Datascript attributes, keeping the full pr-str shape map intact (no breaking change for consumers). After this task:

- `[?a :affordance/input-types :model/Model]` — find all affordances taking Model
- `[?a :affordance/output-types :Phase4Result]` — find all affordances returning Phase4Result
- `[?t :type/field-types :agent/Violation]` — find all types with a Violation-typed field (at any nesting depth)

### Step 1: Shape-type traversal helper

Add to `src/fukan/canvas/core/shape.clj`:

```clojure
(defn type-names
  "Walk a parsed shape; collect the set of all atomic type names and ref
   targets it mentions. Returns a set of keywords."
  [shape]
  (case (:kind shape)
    :atomic   #{(:name shape)}
    :ref      #{(:target shape)}
    :optional (type-names (:inner shape))
    :list     (type-names (:elem shape))
    :set      (type-names (:elem shape))
    :sum      (apply clojure.set/union (map type-names (:variants shape)))
    :map      (clojure.set/union (type-names (:key shape)) (type-names (:val shape)))
    :record   (apply clojure.set/union (map (fn [[_ s]] (type-names s)) (:fields shape)))
    :arrow    (clojure.set/union (type-names (:inputs shape)) (type-names (:outputs shape)))
    #{}))
```

This is a pure traversal over the parsed shape — no Datascript dependency.

### Step 2: Schema additions

In `store.clj`:

```clojure
:affordance/input-types  {:db/cardinality :db.cardinality/many}
:affordance/output-types {:db/cardinality :db.cardinality/many}
:type/field-types        {:db/cardinality :db.cardinality/many}
```

### Step 3: `->datoms :Affordance` and `:Type` updates

For Affordance with arrow shape:

```clojure
(let [shape  (sub/shape-of a)
      inputs  (shape/type-names (:inputs shape))
      outputs (shape/type-names (:outputs shape))]
  (cond-> base-map
    (sub/shape-of a)         (assoc :affordance/shape (pr-str shape))
    (seq inputs)             (assoc :affordance/input-types inputs)
    (seq outputs)            (assoc :affordance/output-types outputs)))
```

For Type with record kind:

```clojure
(let [field-types (shape/type-names {:kind :record :fields (:fields t)})]
  (cond-> base-map
    (seq field-types) (assoc :type/field-types field-types)))
```

### Step 4: Tests

Add to `test/fukan/canvas/core/store_test.clj`:

```clojure
(deftest affordance-input-types-are-queryable
  (let [m (sub/module "evaluator")
        a (sub/affordance "evaluate_rules"
            :shape {:kind :arrow
                    :inputs {:kind :record :fields [["rules" {:kind :list :elem {:kind :ref :target :ast/ConstraintRule}}]
                                                    ["edb" {:kind :ref :target :derivations/EDB}]]}
                    :outputs {:kind :ref :target :derivations/EDB}})
        db (-> (store/create) (store/transact! m) (store/transact! a))]
    (is (= #{[:ast/ConstraintRule] [:derivations/EDB]}
           (set (d/q '[:find ?t :where [?a :affordance/input-types ?t]] db))))
    (is (= #{[:derivations/EDB]}
           (set (d/q '[:find ?t :where [?a :affordance/output-types ?t]] db))))))

(deftest type-field-types-are-queryable
  (let [t (sub/type-record "Phase4Result"
            [["model" {:kind :ref :target :model/Model}]
             ["violations" {:kind :list :elem {:kind :ref :target :agent/Violation}}]])
        db (-> (store/create) (store/transact! t))]
    (is (= #{[:model/Model] [:agent/Violation]}
           (set (d/q '[:find ?ft :where [?ty :type/field-types ?ft]] db))))))
```

### Step 5: Run all tests; confirm 208 + new pass

The existing pr-str shape attribute is unchanged; existing consumers continue to work. The new attributes are additive.

### Step 6: Commit

```bash
jj desc -m "feat(canvas/substrate): extract input/output and field type names to first-class queryable datoms"
jj new
```

---

## Phase 4, Task 4: Sprint 1 verification

- [ ] **Step 1: Run full canvas suite.** Confirm 208 + new tests (~7-10 new across Tasks 1-3) pass.
- [ ] **Step 2: Run full project suite.** Confirm 579 + new tests pass.
- [ ] **Step 3: Re-run `canvas-source/build`** in REPL; confirm primitive count and edge count match Phase 3's (~623 primitives + ~541 edges). No regression in the projection.
- [ ] **Step 4: Confirm new query patterns work** against the rebuilt model. E.g. `(d/q '[:find ?n :where [?a :affordance/output-types :Phase4Result] [?a :entity/name ?n]] db)` should return the affordances that return Phase4Result (`run` and `gate_g2` from canvas/validation/phase4).
- [ ] **Step 5: Brief notes doc** if anything surprising surfaced. Optional.

---

# Sprint 2 — Deferred Phase 3 decisions (Tasks 5–6)

Two items Phase 3 deferred with explicit "wait for more evidence" framing. Sprint 2 settles them.

---

## Phase 4, Task 5: `triggers:` / `returns:` first-class

**Files:**
- Modify: `src/fukan/canvas/construction.clj` — extend `function` lift OR add new forms
- Modify: `src/fukan/canvas/core/substrate.clj` — possibly a new field on Affordance OR a new Relation kind
- Test: extend `test/fukan/canvas/construction_test.clj`
- Modify: a sample canvas port (e.g. `canvas/validation/phase4.clj`) to use the new feature
- Update: `doc/plans/2026-05-26-broader-porting-notes.md` with the resolution

**Context.** Phase 1 verification flagged `triggers:` / `returns:` annotations on functions as "Gap 13." Phase 2 had 2 instances (declined; rule-of-three). Phase 3's broader porting + rule-lift backfill brought it to ~4 instances. Now above threshold.

**Decision (settled before this task):** symmetric treatment, both first-class.

- **`(triggers RuleName)`** → emits a `:triggers` Relation from the function's Affordance to the rule's Affordance.
- **`(returns "label-name")`** → adds an `:affordance/returns-label` attribute (cardinality-one string) on the function's Affordance. The label is queryable: future formal-expression syntax can refer to the binding (e.g. `ensures: post.result.success = true` where `post.result` is the returns-label).

Both go INSIDE `function`'s body grammar, mirroring Allium's nested syntax (`fn run { triggers: …; returns: …; }`).

- [ ] **Step 1: Schema addition.** Add `:affordance/returns-label {:db/index true}` to `store.clj`'s schema map.

- [ ] **Step 2: Extend `function` lift grammar** in `construction.clj`. Add two new forms: `triggers` (required if present; emits Relation) and `returns` (optional; emits the label attribute).

- [ ] **Step 3: Tests.** `function-with-triggers-creates-relation` and `function-with-returns-label-is-queryable`.

- [ ] **Step 4: Backfill the ~4 instances** across `canvas/`. Find via grep. Convert docstring annotations to first-class forms. One per-port commit per file touched.

- [ ] **Step 5: Commit (per backfill commit, plus the lift-extension commit).**

```bash
jj desc -m "feat(canvas): function triggers/returns forms (closes Phase 3 Q4)"
jj new
```

---

## Phase 4, Task 6: `tuple-of` shape combinator — decide

**Files:** decision-dependent.

**Context.** Phase 3's model/ subsystem port surfaced one or two `(tuple-of T1 T2)` patterns (identity helpers like `edge_identity` return 2-tuples or 3-tuples). The agent flagged it as "below rule-of-three; deferred." Phase 4 decides.

Action items:

- [ ] **Step 1: Grep across canvas/** for any tuple-shaped places (look for ad-hoc encodings or comments mentioning tuples). Count exact instances.

- [ ] **Step 2: If ≥3 instances**: ship `(tuple-of T1 T2 ...)` in `core/shape.clj` (parallel to `list-of`/`set-of`/`map-of`). Update the `emit-refs!` walkers in `construction.clj` and `vocab/lifecycle.clj`.

- [ ] **Step 3: If <3 instances**: explicitly defer. Note in `doc/plans/2026-05-26-broader-porting-notes.md` what we decided and why.

- [ ] **Step 4: Commit (either way).**

```bash
# If implemented:
jj desc -m "feat(canvas/shape): tuple-of combinator (rule of three met)"
# If deferred:
jj desc -m "doc(canvas): tuple-of remains deferred — <N> instances below rule-of-three"
jj new
```

---

# Sprint 3 — Substrate stress-test (Tasks 7–8)

The substrate's claim of architecture-neutrality has only been validated against fukan-itself. Sprint 3 tests it against a fundamentally different paradigm to expose any hidden assumptions.

This is *not* "broader porting" — it's a targeted experiment to find what breaks when the substrate is asked to model something it wasn't designed to model.

---

## Phase 4, Task 7: Pick stress-test targets + design notes

**Files:** Decision docs only.

**Decision (settled before this task):** TWO stress-tests, bracketing the substrate's range.

- **Test I — Event-driven service** (upper-bound test): 5-10 modules with `Event` / `Handler` / `Topic` / `Subscription` vocabulary. Most different from fukan-the-monolith; likely requires a new `vocab.event` library if rule-of-three is met within the 5-10 modules.
- **Test IV — Static library** (lower-bound test): 5 modules of types + pure functions, no behavioral content. Tests whether the substrate is over-built for trivial cases — should port in ~1 session with no new vocab.

Both go in a new `demo/` folder at project root:

```
demo/event-driven/<modules>.clj    ; ns: demo.event-driven.<module>
demo/static-lib/<modules>.clj      ; ns: demo.static-lib.<module>
```

**`demo/` is NOT on the classpath.** It's not added to `deps.edn` `:paths`. The demos are advertisements (showcases of expressiveness on architectures fukan itself doesn't use); they're loadable on-demand for testing via an explicit `(load-demo <name>)` REPL helper but don't auto-project into fukan's own model. This keeps fukan's analysis substrate clean while still proving the substrate's range.

- [ ] **Step 1: Write design notes** at `doc/plans/2026-05-26-stress-test-targets.md`:
  - Each test's chosen paradigm + 5-10 module names
  - Anticipated vocab additions (`vocab.event` for Test I; nothing new for Test IV)
  - Success criteria per test
  - Where each demo lives and how it's loaded

- [ ] **Step 2: Implement `(load-demo <name>)` REPL helper** in `dev/user.clj` (or wherever REPL helpers live). The helper requires the demo namespaces and projects into a fresh canvas db separate from `(infra-model/get-model)`.

- [ ] **Step 3: Commit the design notes + helper.**

```bash
jj desc -m "doc(canvas): stress-test design — event-driven + static-lib demos in /demo/"
jj new
```

---

## Phase 4, Task 8: Stress-test execution

**Files:**
- Create: `demo/event-driven/<modules>.clj` + tests
- Create: `demo/static-lib/<modules>.clj` + tests
- Possibly create: `src/fukan/canvas/vocab/event.clj` — new vocab if Test I produces 3+ instances of a missing pattern
- Update: `doc/plans/2026-05-26-stress-test-targets.md` with findings
- Each demo gets a `demo/<paradigm>/README.md` describing what it showcases

- [ ] **Step 1: Port Test IV (static-lib) first** — fast sanity check on the lower bound. Should be ~1 hour. If it requires new vocab or fights the substrate, that's a warning sign.

- [ ] **Step 2: Port Test I (event-driven)** — the upper-bound test. As vocab gaps surface, apply rule of three within the 5-10 modules:
  - 3+ instances → ship a `vocab.event` lift inline (in `src/fukan/canvas/vocab/event.clj`, on the classpath; the vocab library IS fukan-shipped machinery)
  - <3 → document the gap; use bare substrate helpers as escape

- [ ] **Step 3: Per-port commits** as in Sprint 2 of Phase 3.

- [ ] **Step 4: Write findings.** Per test:
  - What lifts were needed beyond the existing vocab?
  - Did the substrate hold without primitive changes?
  - Anything that genuinely couldn't be modeled?
  - How does the demo read aloud — at module-design altitude, or does substrate plumbing leak through?

- [ ] **Step 5: Commit findings doc.**

```bash
jj desc -m "doc(canvas): substrate stress-test findings — event-driven + static-lib demos"
jj new
```

---

# Sprint 4 — LLM authoring ergonomics (Tasks 9–11)

The canvas is designed to be authored by humans + LLMs collaboratively. Phase 3 produced the canvas; Phase 4 makes it pleasant for LLMs to work with. Without UI work, "ergonomics" means: error messages, REPL surface, examples, query semantics.

---

## Phase 4, Task 9: Improved constraint diagnostics

**Files:**
- Modify: `src/fukan/canvas/core/check.clj`
- Test: extend `test/fukan/canvas/core/check_test.clj`

**Context.** Currently when `fc/check` fires a violation, the structured violation map is `{:constraint name :message message :offenders [...]}`. Phase 3 didn't refine this. Phase 4 improves diagnostics:

- Include the canvas spec source file / line where the constraint was registered (if known).
- Include the offending entity's stable id + module-qualified name.
- Optional: format the violation as a "diagnostic" map matching common Clojure conventions (similar to `clj-kondo` output).

- [ ] **Step 1: Test**: assert that violations carry source-location and stable id.
- [ ] **Step 2: Implement**: track source-location at constraint registration; enrich violation maps.
- [ ] **Step 3: Commit.**

```bash
jj desc -m "feat(canvas/check): structured violation diagnostics with source-location and stable ids"
jj new
```

---

## Phase 4, Task 10: Named-entity resolution in queries

**Files:**
- Modify: `src/fukan/canvas/core/defquery.clj`
- Test: extend `test/fukan/canvas/core/defquery_test.clj`

**Context.** Currently `defquery` registers Datalog operators that expand into substrate patterns. A query like `(Module ?x)` finds Module entities. But queries can't reference specific named entities — to find Affordances of "module X" you have to know its id ahead of time.

The improvement: support `(this <module-qualified-name>)` resolution in query bodies. `(this :model/Model)` resolves to the entity named "Model" in module "model" at query time.

- [ ] **Step 1: Spec the resolution rule.** `(this :module/name)` → the entity id of that name in that module.
- [ ] **Step 2: Test.** A query that uses `(this :model/Model)` to start a traversal.
- [ ] **Step 3: Implement.** Add a special form to `defquery`'s expansion grammar.
- [ ] **Step 4: Commit.**

```bash
jj desc -m "feat(canvas/defquery): named-entity resolution via (this :module/name)"
jj new
```

---

## Phase 4, Task 11: Examples library per vocab

**Files:**
- Create: `src/fukan/canvas/construction.md` — examples for `function`, `record`, `value`, `exports`
- Create: `src/fukan/canvas/vocab/behavioral.md` — examples for `invariant`, `rule`
- Create: `src/fukan/canvas/vocab/validation.md` — examples for `checker`
- Create: `src/fukan/canvas/vocab/lifecycle.md` — examples for `getter`
- Create: `src/fukan/canvas/core/shape.md` — examples for the shape grammar

**Context.** LLMs reaching for the canvas vocabulary often discover lifts via grep / file-reads. Examples co-located with the lift definitions accelerate pattern-matching. Each EXAMPLES.md is short (50-100 lines): 2-3 typical use cases, the resulting substrate shape, and any naming conventions.

- [ ] **Step 1: Draft each examples doc.** Mine `canvas/` for representative uses.
- [ ] **Step 2: Per-file commits.** Five docs, five commits.

```bash
jj desc -m "doc(canvas/construction): examples for function/record/value/exports"
jj new
# etc per file
```

---

# Sprint 5 — Verification + Phase 5 brief (Task 12)

## Phase 4, Task 12: Phase 4 verification report

**Files:**
- Create: `doc/plans/2026-05-26-phase-4-verification.md`

Standard verification template per Phase 1 and Phase 3:

- [ ] **Section 1: What was attempted vs. built.** Recap Sprint 1-4 outputs.

- [ ] **Section 2: Did Phase 3's open items close cleanly?** Specifically:
  - Q3 (module-qualified name resolution) — closed?
  - Q4 (triggers/returns coupling) — closed?
  - Q5 (stable id evolution) — contract spec'd; aliases working?
  - `tuple-of` — decided?

- [ ] **Section 3: Did the substrate stress-test reveal architecture-bias?** Walk through what Sprint 3 found. Was the substrate genuinely neutral, or did the new paradigm expose hidden assumptions?

- [ ] **Section 4: LLM authoring ergonomics — are they noticeably better?** Subjective but can be evidenced: shorter time-to-first-correct-port for a fresh subagent; clearer diagnostics on failure; examples library accelerated pattern-matching.

- [ ] **Section 5: Decision.** Three outcomes:
  1. Canvas + substrate are ready for Phase 5 authoring loop work.
  2. Ready with caveats — a Phase 4.5 to close caveats, then Phase 5.
  3. Stress-test revealed something fundamental — reset.

- [ ] **Section 6: Phase 5 implications.** The authoring loop sketch:
  - Browser sanity check (Phase 3 Q1) — the first Phase 5 action.
  - Editing surface decision (Phase 3 Q2) — Phase 5's first design question.
  - LLM as co-author — what shape does the human-LLM loop take?
  - Real-time canvas → graph reflection — does the current approach-B pipeline support this, or does Phase 5 need to rework it?

- [ ] **Step Final: Commit.**

```bash
jj desc -m "doc(canvas): phase-4 verification + phase-5 brief"
jj new
```

---

## Subsequent phases (sketches)

**Phase 5 — Authoring loop**: Browser sanity check; canvas editing surface (embedded? text? chat-driven?); LLM as co-author through the viewer; real-time canvas → graph reflection. The user-facing product surface.

**Phase 6 — Vocab library expansion**: Per-paradigm vocab libraries driven by Phase 4 stress-test evidence + Phase 5 usage. Speculative; depends on what surfaces.

---

## Self-review notes

- **No browser work in Phase 4.** The user deferred UI to "after the rest settles." Phase 4 IS the rest-settling.
- **Substrate stress-test scope.** Sprint 3 is targeted (5-10 modules in a foreign paradigm) not exhaustive. Finding ONE thing the substrate can't model would be a more valuable finding than confirming it can model many.
- **Examples library scope.** One EXAMPLES.md per vocab is enough for now. No need for a comprehensive reference; the source code is the reference. Examples are accelerators.
- **The architect-explorer pattern formalization** (the Phase 2 Sprint 2 system prompt that successfully activated layered-language thinking in LLMs) is not in Phase 4. It probably belongs in Phase 5 or 6 once the authoring loop has shape. Phase 4 keeps focus on substrate-level hardening.
- **Per-port commits in Sprint 3.** The stress-test will produce 5-10 commits. Per-port, no bundling.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–4 | Substrate hardening: module-qualified resolution + stable id contract + shape-type queryability (Phase 3 Q3 + Q5 + the queryability gap) |
| 2 | 5–6 | triggers/returns first-class + tuple-of decision (Phase 3 Q4 + Phase 2's leftover gap) |
| 3 | 7–8 | Substrate stress-test bracketed: event-driven (upper) + static-lib (lower); demos in `/demo/` |
| 4 | 9–11 | LLM authoring ergonomics — diagnostics, query resolution, examples |
| 5 | 12 | Phase 4 verification + Phase 5 brief |

**Estimated calendar:** Sprint 1 ≈ 3 sessions (identity + queryability + tests). Sprint 2 ≈ 1 session. Sprint 3 ≈ 2 sessions (static-lib quick + event-driven thorough). Sprint 4 ≈ 2 sessions. Sprint 5 ≈ 1 session. **Total: 9 working sessions.**
