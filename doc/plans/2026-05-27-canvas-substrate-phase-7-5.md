# Phase 7.5 — instruction-loop cleanup

**Goal.** Close the three substantive defects the Phase 7 Sprint 4 trial run
surfaced, so that `(instruct …)` produces decision-ready output on the
*common* drift kinds — not only the function-shaped happy path. After 7.5
ships, Sprint 5 (verification) runs over Phase 7 + 7.5 as one body of work.

Mirrors the Phase 5 → 5.5 → verification cadence: clean the friction
before rubber-stamping.

## Defects to close

The trial doc (`doc/plans/2026-05-27-instruction-trial-run-findings.md`)
captured these in detail. Recap:

1. **Layer A coverage gap.** `(spec)` against `:canvas/getter`,
   `:canvas/handler`, `:canvas/checker`, `:canvas/operation` returns
   `"no project-lens projection registered"`. The Sprint 1 canonical
   example (`distributed.cluster/get_self_role`, a getter) cannot be
   exercised through the loop today.
2. **Layer A invariant projection emits illegal Clojure.** The symbol is
   derived from the `holds-that` prose string verbatim. `(defn leader
   holds majority for its term [_model] …)` is not a legal symbol; the
   drift comparator's `expected-symbol` carries the same illegal form,
   so no code can ever close an invariant drift finding.
3. **Layer B drift-close is monomorphic on drift-kind.** The rendered
   prose hard-codes the missing-implementation framing ("artifact does
   not exist; add at end of file"). Wrong for `shape-drift-on-record`
   findings, which need rewrite-in-place prose.

These three are independent (touching `project/clojure/*`,
`canvas-source` + `address`, and `instruct/drift_close.clj`
respectively). Dispatch sequentially to keep substrate edits clean.

## Sprints

### Sprint 1 — Layer A dispatch-key audit + coverage fill

Audit every `:affordance/role` and `:Type/*` discriminator emitted by
`canvas-source` against the projection registry. For each role with no
`defmethod project [:clojure <role>]`, decide whether to register a new
projection or document why it is intentionally absent.

Concrete known gaps from the trial:
- `:canvas/getter`
- `:canvas/handler`
- `:canvas/checker`
- `:canvas/operation` (status unclear — iter 3's `get_entry` succeeded
  via `:fukan.canvas.monolith/exposed-call`; verify operations bound by
  monolith-mode produce the same role)

Approach:
1. Enumerate the actual `:canvas-role` values that `canvas-source`'s
   `affordance-kind` emits for affordance kinds. Cross-reference against
   `(canvas-projections)` registry output.
2. For each unfulfilled role, either:
   - Register a Clojure-lens projection under
     `src/fukan/canvas/project/clojure/<role>_to_<idiom>.clj`. Getters
     and handlers are syntactically close to `function-to-defn` —
     reuse its template machinery; differ on Malli signature
     (zero-arg for getters; event-payload-typed for handlers).
   - Or document the role as intentionally unprojected with a comment
     in `project/clojure.clj`.
3. Add a `(canvas-projection-coverage)` agent-api helper or a project
   test that asserts every canvas-source-emitted role has either a
   registered projection or a documented exemption — so this regression
   doesn't reopen.
4. Re-run the Sprint 1 canonical example end-to-end: `(spec
   "distributed.cluster/get_self_role")` should return a valid
   projection through the loop.

**Files touched:** `src/fukan/canvas/project/clojure/*` (new),
`src/fukan/canvas/project/clojure.clj` (loader requires), agent api or
test for the coverage assertion.

### Sprint 2 — Invariant symbol sanitization (both sides)

Both the Layer A invariant projection and the analyzer-fed drift
comparator currently agree on the illegal `"leader holds majority for
its term"` symbol form, because both derive from the same primitive
`:label` set in `canvas-source/project-affordances` (lines 503-515).

Fix structurally — change the primitive `:label` for invariants from
the `holds-that` formal-expression to the invariant's `entity-name`
(PascalCase, like `MajorityRequiredForLeadership`). Then `addr/canonical`
emits `majority-required-for-leadership` on both sides.

Approach:
1. Change `canvas-source/project-affordances`: drop the
   `holds-that`-as-label branch; use `name` (entity-name) for all
   affordance kinds uniformly. Surface `holds-that` in a separate
   primitive field (`:formal-expression` or similar) so Layer A
   still has access to it for docstring purposes.
2. Update Layer A's `invariant-to-predicate`: it already reads
   `:holds-that` from the element map (built by `affordance-element`);
   that path is unaffected. Drop the `:invariant-name` opts fallback
   in the `addr/canonical` call — the primary path now uses
   `entity-name` directly.
3. Verify the analyzer's rules selector (Phase 5/6 path that emits
   `:absent` projection edges for invariants) still produces edges
   whose `:to.id` aligns with the new `majority-required-for-leadership`
   convention.
4. End-to-end check: dispatch `(instruct
   "distributed.cluster/MajorityRequiredForLeadership" :code-side/drift-close)`,
   apply the rendered template verbatim, run `(canvas-drift)`, confirm
   the invariant finding closes.

**Files touched:** `src/fukan/canvas/projection/canvas_source.clj`,
`src/fukan/canvas/project/clojure/invariant_to_predicate.clj`,
`src/fukan/target/clojure/address.clj` (the `:invariant-name` opts
fallback can stay as a defensive belt-and-braces; not strictly
required after the canvas-source fix).

### Sprint 3 — drift-close polymorphism on drift-kind

`instruct/drift_close.clj` currently emits one prose template
regardless of finding kind. Branch on the `:check` keyword:

| `:check`                              | framing                       | insertion |
|---------------------------------------|-------------------------------|-----------|
| `:inspect.drift/missing-implementation` | "artifact does not exist; add" | end-of-file |
| `:inspect.drift/shape-drift-on-record`  | "shape diverged; rewrite in place" | replace existing def |
| (future kinds)                          | escalate / generic            | per-kind  |

Approach:
1. Re-read `drift-close.clj`'s current rendering — single template path.
2. Refactor `build-context` / `render` into a kind-keyed dispatch.
   Drift-close becomes a thin wrapper that picks the right sub-template
   based on the finding's `:check`.
3. For `shape-drift-on-record`, the rendered instruction must:
   - State explicitly that the existing def is wrong-shaped (cite the
     `:detail` block from the finding).
   - Direct the implementing LLM to rewrite the existing def in place,
     NOT append a duplicate.
   - Carry the canvas-side correct shape (already produced by Layer A
     via `type-to-malli`).
4. Trial-run check: `(instruct …)` against `distributed.cluster/type/Cluster`
   (a shape-drift finding) should produce rewrite-in-place prose, not
   "add the missing definition".

**Files touched:** `src/fukan/canvas/instruct/drift_close.clj`,
possibly a small helper in `src/fukan/canvas/instruct/core.clj` if
the kind-dispatch table wants to be shared.

## Out of scope

- **Project invariants to property tests instead of predicates.** The
  trial doc raises this as the right long-term home. Phase 8 work.
- **Agent-tool dispatch fidelity in fukan-architect.** Real subagent
  dispatch was unavailable in the trial harness; testing it end-to-end
  requires invoking fukan-architect from a parent session with the
  tool available. Phase 8 verification work.
- **UI / explorer.** Indefinitely deferred per standing instruction.

## Definition of done

- The Sprint 1 canonical example (`distributed.cluster/get_self_role`)
  closes its drift finding via `(instruct …)` → implementing-LLM →
  `(canvas-drift)`.
- An invariant drift finding (e.g. `MajorityRequiredForLeadership`)
  closes via the same loop.
- A record shape-drift finding closes with the implementing LLM
  rewriting in place (no duplicate def).
- All three iterations land without canvas-author intervention to
  patch the rendered instruction.

Sprint 5 (verification) follows immediately after.
