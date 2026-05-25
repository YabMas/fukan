# Canvas + Substrate Implementation Plan — Phase 2

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the mechanical ergonomic gaps Phase 1 surfaced in the structural lifts (`function`, `record`), then let architect-explorer subagents discover the canvas-native vocabulary for the behavioral and relational dimensions of real specs. Converge their independent explorations into a coherent lift library. Validate by re-porting and broader-porting. Decide Phase 3.

**Phase 2's core conviction:** Phase 1 validated the substrate. Phase 2 validates that *layered language design discipline* — specifically, the Lisp-tradition discipline of building languages bottom-up from minimal primitives — can produce a canvas vocabulary that reads at module-design altitude across a variety of fukan's real specifications. The vocabulary is not predetermined. It emerges from exploration grounded in real ports, then is converged via cross-session synthesis.

**Architecture:** Sprint 1 work modifies existing structural lifts (`function`, `record`) and extends `defconstructor`'s form-grammar to accept shape expressions. Sprint 2 work happens in isolated `src/fukan/canvas/library/explore_N/` namespaces — each parallel exploration session writes its own library, ports its assigned specs, and produces a reflection doc. Sprint 3 synthesizes across sessions and produces a converged `library/` design. The substrate (`substrate.clj`, `substrate/store.clj`) is *not* modified — Phase 1 validated it.

**Tech stack:** Same as Phase 1 — Clojure 1.11, Datascript for the substrate store, clojure.test for tests. nREPL for REPL workflow.

**Reference design docs (read in this order):**

- `doc/plans/2026-05-25-architect-explorer-system-prompt.md` — the system prompt for Sprint 2 subagents. The most load-bearing artifact of Phase 2.
- `doc/plans/2026-05-25-phase-1-verification.md` — what Phase 1 validated and what Phase 2 must address.
- `doc/plans/2026-05-25-pilot-port-findings.md` — Phase 1's gap catalog. Sprint 1 closes Gaps 2, 3, 4, 5, 8, 10, 12. Sprint 2's exploration sessions decide what to do with Gaps 1, 6, 9, 11, 13, 14, 15 (the behavioral and relational ones).
- `doc/plans/2026-05-25-canvas-substrate-redesign.md` — substrate vision. Untouched in Phase 2; consult when an exploration session feels constrained.
- `doc/plans/2026-05-25-canvas-substrate-implementation.md` — Phase 1's plan, for jj workflow patterns and the canvas API conventions.

**Scope of this plan:** Phase 2 only. Phase 3 (graph integration, migration of legacy specs, retirement of `.allium`/`.boundary` analyzers) gets its own plan after Sprint 4's verification report.

---

## File structure (Phase 2)

**Created in Sprint 1 (mechanical fixes):**

```
src/fukan/canvas/library/monolith.clj      ; MODIFIED — adds value lift, integrates shape grammar, cross-module refs
src/fukan/canvas/library/closure.clj       ; NEW — exports / module closure
src/fukan/canvas/shape.clj                 ; NEW — shape-expression grammar
src/fukan/canvas/defconstructor.clj        ; MODIFIED — accepts shape-expression form-grammar (if needed)

test/fukan/canvas/
  library/closure_test.clj
  shape_test.clj
  library/monolith_test.clj                ; EXTENDED
```

**Created in Sprint 2 (parallel exploration; per-session sibling namespaces):**

```
src/fukan/canvas/library/explore_1/        ; Session 1's invented lifts
  <whatever-files-the-session-creates>.clj
src/fukan/canvas/library/explore_2/        ; Session 2's invented lifts
src/fukan/canvas/library/explore_3/        ; Session 3's invented lifts

src/fukan/canvas/pilot/explore_1/          ; Session 1's port files
src/fukan/canvas/pilot/explore_2/
src/fukan/canvas/pilot/explore_3/

test/fukan/canvas/explore_1/               ; per-session tests
test/fukan/canvas/explore_2/
test/fukan/canvas/explore_3/

doc/plans/
  2026-05-25-explore-1-notes.md            ; per-session reflection docs
  2026-05-25-explore-2-notes.md
  2026-05-25-explore-3-notes.md
```

**Created in Sprint 3 (synthesis + validation):**

```
src/fukan/canvas/library/behavioral.clj    ; NEW — converged behavioral lift surface (form depends on synthesis)
src/fukan/canvas/library/<additional>.clj  ; whatever synthesis produces beyond behavioral
src/fukan/canvas/pilot/<re-ported>.clj     ; explore-session ports rewritten using the converged library

doc/plans/
  2026-05-25-synthesis-notes.md            ; the synthesis design doc
  2026-05-25-pilot-port-findings-2.md      ; Sprint 3 findings
```

**Created in Sprint 4 (verification):**

```
doc/plans/2026-05-25-phase-2-verification.md
```

**Files NOT touched in Phase 2:**

- `src/fukan/canvas/substrate.clj`, `substrate/store.clj` — Phase 1 validated.
- `src/fukan/canvas/helpers.clj`, `defquery.clj`, `check.clj` — modified only if Sprint 2 exploration discovers concrete needs.
- `.allium` / `.boundary` files, `src/fukan/model/`, `src/fukan/infra/`, `src/fukan/web/` — untouched until Phase 3.

---

# Sprint 1 — Mechanical ergonomic fixes (Tasks 1–4)

Phase 1's findings doc cataloged nine root-cause gaps. Four of them are mechanical ergonomic fixes in the existing structural lifts — they make `function` and `record` express what they were already supposed to express, without commitment to *new* vocabulary. Sprint 1 closes those four. The behavioral/relational gaps are deliberately left for Sprint 2's exploration.

Sprint 1 tasks are well-specified and TDD-shaped. Subagent-driven execution with one task per fresh subagent.

---

## Phase 2, Task 1: `value` lift — opaque named types

**Files:**
- Modify: `src/fukan/canvas/library/monolith.clj` (add `value` lift)
- Test: `test/fukan/canvas/library/monolith_test.clj` (add tests)

Closes Gap 4. The current `record` lift requires at least one `(field …)`; opaque named types (Allium-style `value`s with no exposed fields, like `Stratum` or `Binding`) cannot be expressed without dropping to substrate helpers. A `value` lift produces a Type with `:kind :atomic` and no fields — semantically a named token.

- [ ] **Step 1: Write the failing test.**

```clojure
(deftest value-lift-creates-opaque-type
  (testing "(value …) produces an atomic Type"
    (let [db (h/with-canvas
               (h/within-module "constraint.evaluator"
                 (value "Stratum" "An opaque stratification level.")
                 (value "Binding" "A logical-variable binding.")))]
      (is (= 2 (count (d/q '[:find ?n :where [?e :entity/type :Type] [?e :entity/name ?n]] db)))))))
```

- [ ] **Step 2: Confirm failure.**

`clj -M:test -n fukan.canvas.library.monolith-test` — expect FAIL.

- [ ] **Step 3: Implement.**

```clojure
(defconstructor value
  "An opaque named type — a named concept whose internal structure is withheld."
  (produces [name doc forms]
    (let [t (sub/type-primitive (keyword name))]
      (swap! h/*store* store/transact! t))))
```

- [ ] **Step 4: Verify** — run full canvas suite, expect all pass.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): value lift — opaque named types (closes Gap 4)"
jj new
```

---

## Phase 2, Task 2: Richer shape-expression grammar

**Files:**
- Create: `src/fukan/canvas/shape.clj` — shape-expression parser
- Modify: `src/fukan/canvas/library/monolith.clj` — `record`'s `field` and `function`'s `takes`/`gives` use `shape/parse`
- Possibly modify: `src/fukan/canvas/defconstructor.clj` — if form-grammar needs to accept expression-shapes alongside keyword-shapes
- Test: `test/fukan/canvas/shape_test.clj`, extend `monolith_test.clj`

Closes Gaps 3, 8, 10. Phase 1's lifts accept only keyword type refs (`:String`); after this task they accept full shape expressions:

```
:Keyword                  ; atomic type by name
(optional <shape>)        ; T?
(list-of <shape>)         ; List<T>
(set-of <shape>)          ; Set<T>
(sum-of <shape>+)         ; T1 | T2 | ...
(record-of <field+>)      ; inline anonymous record
(ref-to :module/Type)     ; cross-module reference (Task 3 may refine)
```

Shapes are *data*. `shape/parse` returns an edn map. Lifts consume the parsed map; the substrate stores it on Affordance shapes and Type fields.

- [ ] **Step 1: Write shape-parse tests.**

```clojure
(deftest atomic-shape
  (is (= {:kind :atomic :name :String} (shape/parse :String))))

(deftest optional-shape
  (is (= {:kind :optional :inner {:kind :atomic :name :Integer}}
         (shape/parse '(optional :Integer)))))

(deftest list-shape
  (is (= {:kind :list :elem {:kind :atomic :name :Any}}
         (shape/parse '(list-of :Any)))))

(deftest nested-shape
  (is (= {:kind :optional :inner {:kind :list :elem {:kind :atomic :name :Integer}}}
         (shape/parse '(optional (list-of :Integer))))))
```

- [ ] **Step 2: Implement `shape/parse`.**

```clojure
(ns fukan.canvas.shape)

(defn parse [expr]
  (cond
    (keyword? expr) {:kind :atomic :name expr}
    (and (seq? expr) (= 'optional (first expr))) {:kind :optional :inner (parse (second expr))}
    (and (seq? expr) (= 'list-of (first expr)))  {:kind :list :elem (parse (second expr))}
    (and (seq? expr) (= 'set-of (first expr)))   {:kind :set :elem (parse (second expr))}
    (and (seq? expr) (= 'sum-of (first expr)))   {:kind :sum :variants (mapv parse (rest expr))}
    (and (seq? expr) (= 'ref-to (first expr)))   {:kind :ref :target (second expr)}
    (and (seq? expr) (= 'record-of (first expr)))
    {:kind :record :fields (mapv (fn [[n s]] [n (parse s)]) (rest expr))}
    :else (throw (ex-info "unknown shape expression" {:expr expr}))))
```

- [ ] **Step 3: Integrate into `record` and `function` lifts.** Replace keyword-only shape handling with `shape/parse`. Verify all Phase 1 tests still pass (they use plain keywords; `shape/parse` accepts keywords identically).

- [ ] **Step 4: Add lift-level tests** using shape expressions:

```clojure
(deftest record-accepts-optional-and-list-fields
  ;; (field shapes (list-of :Any)) and (field port (optional :Integer)) both work
  ...)
```

- [ ] **Step 5: Verify, Step 6: Commit**

```bash
jj desc -m "feat(canvas): shape-expression grammar (closes Gaps 3/8/10)"
jj new
```

---

## Phase 2, Task 3: Cross-module type references

**Files:**
- Modify: `src/fukan/canvas/shape.clj` — namespaced keywords are refs implicitly
- Modify: `src/fukan/canvas/library/monolith.clj` — ref-shapes emit `:references` Relations
- Test: extend `shape_test.clj` and `monolith_test.clj`

Closes Gaps 5 and 12. `:module/Type` becomes a ref-shape; lift consumers (`function`, `record`) walk parsed shapes and emit a `:references` Relation per ref. Cross-module linkage is captured as substrate Relations.

- [ ] **Step 1: Test** — `(takes [rules (list-of :ast/ConstraintRule)])` produces both an Affordance for the function AND a `:references` Relation to `:ast/ConstraintRule`.

- [ ] **Step 2: Update `shape/parse`** — namespaced keywords are refs:

```clojure
(cond
  (and (keyword? expr) (namespace expr)) {:kind :ref :target expr}
  (keyword? expr)                        {:kind :atomic :name expr}
  ...)
```

- [ ] **Step 3: Implement ref-emission in lifts.** A small `emit-refs` walker over parsed shapes calls `h/declare-relation` for each `:ref` found:

```clojure
(defn- emit-refs [from-id shape]
  (case (:kind shape)
    :ref      (h/declare-relation from-id :references (:target shape))
    :optional (emit-refs from-id (:inner shape))
    :list     (emit-refs from-id (:elem shape))
    :set      (emit-refs from-id (:elem shape))
    :sum      (run! #(emit-refs from-id %) (:variants shape))
    :record   (run! (fn [[_ s]] (emit-refs from-id s)) (:fields shape))
    nil))
```

Apply over all shapes inside `function`'s `takes`/`gives` and `record`'s `field`.

- [ ] **Step 4: Verify, Step 5: Commit**

```bash
jj desc -m "feat(canvas): cross-module type references (closes Gaps 5/12)"
jj new
```

---

## Phase 2, Task 4: Module closure mechanism (`exports`)

**Files:**
- Create: `src/fukan/canvas/library/closure.clj`
- Test: `test/fukan/canvas/library/closure_test.clj`

Closes Gap 2. `.boundary`'s `exports:` clause lists which declarations form the module's public surface. The current canvas has no way to express this. The simplest mechanism: an `exports` form callable inside `within-module` that tags the listed entities with `:exported`.

- [ ] **Step 1: Test.**

```clojure
(deftest exports-tags-listed-names
  (let [db (h/with-canvas
             (h/within-module "infra.server"
               (record "ServerOpts" "." (field port :Integer))
               (function "start_server" "." (gives :Unit))
               (exports ServerOpts start_server)))
        tagged (d/q '[:find ?n :where [?e :entity/tag :exported] [?e :entity/name ?n]] db)]
    (is (= #{"ServerOpts" "start_server"} (set (map first tagged))))))
```

- [ ] **Step 2: Implementation.** `exports` is the first non-`defconstructor` lift (it takes bare symbols as positional args, doesn't fit the form-grammar pattern). Document this as a deliberate special case in the namespace docstring:

```clojure
(ns fukan.canvas.library.closure
  "Module closure declarations. `exports` is a Clojure macro rather than a
   `defconstructor`-built lift because it takes bare names positionally."
  (:require [fukan.canvas.helpers :as h]
            [datascript.core :as d]))

(defn- find-entity-by-name [db nm]
  (-> (d/q '[:find ?e :in $ ?n :where [?e :entity/name ?n]] db (str nm))
      ffirst))

(defmacro exports
  "Tag the listed declarations as :exported. Must appear inside `within-module`
   after the named declarations have been transacted."
  [& names]
  `(let [db# @h/*store*]
     (doseq [n# '~names]
       (when-let [eid# (find-entity-by-name db# n#)]
         (swap! h/*store*
                #(d/db-with % [{:db/id eid# :entity/tag :exported}]))))))
```

- [ ] **Step 3: Verify, Step 4: Commit**

```bash
jj desc -m "feat(canvas): exports — module closure (closes Gap 2)"
jj new
```

---

# Sprint 2 — The Architect Explorer (Tasks 5–6)

This is Phase 2's load-bearing sprint. Three independent exploration sessions are dispatched in parallel (or near-parallel), each as a fresh subagent with the architect-explorer system prompt and a distinct set of specs to port. Each session invents whatever lift vocabulary makes its assigned specs read naturally, commits the work to its own `explore_N/` namespace, and produces a reflection doc.

**Why parallel and independent.** If three sessions independently discover the same lift, that's strong evidence the abstraction is real. If they discover different lifts for the same kind of content, the differences become the synthesis conversation. Coordinated sessions would compress the evidence; uncoordinated sessions widen it.

**The system prompt is the load-bearing artifact.** `doc/plans/2026-05-25-architect-explorer-system-prompt.md`. Read it before dispatching. The prompt:
- Names the substrate without prescribing canvas vocabulary
- Invokes the Lisp / Scheme / Racket / Clojure lineage (SICP, Steele's "Growing a Language", Hickey's talks, Felleisen on linguistic reuse, Backus's Turing lecture) to activate the right mode of thinking
- Forbids Allium-mirror lifts as a goal
- Requires evidence-driven justification for every lift the session ships

If the prompt needs revision before Sprint 2, edit it in place — it's not frozen; it's the working setup.

---

## Phase 2, Task 5: Dispatch three parallel architect-explorer sessions

**Files:** created by the sessions themselves (per the file structure section above).

**Specs to assign** — three groupings, each with related modules from one fukan subsystem. Picking from rich-behavioral content (these subsystems have invariants, rules, multi-step computation, cross-module relations):

- **Session 1: `constraint/` subsystem** — assign three modules from `src/fukan/constraint/`. Recommended: `builtins`, `derivations`, `well_known`. Together they cover the constraint engine's data primitives, derivation machinery, and known-constraint surface. Rich invariants and behavioral declarations.
- **Session 2: `validation/` subsystem** — assign `phase4` plus two of the `rules_4X` files (e.g. `rules_4a`, `rules_4b`). The Phase 1 verification doc explicitly flagged structural repetition across the rules files (Gap 14); this session is well-positioned to discover whether a family / pattern lift emerges.
- **Session 3: `vocabulary/` subsystem** — assign `vocabulary/allium/pipeline`, `vocabulary/allium/expression`, `vocabulary/allium/effect_canonicalise`. These are meta-interesting (specs of the spec languages) and the largest, most complex modules in the subsystem.

(If a recommended file proves too thin or too sprawling, the dispatcher may swap with a sibling — the principle is "three subsystems × three related modules each," not the specific list.)

- [ ] **Step 1: Confirm the system prompt is current.** Read `doc/plans/2026-05-25-architect-explorer-system-prompt.md` once before dispatching. If anything reads as prescriptive or steers toward a specific vocabulary, revise.

- [ ] **Step 2: Dispatch session 1 — `constraint/`.**

  - System prompt: contents of `doc/plans/2026-05-25-architect-explorer-system-prompt.md`.
  - User prompt: identifies session number (1), names the three assigned modules with their `.allium`/`.boundary` paths, points to the Phase 1 verification + findings docs for context, and names the existing Sprint 1 lift library as the starting point. No mention of behavioral/rule/invariant vocabulary. Let the session find what it finds.
  - Subagent type: `general-purpose` with sonnet model (capable enough for design judgment; not so expensive that three parallel sessions blow the budget).

- [ ] **Step 3: Dispatch session 2 — `validation/` — in parallel** if budget allows; sequentially otherwise.

- [ ] **Step 4: Dispatch session 3 — `vocabulary/` — in parallel** with the others.

- [ ] **Step 5: Collect outputs.** Each session should commit:
  - Code in `src/fukan/canvas/library/explore_N/` and `src/fukan/canvas/pilot/explore_N/`
  - Tests in `test/fukan/canvas/explore_N/`
  - Reflection doc at `doc/plans/2026-05-25-explore-N-notes.md`
  - Each spec port as its own commit; lift additions as their own commits; reflection doc as a final commit.

- [ ] **Step 6: Verify each session reports DONE or DONE_WITH_CONCERNS.** Sessions reporting BLOCKED need triage — usually a clarifying message via SendMessage to the agent rather than re-dispatching from scratch.

- [ ] **Step 7: No squash, no merge.** Sprint 2 leaves the three exploration trees in place as siblings. Sprint 3 reads them all and synthesizes.

---

## Phase 2, Task 6: Synthesis — converge the discovered lifts

**Files:**
- Create: `doc/plans/2026-05-25-synthesis-notes.md` — the synthesis design doc
- Create: `src/fukan/canvas/library/behavioral.clj` (and possibly siblings) — the converged library, form decided by synthesis
- Modify: existing pilot files from Sprint 2 if synthesis refactors them inline (or create fresh `src/fukan/canvas/pilot/` ports for Sprint 3 Task 7)

This is the human-in-loop step. Three independent sessions have produced three vocabularies for the same kind of content. Synthesis answers: *what's common, what's divergent, and what's the converged form?*

**Process:**

- [ ] **Step 1: Synthesist subagent.** Dispatch a single subagent (capable model) with read-access to all three sessions' code and reflection docs. Task: write a synthesis report covering:
  - Lifts that appeared (semantically) in multiple sessions, even if named differently — convergent evidence.
  - Lifts unique to one session — interesting but weaker evidence; some are genuine inventions, some are accidents.
  - Tendencies vs. abstractions — did sessions reach for Allium-mirror shapes despite the system prompt? Did they reach further? Where did the lineage thinking show up?
  - Open conflicts — places where sessions disagreed in ways that don't resolve via composition. These are real design decisions.
  - A recommended converged library design with rationale.

  The synthesist's output is `doc/plans/2026-05-25-synthesis-notes.md`. It is NOT yet implemented — it's a proposal for human review.

- [ ] **Step 2: Human review.** The user reads the synthesis report and the three reflection docs. Approves, refines, or pushes back on the recommended converged design. This is a design conversation, not a rubber stamp.

- [ ] **Step 3: Implementation subagent.** Dispatch a fresh subagent with the approved converged design and the three exploration trees. Task: build the converged library at `src/fukan/canvas/library/` (real names, not `explore_N/`). Tests, of course. Document each lift with reference back to the synthesis report and the porting moments that justified it.

- [ ] **Step 4: Verify the canvas test suite passes.** Run `clj -M:test -r 'fukan\.canvas\..*-test'`. The three exploration sessions' tests should still pass (their code is in isolated namespaces). The new converged library should have full test coverage of its own.

- [ ] **Step 5: Commit each piece separately.**

```bash
jj desc -m "doc(canvas): synthesis report — convergence across explore-1/2/3 sessions"
jj new
jj desc -m "feat(canvas): converged behavioral lift library (Sprint 2 outcome)"
jj new
```

(Multiple commits if the converged library spans multiple files. One commit per coherent library file is the goal.)

---

# Sprint 3 — Validation through use (Tasks 7–8)

The converged library proved itself on the three exploration-session specs. Sprint 3 stress-tests it by re-porting those specs cleanly (using the converged library, not the per-session ones) and broader-porting onto territory the explorations didn't cover. The point is to find out whether convergence held up or whether it papered over disagreements.

---

## Phase 2, Task 7: Re-port the exploration specs using the converged library

**Files:**
- Create: `src/fukan/canvas/pilot/<module>.clj` files for each of the nine modules from Sprint 2 (three per session × three sessions) — using the converged library
- Extend: `test/fukan/canvas/pilot2_test.clj` covering all re-ported modules

Re-port each of the nine modules from Sprint 2's assignment list, this time using only the converged lift library (`src/fukan/canvas/library/`) — NOT the `explore_N/` per-session libraries. Compare each re-port to the original exploration port. Anything lost? Anything cleaner?

- [ ] **Step 1: Re-port one module at a time.** One module → one commit. Per-port commits this time (Phase 1's bundled-pilot commit was a process violation; do not repeat).

- [ ] **Step 2: For each module, note differences** in the per-port file's docstring or an inline comment:
  - Concepts that the converged library expresses *better* than the exploration session.
  - Concepts the converged library expresses *worse* — these are concrete evidence the convergence was incomplete.
  - Concepts the converged library can't express at all — the library is wrong; back to Sprint 3 design or escalate.

- [ ] **Step 3: After all nine modules re-port,** run all canvas tests. Confirm no regression.

- [ ] **Step 4: Pick one or two specs from a fukan subsystem not yet touched by Phase 1 or Sprint 2.** Candidates: `infra/model`, `web/handler`, `vocabulary/boundary/analyzer`. Broader-port them using the converged library. New territory exposes new gaps if there are any.

- [ ] **Step 5: Commits per re-ported module + per broader-port spec.**

```bash
jj desc -m "feat(canvas): re-port <module> against converged library"
jj new
# repeat per module
```

---

## Phase 2, Task 8: Pilot-port findings 2 doc

**Files:**
- Create: `doc/plans/2026-05-25-pilot-port-findings-2.md`

Mirror Phase 1's findings doc structure. One section per re-ported module + each broader port. For each: what reads better than the original exploration / Allium spec; what reads worse; new gaps surfaced.

- [ ] **Step 1: Walk each port** comparing canvas (converged-library) vs original `.allium`/`.boundary` AND vs the exploration-session port.

- [ ] **Step 2: Catalog gaps.** Classify each: addressed-by-Sprint-1-fixes, addressed-by-converged-behavioral-lifts, still-open. Still-open gaps drive Phase 3 work.

- [ ] **Step 3: Identify the most surprising finding** for the verification report (Task 9) to build around.

- [ ] **Step 4: Commit**

```bash
jj desc -m "doc(canvas): pilot port findings 2 — convergence validation"
jj new
```

---

# Sprint 4 — Verification + Phase 3 brief (Task 9)

## Phase 2, Task 9: Phase 2 verification report

**Files:**
- Create: `doc/plans/2026-05-25-phase-2-verification.md`

Same structure as Phase 1's verification report. Three sections matter most:

- [ ] **Section 1: What was attempted vs. built.** Recap Sprint 1's mechanical fixes; Sprint 2's three exploration sessions and the synthesis; Sprint 3's re-ports and broader ports.

- [ ] **Section 2: Was the Architect Explorer experiment validated?** The most consequential Phase 2 question — independent of any lift the synthesis produced.

  Specifically:
  - Did the Lisp-lineage system prompt activate genuinely different thinking, or did sessions default to Allium-mirror lifts despite it?
  - Did the three sessions converge on similar abstractions, diverge productively, or produce noise?
  - Was synthesis a tractable step, or did it require so much arbitration that it amounted to a fourth exploration?
  - What does this tell us about how to design subagent prompts for genuinely-creative work, vs. the more usual prescribed-implementation work?

  This section is part of fukan's Phase 2 verification and part of a more general finding about LLM-architect collaboration. Don't bury it.

- [ ] **Section 3: Phase 3 implications.**

  - Did the converged library cover all the behavioral and relational content the original `.allium`/`.boundary` files express? If yes, Phase 3 can plan migration. If no, what remains?
  - Phase 3 candidate scope: migration of legacy specs to canvas; retirement of `.allium`/`.boundary` analyzers; integration of canvas-substrate into the fukan graph viewer; update of vision docs (`VISION.md`, `MODEL.md`, `DESIGN.md`).
  - Open architectural questions that surfaced in Sprint 2/3 but weren't settled.

- [ ] **Section 4: Decision.**

  Three outcomes:
  1. **Converged library is sufficient** → write Phase 3 plan.
  2. **Converged library is sufficient with caveats** → another sprint of vocabulary work (Phase 2.5), then Phase 3.
  3. **The Architect Explorer pattern didn't produce a usable convergence** → bigger reset. Reconsider whether the substrate suffices, or whether something about the design process needs to change.

  Likely call: (1) or (2). The Phase 1 substrate validation makes (3) about the substrate unlikely; (3) about the *process* (parallel-independent-then-synthesize) is genuinely possible and worth being honest about.

- [ ] **Step Final: Commit**

```bash
jj desc -m "doc(canvas): phase-2 verification + phase-3 brief"
jj new
```

---

## Subsequent phases (sketches; each gets its own plan)

**Phase 3 — Migration + retirement + graph integration**: Auto-translate (or hand-port) existing `.allium`/`.boundary` files to canvas using Phase 2's converged library. Project the canvas substrate's datoms into the existing fukan graph viewer (or build a new pipeline reading directly from the canvas store). Retire `src/fukan/model/` and the `.allium`/`.boundary` analyzers once the canvas store replaces them. Update vision docs to reflect canvas-first state.

**Phase 4 — LLM ergonomics deepening**: Constraint violation diagnostics, named-entity resolution, examples library shipped with each lift. Driven by Phase 3 usage evidence.

**Phase 5 — Optional GUI authoring surface**: If/when text-based canvas authoring shows clear LLM-architect limits.

---

## Self-review notes

- **Substrate untouched.** Phase 1 validated it; Phase 2 doesn't revisit. If a Sprint 2 exploration session feels constrained by the substrate, it should escalate (escalation = evidence for or against Phase 1's verification) rather than modify a primitive on its own authority.
- **Sprint 2 is the load-bearing sprint.** The system prompt is the load-bearing artifact within it. Review the prompt carefully before dispatching sessions; revise if it reads prescriptively.
- **Independence in Sprint 2 is not coordination-avoidance — it's evidence-widening.** Sessions don't read each other's reflection docs during their own work. Synthesis (Sprint 3) is where the cross-session conversation happens.
- **Synthesis includes human review.** Task 6 Step 2 is explicit: the synthesist subagent proposes; the human approves or refines. The converged library is a human-stamped artifact, not an LLM autonomous output.
- **Per-port commits in Sprint 3.** Phase 1 collapsed pilot ports into one commit, against the per-port commit rule. Don't repeat.
- **Section 2 of the verification report (the meta-finding about the Architect Explorer pattern) matters as much as the technical Phase 3 brief.** It's evidence about a design process pattern that fukan can re-use.
- **Allium and Boundary remain readable** for analysis purposes throughout Phase 2 and Phase 3. They are not deleted; they are demoted from "design surface" to "legacy spec format the analyzer continues to read." Retirement is a Phase 3 step, not a Phase 2 step.

---

## Tracking summary

| Sprint | Tasks | Outcome |
|--------|-------|---------|
| 1 | 1–4 | Mechanical ergonomic fixes in `function`/`record`; Sprint 2 starts with a clean structural foundation |
| 2 | 5–6 | Three parallel architect-explorer sessions + synthesis; converged behavioral lift library |
| 3 | 7–8 | Re-port + broader-port + findings doc 2 — validates whether synthesis held |
| 4 | 9 | Verification report; Phase 3 brief; meta-finding on the explorer pattern |

**Estimated calendar:** Sprint 1 ≈ one session (mechanical, well-specified). Sprint 2 ≈ one dispatch wave + three parallel sessions ≈ one session of orchestration with parallel agent work. Sprint 3 synthesis ≈ one design conversation + one implementation session. Sprint 3 validation ≈ one session of porting work. Sprint 4 ≈ one session. **Total: 5–7 working sessions.**
