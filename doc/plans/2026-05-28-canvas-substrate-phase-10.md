# Phase 10 — Bidirectional drift: canvas-side actions

**Date:** 2026-05-28
**Status:** Drafted

Phase 6 closed drift *detection*. Phase 7 closed *code-side instruction*.
Phase 8 closed *code-side dispatch*. Phase 9 metabolised the harness
constraint into the 2-seat handoff protocol. Across all four phases,
drift has been one-directional: fukan describes a code-side artifact
that's missing or wrong, the implementing-LLM writes the code.

But Phase 7's trial findings raised — and Phase 8's hint heuristics
confirmed — that drift findings are *sometimes canvas-side*. A canvas
declaration may be over-eager (an invariant whose property-test
implementing-LLMs can't write because the canvas itself hasn't
specified enough); a record's fields may be ahead of the implementation
because the canvas-author added them speculatively; a cross-module
reference may point at a declaration that's moved. The closure
controller has no way to *act* on the canvas side today — Phase 8's
hints surface signal; Phase 10 builds action.

This is the bidirectional move. The canvas becomes the load-bearing
design surface in both directions: drift findings can resolve via
canvas edits, not just code edits, and the closure controller knows
the difference.

## Strategic frame

The trajectory holds — canvas-author writes design; fukan tells the
implementing-LLM what to write; canvas as the intersection between
LLM and human. Phase 10 expands the canvas-author seat: instead of
asking "which code edit would close this drift?", fukan asks "which
*side* should move?" — and proposes the action accordingly.

Critical design choice: **canvas-side actions remain canvas-author-
approved**. Code-side closures happen automatically through dispatch
because code is implementation. Canvas-side closures propose, the
canvas-author accepts/rejects/edits. The canvas is the design — design
changes pass through the human, not around them.

Phase 10 ships:
- New Layer B scenarios in the `:canvas-side/*` namespace (drop-declaration,
  restructure-record, cross-module-realign)
- A canvas-side dispatcher protocol: closure controller proposes canvas
  edits; canvas-author approves; approved edits dispatch via a
  general-purpose subagent with a canvas-aware instruction template
- Drift comparator awareness: canvas-side closure (declaration removed,
  shape updated) recognised as drift resolution
- The Phase 8 deferred heuristic (c) — cross-module reference recency —
  finally lands as the heuristic-driving scenario `:canvas-side/cross-module-realign`
- A small batch of Phase 9 carry-forward closures bundled in (name+role
  prompt note; opt-in producer-side `(projects-to :predicate)` for the
  cases where bidirectional drift wants to retreat from property-test
  projection)

Plus the loop-quality verification trial — Phase 10's Sprint 6 runs the
new bidirectional flow against the carry-forward findings from prior
trials (the rule+invariant pair from Phase 9 Sprint 3 is the canonical
target).

## Out of scope

- **Multi-language lens trial.** A second project-lens (Python/TypeScript)
  waits. Phase 10's substrate work on bidirectional scenarios is
  language-neutral, but the trial-fidelity exercise stays single-lens.
- **Richer canvas-author dialogue surface.** "Talk back to the loop" /
  mid-flight conversation capability remains a Phase 11+ candidate.
  Phase 10's dialogue stays within the established 2-seat protocol —
  canvas-author + architect + implementing-LLM (now plus canvas-editor).
- **Browser UI / explorer.** Indefinitely deferred per standing instruction.
- **Refactor scenario.** Still deferred; the new bidirectional scenarios
  cover specific drift shapes that the refactor scenario would
  generalise. Phase 11+ candidate.
- **Cold-write extensions for canvas-side.** Cold-write currently writes
  code from scratch; the inverse (drafting a fresh canvas declaration
  from a code-side artifact) is a Phase 12+ idea, not Phase 10.

## Sprint plan

Seven sprints. Two design + four working + verification.

### Sprint 1 — Bidirectional drift scenarios + dispatcher protocol design

Two design docs paused for user review before code lands.

**Task 1 — Bidirectional drift scenarios design.** Output:
`doc/plans/2026-05-28-bidirectional-drift-design.md`. Covers:

- **Why bidirectional now.** The framing question — when does drift
  resolve canvas-side vs code-side? Synthesise the trial-finding
  observations from Phase 7 (invariant illegal-symbol → canvas was
  unclear) + Phase 8 Sprint 6 (predicate stubs orphaned by property-test
  migration) + Phase 9 Sprint 3 (rule+invariant pair leaves implicit
  dual findings).
- **Three new scenario kinds.** Each gets a section: what triggers it,
  what the instruction renders, what closure looks like, what the
  drift comparator needs to recognise as resolution.
  - **`:canvas-side/drop-declaration`** — propose deleting a canvas
    entity. Fires when: invariant has been stubbed-and-failed across
    iterations (Phase 8 heuristic a, now actionable); affordance has
    no consumer canvas-side and no implementer code-side; canvas-author
    has flagged "this was speculative" via metadata or by hand.
  - **`:canvas-side/restructure-record`** — propose updating a record's
    shape. Fires when: shape-drift where canvas adds fields but src has
    been stable >1 week (Phase 8 heuristic b, now actionable); type
    references diverge between canvas and code.
  - **`:canvas-side/cross-module-realign`** — propose realigning a
    cross-module reference. Fires when: referenced canvas declaration
    was edited recently but src hasn't followed (Phase 8 heuristic c,
    new in Phase 10).
- **Naming convention.** `:code-side/drift-close` + `:code-side/cold-write`
  use the `:code-side/*` namespace today; `:canvas-side/*` is the
  parallel. Sprint 1 picks final keyword names.
- **Closure recognition.** Drift comparator currently sees a finding as
  closed when the expected code-side artifact appears. Canvas-side
  closure inverts: the finding closes when the canvas declaration is
  *removed* or *changed* such that the canvas-side stable-id no longer
  generates the drift edge. The comparator needs to handle this without
  emitting false "missing implementation" findings during the canvas
  rebuild.
- **Approval semantics.** Code-side closures dispatch automatically
  through the controller; canvas-side closures require canvas-author
  approval before dispatch. The handoff package surfaces the proposal
  as advisory; the canvas-author has to explicitly say "dispatch
  finding X canvas-side" before any canvas edit happens.

**Task 2 — Canvas-side dispatcher protocol design.** Output:
`doc/plans/2026-05-28-canvas-side-dispatcher-design.md`. Covers:

- **The dispatcher.** Who edits canvas? Three candidates: (i) the
  human canvas-author edits by hand from the architect's proposal;
  (ii) a general-purpose subagent with a canvas-aware instruction
  template; (iii) a new `fukan-canvas-editor` agent type. Recommend
  one with reasons.
- **Instruction template.** Canvas-edit instructions differ from
  code-edit instructions — they target `canvas/**/*.clj` files using
  the canvas vocab (`(function …)`, `(invariant …)`, `(record …)`,
  `(exports …)` etc.). The template explains the canvas vocab,
  references CLAUDE.md's "Canvas vocabulary catalog", and discipline
  about not over-editing.
- **Verify step for canvas-side closures.** When a canvas edit lands,
  the drift finding closes by *disappearing* from `(canvas-drift)`
  output. Sprint 1 specifies the post-canvas-edit reset + verify
  flow.
- **Same-canvas-file batching.** Like same-file code edits, multiple
  canvas-side actions on the same canvas file need serial dispatch +
  re-render between dispatches.
- **Approval handoff shape.** The architect's handoff package adds a
  canvas-side proposals section. Canvas-author marks proposals as
  approved/rejected; approved set re-flows into a second dispatch
  cycle.

Both docs pause for user review before Sprints 2–6 begin. Maintain the
amendment cycle that's been load-bearing across Phases 5–9.

### Sprint 2 — Layer B canvas-side scenarios substrate

Three new scenarios under `src/fukan/canvas/instruct/canvas_side/*.clj`.

**Task 3 — `:canvas-side/drop-declaration` scenario.** Implement per
Sprint 1's design. The scenario's `build-context` reads the canvas
declaration's full surface — the entity-name, holds-that (for
invariants), referenced consumers — so the proposal instruction can
spell out "drop this declaration; here's everything it references
and is referenced by". Layer B registry update; coverage regression
test extension.

**Task 4 — `:canvas-side/restructure-record` scenario.** Implement per
Sprint 1's design. The scenario's `build-context` carries both sides
of the shape divergence — canvas-declared fields, code-side fields,
the delta — so the proposal can recommend which side to favour.
Re-uses Phase 7.5 Sprint 3's compound-shape comparator output.

**Task 5 — `:canvas-side/cross-module-realign` scenario.** Implement
per Sprint 1's design. New territory — the heuristic identifies
referenced declarations that have moved; the scenario proposes
updating the canvas-side reference. Requires canvas-db introspection
to find cross-module reference shapes. May surface additional
substrate needs (a `:references` index in the canvas-db); document
in Sprint 1's design.

Sprint 2 is substrate-heavy but mechanical — three scenarios mirror
each other in shape, each follows the established Layer B convention
from Phase 7.

### Sprint 3 — Drift comparator + dispatch backbone for canvas-side

The closure controller learns to route findings by side.

**Task 6 — Drift comparator canvas-side awareness.** Extend
`src/fukan/canvas/inspect/drift.clj`'s finding emission. Each finding
gains a `:resolution-side :code | :canvas | :ambiguous` field derived
from the Phase 8 hint heuristics. The controller routes findings
accordingly:
- `:code` (default) → code-side scenarios (drift-close, cold-write)
- `:canvas` → canvas-side scenarios (drop, restructure, realign)
- `:ambiguous` → both sides proposed in the handoff; canvas-author
  decides

Phase 8's three canvas-side heuristics (a/b/c — invariant
stub-and-fail, record shape-drift recency, cross-module reference
recency) now feed the resolution-side classification. Sprint 3
finally lands heuristic (c) (Phase 8 deferral, Phase 9 carry-forward).

**Task 7 — `close-drift-plan` canvas-side scope.** Extend the plan
return shape:

```clojure
{:plan-code-side    [...]   ; existing — code-side findings
 :plan-canvas-side  [...]   ; NEW — canvas-side findings; advisory
 :plan-ambiguous    [...]   ; NEW — both-side proposals
 :batches           ...
 :counts            {...}}
```

Canvas-side and ambiguous entries are advisory — the canvas-author
reviews and decides which to dispatch.

**Task 8 — `close-drift-verify` canvas-side closure recognition.**
Verify needs to recognise canvas-side closure (finding disappears
because canvas changed, not because code was added). Update the
classification path to handle the inverted closure shape. Per-finding
outcome gains a `:closed-side :code | :canvas` field so the report
can summarise where each closure landed.

Plus the **Phase 9 carry-forward closures** that fit here:
- Name+role disambiguation prompt note (canvas-authoring system
  prompt): one-line addition explaining the dual-finding implication.
- `(projects-to :predicate)` producer-side: now load-bearing because
  bidirectional drift may want to retreat from property-test back to
  predicate projection for invariants whose canvas-side action is
  "stay-as-stub". Extend `canvas/vocab/behavioral.clj`'s `(invariant
  …)` lift to accept `(projects-to :predicate)` clause; thread through
  canvas-source.

### Sprint 4 — Architect handoff protocol extension + canvas-editor agent

The 2-seat collaboration extends to a 3-seat: architect plans, canvas-
author dispatches (or approves canvas-side), implementing-LLM does
code edits, canvas-editor does canvas edits.

**Task 9 — Architect handoff package extension.** The handoff package
adds a canvas-side proposals section. Each proposal carries the
rendered canvas-edit instruction + the resolution-side classification
+ a brief rationale (which heuristic fired, what the canvas-author
should consider). The canvas-author reads, marks approved/rejected,
re-dispatches the architect with the approved set.

**Task 10 — Canvas-editor dispatch.** Sprint 1's Task 2 picks
between human canvas-edit, general-purpose subagent, or new agent
type. Sprint 4 implements that pick. If the call is for a new agent
type:
- New `.claude/agents/fukan-canvas-editor.md` agent definition
- Tools: Edit, Write, Read, Bash for verification
- Canvas-aware instruction template lives in the architect's handoff
  package (per `:canvas-side/*` scenario)

If general-purpose: just add the canvas-aware instruction template
to the scenarios; canvas-author dispatches with `subagent_type:
general-purpose`.

**Task 11 — Approval handoff cycle.** The architect's flow extends:

1. Canvas-author asks architect "plan close-drift for X"
2. Architect returns handoff with code-side dispatch instructions +
   canvas-side proposals (advisory)
3. Canvas-author dispatches code-side implementing-LLMs immediately
4. Canvas-author reviews canvas-side proposals; marks approved/rejected
5. Canvas-author re-dispatches architect: "dispatch approved canvas-side
   actions: [list]"
6. Architect produces a second handoff package with just the approved
   canvas-side instructions
7. Canvas-author dispatches the canvas-editor (or edits by hand) per
   approval
8. Canvas-author calls verify with full report set

Document the flow in the architect prompt + canvas-authoring system
prompt.

### Sprint 5 — Canvas-side trial run

The empirical test of the bidirectional flow. Same pattern as Phase 8
Sprint 6 + Phase 9 Sprint 3 — canvas-author drives from top-level
session.

**Task 12 — Trial against the canonical carry-forward cases.** Three
canvas-side proposals are pre-known from Phase 7+8+9 trial findings:

(i) The `ExposesPathsResolve` invariant-pair half (Phase 9 Sprint 3
bonus finding) — implicit dual drift on a rule+invariant
declaration. Canvas-side action: either close the property-test
half (code-side, already exercised) OR drop the invariant
declaration if the rule's reactive shape is sufficient.

(ii) A speculative invariant whose property-test repeatedly fails
(synthesise one if no real case exists by Sprint 5) — canvas-side
action: drop the invariant.

(iii) A cross-module reference where the referenced declaration has
moved (synthesise via canvas edit if no real case exists) —
canvas-side action: realign the reference.

For each: render via the new scenarios, surface as a canvas-side
proposal in the architect handoff, walk through canvas-author
approval, dispatch canvas-editor, verify closure.

**Task 13 — Findings doc.**
`doc/plans/2026-05-28-bidirectional-drift-trial-findings.md`.
Same structure as Phase 8 Sprint 6 + Phase 9 Sprint 3 — per-iteration
outcome, instruction quality, defects surfaced, recommendations.

### Sprint 6 — Carry-forward cleanup + canvas-side hint refinement

The hygiene sprint, smaller than Phase 9 because Phase 10 mostly
shipped new mechanism.

**Task 14 — `:projection-emits-warning` source.** Phase 8 reserved
the trigger; Phase 9 left it reserved. Phase 10 may have surfaced a
real cause — canvas-side scenarios that "skip" or "warn" (e.g.
proposing a drop on a declaration with downstream consumers should
emit a warning that closing this drift will produce new drift
elsewhere). Decide: implement a Layer B warning surface for
canvas-side scenarios, OR leave reserved with documented future case.

**Task 15 — Approval-cycle edge cases.** Sprint 5's trial likely
surfaces handling for: canvas-author approves a subset of canvas-side
proposals; canvas-author rejects all; canvas-author requests
re-analysis with different hint thresholds. Document in the architect
prompt how to handle each case cleanly.

**Task 16 — Documentation pass.** Update `CLAUDE.md` and `AGENTS.md`
with bidirectional-drift framings; ensure the canvas-authoring system
prompt covers the full 3-seat protocol; spot-check the architect
prompt for consistency.

### Sprint 7 — Verification

Mirrors prior verification doc patterns.
`doc/plans/2026-05-28-phase-10-verification.md`. Covers:

- What was attempted vs. built across Sprints 1–6
- Bidirectional scenarios shipped + exercised; closure-rate per scenario
- The 3-seat protocol (architect + canvas-author + canvas-editor) —
  did it work?
- Approval-cycle UX from the canvas-author seat — was it usable?
- Defects surfaced + carried forward
- Decision (outcome 1/2/3)
- Phase 11 implications

Likely outcome 2 (works with caveats) — new substrate always carries
small surprises. Possibly outcome 1 if Phase 10 lands as tightly as
Phase 9.

## Definition of done

- Three `:canvas-side/*` scenarios shipped under
  `src/fukan/canvas/instruct/canvas_side/*.clj`.
- Drift comparator emits `:resolution-side` classification per finding;
  closure controller routes accordingly.
- `close-drift-plan` returns separated code-side / canvas-side /
  ambiguous plan vectors.
- `close-drift-verify` recognises canvas-side closure.
- Architect handoff package extended with canvas-side proposals section
  + approval cycle.
- Canvas-editor dispatch shipped (per Sprint 1's choice).
- Phase 9 carry-forward closures landed: name+role prompt note,
  `(projects-to :predicate)` producer-side, `:projection-emits-warning`
  classification.
- Sprint 5's trial closed all three canonical canvas-side cases (one
  drop, one restructure, one cross-module realign).
- Sprint 7 verification doc lands; decision is outcome 1 or 2 with
  named Phase 11 implications.

## Carried-forward concerns from Phase 9 that Phase 10 addresses

| Concern | Phase 10 sprint |
|---|---|
| Name+role disambiguation creates implicit dual drift findings | Sprint 3 (prompt note + Sprint 5 trial Case i) |
| `(projects-to :predicate)` producer-side | Sprint 3 |
| `:projection-emits-warning` source | Sprint 6 Task 14 |
| Canvas-side hint heuristic (c) cross-module reference recency | Sprint 2 Task 5 + Sprint 3 Task 6 |
| Cross-module + multi-language lens trial | NOT addressed — Phase 11+ candidate |
| Richer canvas-author dialogue surface | NOT addressed — Phase 11+ candidate |

The multi-language and dialogue-surface framings stay deferred because
each pairs naturally with its own phase. Phase 10's bidirectional move
is one trajectory; multi-language is generality validation; dialogue is
canvas-author seat enhancement. Three independent directions.

## Phase 10 sizing

Substantial — 7 sprints comparable to Phase 8. Two design + four working
+ verification. New substrate (3 scenarios + dispatcher + classification)
plus carry-forward closures plus trial.

If Phase 10 wants to ship faster, the optional cuts:
- **Defer `:canvas-side/cross-module-realign`** to Phase 11 — the
  heuristic is implementable but the cross-module substrate may want
  its own pairing with multi-language exercises.
- **Defer the new agent type** if Sprint 1 picks general-purpose
  canvas-edit — saves Sprint 4 a substantial task.

These shrink Phase 10 to ~5 sprints. Trade-off: less complete
bidirectional coverage at ship.
