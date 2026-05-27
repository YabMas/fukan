# Phase 6 Sprint 1 — Canvas → code projection design

**Date:** 2026-05-27
**Status:** Draft for user review (pause point before Sprint 2 dispatch)
**Companion doc:** `doc/plans/2026-05-27-drift-signals-design.md` (Sprint 1 Task 1)

---

## Strategic frame

The Phase 6 plan asked Task 2 to choose between two paths for getting canvas content into
the Clojure target analyzer:

- (a) Build a new code path that reads canvas datoms directly from the canvas db.
- (b) Keep the analyzer's existing input shape and have canvas content project INTO that
  shape first.

Task 1's drift-signals doc claimed option (b) is **already in place** — the build
pipeline runs `canvas-source/build` → `phase4` → `phase5` → `clj-analyzer/run`, and the
analyzer's selectors filter primitive maps by `:kind`. Task 2's job is to verify that
finding, document the path as it stands, and identify the minimum changes Sprint 3's
drift helper needs.

**Verification result: option (b) is in place STRUCTURALLY, but the wiring is partial
and broken in load-bearing places.** Canvas affordances reach the analyzer as
`:primitive/operation` and `:primitive/rule` primitives, and the analyzer DOES walk them
— but (i) `module-coord-of-primitive` uses a `::` separator the canvas stable-id format
doesn't emit, so canonical-address derivation goes wrong; (ii) canvas records project as
`:primitive/container` primitives WITHOUT the `Allium::Entity` tag the analyzer's
`entities-values-variants` selector requires, so types are entirely invisible to the
schema-projection path; (iii) canvas events get role `:canvas/event` but
`canvas-source/affordance-kind` defaults them to `:primitive/operation`, not
`:primitive/event`, so the analyzer's `events` selector finds nothing; (iv) the project
registry's `:root-prefix` is `""` while fukan's actual code lives under `fukan.*`, so
the canonical namespace lookup misses the source root.

Phase 6 Sprint 3 cannot ship trust-tier drift findings until these gaps are closed.
The good news: the analyzer architecture itself is sound. Every gap is a localised
projection-layer fix (canvas-source emits the right primitive shape and tags, plus a
small kernel adjustment to `module-coord-of-primitive`'s separator handling and a
`root-prefix` correction in the project registry). No analyzer redesign is needed; the
work fits the half-to-one-session-per-fix profile Sprint 3 Task 5 was scoped for.

---

## The current pipeline (verified)

The end-to-end path canvas content travels to land at a `:validity {:valid | :absent}`
projects edge:

```
canvas/*.clj
   │  defines (function …), (record …), (event …), (rule …), (invariant …), (getter …)
   │  via canvas.construction + canvas.vocab.*
   │
   ▼
canvas-source/build-canvas-db          (src/fukan/canvas/projection/canvas_source.clj:387)
   │  requires all 62 canvas ns; calls each (build-canvas); merges per-module Datascript
   │  dbs into one unified canvas db keyed on :entity/id UUIDs.
   │
   ▼
canvas-source/project                  (canvas_source.clj:735)
   │  project-modules    → {:kind :primitive/container :id "infra.server" …}
   │  project-affordances→ {:kind :primitive/operation :id "infra.server/start_server"
   │                        :label "start_server" :parameters []}  ← role-routed
   │  project-types      → {:kind :primitive/container :id "infra.server/type/ServerOpts"
   │                        :label "ServerOpts"}                   ← no :fields!
   │  project-states     → {:kind :primitive/container …}
   │  project-edges      → :relation/uses edges (cross-module :references)
   │  project-tag-apps   → carries :entity/tag values as tag-applications
   │
   ▼  model map {:primitives, :edges, :tag-defs, :tag-apps, :predicates, …, :artifacts {}}
   │
phase4/run → phase5/run                (validation + constraint evaluation)
   │
   ▼
clj-analyzer/run model registry "src"  (src/fukan/target/clojure/analyzer.clj:190)
   │  walk-symbols → flat list of {:kind :ns :name :file} from every src/**/*.clj
   │  operations  selector → filter :primitives by :kind = :primitive/operation
   │  rules       selector → filter :primitives by :kind = :primitive/rule
   │  entities-values-variants selector
   │              → :primitive/container ∧ tag-app[Allium::Entity|Value|Variant]
   │  events      selector → filter :primitives by :kind = :primitive/event
   │
   │  For each primitive, derive canonical address via
   │    addr/canonical(registry, primitive-kind, projection-kind, module-coord, label)
   │  Look up source-index[[ns name :function|:data-structure]]
   │  Emit Code.* artifact + :relation/projects edge with :validity ∈ {:valid :absent}
   │  Then materialize-unprojected: any src/ defn/def without a projects edge
   │  lands as an unbound Code.* artifact.
   ▼
final model {:primitives, :edges (incl. projects), :artifacts, …}
```

The structural shape Task 1's doc claims is real: canvas-source emits the four primitive
kinds the analyzer selectors recognise, and the analyzer's output already includes
`:relation/projects` edges with `:validity` tags. The Sprint 3 drift helper can in principle
read those edges directly — no Datascript-level direct-read path needed.

### Worked example: `function "start_server"` in `canvas/infra/server.clj`

Canvas-side declaration:

```clojure
(function "start_server"
  "Start the HTTP server on the given options."
  (takes [opts :ServerOpts])
  (gives (optional :ServerInfo)))
```

Canvas-construction lift produces an Affordance with role
`:fukan.canvas.monolith/exposed-call`. After `build-canvas-db`, the unified db has a
`:Affordance` entity with `:entity/name "start_server"`, owned (via `:module/child`) by
the `:Module` entity `infra.server`.

`project-affordances` reads it and `affordance-kind` maps the role
`:fukan.canvas.monolith/exposed-call` → `:primitive/operation`. The primitive lands in
the model as:

```clojure
{:kind        :primitive/operation
 :id          "infra.server/start_server"      ; ← canvas stable-id format
 :label       "start_server"
 :parameters  []                                ; ← canvas-source forces empty
 :description "Start the HTTP server on the given options."}
```

The analyzer's `operations` selector picks it up. It then calls
`emit-function-projection`:

```clojure
(let [module-coord (module-coord-of-primitive "infra.server/start_server")
      ; ↳ no "::" in id → returns the FULL string "infra.server/start_server"  ✗
      {:keys [ns name]} (addr/canonical registry :primitive/operation
                                        :projection-kind/operation
                                        module-coord  "start_server")
      ; ↳ module-ns: "" + ("infra.server/start_server" →"infra.server.start_server")
      ;             = ns "infra.server.start_server"                              ✗
      ; ↳ local-name: snake→kebab "start_server" → "start-server"                 ✓
      public-match (find-symbol source-index ns name :function)
      ; ↳ looks up ["infra.server.start_server" "start-server" :function]
      ;   which is NOT where the actual code is — code is at
      ;   ["fukan.infra.server" "start-server" :function]                         ✗
      validity (if public-match :valid :absent)]  ; → :absent  ✗ (false positive)
  …)
```

So the analyzer DOES walk every canvas function, but the address it derives is wrong
on two axes: the module-coord separator is `/` (canvas stable-id) where the
analyzer expects `::` (legacy Allium-era stable-id), and the registry's `:root-prefix`
is `""` where it needs `"fukan"` for the fukan-on-fukan analysis.

**Net effect: against fukan-itself today, the analyzer produces `:absent` for
essentially every canvas-declared function — a false negative drift signal.** The
pipeline runs end-to-end without exceptions (each step is wired correctly in isolation),
but the canonical-address derivation hits two simultaneous gaps that make the validity
output unreliable.

The same trace applied to `record "ServerOpts"` exits even earlier: project-types
produces a `:primitive/container` with no `Allium::Entity` tag, so
`entities-values-variants` filters it out, and no projection edge is ever emitted.
Records and values are silently invisible to the analyzer today.

---

## Canonical-address mapping today

Lives in `src/fukan/target/clojure/address.clj` (69 LoC). Three rules:

### Rule 1 — Module-coord → Clojure namespace

```clojure
(defn module-ns [registry module-coord]
  (let [pfx (:root-prefix registry "")
        dotted (str/replace module-coord #"/" ".")]
    (if (str/blank? pfx) dotted (str pfx "." dotted))))
```

- `module-coord = "infra.server"`, `root-prefix = ""` → `"infra.server"`.
- `module-coord = "infra.server"`, `root-prefix = "fukan"` → `"fukan.infra.server"`.
- `/` in coord becomes `.` (legacy Allium nested-module convention).

### Rule 2 — Primitive label → local Clojure name

```clojure
(case projection-kind
  :projection-kind/schema     label                              ; PascalCase preserved
  (:projection-kind/operation
   :projection-kind/rule
   :projection-kind/invariant
   :projection-kind/test)     (-> label snake→kebab pascal→kebab-lower))
```

- `"start_server"` → `"start-server"`. ✓
- `"ProcessSubmission"` → `"process-submission"`. ✓
- `"ServerOpts"` (schema kind) → `"ServerOpts"` (unchanged). ✓
- `"check_4a"` → `"check-4a"`. ✓

### Rule 3 — Test addresses

`:projection-kind/test` appends `"-test"` to both ns and name. Not used in Phase 6 drift
work; mentioned for completeness.

### Verified gaps that bite Sprint 3

1. **`module-coord-of-primitive` separator mismatch (load-bearing).** The analyzer in
   `analyzer.clj:82-90` uses `::` as the module-coord separator inside primitive ids.
   Canvas-source's stable-id format uses `/` (`"infra.server/start_server"`,
   `"infra.server/type/ServerOpts"`). The analyzer's helper has no `::` to split on
   and returns the entire id as the module-coord, polluting downstream address
   derivation. **Fix scope:** one-liner in `module-coord-of-primitive` — change to
   split on `/` (or, more conservatively, handle both separators). Half-session of
   tests + run-against-fukan to confirm. This is the single highest-impact fix in
   Phase 6.

2. **`:root-prefix` empty for fukan-on-fukan (load-bearing).** `project-defaults.clj`
   returns `(r/make-registry)` with `:root-prefix ""`. fukan's source is at
   `fukan.infra.server`, etc. — the prefix should be `"fukan"`. **Fix scope:** one
   line in `defaults.clj`: `(r/with-root-prefix (r/make-registry) "fukan")`. Tests
   confirm `valid` validity against existing canvas+src pairs.

3. **Type primitives carry no `Allium::Entity` tag (load-bearing).** The analyzer's
   `entities-values-variants` selector filters by tag, not by primitive kind. Canvas
   `(record …)` and `(value …)` produce Type entities but `project-types` does not
   emit a tag-application. **Fix scope:** either (a) extend `project-tag-apps` /
   `project-types` to emit an `Allium::Entity` tag for record/value primitives, or
   (b) loosen the analyzer's selector to match by primitive kind directly when the
   tag is absent. Recommend (b) — the `Allium::Entity` tag is a vestigial coupling
   to the retired Allium analyzer; canvas's kind keyword is the modern surface.
   Half-session.

4. **Canvas events misrouted to `:primitive/operation` (load-bearing for category 7a).**
   `affordance-kind` in canvas-source has no case for `:canvas/event`, so events fall
   through to the `:primitive/operation` default. The analyzer's `events` selector
   filters by `:kind :primitive/event` and finds nothing. **Fix scope:** add the case
   `:canvas/event → :primitive/event` in `affordance-kind`. Trivial — three lines —
   but it's the difference between events being drift-checked and silently ignored.

5. **Canvas records carry no `:fields` on the projected primitive (blocks Task 1's
   shape-drift category).** The canvas db has `:type/fields` (cardinality-many) but
   `project-types` reads only `:entity/name` and `:type/doc`. **Fix scope:** extend
   `project-types` to populate `:fields` on the primitive map from the db's
   `:type/fields` set. Without this, the Projector's `signature-for` can't build a
   `:malli-shape` either, and Sprint 3's record-shape-drift check has no canvas-side
   data to compare against. Half-session.

### Address gaps that are FINE today

- **Multi-segment module names.** `canvas.web.views.shell` (module name
  `"web.views.shell"`) → `module-ns` produces `"web.views.shell"` (or with prefix
  `"fukan.web.views.shell"`). Matches the actual ns. ✓
- **PascalCase records.** `ServerOpts` survives address derivation unchanged because
  schema-kind addresses preserve case. ✓
- **snake_case functions.** Convert correctly to kebab-case (verified above). ✓
- **`-` vs `_` in canvas module names.** Canvas modules use dots; `agent.views_loader`
  has an underscore-bearing segment. `module-name-matches-ns?` (in canvas-source) handles
  the underscore↔hyphen variant for reference resolution; address.clj doesn't but it
  doesn't need to — the dotted module-name passes through unchanged.

### Address gaps that are minor judgment calls

- **Numeric module suffixes.** `canvas.validation.rules-4a` → module-coord
  `"validation.rules-4a"`. `module-ns` produces `"validation.rules-4a"` (or with prefix
  `"fukan.validation.rules-4a"`). Real code is at `fukan.validation.rules-4a`. ✓
  But the local-name conversion for the affordance `check_4a` (snake→kebab→Pascal→kebab)
  would yield `check-4a`. Real code: `(defn check …)` at `fukan.validation.rules-4a/check`.
  **Drift would fire for every rules-4* module today** because the canvas label is
  `"check_4a"` but the implementation is named `check`. This is a NAMING CONVENTION
  question, not an address-mapping question — the canvas naming should be reviewed in
  Sprint 3 against actual src/ naming. Not blocking; flag for Sprint 3 evidence.

---

## What's projected vs what's not (per canvas entity type)

| Canvas form / role | Reaches model as | Reaches analyzer? | Projects to Code.* artifact? | Notes |
|---|---|---|---|---|
| `(function …)` (role `:fukan.canvas.monolith/exposed-call`) | `:primitive/operation` | Yes (operations selector) | Code.Function via `:projection-kind/operation` | Works after gap-fixes #1+#2 |
| `(getter …)` (role `:canvas/getter`) | `:primitive/operation` | Yes | Code.Function (operation kind) | Same path as function; verified projection-kind suffices |
| `(checker …)` (role `:canvas/checker`) | `:primitive/operation` | Yes | Code.Function (operation kind) | Same path |
| `(handler …)` (role `:canvas/handler`) | `:primitive/operation` (default fallback) | Yes — but routed as operation | Code.Function | Acceptable for Phase 6: handlers ARE fns; no separate projection-kind needed |
| `(rule …)` (role `:canvas/rule`) | `:primitive/rule` | Yes (rules selector) | Code.Function via `:projection-kind/rule` | Works after gap-fix #1 |
| `(invariant …)` (role `:canvas/invariant`) | `:primitive/rule` (collapsed with rule by `affordance-kind`) | Yes (rules selector) | Code.Function | Acceptable BUT see open question 1: invariants in canvas are prose `holds-that`, no code counterpart expected → `:absent` is the wrong signal. Task 1 already deferred category 5 (invariant-without-enforcement). Recommendation: **exclude invariants from the operations/rules projection pass** (skip primitives whose source role was `:canvas/invariant`) so they don't generate noise findings. |
| `(record …)` (kind `:record`) | `:primitive/container` (no tag, no fields) | **No** (selector requires `Allium::Entity` tag) | None today | Gap-fixes #3 + #5 required |
| `(value …)` (kind `:atomic`) | `:primitive/container` (no tag) | **No** (same selector gap) | None today | Gap-fix #3 required |
| `(event …)` (role `:canvas/event`) | `:primitive/operation` (misrouted) | Misrouted — picked up by operations, not events | Code.Function (wrong artifact kind) | Gap-fix #4 routes to `:primitive/event` → Code.DataStructure via `:projection-kind/schema` |
| Module itself | `:primitive/container` | No (no selector for module-as-primitive) | None | This is correct — modules project to namespaces, but namespace existence isn't currently a separate drift check. The per-affordance check is sufficient because every affordance lands at `<module-ns>/<name>`; if the ns doesn't exist, every affordance in it is `:absent`. |
| `(exports …)` | Tag application (`:exported`) on referenced entities | Yes (in `:tag-apps`) | None (not a projection target) | Used for inspect/coverage's role-exemption; passes through to Phase 6 unchanged. |

**Headline:** functions, getters, checkers, handlers project after gap-fixes #1+#2;
records and values project after gap-fixes #3+#5; events project after gap-fix #4; rules
project after gap-fix #1; invariants are deliberately excluded (open question 1).

---

## What Phase 6 needs from the projection layer

### For missing-implementation (drift category 1) — needs gap-fixes #1, #2, #4

Sprint 3 Task 6 ships missing-implementation by reading `:validity :absent` edges from
the model. The substrate is in place but the validity tag is unreliable today because
of the canonical-address gaps. After the four load-bearing fixes (#1 separator, #2
root-prefix, #3 tag-or-selector for types, #4 event kind routing), the existing
`:validity :absent` edges become trustworthy.

**No new analyzer logic.** Sprint 3 Task 6 stays in the drift-helper namespace
(`canvas/inspect/drift.clj`) and the projection-layer fixes belong in Sprint 2 Task 4
(prereqs surfaced in Sprint 1).

### For event-schema-missing (category 7a) — needs gap-fix #4

Same machinery as category 1 once events route correctly. No separate analyzer work.

### For record-shape-drift (category 3) — needs gap-fix #5 + analyzer extension

Gap-fix #5 (project `:fields` from `:type/fields`) lands the canvas side. The analyzer
side — reading `def` bodies to extract Malli-schema fields — is the **half-session
analyzer extension** Task 1 estimated.

**Feasibility verdict — verified.** `source/extract-symbols` (in
`target/clojure/source.clj`, 72 LoC) already reads top-level forms as data via
`(read {:read-cond :allow …})`. Extending it to capture the third element of `def`
forms (the body — a Malli vector like `[:map [:port {…} [:int …]] …]`) is mechanical.
Parsing the Malli map shape into `{:name :type}` pairs is straightforward — Malli's
`[:map [:field-name opts? type-expr]]` grammar is regular.

**Type-keyword normalisation is the rub.** Canvas declares fields as `:Integer`,
`:String`, `:ServerInfo`; Malli uses `:int`, `:string`, `:ServerInfo`. A small alias
table reconciles. The Phase 4 type vocabulary (in `canvas.model.vocabulary`) likely
already has the mapping fukan uses internally; check first before building a new one.

**Half-session estimate stands.** ~30-50 LoC in `source.clj` to capture def bodies and
parse Malli `:map` shapes, plus a 10-15 entry alias table, plus a new attribute on
Code.DataStructure artifacts (`:fields` carrying the parsed list). The drift helper
then compares canvas-side `:fields` against artifact-side `:fields`.

### Sequencing / output-shape changes the drift helper needs

The drift helper wants two pieces of evidence per finding that aren't directly in the
substrate today:

1. **The canvas stable-id of the originating primitive.** Already present —
   `:relation/projects` edges carry `:from {:case :endpoint/primitive :id <stable-id>}`.
   The drift helper reads `(-> edge :from :id)` and that's the canvas side.

2. **The canvas entity TYPE behind that primitive** (`function` vs `record` vs `event`
   etc., needed to phrase finding messages — "Canvas function X has no implementation"
   vs "Canvas record Y has no schema"). The model's `:primitives` map has `:kind` but
   canvas-source's collapsing (everything record-shaped becomes `:primitive/container`,
   handlers and events both fall through to `:primitive/operation`) loses the
   canvas-side distinction. **Recommendation: extend `:primitives` entries with a
   `:canvas-role` field** copied straight from `:affordance/role` (and a synthetic
   `:canvas/record` / `:canvas/value` for types). This is a small canvas-source change
   inside `project-affordances` / `project-types`. The drift helper then reads
   `:canvas-role` to phrase messages bilaterally.

3. **Optional: tag artifacts with the canvas entity they were projected from.**
   Phase 6 plan's Sprint 1 self-review item suggested this. After verification: not
   needed. The projects-edge `:from` endpoint already carries the canvas-side id;
   re-tagging the artifact would duplicate the link. **Skip.**

---

## Trial-run target (open question 5)

The Phase 6 plan suggested extending `demo/distributed/` with matching `src/` code as
the trial-run subject. Verified directory state:

- `canvas/distributed/cluster.clj`, `election.clj`, `log.clj` exist (Phase 5 Sprint 4
  trial-run output).
- `src/fukan/distributed/` does **not** exist.
- `demo/distributed/` does **not** exist either — the canvas was named `distributed.*`
  inside the canvas tree directly, not under `demo/`.

The Phase 6 plan's framing in open question 5 was therefore slightly off: the trial-run
target is `canvas/distributed/*` against a new `src/fukan/distributed/*` (created during
Sprint 4 Task 10). The trial-run agent builds out *some* of the distributed
implementation (e.g. `cluster.clj` with a couple of fns, leaving `election.clj` /
`log.clj` empty or partial), invokes drift, observes the gap, closes it, invokes drift
again. **This matches Phase 5's lineage cleanly — the distributed canvas was the
Phase 5 trial-run target and reusing it for Phase 6's trial run carries substantive
design context forward.**

**Recommendation: confirm distributed canvas + new `src/fukan/distributed/` as Sprint 4
target.** No need to invent a new corpus.

---

## Open questions for the user

1. **Invariant exclusion from analyzer projection.** Canvas invariants are `holds-that`
   prose with no expected code counterpart. Today `affordance-kind` routes them to
   `:primitive/rule`, so the analyzer projects them as code functions and tags every
   one `:absent` — a false-negative drift signal. **Recommendation:** in
   `affordance-kind`, route `:canvas/invariant` to a new `:primitive/expression` kind
   (or simply tag the primitive with `:canvas-role :canvas/invariant` and have the
   analyzer's `rules` selector skip those). Drift helper does NOT report them as
   missing-implementation. Category 5 (invariant-without-enforcement) is deferred to
   Phase 7 per Task 1; until that lands, invariants stay outside the drift surface.
   Confirm.

2. **`module-coord-of-primitive` separator fix scope.** The one-liner fix from `::` to
   `/` will change canonical-address derivation across every primitive. After the fix,
   the analyzer's behaviour against fukan-itself flips from "almost all absent" to
   "most valid, some absent" — the latter being the real drift signal. **Risk:** if any
   downstream code (tests, other targets, integrity checks) depends on the pre-fix
   address shape, it'll break. Verified: no other consumer parses primitive ids with
   `::` (grepped). But Sprint 2 Task 4 should run the full test suite after the fix and
   patch any tests that hard-coded the wrong addresses. Confirm fix-now (Sprint 2)
   rather than defer.

3. **`:root-prefix` fix for fukan-on-fukan.** Change `fukan-on-fukan` registry to
   `(r/with-root-prefix (r/make-registry) "fukan")`. Same risk profile as #2 — flips
   address output, may break tests that assumed empty prefix. Confirm fix-now.

4. **Type-projection selector: tag-based vs kind-based.** The analyzer's
   `entities-values-variants` selector currently requires an `Allium::Entity|Value|Variant`
   tag. Recommendation: loosen to filter by `:kind :primitive/container` for primitives
   whose `:canvas-role` is `:canvas/record` or `:canvas/value`. This drops the
   vestigial Allium-tag coupling. **Alternative:** emit the `Allium::Entity` tag from
   canvas-source. Both work; the first is cleaner architecturally (canvas's own
   role keyword becomes the source of truth). Pick.

5. **Event kind routing.** Add `:canvas/event → :primitive/event` to `affordance-kind`.
   Trivial. No alternative seriously considered. Confirm.

6. **`:fields` projection from `:type/fields`.** Extend `project-types` (and possibly
   `project-affordances` for the function `takes`/`gives` shape) to populate
   `:fields` / `:parameters` from the canvas db's cardinality-many attributes. Without
   this, the Projector's blueprint signatures are empty and Sprint 3's shape-drift
   check has no canvas-side fields to compare. Sprint 2 Task 4 territory. Confirm.

7. **rules-4a naming-convention drift.** Canvas labels `check_4a` etc.; src code is
   plain `check`. After all gap-fixes land, drift will fire for every rules-4* module
   on the naming mismatch alone. Three options: (a) rename canvas labels to `check`;
   (b) rename src defns to `check-4a`; (c) accept the findings as evidence of a real
   convention drift to resolve. Recommend **(a)** — canvas should match the
   one-per-module convention the src/ side adopted. Flag for Sprint 3 evidence; not
   blocking Sprint 1.

---

## Summary table — gap-fix inventory for Sprint 2 Task 4

| # | Fix | Location | Size | Required for |
|---|-----|----------|------|--------------|
| 1 | `module-coord-of-primitive`: split on `/` not `::` | `target/clojure/analyzer.clj:82-90` | 1-line + tests | All drift signals |
| 2 | `:root-prefix "fukan"` for fukan-on-fukan registry | `project_layer/defaults.clj:8` | 1-line | All drift signals against fukan-itself |
| 3 | Type-projection selector loosened to kind-based | `target/clojure/analyzer.clj:131-139` | ~10 LoC | Records, values reach drift |
| 4 | `:canvas/event → :primitive/event` in `affordance-kind` | `canvas/projection/canvas_source.clj:441-450` | 1-line | Event-schema-missing (category 7a) |
| 5 | Populate `:fields` on Type primitives from `:type/fields` | `canvas/projection/canvas_source.clj:530-550` | ~10 LoC | Shape-drift (category 3) |
| 6 | Populate `:parameters` on Operation primitives from `:affordance/input-types` (optional) | `canvas/projection/canvas_source.clj:503-528` | ~15 LoC | Future signature-drift (category 4, deferred); not required for Sprint 3 |
| 7 | Carry `:canvas-role` through to primitives | `canvas/projection/canvas_source.clj:503-566` | ~5 LoC | Drift helper messages name canvas-side correctly |
| 8 | Exclude invariants from rule-projection (analyzer or canvas-source) | `canvas_source.clj` or `analyzer.clj` | ~5 LoC | Avoid false-negative invariant findings |

**Total Sprint 2 Task 4 surface: ~50 LoC of localised projection-layer adjustments
plus a one-line analyzer separator fix and a one-line registry prefix fix.** No
architectural rework; no new substrate primitive; no new analyzer subsystem.

The analyzer's projection mechanic itself (artifact emission, projects-edge with
`:validity`, materialize-unprojected for code-without-canvas) is sound and stays as-is.
Phase 6 adapts the projection-layer wiring to feed the analyzer correctly; Sprint 3's
drift helper then reads the analyzer's existing output through a finding-shaped lens.

---

## Amendment — 2026-05-27 user-review settlement

After review, the user resolved the projection question for invariants and rules: **both ARE projected into code**, contrary to the conservative default this doc was working with.

**Two new wiring gaps added to Sprint 2's load** (bringing the total from 4 to 6):

5. **Invariant projection**. Canvas `(invariant X (holds-that "Y"))` projects to an expected code-side predicate fn named `Y`. The `holds-that` clause is the canonical name — the canvas declaration already encodes the code-side counterpart. Implementation: the canvas-source projection layer emits a primitive of kind `:primitive/rule` (or a new dedicated kind) per invariant, with the `holds-that` string carried through as the canonical-address name. The analyzer's existing `rules` selector then picks it up; `:validity :valid`/`:absent` falls out naturally.

6. **Rule projection**. Canvas `(rule X (when X (params)))` projects to an expected code-side predicate fn named `kebab(X)` — the rule's own name, kebab-cased. **Option (a) from the user-review conversation**, chosen over (b) constraint-registry projection and (c) "rules are the code". Reasoning: symmetric with invariants, mechanically simple, easy to detect drift. Implementation: same as invariant projection — emit `:primitive/rule` (or kind variant) per rule with the rule's name as canonical address.

**Address derivation** for invariants/rules:
- Module: the canvas module that owns the invariant/rule (e.g. `validation.rules-4a` → `src/fukan/validation/rules_4a.clj`)
- Identifier: `holds-that` string for invariants (already kebab-case by convention); rule's own name kebab-cased for rules

**Events stay deferred**. Event-without-handler-in-code detection needs call-site analysis the analyzer doesn't have. The event-schema-missing detection (one of Sprint 3's original signal candidates) is now subsumed under the unified missing-implementation signal once gap #4 (`:canvas/event` routing) lands.

**Sprint 3 simplification**: drift "categories" collapse to two signals — missing-implementation (uniform across projected entity types: functions, events, invariants, rules, getters, checkers) and shape-drift-on-records. See `doc/plans/2026-05-27-drift-signals-design.md` § Amendment for the resolution.

**Sprint 2 wiring gap list — final**:

1. `module-coord-of-primitive`: `::` → `/`
2. `fukan-on-fukan` `:root-prefix`: `""` → `"fukan"`
3. Type selector: tag-based → kind-based (so canvas records/values are visible)
4. `affordance-kind` adds `:canvas/event` routing
5. **NEW**: Invariant projection via `holds-that` clause as canonical name
6. **NEW**: Rule projection via rule's own name (kebab-cased) as canonical name
7. **NEW**: Rules-4* naming-convention realignment — canvas labels `check_4a` etc. need to align with code's plain `check` (rename canvas side, ~7 files)
8. **Pulled in from Phase 5.5 carryover**: auto-discover canvas/**/*.clj files

The trial-run target remains `canvas/distributed/*` paired with new `src/fukan/distributed/*` implementation code. The asymmetry (canvas exists; src/ doesn't yet) makes it a ready-made test corpus for Sprint 4's canvas↔code loop.
