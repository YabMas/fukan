# Phase 8 Sprint 1 Task 2 — Invariant property-test projection design

**Date:** 2026-05-28
**Status:** Draft for user review (pause point before Sprint 5 lands code)
**Companion doc:** doc/plans/2026-05-28-closure-controller-design.md (Sprint 1 Task 1)
**Parent plan:** doc/plans/2026-05-28-canvas-substrate-phase-8.md (Sprints 5 + 6)

---

## 1. Strategic frame

Phase 7 shipped `invariant-to-predicate`. It projects a canvas
`(invariant "MajorityRequiredForLeadership" "…doc…"
(holds-that "…"))` to a `defn` predicate stub under `src/`:

```clojure
(defn majority-required-for-leadership
  "…doc…"
  [model]
  (throw (ex-info "majority-required-for-leadership: not yet implemented"
                  {:canvas-id "distributed.cluster/invariant/MajorityRequiredForLeadership"
                   :invariant-name "MajorityRequiredForLeadership"
                   :holds-that "…"})))
```

The trial run (doc/plans/2026-05-27-instruction-trial-run-findings.md,
iter 2 + recommendation #4) made the deeper point load-bearing: an
invariant is a *timeless behavioral commitment*. Its natural code-side
counterpart is a generative property test, not a `defn` stub that
throws. A property test *runs* the invariant — generates models, walks
them, asserts the commitment holds. A `defn` predicate stub closes
the structural drift finding (the symbol exists) but moves no
behavioral verification work. The implementing-LLM that closes the
predicate drift writes a one-arg fn that returns true; nothing in the
test suite ever calls it. The commitment is asserted by canvas; the
code declares the right symbol but no test runs the canvas's
commitment.

Property tests fix this. A `defspec` with a generator over `Cluster`
states and a property body that asserts "no node holds leadership for
a term without a strict majority of vote grants for that term" turns
the invariant into a *checked* commitment. Closing the drift means
writing a real test, not a stub.

The predicate projection STAYS — see §2. Both projections coexist;
the property test is the primary code-side counterpart for new
invariant drift; the predicate stays a registered alternative.

The hardest case for the Phase 8 closure controller (Sprint 3) is
this one: test-side artifacts, distinct address convention, drift
comparator gains a test-side awareness path. Sprint 6's trial run is
the empirical test that the controller closes invariant drift via the
new projection end-to-end.

---

## 2. Coexistence with `invariant-to-predicate`

Three candidate selection mechanisms. Each option says when each
projection fires.

### Option A — Canvas-side flag

The canvas author opts in per invariant:

```clojure
(invariant "MajorityRequiredForLeadership"
  "A node becomes leader only with majority vote grants."
  (holds-that "no node is leader for term T without > N/2 grants for T")
  (projects-to :test))   ; or :predicate; default :test
```

`(projects-to …)` is read by canvas-source and propagated as
`:canvas/projection-kind` on the affordance entity. Layer A reads it
to pick which projection to register for this invariant. The
multimethod's dispatch key gains the flag (see §5).

### Option B — Default-on-projection-kind + override

Property-test is the *default*. A canvas author opts back to
predicate only when the invariant is genuinely a state-walk (no
useful generator exists): `(projects-to :predicate)`. Most invariants
get property tests without canvas-side ceremony.

### Option C — Both always

Every invariant projects BOTH. Each canvas declaration has two
parallel code-side counterparts: a predicate stub under `src/` AND a
property test under `test/`. Drift opens TWO findings per invariant
when both are absent. The canvas-author closes both through the
controller; either can be closed first.

### Recommendation — Option B

Default-on-property-test, override-to-predicate via the flag. Three
reasons:

1. **The trial finding's framing.** Invariants ARE behavioral
   commitments. The default code-side counterpart should reflect what
   they are. Option C's "both always" gives equal weight to a
   throwaway stub and a real test — wrong default.

2. **Cost minimization.** Canvas authors writing new invariants don't
   need to type a flag. The single-mode call site `(invariant "X"
   "doc" (holds-that "..."))` produces a property test. Authors that
   genuinely want a predicate (state walks, formal-method-style
   invariants on a static model snapshot) explicitly opt out.

3. **Controller scope hygiene.** Option C doubles the per-invariant
   work the controller does. Each closure becomes two dispatches
   instead of one. The Phase 8 verification doc will need to
   distinguish "closed predicate, not property" outcomes, which is a
   semantic gap, not a structural one. Option B keeps each invariant's
   closure a single dispatch.

**The default's reach.** Existing canvas invariants — the three in
`canvas/distributed/cluster.clj` are the canonical set — pick up the
default. Their predicate stubs (closed manually in Phase 7's trial)
become orphans. See §10 for migration.

---

## 3. New projection-kind

A new keyword: **`:projection-kind/property-test`**.

Joins the existing `function-kinds` set in
`src/fukan/target/clojure/address.clj` for local-name kebab-lower
routing. The set becomes:

```clojure
(def ^:private function-kinds
  #{:projection-kind/rule
    :projection-kind/operation
    :projection-kind/invariant
    :projection-kind/test
    :projection-kind/property-test})
```

**Distinct from `:projection-kind/test`.** The existing
`:projection-kind/test` is the analyzer's generic test-projection
edge — `addr/canonical` appends `-test` to both ns and name (see
address.clj L88-89). It dates to MODEL.md's universal projection
mechanic — every projection has a test pair. It's NOT specific to
invariants; it's the verification-test edge for any primitive. Phase
6's `analyzer_data_test` notes "verification projections were
removed", so today no edges actually carry this kind, but the
infrastructure remains.

`:projection-kind/property-test` is a *new, distinct* kind that
specifically expresses the invariant-as-property idiom. It does NOT
auto-append `-test` to the local name via `addr/canonical`; address
derivation is explicit (§6). Reusing `:projection-kind/test` would
conflate two meanings — "any verification test for a primitive" vs
"the property-test that IS the invariant's commitment-check" — and
the second has a different address convention (no `-test` suffix on
the local name; ns goes through the same `-test` suffix path).

The artifact-kind enum addition is in §4.

---

## 4. New artifact-kind

`fukan.model.artifact` ships two artifact sub-cases today:
`:code/function` and `:code/data-structure`. The new kind:

**`:code/property-test`**

```clojure
(defn make-code-property-test
  ([language qualified-name] (make-code-property-test language qualified-name nil))
  ([language qualified-name source-location]
   (cond-> {:case :artifact/code, :language language
            :sub {:case :code/property-test, :qualified-name qualified-name}}
     source-location (assoc-in [:sub :source-location] source-location))))
```

The Malli schema gains a `[:code/property-test [:map …]]` branch
alongside `:code/function` and `:code/data-structure`. Identity tuple
shape is unchanged — `(case-discriminator, language, qualified-name)`.

**Analyzer recognition.** The Clojure analyzer's source walker
(`src/fukan/target/clojure/source.clj`) currently extracts `def` /
`defn` / `defn-` / `defrecord` from `src/` paths. The extension:
also walk `test/` paths; recognize `defspec` and `defproperty` (and
their conventional dispatch-via-`prop/for-all` inside `deftest`
forms — see §7 for the chosen idiom) as artifacts of kind
`:code/property-test`. The qualified-name shape is
`<test-ns>/<symbol>`; e.g.
`fukan.distributed.cluster-test/majority-required-for-leadership-property`.

**Projector update.** `target/clojure/projector.clj`'s
`artifact-kind-for` gains the third branch:

```clojure
(defn- artifact-kind-for
  [projection-kind]
  (cond
    (= :projection-kind/property-test projection-kind)  :code/property-test
    (contains? function-kinds projection-kind)          :code/function
    (contains? data-structure-kinds projection-kind)    :code/data-structure
    :else (throw (ex-info "unknown projection-kind" {:projection-kind projection-kind}))))
```

The branch order matters: `:projection-kind/property-test` is ALSO in
`function-kinds` (for local-name kebab routing), so the explicit
check fires first.

---

## 5. Dispatch mechanism

Today `dispatch-key-of` (core.clj L30-51) returns one of:
`:canvas-role` (Affordances), `:Type/atomic`, `:Type/record`, `:Type`
fallback, or the bare `:model-element-kind`. Two projections both
registering `[:clojure :canvas/invariant]` collide.

Two architectural options.

### Option α — Extend the dispatch tuple

`[lens-id, dispatch-key, projection-kind]` — 3-tuple registration.
Default `:projection-kind` resolved by a separate mechanism (canvas
flag from §2). Every existing projection becomes
`[:clojure :canvas/operation :clojure/function-to-defn]` etc.

Backwards-incompatible. Phase 7 + 7.5 shipped 9 projections; all 9
registration sites would need a third element. The `defmethod project
:default` would have to know how to find the canvas-flagged
projection-kind when the registration omits the third slot. The cost
is high; the benefit is principled tuple dispatch.

### Option β — Wrap dispatch (role-aware fallback)

Keep 2-tuple `[lens-id, dispatch-key]`. Make `dispatch-key-of`
projection-kind-aware: when the model element's primitive carries
`:canvas/projection-kind :test` (set by canvas-source from the
`(projects-to :test)` flag, or by the default-on-property-test
heuristic in §2), `dispatch-key-of` returns a NEW dispatch key
`:canvas/invariant+property-test`. The existing `:canvas/invariant`
key stays bound to `invariant-to-predicate`; the new key binds to
`invariant-to-property-test`.

The dispatch key shape extends from "discriminator from substrate
shape" to "discriminator from substrate shape AND author intent
where one is declared." `dispatch-key-of` becomes:

```clojure
(defn dispatch-key-of
  [{:keys [model-element-kind canvas-role type-kind canvas-projection-kind] :as el}]
  (case model-element-kind
    :Affordance (if (and (= :canvas/invariant canvas-role)
                         (= :test canvas-projection-kind))
                  :canvas/invariant+property-test
                  canvas-role)
    :Type       (cond
                  (= :atomic type-kind) :Type/atomic
                  (= :record type-kind) :Type/record
                  :else                  :Type)
    model-element-kind))
```

Every existing 2-tuple registration unchanged. The new projection
registers `[:clojure :canvas/invariant+property-test]`. The selection
logic from §2 (Option B default) lives in the canvas-source layer
that populates `canvas-projection-kind`:

- `(invariant "X" "doc" (holds-that "..."))` — no flag — gets
  `:canvas-projection-kind :test` (the default).
- `(invariant "X" "doc" (holds-that "...") (projects-to :predicate))`
  gets `:canvas-projection-kind :predicate`, which `dispatch-key-of`
  ignores (falls through to `:canvas/invariant`), routing to
  `invariant-to-predicate`.

### Recommendation — Option β

The 2-tuple + augmented dispatch-key keeps every existing
registration untouched. The 9 projections from Phase 7 + 7.5 don't
move. The asymmetry — invariants get a special dispatch-key branch,
other Affordance roles route on plain `:canvas-role` — is honest:
invariants are the ONLY canvas role with two valid projections at
present. If a future role wants the same treatment, the
`dispatch-key-of` branch extends in place.

The 3-tuple is more principled but the 9-projection migration is
real work and the benefit doesn't compound — only invariants gain
the `:projection-kind` discriminator slot. The narrow extension
matches the narrow need.

---

## 6. Address convention for property-test artifacts

Per Phase 8 plan: target file under `test/`, distinct symbol shape
from the predicate.

**Conventional address for the canonical case
`distributed.cluster/MajorityRequiredForLeadership`:**

```
target ns:     fukan.distributed.cluster-test
target file:   test/fukan/distributed/cluster_test.clj
target symbol: majority-required-for-leadership-property
```

**Derivation rules** (extend `addr/canonical`):

The existing `addr/canonical` returns
`{:ns base-ns :name (kebab(label))}` for function-kind projections;
for `:projection-kind/test` it suffixes both ns and name with
`-test`. The new `:projection-kind/property-test` follows a SIMILAR
but distinct convention:

- **ns:** `<base-ns>-test` (same as `:projection-kind/test`)
- **name:** `<kebab(label)>-property` (NOT `-test`; the suffix names
  the *kind of test*, not the test itself)
- **file path:** derived from ns via the existing
  `dot→slash, hyphen→underscore` convention, prefixed `test/`
  instead of `src/`

The path-prefix change is the key new behavior. `core/ns->path` is
`src/`-only today (core.clj L163-172). Extension: a sibling
`ns->test-path` that prefixes `test/` instead. Layer A's
`invariant-to-property-test` calls `ns->test-path` directly;
`invariant-to-predicate` keeps calling `ns->path`.

**`addr/canonical` extension:**

```clojure
(defn canonical
  [registry primitive-kind projection-kind module-coord primitive-label]
  (let [base-ns (module-ns registry module-coord)
        base-name (local-name primitive-kind projection-kind primitive-label)]
    (cond
      (= :projection-kind/test projection-kind)
      {:ns (str base-ns "-test") :name (str base-name "-test")}

      (= :projection-kind/property-test projection-kind)
      {:ns (str base-ns "-test") :name (str base-name "-property")}

      :else {:ns base-ns :name base-name})))
```

**Local-name choice — `-property` vs `-holds`:**

`majority-required-for-leadership-property` vs
`majority-required-for-leadership-holds`. Recommend `-property`. It
names *what the symbol is* (a property test). `-holds` reads as a
predicate (something that returns a boolean), which is exactly the
shape we're moving away from. The `clojure.test.check` idiom is
`defspec foo-property …` already.

**No collision with the predicate.** The predicate at
`fukan.distributed.cluster/majority-required-for-leadership` lives
in a different ns AND has a different local name from the property
test at
`fukan.distributed.cluster-test/majority-required-for-leadership-property`.
A canvas-author who declares an invariant via the property-test
default doesn't get a predicate at all (per §2 Option B); but if
they later switch the canvas declaration to `(projects-to :predicate)`,
the predicate symbol gets the kebab-base name and the property-test
file goes away (its drift becomes "only in code" — handled by drift
comparator's two-way framing, but in practice the canvas author
deletes the test file).

---

## 7. Template shape

Two candidate idioms.

### Candidate 1 — `clojure.test.check` `defspec`

```clojure
(defspec majority-required-for-leadership-property 100
  (prop/for-all [model (gen/return ::placeholder)]
    ;; Invariant: MajorityRequiredForLeadership.
    ;; What must hold: no node is leader for term T without > N/2 vote grants for T.
    ;;
    ;; Property-check approach: generate a Cluster state, walk the leader
    ;; assignments, count vote grants per term, assert the majority condition.
    (throw (ex-info "majority-required-for-leadership-property: not yet implemented"
                    {:canvas-id "distributed.cluster/invariant/MajorityRequiredForLeadership"
                     :invariant-name "MajorityRequiredForLeadership"
                     :holds-that "no node is leader for term T without > N/2 grants for T"}))))
```

### Candidate 2 — `deftest` + manual `prop/for-all`

```clojure
(deftest majority-required-for-leadership-property
  (let [result (tc/quick-check 100
                  (prop/for-all [model (gen/return ::placeholder)]
                    …))]
    (is (:pass? result) (str "MajorityRequiredForLeadership failed: " result))))
```

### Recommendation — Candidate 1

`defspec` is the canonical `clojure.test.check` idiom. Three reasons:

1. **Less template friction.** `defspec` is a one-form declaration
   that already binds the property to the test runner.
2. **The runner reports it as a property.** `clojure.test`'s output
   distinguishes properties from cases; this is useful for trial-run
   evidence.
3. **The implementing LLM's path is shorter.** They replace
   `gen/return ::placeholder` with a real generator and the throw
   with a real assertion. The `defspec` envelope stays.

### Skeleton template (the literal output of Layer A)

```clojure
(defspec {{kebab-invariant-name}}-property 100
  (prop/for-all [model (gen/return ::placeholder)]
    ;; Invariant: {{invariant-name}}.
    ;; {{descriptive-doc-trimmed}}
    ;;
    ;; What must hold: {{holds-that}}.
    ;;
    ;; Property-check approach: generate a representative model state,
    ;; walk the relevant entities, and assert the invariant. Replace
    ;; the placeholder generator and the throw-body below.
    (throw (ex-info "{{kebab-invariant-name}}-property: not yet implemented"
                    {:canvas-id "{{stable-id}}"
                     :invariant-name "{{invariant-name}}"
                     :holds-that "{{escaped-holds-that}}"}))))
```

The skeleton throws — same audit-trail shape as the predicate's
`ex-info` (carries `:canvas-id`, `:invariant-name`, `:holds-that` so a
"still unimplemented" detector finds it without parsing source). The
generator is `gen/return ::placeholder` — a known-bad generator that
the implementing LLM must replace; if they don't, the test still
throws on first generation. This is a deliberately broken generator,
not a `gen/any-equatable` or similar — implementing LLMs that copy
mechanically should hit the throw, not silently emit a passing test.

### Template-level prose contract

The skeleton carries the *intent* in comments (Layer A's invariant
projection's `:prose` envelope is the source). Layer B's
`drift-close` scenario surfaces the same prose around the skeleton
in the rendered instruction; the comments are the implementing-LLM's
in-file context once Layer B's prose drops away.

### Test-file structure (when the file is fresh)

The Layer B cold-write scenario (deferred — see §9) is responsible
for the `(ns …)` form. When the property-test file ALREADY exists
(an existing test file with neighbor tests), the projection just
emits the `defspec` form; the implementing LLM inserts it alongside
neighbor tests and updates the `(:require …)` form if `defspec` or
`prop/for-all` isn't already required.

A fresh test file's `(ns …)` form, when needed:

```clojure
(ns fukan.distributed.cluster-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))
```

The cold-write controller handles this; the property-test projection
itself does not need to render the `(ns …)` form for the existing-file
case.

---

## 8. Drift comparator updates

Today `src/fukan/canvas/inspect/drift.clj` derives
`:expected-code-path` from the artifact id's ns via `ns->file-path`
(L97-108): replaces `.` with `/`, `-` with `_`, prefixes `src/`. The
comparator looks for the artifact at the impl-side path.

Two changes for property-test projections.

### Path resolution per projection-kind

The `:from`-side of a projects-edge carries the primitive. The
`:to`-side carries the artifact id. The artifact id's qualified-name
is the property-test's ns/symbol — e.g.
`fukan.distributed.cluster-test/majority-required-for-leadership-property`.
The ns alone tells the comparator the file is a test file (ends in
`-test`).

`ns->file-path` already returns `src/<ns-as-path>.clj`. For an
invariant's property-test artifact, the expected path is
`test/<ns-as-path>.clj` instead.

Extension to drift's `absent-edge->finding`:

```clojure
(defn- expected-path-for
  "Derive the expected source-file path for a projects-edge target.
   For property-test projection-kinds, the path is under test/ instead
   of src/."
  [edge ns-str]
  (case (:projection-kind edge)
    :projection-kind/property-test  (ns->test-path ns-str)
    (ns->file-path ns-str)))
```

`ns->test-path` mirrors `ns->file-path` but prefixes `test/`.

The finding's `:expected-code-path` now correctly points at
`test/fukan/distributed/cluster_test.clj` for the property-test case,
and `src/fukan/distributed/cluster.clj` for the predicate case.

### Symbol comparison

The artifact id's qualified-name already carries the right symbol
(`majority-required-for-leadership-property` for the property-test
case; `majority-required-for-leadership` for the predicate case).
`qualified-name->parts` (L110-116) already splits ns/symbol; the
`:expected-symbol` field of the finding gets the right value
automatically.

No change needed for symbol comparison — `addr/canonical`'s new
`:projection-kind/property-test` branch (per §6) produces the right
qualified-name, which flows through canvas-source's projects-edge
emission, which is read verbatim by drift.

### Verification — implementer wrote the test

After the implementing LLM commits the property-test file, the
analyzer (`target/clojure/source.clj`) walks `test/...`, recognizes
the `defspec` form, emits a `:code/property-test` artifact (per §4).
The projects-edge from the primitive to the artifact flips from
`:absent` to `:valid`. The next `(canvas-drift)` run no longer
surfaces the finding.

**Open question for Sprint 5:** does the analyzer walk `test/` today?
Search the canvas-source/analyzer code to confirm. If not, extension
required.

---

## 9. Layer B `drift-close` extension

The scenario at `src/fukan/canvas/instruct/drift_close.clj` renders
the instruction the implementing LLM receives. Its `neighbors-section`
multimethod (drift_close.clj L426) walks the target file looking for
`def`/`defn`/`defrecord`/`defmulti`/`defmethod` neighbors. The
property-test target file's neighbors are different shapes —
`defspec`, `deftest`, `prop/for-all` blocks.

### Neighbor-section extension

The existing `neighbors-base-section` (L388) uses a regex pattern
matching `def`-family forms (drift_close.clj L91). Two extensions:

1. **Extend the regex.** Add `defspec`, `defproperty` (if used), and
   `deftest` to the form-head alternation.

```clojure
(let [pattern #"(?m)^\((def|defn|defn-|defrecord|defmulti|defmethod|defspec|deftest)\s+…"
```

This is sufficient for the neighbor-summary case; the existing logic
extracts the form's symbol and the optional docstring, which works
unchanged for `defspec` / `deftest` forms.

2. **Optional — kind-aware neighbor framing.** The current `neighbors-section`'s
   default branch produces a generic "neighbor declarations in this
   file" list. For property-test files, a kind-aware overlay could
   add: "this file is a test file; existing properties cover X, Y, Z."
   Recommend NOT adding this in Sprint 5 — the generic neighbor list
   is adequate signal for the implementing LLM. Add only if Sprint 6
   trial-run shows implementing LLMs missing the test-file context.

### Frame / gap section — invariant-specific path

Today the `:inspect.drift/missing-implementation` branch frames "a
canvas primitive declares X but no code-side artifact at
<expected-path>". For property-test projections, the frame's
expected-path is in `test/`. The frame text doesn't currently
distinguish — it just names the path. This is FINE; the implementing
LLM reads "no code-side artifact at test/fukan/distributed/cluster_test.clj"
and goes there. The instruction's prose carries the invariant's
holds-that clause already (via the projection's `:prose` envelope).

**Recommend no frame-section change** in Sprint 5. The existing
frame is path-neutral. Re-evaluate after Sprint 6.

### Output prose — discipline-prose

drift_close.clj L460-468 emits the closing `discipline-prose`. The
existing prose is "your edit must satisfy: <discipline-prose>". For
property-test closures, the discipline-prose should name the property:
"the defspec form named X must encode the invariant Y's property
check; the generator must produce model states the property body can
walk." This is per-finding context the projection's `:prose` field
already carries. No new mechanism — flow the projection's `:prose`
into `discipline-prose` for `:projection-kind/property-test`
findings. Verify the existing flow does this; if not, extend.

---

## 10. Migration of existing invariants

Phase 7 closed three invariant drift findings during the trial via the
predicate projection:

- `canvas/distributed/cluster.clj :: TermMonotonicity` —
  `src/fukan/distributed/cluster.clj` predicate stub
- `canvas/distributed/cluster.clj :: AtMostOneLeaderPerTerm` — same
- `canvas/distributed/cluster.clj :: MajorityRequiredForLeadership` —
  same (L116 per the trial findings)

§2's Option B (property-test default) means these predicate stubs
become orphans — the canvas-side declaration no longer projects to
them; drift's "only in code" half (not currently emitted, but the
substrate carries the edge) would surface them.

### Two paths

**Path 1 — Coexist.** Keep the predicate stubs. The canvas-side
declarations keep the `(projects-to :predicate)` flag (Sprint 5 adds
the flag to them retroactively). Drift closes via the predicate
projection as it does today. New invariants get the property-test
default; existing ones grandfather.

**Path 2 — Migrate.** Sprint 6's trial run includes a migration step:
the three existing predicate stubs are deleted; the canvas
declarations drop the `(projects-to :predicate)` flag (or never get
it); property-test files are written by the controller. The
predicate stubs vanish from `src/`.

### Recommendation — Path 2 (Migrate)

The trial-run framing is clear: invariants ARE commitments. The
predicate stubs Phase 7 closed manually were *structural* closures
— they made drift stop complaining, but no behavioral commitment got
checked. The Phase 8 closure controller's job is to close drift in a
way that actually performs the commitment. Keeping the orphaned
predicates contradicts that framing.

**Migration step (Sprint 6 Task 18):**

1. Use the closure controller to close `MajorityRequiredForLeadership`'s
   drift via property-test (a fresh drift finding now that the
   predicate stub is no longer the canvas's chosen projection — see
   below for how the swap happens).
2. Repeat for `TermMonotonicity` and `AtMostOneLeaderPerTerm`.
3. After verification (canvas-drift confirms zero findings on
   distributed.cluster), delete the three predicate stubs from
   `src/fukan/distributed/cluster.clj`.

**How the swap happens.** Sprint 5 lands the substrate. The canvas
declarations stay unchanged (Option B's property-test default fires
for them automatically; no `(projects-to …)` flag is required). On
the next `(canvas-drift)` run after Sprint 5 lands, the three
invariants emit fresh `:missing-implementation` findings — they
project to property-test artifacts under `test/` which don't exist
yet. The existing predicate stubs become orphan code-side artifacts
(no canvas-side projects-edge), surfaced by a separate "only in code"
check IF one is added; absent that, they're silent orphans until
manually cleaned.

The Sprint 6 trial-run closes the three new property-test findings,
THEN manually deletes the three predicate stubs as a cleanup step.
This is a one-time migration; future invariants land straight as
property tests.

**Alternative if migration is too aggressive.** Path 1 (Coexist) is
viable if the user wants to preserve the Phase 7 trial's outputs.
Cost: three permanent canvas declarations carrying `(projects-to
:predicate)` for archaeological reasons. Recommendation stays Path 2.

---

## 11. Coverage regression test updates

`test/fukan/canvas/project/coverage_test.clj` (L92-106) asserts every
emitted canvas-role has a registered projection. After §5's dispatch
extension, `:canvas/invariant+property-test` joins the registered
dispatch-key set. The test's `emitted-affordance-roles` query reads
raw `:affordance/role` values from the canvas db — it doesn't see
the synthetic `+property-test` discriminator (which is computed by
`dispatch-key-of`, not stored).

### Two changes

1. **Verify the existing test passes.** With Option B (property-test
   default), every `:canvas/invariant` role emitted by canvas-source
   gets routed to `:canvas/invariant+property-test` by
   `dispatch-key-of`. The existing test asks "does `:canvas/invariant`
   appear in registered dispatch keys?" It WILL — `invariant-to-predicate`
   still registers `[:clojure :canvas/invariant]` as the
   override-case target. So the existing test passes unchanged.

2. **New positive test.** Add an assertion that
   `:canvas/invariant+property-test` IS registered, with an explicit
   smoke-test pattern matching
   `canonical-sprint-1-example-projects-through` (L123-136):

```clojure
(deftest canonical-invariant-projects-to-property-test
  (testing "an invariant without :predicate override projects to a property test"
    (let [el {:model-element-kind :Affordance
              :canvas-role        :canvas/invariant
              :canvas-projection-kind :test
              :stable-id          "distributed.cluster/invariant/MajorityRequiredForLeadership"
              :entity-name        "MajorityRequiredForLeadership"
              :module-coord       "distributed.cluster"
              :doc                "…"
              :holds-that         "no node is leader for term T without > N/2 grants for T"}
          p  (core/project :clojure el {:registry {:root-prefix "fukan"}})]
      (is (core/valid-projection? p))
      (is (= :clojure/invariant-to-property-test (:projection-kind p)))
      (is (= "majority-required-for-leadership-property" (-> p :target :symbol)))
      (is (str/starts-with? (-> p :target :path) "test/")))))
```

3. **Allow-list verification.** The
   `intentionally-unprojected-affordance-roles` set stays empty;
   `:canvas/invariant` keeps a projection (it still routes to
   `invariant-to-predicate` when the canvas flag opts in). No
   exemption needed.

---

## 12. Open questions for Sprint 6 to answer

Sprint 6's trial-run via the controller on real canvas invariants
will produce empirical data. The questions the data answers:

1. **Closure rate for invariant drift via property-test projection.**
   Comparable to the Phase 7 trial's predicate path (3/3 closed
   manually). The Phase 8 dispatch loop is the new variable; the
   property-test projection is the second. Does the controller close
   3/3 via property tests with no manual intervention?

2. **Template quality.** Does the `gen/return ::placeholder` skeleton
   adequately signal "replace me" to the implementing LLM? Or do
   they leave the placeholder in place and write a property body
   that operates on `::placeholder` (effectively a no-op test)?
   Counter-measure already in place: the `throw` body forces a
   failure on first generation. Verify it works.

3. **Generator authorship.** Do implementing LLMs write *useful*
   generators? A generator over `Cluster` states is non-trivial —
   it requires knowing the Cluster type's shape, the legal moves,
   the vote-grant relation. Does the Layer B `drift-close`
   instruction carry enough context (related-elements, model-side
   shape information) for the implementing LLM to write a useful
   generator? Or does the property test pass trivially because the
   generator only produces uninteresting states?

4. **Drift recognition of test-side closure.** After the implementing
   LLM commits the property-test file, does `(canvas-drift)`
   correctly mark the projects-edge `:valid` on the next run? This
   tests the analyzer's `test/` walking (open question per §8).

5. **Migration cleanliness.** The §10 Path 2 migration deletes three
   predicate stubs. Does the canvas-drift output before-and-after
   match the expected counts (3 fewer "only in src" orphans, 3
   fewer canvas-side findings closed)? Or does some surprise edge
   (a cross-module reference to the deleted predicate) surface?

6. **Layer B context adequacy.** Does the property-test-aware
   neighbor-section extension (§9) actually help, or is the generic
   neighbor list sufficient? Evidence: did the implementing LLM
   miss test-file conventions (e.g. forget to require
   `clojure.test.check`)?

7. **Coexistence cost (if Path 1 chosen instead).** Three permanent
   `(projects-to :predicate)` flags for archaeological reasons —
   does this clutter the canvas semantics? Sprint 6 evidence (if
   Path 1) tells us.

8. **Property-test as the second-hardest closure case.** Phase 8's
   plan named property-test as the harder case (test-side
   artifacts; new comparator path). Does the controller actually
   find it harder — more retries, more escalation — than function
   or record drift closures? If yes, the retry-policy (Sprint 4)
   may need a property-test-specific override.

These questions feed Sprint 7's verification doc and inform Phase 9's
opener.

---

## Tier verdict

**Trust tier, `:severity :info`.**

Same reasoning as the project-lens design (doc/plans/2026-05-27-project-lens-design.md
§ Tier verdict). The projection is mechanical: given an invariant
model element + the Clojure lens, the output is deterministic. The
implementing LLM may decide differently (the canvas-author may pick
`(projects-to :predicate)`); the projection itself is a fact about
"if you write a property test for this invariant, this is the
canonical skeleton."

The behavioral commitment encoding is *not* trust-tier — the property
body the implementing LLM writes IS judgment. But Layer A's job is
the skeleton, not the body.

---

## Recommendation summary (one-line each)

1. **Coexistence (§2):** Option B — property-test default, predicate via opt-in `(projects-to :predicate)`.
2. **New projection-kind (§3):** `:projection-kind/property-test`, distinct from existing `:projection-kind/test`.
3. **New artifact-kind (§4):** `:code/property-test`, sibling of `:code/function` / `:code/data-structure`.
4. **Dispatch (§5):** Option β — augmented `dispatch-key-of` returns `:canvas/invariant+property-test` when the canvas declaration opts to the default. No existing registration changes.
5. **Address (§6):** ns `<base>-test`; symbol `<kebab>-property`; file `test/<path>_test.clj`.
6. **Template (§7):** `defspec`-shaped skeleton with placeholder generator + `throw` body carrying audit metadata.
7. **Drift (§8):** `expected-path-for` branches on `:projection-kind`; `ns->test-path` mirrors `ns->file-path` with `test/` prefix.
8. **Layer B (§9):** extend the neighbor regex to include `defspec` + `deftest`; no frame-section change in Sprint 5.
9. **Migration (§10):** Path 2 — Sprint 6 closes via property test, then deletes the three orphan predicate stubs.
10. **Coverage test (§11):** add a positive test for `:canvas/invariant+property-test` registration.

---

## Cross-cutting verification (Sprint 5 Task 17 + Sprint 6)

The coverage regression test (§11) is the structural guard. Trial-run
evidence (§12) is the behavioral guard. Both must land before Phase 8
verification (Sprint 7) signs off.

The predicate projection remains in place; this design adds a sibling
without retiring the original. The substrate complexity is one
augmented dispatch-key branch, one new projection-kind constant, one
new artifact-kind enum value, one ns→path variant, and one
projection-kind-aware path-derivation branch in drift. Each is local
and reversible.
