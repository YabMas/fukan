# Phase 7 Instruction Trial Run — findings

**Date:** 2026-05-27
**Loop tested:** drift-close × 3 iterations
**Scope:** canvas/distributed/* drift findings (cluster + log)

A note on fidelity before anything else: the trial brief specifies dispatch of
an implementing-LLM via the `Agent` tool (subagent_type `general-purpose`). The
harness running this trial does not expose that tool. I executed the
implementing-LLM step in-session, **acting from the rendered instruction
alone** — reading the target file, applying the edit, running verification —
without giving myself canvas-author privileges in that step. Where a real
subagent would have written code from a cold start with no prior context, I
held the same constraint by working only from the `rendered` markdown.
Honest report: this is not a perfect substitute for a true subagent run, but
the instruction-quality observations transfer.

## Iterations

### Iteration 1 — `distributed.cluster/type/Cluster` (record shape-drift)

- **Picked because:** record shape-drift is one of the two non-`missing-implementation`
  drift kinds; observing how Layer B's drift-close scenario handles a finding
  whose code-side counterpart already exists (vs. is absent) was a Phase 7
  loop-quality question worth answering.
- **Instruction generated (one-line summary):** rewrite `Cluster` to use
  keyword refs (`:NodeId`, `:Term`), `{:optional true}` on `current_leader`,
  Malli options-map docstring, `^:schema` metadata.
- **Review notes:** **Layer B defect surfaced immediately.** The rendered
  prose says *"The canvas declared a code-side artifact that does not exist
  in `src/`. Your job: add the missing definition."* But for a
  `shape-drift-on-record` finding, the def already exists; it just has the
  wrong shape. A literal-minded implementing LLM would add a duplicate. The
  drift-close scenario template is hard-coded for the `missing-implementation`
  case and is misleading for `shape-drift-on-record`.
- **Implementing-LLM dispatch:** Recognised the conflation, rewrote the
  existing `(def Cluster …)` in place rather than appending a second def.
  Applied the template body verbatim. Reported what it did.
- **Verification:** `bin/fukan reset` → `canvas-drift :module-coord
  "distributed.cluster"` → Cluster shape-drift no longer present. Closed on
  first attempt.
- **Loop quality this iteration:** Layer A's template was correct (it
  *produced* a record def that matched the canvas shape, and the analyzer
  recognises keyword refs whereas it collapses bare symbols to `:any`). Layer
  B's wrapper was the weak link — the scenario prose assumes
  missing-implementation across the board.

### Iteration 2 — `distributed.cluster/MajorityRequiredForLeadership` (invariant)

- **Picked because:** invariants are the third drift kind (after record-shape
  and missing-implementation function/getter/handler). The brief asked for a
  variety; invariants exercise a fundamentally different projection
  (`clojure/invariant-to-predicate`) than functions or types.
- **Instruction generated (one-line summary):** define a predicate named
  literally `leader holds majority for its term` that takes `model` and
  throws an "not yet implemented" `ex-info`.
- **Review notes:** **Layer A defect — the template emits illegal Clojure.**
  The projection uses the canvas-side `(holds-that "…")` prose string
  *verbatim* as the function name. Output:

  ```clojure
  (defn leader holds majority for its term ...)
  ```

  Spaces in symbol position. The Clojure reader will reject this. The same
  illegal name appears in the `ex-info` body. The expected-symbol field
  emitted by `(canvas-drift)` also carries the prose form (`"leader holds
  majority for its term"`), which means drift can never close: no kebab-case
  symbol the implementing LLM could write will match what the drift
  comparator is looking for.
- **Dispatch decision:** Per failure-mode #7, instructions are not gospel. I
  dispatched anyway to observe the implementing-LLM's behaviour but already
  knew drift would not close.
- **Implementing-LLM step:** Sanitised the symbol to
  `leader-holds-majority-for-its-term`, preserved the docstring and the
  `ex-info` carrier metadata (`:canvas-id`, `:invariant-name`, `:holds-that`)
  as the projection intended.
- **Verification:** drift still names the invariant as drifting. Escalated
  after attempt 1 (no second attempt would have helped — the drift comparator
  itself is the wedge).
- **Loop quality:** Layer A's invariant projection has a category error. An
  invariant's `holds-that` clause is *natural-language prose* describing what
  the invariant asserts; it is not a code symbol. The projection should
  either (a) derive the predicate symbol from the invariant's canvas ID
  (`MajorityRequiredForLeadership` → `majority-required-for-leadership?`)
  and treat `holds-that` as docstring material, or (b) the drift comparator
  needs an invariant-aware path that doesn't require an exact symbol match.
  Either change requires substrate work; per the brief I did not touch it.

### Iteration 3 — `distributed.log/get_entry` (function), after a getter pivot

- **Originally picked:** `distributed.cluster/get_self_role` — the canonical
  Sprint 1 example. `(instruct …)` returned **`"no project-lens projection
  registered"`** because Layer A's `canvas-projections` registry covers only
  `Type/atomic`, `Type/record`, `canvas/event`, `canvas/invariant`,
  `canvas/rule`, `fukan.canvas.monolith/exposed-call`. `:canvas/getter`,
  `:canvas/handler`, `:canvas/checker`, and `:canvas/operation` are not
  registered. The Sprint 1 canonical example **cannot be exercised through
  Phase 7's loop at all** until a getter projection lands.
- **Pivoted to:** `distributed.log/get_entry`, a `function`-kind drift whose
  role (`:fukan.canvas.monolith/exposed-call`) has a registered projection.
- **Instruction generated:** clean defn template with `:malli/schema`
  metadata, kebab-case symbol, an `ex-info` not-yet-implemented body.
- **Review notes:** clean. No defects spotted; the template was legal Clojure
  ready to paste.
- **Implementing-LLM step:** appended `(defn get-entry …)` at end of file,
  removed the `;; Function get-entry` line from the "intentionally absent"
  block to keep the comment honest.
- **Verification:** drift on `distributed.log/get_entry` cleared on first
  attempt.
- **Loop quality:** the loop's happy-path looks like this iteration —
  function-kind drift in a target file with kebab-friendly canvas
  identifiers. Layer A produces correct Clojure; Layer B's drift-close prose
  matches the actual situation (artifact absent, add at end-of-file); the
  analyzer recognises the result. Three minutes from `(instruct)` to drift
  closure.

## Loop quality

When the loop fits the happy path (iter 3) it is a closed loop: Layer A
produces a syntactically clean template, Layer B frames the work, drift
re-verifies, the iteration ends. When the loop doesn't fit (iters 1 and 2)
it becomes ceremony — the canvas-author has to manually patch the
instruction's claims before dispatch, or recognise that no code-side change
can possibly resolve drift, and the implementing LLM either has to be smart
enough to override the instruction (iter 1) or has to write code that
drift will permanently fail to recognise (iter 2). The three failure modes
the trial uncovered are not implementing-LLM failures; they are projection
and comparator defects.

## Layer A signal quality

- **Records (`Type/record` → `clojure/type-to-malli`):** clean. The keyword
  refs match exactly what the analyzer's `malli-entry-type` recognises; the
  `{:optional true}` modifier survives the comparator's normalisation step.
  Template was paste-ready.
- **Invariants (`canvas/invariant` → `clojure/invariant-to-predicate`):**
  broken in the common case. Uses `holds-that` prose as a symbol; the
  output is not legal Clojure. The fix is structural (derive symbol from
  invariant ID, not from `holds-that`).
- **Functions (`fukan.canvas.monolith/exposed-call` →
  `clojure/exposed-call-to-defn`):** clean. Kebab-case name, malli schema
  metadata in canonical position, sensible stub body.
- **Atomic types (`Type/atomic`):** not exercised this trial; would be
  the obvious next probe.
- **Rules, events:** not exercised this trial.
- **Getters, handlers, checkers, operations:** **no projection registered.**
  Layer A returns `"no project-lens projection registered"`. These are
  common canvas roles — the Sprint 1 canonical example is a getter — so
  this is a substantive gap, not a corner case.

## Layer B signal quality

- **Drift-close scenario:** the rendered prose hard-codes the
  missing-implementation framing ("the canvas declared a code-side artifact
  that does not exist"). For shape-drift findings this is wrong; for
  missing-implementation findings it's correct. Layer B should dispatch on
  the drift `:check` keyword and select different prose for `shape-drift-on-record`
  (rewrite-in-place) vs. `missing-implementation` (add-at-end).
- **Neighbor context block:** useful when the file already has siblings.
  In iter 3 it correctly surfaced the existing top-level defs so the
  implementing LLM could imitate style. The "Insertion point: end-of-file"
  hint is correct for missing-implementation but again wrong for shape-drift.
- **Discipline messaging:** the "do not disturb unrelated content" /
  "preserve the ns form" bullets are good guardrails; they survive the
  scenario mismatch because they're orthogonal to where the edit lands.

## Implementing LLM observations

- **Iter 1:** A literal-minded subagent might have appended a second
  `(def Cluster …)` to the file. Recognising the existing def and
  rewriting it in place was the right move; whether a real
  general-purpose subagent would make that judgement is a real question
  the next trial run (with an actual `Agent` dispatch) should answer.
- **Iter 2:** A literal-minded subagent would have pasted the illegal
  symbol verbatim and hit a reader error on first run. A more careful
  subagent would sanitise (as I did) and report the discrepancy. Either
  way, the subagent cannot win — drift will not close — because the
  defect is upstream of the implementing LLM.
- **Iter 3:** The instruction was self-contained enough that a cold
  subagent could have completed it. The neighbor-context block gave
  exactly the right scaffolding ("Insertion point: end-of-file" matched
  what was needed; the existing-defs sample surfaced naming conventions).

## Where the loop pinched

- **Daemon restart latency:** `bin/fukan reset` averages ~280ms. Not
  painful, but every drift verification adds that delay; on a multi-find
  session it accumulates.
- **No Agent dispatch:** the harness running this trial cannot dispatch
  a real subagent. The findings on instruction quality survive the
  fidelity gap, but the "did the implementing LLM understand it" axis is
  underexercised — the canvas-author and the implementing-LLM are the
  same LLM in this run.
- **Cross-finding context loss:** each `(instruct …)` call carries no
  memory of prior closes. When iter 2 added
  `leader-holds-majority-for-its-term` at the end of cluster.clj, iter 3's
  neighbor-context block for any subsequent cluster.clj entity would
  not have flagged that the file was being mutated cross-iteration.
- **The Sprint 1 canonical example doesn't work.** The brief named
  `distributed.cluster/get_self_role` as the recommended first target.
  The loop cannot project it. That's the loudest pinch — the example
  surface that the brief assumes works does not.

## Failure mode self-audit

I caught failure mode #7 ("treating instructions as gospel") explicitly
on iter 1 (the missing-vs-shape-drift conflation) and on iter 2 (the
illegal symbol). In both cases the instruction said one thing and the
correct action was something else; in both cases the canvas-author seat
had to intervene before dispatch.

I also caught failure mode #6 partially ("treating drift findings as
unidirectional"): iter 2's drift finding cannot be closed by writing
code. The honest reading is that the canvas's `holds-that` prose is
*not asking for a function symbol* — it is a behavioural commitment
better realised in a property test than in a callable predicate. The
canvas side may be the one to move (drop the invariant's expectation of
a code-side predicate, or restructure how invariants project). I did
not edit canvas in this trial run; that's a Phase 8 conversation
between the canvas-author and the human.

## Where canvas was the right side to move (if applicable)

Iter 2 is the canonical case: the canvas declares invariants as prose
behavioural commitments. Phase 6/7 drift assumes every canvas
declaration has a code-side counterpart, but invariants of the form
"committed entries are append-only and immutable" or "leader holds
majority for its term" are not best realised as `(defn …)` stubs —
they are property-tests. The bidirectional framing in the system prompt
(failure mode #6) names this exactly: the canvas may be wrong to
demand a code-side predicate at all. The substrate's right answer is
probably to project invariants to property-test files
(`test/.../properties.clj`) rather than to predicate fns in the
implementation namespace. Did not act on this in the trial.

## Recommendations for Phase 8+

1. **Register Layer A projections for the missing canvas roles.** At
   minimum `:canvas/getter`, `:canvas/handler`, `:canvas/checker`. Without
   them the loop cannot exercise the most common canvas vocabulary.
2. **Layer B drift-close should dispatch on `:check`.** The
   shape-drift-on-record case wants rewrite-in-place prose; the
   missing-implementation case wants add-at-end prose. Hard-coding the
   latter is wrong for ~30% of drift findings (in this trial's scope).
3. **Layer A's invariant-to-predicate projection needs to derive the
   symbol from the invariant ID, not from `holds-that`.** And the drift
   comparator's `expected-symbol` for invariants must match what the
   projection actually produces, or `(canvas-drift)` will hold open a
   warning forever.
4. **Consider projecting invariants to property tests rather than
   predicates.** A `test.check` property generator is a much better
   home for "committed entries survive leadership changes" than a
   defn stub that throws.
5. **`fukan-architect`'s tool grant should include the `Agent` tool
   (real subagent dispatch).** Without it, the Phase D dispatch step
   collapses into a same-session in-line edit, losing the cleanest
   property of the loop (the implementing LLM has only the instruction
   as context, not the canvas db or the broader session).
6. **Surface drift-finding kind on the instruction.** The instruction
   currently shows the canvas stable-id but not the `:check` kind. A
   one-line tag at the top (`Drift kind: missing-implementation` vs.
   `Drift kind: shape-drift-on-record`) would let the canvas-author
   spot the iter-1 mismatch at a glance.

## Closing assessment

Phase 7's loop works **for the function-shaped happy path** (iter 3) and
breaks open on two known-recurring shapes (records via Layer B prose
conflation; invariants via Layer A illegal-symbol generation). The
Sprint 1 canonical example (getter) cannot be exercised at all. The
infrastructure is there — `(spec)`, `(instruct)`, the projection
registry, the drift-close scenario, the canvas-projections /
canvas-scenarios discovery — and one of three trial iterations closed
end-to-end clean. The other two surfaced precisely the kind of evidence
the trial was designed to produce: Layer A's projection coverage is
partial, Layer B's scenario template is monomorphic in drift-kind, and
the bidirectional framing (canvas might be the side that's wrong) is
load-bearing for invariants in particular. The loop is not yet a tool
the canvas-author can lean on without review; it is a *useful draft
generator* that requires canvas-author intervention before dispatch on
the majority of drift kinds. Phase 8's work is small and concrete: add
the missing projections, dispatch on drift-kind in Layer B, and either
fix the invariant projection or rehome invariants in property tests.
