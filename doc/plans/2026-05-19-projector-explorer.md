# Projector + Explorer + Generation Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the second half of the Clojure Target extension — the **Projector** (assembles Implementation Blueprints on demand per [MODEL.md §7.7](../MODEL.md#77-the-target-language-extension--analyzer-and-projector)) — plus surface Plan 5's currently-invisible Model in the **Explorer** (artifacts as nodes, `projects` edges with `:validity`-driven drift markers, all thirteen kernel relations), plus an inspection-only **generation flow** (HTTP endpoint `/projector?...` → Blueprint rendered for human copy/paste; no LLM wiring in MVP), plus close Plan 5's two deferred lints (duplicate-canonical-address detection + unprojected-defn discovery).

**Architecture:** Three new namespaces — `fukan.target.clojure.projector` (Blueprint assembly), `fukan.target.clojure.blueprint` (record shape + serialisation), and `fukan.projection` (Model → cytoscape-compatible graph projection — replaces the OLD code-graph projection per [DESIGN.md](../DESIGN.md) line 493). The Explorer's existing cytoscape transformer + sidebar stay structurally, but their inputs change to the new Model (primitives + thirteen relations + artifacts). One new HTTP route (`GET /projector`). Drift markers are CSS classes attached at projection time, not new edge kinds. The Projector is pure; the endpoint and Explorer are the imperative shell.

**Tech Stack:** Existing Clojure 1.11. Existing reitit ring + http-kit + hiccup + cheshire. Existing Cytoscape.js + Datastar frontend. No new dependencies. The MVP Blueprint serialises to two forms: structured EDN (for tests + Analyzer verification) and markdown (for LLM prompts + human inspection).

---

## Plan-of-plans context

This is **Plan 6 of 9** in the next-chapter overhaul. The sequence:

1. Kernel substrate *(closed)*
2. 2a. Allium parser *(closed)*
3. 2b. Allium analyzer *(closed)*
4. 3a. Boundary parser *(closed)*
5. 3b. Boundary analyzer + multi-extension pipeline *(closed)*
6. 3c. Phase 4 structural validation *(closed)*
7. 4. Constraint language + Phase 5 *(closed)*
8. 5. Clojure Target Analyzer + project layer *(closed — 391 tests / 0 failures)*
9. **6. Projector + Explorer + generation flow + Plan-5 carry-forwards** *(this plan)*

Plan 5 closed with the Analyzer producing 108 artifacts + 126 `:relation/projects` edges against the fukan-on-fukan corpus, all currently `:validity :absent` because the fukan project hasn't yet committed to one-Allium-module ↔ one-Clojure-namespace alignment. **All 126 of those edges are invisible in the Explorer** — that's the visible motivation for this plan's Explorer half.

Two Plan-5 deferrals land here:
- **Duplicate-canonical-address detection** — when two `defn`s land at the same `{ns}/{name}`, that's a lint error per [DESIGN.md "Strict enforcement"](../DESIGN.md). Plan 5 deferred this; Plan 6 lands it as a Phase 6 violation.
- **Unprojected-defn discovery** — `defn`s whose `[ns name]` matches no canonical address surface as `Code.Function` nodes not bound to any primitive (per [DESIGN.md "Couplings"](../DESIGN.md) line 730). These are visible in the Explorer's "unbound code" view.

Authoritative refs:
- [VISION.md](../VISION.md) lines 96-99, 119, 127 — the generation-flow MVP claim ("clicking a red `absent` drift marker summons the Projector ... Generation is part of MVP, not a future chapter").
- [MODEL.md §7.7](../MODEL.md#77-the-target-language-extension--analyzer-and-projector) — substrate-level commitments for the projection mechanic + Blueprint protocol. Six-component assembly: canonical address, artifact kind, expected signature, type renderings, surrounding model context, selected idioms.
- [DESIGN.md "Implementation linkage"](../DESIGN.md) lines 628-735 — application-design choices: Projector shape, Blueprint record shape, generation flow, strict enforcement, the "Code that doesn't match any expected projection address" couplings rule.
- [DESIGN.md "Explorer"](../DESIGN.md) line 738 — the thirteen relation kinds + tag-applied projection scoping.
- [Plan 5](2026-05-18-clojure-analyzer.md) — its Task 8 explicitly defers the two lints to here.

Existing surfaces Plan 6 builds on:
- `src/fukan/target/clojure/address.clj` — `module-ns`, `local-name`, `canonical` per primitive kind + projection-kind. Projector reuses these.
- `src/fukan/target/clojure/types.clj` — `render` for substrate Type → malli. Projector reuses these.
- `src/fukan/project_layer/registry.clj` — `:root-prefix`, `:type-overrides`, `:idioms`. Projector consumes all three (Analyzer in Plan 5 consumes the first two; idioms are Projector-only).
- `src/fukan/model/artifact.clj` — `Code.Function`, `Code.DataStructure`, `artifact-identity`.
- `src/fukan/model/build.clj` — `:artifacts` map; `:edges` vector; `endpoint-resolves?` supports `:endpoint/artifact`.
- `src/fukan/web/handler.clj` — current Plan-1 stub (line 4: *"Full router is rewritten in Plan 6"*). Replaces.
- `src/fukan/web/views/cytoscape.clj`, `graph.clj` — render OLD code-graph format. Plan 6 extends to the new Model (primitives + thirteen relations + artifacts) without throwing away the cytoscape transformer; the projection layer changes.
- `src/fukan/web/views/sidebar.clj`, `breadcrumb.clj` — render legacy EntityDetails. Plan 6 adapts the entry-point to the new Model. Full sidebar rewrite is **out of scope** — MVP renders primitive id/label/intent and artifact qualified-name/source-location only.

---

## Repository conventions (jj over git)

Identical to prior plans. **NEVER `jj squash -m "..."`** (silently collapses commits). Use `jj desc -m "..."` + `jj new` after each task commit.

---

## Conventions used throughout this plan

- **Namespace structure** —
  - `src/fukan/target/clojure/blueprint.clj` — Blueprint record + serialisation (EDN + markdown).
  - `src/fukan/target/clojure/projector.clj` — Blueprint assembly (six-component build).
  - `src/fukan/projection/core.clj` — Model → graph projection (primitives + artifacts as nodes; thirteen relations as edges).
  - `src/fukan/projection/drift.clj` — drift-marker decoration for nodes/edges.
  - The existing `web/views/*` mostly stays; only the projection input changes.
- **Test-as-spec** — every Projector helper has a per-construct test. UI changes are tested with REPL smoke ("model loads, projection has expected node counts") + manual browser smoke at task close. Where automated, use `(ring/ring-handler ...)` directly invoked.
- **No LLM call in MVP** — the `/projector` endpoint returns Blueprint data. Users copy/paste to their LLM. Per [DESIGN.md *Generation UX details*](../DESIGN.md) line 796: "the concrete affordance shape (button placement, batch operations, diff UI, accept/reject mechanics) lands at implementation" — Plan 6 commits to inspection-only.
- **Phase 6 stays non-gating.** Lint additions (duplicate-address + unprojected) emit `:warning` violations, not `:error`.
- **Convention-driven binding remains.** Projector applies the same address-resolution rules as the Analyzer (in reverse) — no per-primitive overrides, no annotations.

---

## File Structure

### Files to create

- `src/fukan/target/clojure/blueprint.clj` — Blueprint record shape, `make-blueprint`, EDN + markdown serialisers.
- `src/fukan/target/clojure/projector.clj` — `project` entry point; per-primitive-kind assembly helpers; idiom router.
- `src/fukan/projection/core.clj` — `project-model` returns `{:nodes :edges}` in cytoscape-internal format.
- `src/fukan/projection/drift.clj` — decorates nodes with `:drift-state` from absent/valid projects edges.
- `test/fukan/target/clojure/blueprint_test.clj`
- `test/fukan/target/clojure/projector_test.clj`
- `test/fukan/projection/core_test.clj`
- `test/fukan/projection/drift_test.clj`

### Files to modify

- `src/fukan/web/handler.clj` — replace Plan-1 stub with routes: `GET /` (existing shell), `GET /graph` (cytoscape JSON), `GET /projector` (Blueprint), `GET /sidebar` (entity detail).
- `src/fukan/web/views/cytoscape.clj` — extend node/edge kind enums to cover primitives + artifacts + thirteen relations + projection-kind + validity.
- `src/fukan/web/views/graph.clj` — replace OLD-projection input with `projection.core/project-model` output.
- `src/fukan/web/views/sidebar.clj` — narrow MVP scope; render primitive id/label/intent + artifact qualified-name/source-location only.
- `src/fukan/target/clojure/analyzer.clj` — add Plan-5-carry-forward calls: `detect-duplicate-addresses` + emit unprojected-Code.Function/DataStructure artifacts.

### Files to leave untouched

- Plan 5 substrate (`address.clj`, `types.clj`, `source.clj`, `project_layer/*`). The Projector consumes these without modification.
- Allium / Boundary parsers + analyzers — frozen.
- Constraint / Phase 5 — frozen.
- Kernel (`model/*`) — no changes expected. If the Projector requires kernel widening, document and stop for review.

---

## Reading the canonical reference

The Blueprint's six components per [MODEL.md §7.7](../MODEL.md#77-the-target-language-extension--analyzer-and-projector):

| # | Component | Source |
|---|---|---|
| 1 | Canonical address | `address/canonical` (Plan 5) keyed by `(primitive-kind, projection-kind, module-coord, label)` |
| 2 | Artifact kind | `Code.Function` for Operation/Rule/Invariant/Test; `Code.DataStructure` for Entity/Value/Variant/Event |
| 3 | Expected signature | Operation `:parameters` → Clojure arglist; Entity/Value/Variant `:fields` + `:type-ref` → malli `[:map ...]`; Event `:parameters` → malli `[:map ...]`; Rule + Invariant → arity-0 `defn` (assertion-as-test convention) |
| 4 | Type renderings | `types/render` (Plan 5) applied to every Type in the signature |
| 5 | Surrounding model context | host primitive's `:description` + `:intent`; outgoing `triggers`/`emits`/`reads`/`writes`/`creates`/`destroys`/`observes` edges; for Operations, the `realises` Contract; for Rules, the host Behaviour/Container |
| 6 | Selected idioms | `:idioms` from project registry, filtered by route predicate matching `(primitive-kind, projection-kind, address)` |

Per [DESIGN.md "Implementation Blueprint — concrete shape"](../DESIGN.md) line 686, the MVP Blueprint record:

```clojure
{:case            :blueprint/v1
 :primitive-id    "<module>::<name>"
 :projection-kind :projection-kind/...
 :address         {:ns "..." :name "..."}
 :artifact-kind   :code/function | :code/data-structure
 :signature       <malli-shape | arglist>
 :context         {:description ... :intent ... :related-edges [...]}
 :idioms          [<idiom-body>...]
 :rendered        {:markdown "..." :edn "..."}}
```

---

## Task 0: Scaffold + smoke target

**Files:**
- Create: `src/fukan/target/clojure/blueprint.clj` (stub `make-blueprint` returning identity record)
- Create: `src/fukan/target/clojure/projector.clj` (stub `project` returning `{:case :blueprint/v1}`)
- Create: `src/fukan/projection/core.clj` (stub `project-model` returning `{:nodes [] :edges []}`)
- Create: `test/fukan/target/clojure/projector_test.clj` (1 smoke + 1 forward-compatible test)
- Create: `test/fukan/projection/core_test.clj` (1 smoke)

Stand up the three namespaces with forward-compatible smokes.

- [ ] **Step 0.1: Create `src/fukan/target/clojure/blueprint.clj`**

```clojure
(ns fukan.target.clojure.blueprint
  "Implementation Blueprint — per-projection ephemeral record assembled
   by the Projector (Plan 6). Per MODEL.md §7.7 + DESIGN.md
   'Implementation Blueprint — concrete shape'.

   Tasks 1-7 fill this in; for now make-blueprint returns the identity
   record shape with :case :blueprint/v1.")

(defn make-blueprint
  "Construct an empty Blueprint v1 record. Fields populated by Projector
   tasks (1-6)."
  []
  {:case :blueprint/v1})
```

- [ ] **Step 0.2: Create `src/fukan/target/clojure/projector.clj`**

```clojure
(ns fukan.target.clojure.projector
  "Projector — assembles Implementation Blueprints on demand.

   Plan 6 fills this in. The Projector consumes the same project layer
   registry the Analyzer uses (Plan 5) and applies the same six-component
   universal projection mechanic in reverse: spec primitive → Blueprint."
  (:require [fukan.target.clojure.blueprint :as bp]))

(defn project
  "Project a primitive into a Blueprint. Stub: returns identity Blueprint.
   Tasks 1-6 implement the six-component assembly."
  [_model _registry _primitive-id _projection-kind]
  (bp/make-blueprint))
```

- [ ] **Step 0.3: Create `src/fukan/projection/core.clj`**

```clojure
(ns fukan.projection.core
  "Model → cytoscape-compatible graph projection.

   Plan 6 replaces the OLD code-graph projection (per DESIGN.md line 493)
   with one that consumes the new Model (primitives + thirteen kernel
   relations + artifacts). Existing web/views/cytoscape.clj stays as the
   format boundary; only the projection input changes.

   Tasks 7-10 fill this in.")

(defn project-model
  "Project a Model into {:nodes :edges} ready for cytoscape transformation.
   Stub: returns empty graph. Tasks 7-10 implement primitives, artifacts,
   thirteen relation kinds, and drift decoration."
  [_model]
  {:nodes [] :edges []})
```

- [ ] **Step 0.4: Create `test/fukan/target/clojure/projector_test.clj`**

```clojure
(ns fukan.target.clojure.projector-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest projector-on-empty-model-returns-empty-blueprint
  (let [bp (projector/project (build/empty-model) (registry/make-registry)
                              "unknown::id" :projection-kind/rule)]
    (is (map? bp))
    (is (= :blueprint/v1 (:case bp)))))
```

- [ ] **Step 0.5: Create `test/fukan/projection/core_test.clj`**

```clojure
(ns fukan.projection.core-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.projection.core :as proj]
            [fukan.model.build :as build]))

(deftest project-model-on-empty-returns-empty-graph
  (let [g (proj/project-model (build/empty-model))]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))
```

- [ ] **Step 0.6: Run, expect baseline + 2 new pass**

```
clj -M:test
```

Expected: 391 (Plan 5 close) + 2 = **393/0/0**.

- [ ] **Step 0.7: Commit**

```bash
jj desc -m "scaffold(target-clojure, projection): projector + blueprint + projection stubs

Plan 6 Task 0: stand up the three new namespaces — Blueprint record
shape, Projector entry point, and the new Projection layer that
replaces the OLD code-graph projection (per DESIGN.md line 493).

Stubs return identity values so subsequent tasks can replace them
incrementally without breaking the suite.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: Blueprint record shape + EDN serialisation

**Files:**
- Modify: `src/fukan/target/clojure/blueprint.clj`
- Create: `test/fukan/target/clojure/blueprint_test.clj`

Define the full Blueprint v1 record fields per DESIGN.md "Implementation Blueprint — concrete shape". EDN serialisation is just `pr-str` over the data; markdown comes in Task 7. Identity is `(primitive-id, projection-kind)` for inspection caching.

### Step 1.1: Test

Create `test/fukan/target/clojure/blueprint_test.clj`:

```clojure
(ns fukan.target.clojure.blueprint-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.blueprint :as bp]))

(deftest make-blueprint-with-required-fields
  (let [b (bp/make
            {:primitive-id    "m::Foo"
             :projection-kind :projection-kind/rule
             :address         {:ns "m" :name "foo"}
             :artifact-kind   :code/function})]
    (is (= :blueprint/v1 (:case b)))
    (is (= "m::Foo" (:primitive-id b)))
    (is (= :projection-kind/rule (:projection-kind b)))
    (is (= {:ns "m" :name "foo"} (:address b)))
    (is (= :code/function (:artifact-kind b)))))

(deftest make-blueprint-defaults-empty-optional-fields
  (let [b (bp/make {:primitive-id "m::F" :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "f"} :artifact-kind :code/function})]
    (is (nil? (:signature b)))
    (is (= {} (:context b)))
    (is (= [] (:idioms b)))))

(deftest blueprint-identity
  (let [b (bp/make {:primitive-id "m::F" :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "f"} :artifact-kind :code/function})]
    (is (= ["m::F" :projection-kind/rule] (bp/identity b)))))

(deftest blueprint-to-edn-roundtrip
  (let [b (bp/make {:primitive-id "m::F" :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "f"} :artifact-kind :code/function
                    :signature [:=> [:cat] :any]
                    :context {:description "X" :intent nil :related-edges []}
                    :idioms []})
        edn (bp/to-edn b)
        parsed (clojure.edn/read-string edn)]
    (is (string? edn))
    (is (= b parsed) "blueprint EDN roundtrip is identity")))
```

### Step 1.2: Implement

Replace `src/fukan/target/clojure/blueprint.clj`:

```clojure
(ns fukan.target.clojure.blueprint
  "Implementation Blueprint — per-projection ephemeral record assembled
   by the Projector (Plan 6). Per MODEL.md §7.7 + DESIGN.md
   'Implementation Blueprint — concrete shape'.

   Blueprint v1 shape:
     :case            :blueprint/v1
     :primitive-id    string (spec primitive id, e.g. 'm::Foo')
     :projection-kind keyword (:rule | :operation | :invariant | :schema | :test)
     :address         {:ns string :name string}
     :artifact-kind   keyword (:code/function | :code/data-structure)
     :signature       malli shape | arglist | nil
     :context         {:description :intent :related-edges} — Task 5
     :idioms          [<idiom-body>...] — Task 6
     :rendered        {:markdown :edn} — Task 7

   The Blueprint is NOT persisted — it's regenerated on every Projector
   call from current spec + project registry + Model state.")

(defn make
  "Construct a Blueprint v1. Required keys: :primitive-id, :projection-kind,
   :address, :artifact-kind. Optional: :signature, :context, :idioms, :rendered."
  [{:keys [primitive-id projection-kind address artifact-kind
           signature context idioms rendered]}]
  {:case            :blueprint/v1
   :primitive-id    primitive-id
   :projection-kind projection-kind
   :address         address
   :artifact-kind   artifact-kind
   :signature       signature
   :context         (or context {})
   :idioms          (or idioms [])
   :rendered        rendered})

(defn identity
  "Blueprint identity for inspection caching: (primitive-id, projection-kind)."
  [blueprint]
  [(:primitive-id blueprint) (:projection-kind blueprint)])

(defn to-edn
  "Serialise a Blueprint to EDN. Roundtrip is identity for inspection /
   Analyzer-verification consumption."
  [blueprint]
  (pr-str blueprint))
```

### Step 1.3: Run, expect pass + commit

Expected: 393 + 4 = **397/0/0**.

```bash
jj desc -m "feat(target-clojure): Blueprint v1 record shape + EDN serialisation

Per MODEL.md §7.7 + DESIGN.md 'Implementation Blueprint — concrete shape'.
make accepts the six-component fields plus :rendered. identity is
(primitive-id, projection-kind). EDN serialisation is pr-str-driven
and roundtrips to identity for tests + Analyzer verification consumption.

Markdown serialisation lands in Task 7 alongside the assembled rendered field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: Canonical-address + artifact-kind resolution

**Files:**
- Modify: `src/fukan/target/clojure/projector.clj`
- Modify: `test/fukan/target/clojure/projector_test.clj`

Wire components 1 and 2 of the six-component assembly. Reuse Plan 5's `address/canonical`. Artifact-kind is mechanical from `(primitive-kind, projection-kind)`:

| primitive-kind | projection-kind | artifact-kind |
|---|---|---|
| `:primitive/operation` | `:operation` or `:test` | `:code/function` |
| `:primitive/rule` | `:rule` or `:test` | `:code/function` |
| `:primitive/expression` (invariant) | `:invariant` or `:test` | `:code/function` |
| `:primitive/container` (Entity/Value/Variant) | `:schema` | `:code/data-structure` |
| `:primitive/event` | `:schema` | `:code/data-structure` |

### Step 2.1: Test

Append to `test/fukan/target/clojure/projector_test.clj`:

```clojure
(ns fukan.target.clojure.projector-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(deftest project-operation-as-operation
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-operation
                                         {:id "m::Contract.submit" :label "submit"
                                          :parameters []})))
        bp (projector/project model (registry/make-registry)
                              "m::Contract.submit" :projection-kind/operation)]
    (is (= "m::Contract.submit" (:primitive-id bp)))
    (is (= :projection-kind/operation (:projection-kind bp)))
    (is (= {:ns "m" :name "submit"} (:address bp)))
    (is (= :code/function (:artifact-kind bp)))))

(deftest project-rule-as-test
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-rule {:id "m::ProcessOrder" :label "ProcessOrder"})))
        bp (projector/project model (registry/make-registry)
                              "m::ProcessOrder" :projection-kind/test)]
    (is (= {:ns "m-test" :name "process-order-test"} (:address bp)))
    (is (= :code/function (:artifact-kind bp)))))

(deftest project-entity-as-schema
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-container {:id "m::Order" :label "Order"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Entity"}
                       :target {:case :target/primitive :id "m::Order"}})))
        bp (projector/project model (registry/make-registry)
                              "m::Order" :projection-kind/schema)]
    (is (= {:ns "m" :name "Order"} (:address bp)))
    (is (= :code/data-structure (:artifact-kind bp)))))

(deftest project-unknown-primitive-throws
  (let [model (build/empty-model)]
    (is (thrown? Exception
                 (projector/project model (registry/make-registry)
                                    "missing::F" :projection-kind/rule)))))
```

### Step 2.2: Implement

Replace `src/fukan/target/clojure/projector.clj`:

```clojure
(ns fukan.target.clojure.projector
  "Projector — assembles Implementation Blueprints on demand.

   Six-component universal projection mechanic per MODEL.md §7.7:
     1. Canonical address          (Task 2)
     2. Artifact kind              (Task 2)
     3. Expected signature         (Task 3)
     4. Type renderings            (Task 4)
     5. Surrounding model context  (Task 5)
     6. Selected idioms            (Task 6)
   Plus serialisation               (Task 7).

   Application of the same mechanic the Analyzer (Plan 5) uses, in
   reverse: spec primitive → Blueprint that the LLM (generation) and the
   Analyzer (verification) both consume."
  (:require [clojure.string :as str]
            [fukan.target.clojure.address :as addr]
            [fukan.target.clojure.blueprint :as bp]))

(defn- module-coord-of
  [primitive-id]
  (when (and (string? primitive-id) (str/includes? primitive-id "::"))
    (first (str/split primitive-id #"::" 2))))

(def ^:private function-kinds
  #{:projection-kind/rule :projection-kind/operation
    :projection-kind/invariant :projection-kind/test})

(def ^:private data-structure-kinds
  #{:projection-kind/schema})

(defn- artifact-kind-for
  [projection-kind]
  (cond
    (contains? function-kinds projection-kind)       :code/function
    (contains? data-structure-kinds projection-kind) :code/data-structure
    :else (throw (ex-info "unknown projection-kind"
                          {:projection-kind projection-kind}))))

(defn project
  "Project a primitive into a Blueprint.

   Tasks 1-2 cover the address + artifact-kind assembly. Tasks 3-7
   extend with signature, context, idioms, and rendered serialisations."
  [model registry primitive-id projection-kind]
  (let [primitive (get-in model [:primitives primitive-id])]
    (when-not primitive
      (throw (ex-info "primitive not found in model"
                      {:primitive-id primitive-id})))
    (let [primitive-kind (:kind primitive)
          module-coord   (module-coord-of primitive-id)
          address        (addr/canonical registry primitive-kind projection-kind
                                         module-coord (:label primitive))
          artifact-kind  (artifact-kind-for projection-kind)]
      (bp/make
        {:primitive-id    primitive-id
         :projection-kind projection-kind
         :address         address
         :artifact-kind   artifact-kind}))))
```

### Step 2.3: Run, expect pass + commit

Expected: 397 + 4 = **401/0/0**. The Task 0 smoke `projector-on-empty-model-returns-empty-blueprint` now fails because `project` throws on unknown primitive — DELETE that test, replaced by `project-unknown-primitive-throws` in Step 2.1.

```bash
jj desc -m "feat(target-clojure): Projector — address + artifact-kind assembly

Components 1-2 of the six-component Blueprint per MODEL.md §7.7. Reuses
Plan 5's address/canonical for the canonical address; artifact-kind
mechanical from projection-kind via two enums (function-kinds,
data-structure-kinds). Throws when the primitive isn't in the model.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: Expected-signature assembly

**Files:**
- Modify: `src/fukan/target/clojure/projector.clj`
- Create: `test/fukan/target/clojure/projector_signature_test.clj`

Component 3. Per primitive-kind + projection-kind:

| Case | Signature form |
|---|---|
| Operation (operation kind) | `{:arglist [<param-name>...] :return-malli <type-renderings>}` |
| Operation (test kind) | `{:arglist [] :return-malli nil}` (assertion-as-test convention) |
| Rule (rule kind) | `{:arglist [] :return-malli nil}` |
| Rule (test kind) | `{:arglist [] :return-malli nil}` |
| Invariant (invariant kind) | `{:arglist [] :return-malli nil}` |
| Invariant (test kind) | `{:arglist [] :return-malli nil}` |
| Container (schema) | `{:malli-shape [:map [<field-name> <type-rendering>...]]}` |
| Event (schema) | `{:malli-shape [:map [<param-name> <type-rendering>...]]}` |

For Operations: `:parameters` is a vector of Parameter records with `{:name :type-ref :optional :ordinal}`. The arglist is `[name1 name2 ...]` in ordinal order; the per-parameter type renderings go into `:param-types`.

For Containers: `:fields` is a vector of Field records with `{:name :type-ref :optional}`. Translate field names to kebab-case keywords for the malli map keys.

### Step 3.1: Test

Create `test/fukan/target/clojure/projector_signature_test.clj`:

```clojure
(ns fukan.target.clojure.projector-signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

(defn- module-with [primitive]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive primitive)))

(deftest operation-signature-from-parameters
  (let [op (p/make-operation
             {:id "m::Contract.submit" :label "submit"
              :parameters [(p/make-parameter "order-id" (t/make-scalar "String") false 0)
                           (p/make-parameter "qty"      (t/make-scalar "Integer") false 1)]})
        model (module-with op)
        ;; also tag the container as a Contract for completeness
        bp (projector/project model (registry/make-registry)
                              "m::Contract.submit" :projection-kind/operation)]
    (is (= ["order-id" "qty"] (-> bp :signature :arglist)))
    (is (= [:string :int]    (-> bp :signature :param-types)))))

(deftest operation-test-signature-is-arity-zero
  (let [op (p/make-operation
             {:id "m::Contract.go" :label "go" :parameters []})
        model (module-with op)
        bp (projector/project model (registry/make-registry)
                              "m::Contract.go" :projection-kind/test)]
    (is (= [] (-> bp :signature :arglist)))))

(deftest rule-signature-is-arity-zero
  (let [r (p/make-rule {:id "m::CheckIt" :label "CheckIt"})
        model (module-with r)
        bp (projector/project model (registry/make-registry)
                              "m::CheckIt" :projection-kind/rule)]
    (is (= [] (-> bp :signature :arglist)))
    (is (nil? (-> bp :signature :return-malli)))))

(deftest container-schema-signature-is-malli-map
  (let [c (p/make-container
            {:id "m::Order" :label "Order"
             :fields [(p/make-field "id"    (t/make-scalar "String") false)
                      (p/make-field "total" (t/make-scalar "Integer") false)]})
        model (-> (module-with c)
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Entity"}
                       :target {:case :target/primitive :id "m::Order"}})))
        bp (projector/project model (registry/make-registry)
                              "m::Order" :projection-kind/schema)]
    (is (= [:map [:id :string] [:total :int]]
           (-> bp :signature :malli-shape)))))

(deftest event-schema-signature-from-parameters
  (let [e (p/make-event
            {:id "m::events::OrderPlaced" :label "OrderPlaced"
             :parameters [(p/make-parameter "order-id" (t/make-scalar "String") false 0)]})
        model (module-with e)
        bp (projector/project model (registry/make-registry)
                              "m::events::OrderPlaced" :projection-kind/schema)]
    (is (= [:map [:order-id :string]]
           (-> bp :signature :malli-shape)))))
```

### Step 3.2: Implement

Add to `src/fukan/target/clojure/projector.clj`:

```clojure
(require '[fukan.target.clojure.types :as types])

(defn- field->malli-pair
  [registry field]
  [(keyword (:name field)) (types/render registry (:type-ref field))])

(defn- param->malli-pair
  [registry param]
  [(keyword (:name param)) (types/render registry (:type-ref param))])

(defn- signature-for
  [registry primitive projection-kind]
  (case [(:kind primitive) projection-kind]
    [:primitive/operation :projection-kind/operation]
    {:arglist     (mapv :name (:parameters primitive))
     :param-types (mapv #(types/render registry (:type-ref %)) (:parameters primitive))
     :return-malli (when-let [rt (:return-type primitive)]
                     (types/render registry rt))}

    ;; arity-zero forms
    [:primitive/operation :projection-kind/test]   {:arglist [] :return-malli nil}
    [:primitive/rule      :projection-kind/rule]   {:arglist [] :return-malli nil}
    [:primitive/rule      :projection-kind/test]   {:arglist [] :return-malli nil}
    [:primitive/expression :projection-kind/invariant] {:arglist [] :return-malli nil}
    [:primitive/expression :projection-kind/test]      {:arglist [] :return-malli nil}

    ;; schema map shape
    [:primitive/container :projection-kind/schema]
    {:malli-shape (into [:map] (map #(field->malli-pair registry %) (:fields primitive)))}

    [:primitive/event :projection-kind/schema]
    {:malli-shape (into [:map] (map #(param->malli-pair registry %) (:parameters primitive)))}

    nil))
```

Extend `project` to attach `:signature`:

```clojure
(defn project
  [model registry primitive-id projection-kind]
  (let [primitive (get-in model [:primitives primitive-id])]
    (when-not primitive
      (throw (ex-info "primitive not found in model" {:primitive-id primitive-id})))
    (let [primitive-kind (:kind primitive)
          module-coord   (module-coord-of primitive-id)
          address        (addr/canonical registry primitive-kind projection-kind
                                         module-coord (:label primitive))
          artifact-kind  (artifact-kind-for projection-kind)
          signature      (signature-for registry primitive projection-kind)]
      (bp/make
        {:primitive-id primitive-id :projection-kind projection-kind
         :address address :artifact-kind artifact-kind
         :signature signature}))))
```

### Step 3.3: Run, expect pass + commit

Expected: 401 + 5 = **406/0/0**.

```bash
jj desc -m "feat(target-clojure): Projector — expected-signature assembly

Component 3 of six. Per primitive-kind + projection-kind:
- Operation (operation): arglist + param-types + return-malli
- Operation/Rule/Invariant (test/rule/invariant): arity-zero
- Container (Entity/Value/Variant, schema): malli [:map ...] from :fields
- Event (schema): malli [:map ...] from :parameters

Field/param names render as kebab-keywords. Type translation via
Plan 5's types/render.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: Type renderings already covered

Plan 5's `fukan.target.clojure.types/render` handles component 4 in full. Task 3 already pulls type renderings into both `:param-types` and `:malli-shape`. No standalone task needed.

This entry exists in the plan for **traceability with MODEL.md §7.7's six-component list** — there's nothing further to implement. Skip to Task 5.

---

## Task 5: Surrounding model context

**Files:**
- Modify: `src/fukan/target/clojure/projector.clj`
- Create: `test/fukan/target/clojure/projector_context_test.clj`

Component 5. The context bundles enough surrounding Model state that the LLM (or human reviewer) can write the projection without re-reading the spec:

```clojure
{:description   <primitive's :description or nil>
 :intent        <primitive's :intent map or nil — :clauses + :assertions>
 :related-edges [<edge>...]  ;; outgoing edges from this primitive in human-readable shape
 :host          <enclosing primitive id if relevant>}
```

`:related-edges` is filtered + denormalised:
- Include edges where `(:from %) = primitive-ref(primitive-id)` only (outgoing).
- Drop `:relation/projects` edges (they're meta-noise inside a Blueprint).
- For each kept edge, emit `{:kind <relation/X> :to-label <target primitive's :label>}`.

`:host`:
- For an Operation: the Contract container id (extracted from `m::Contract.op` → `m::Contract`).
- For a Rule: the Behaviour or Container that lists this Rule in its `:rules`.
- For an Invariant: the host Container id (stored on the Expression's location).
- For an Entity/Value/Variant/Event: the parent Module container id.

### Step 5.1: Test

Create `test/fukan/target/clojure/projector_context_test.clj`:

```clojure
(ns fukan.target.clojure.projector-context-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.relations :as r]))

(defn- module-with [primitive]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive primitive)))

(deftest rule-context-includes-description
  (let [r (p/make-rule {:id "m::CheckIt" :label "CheckIt"
                        :description "checks that the thing is ok"})
        model (module-with r)
        bp (projector/project model (registry/make-registry)
                              "m::CheckIt" :projection-kind/rule)]
    (is (= "checks that the thing is ok" (-> bp :context :description)))))

(deftest rule-context-related-edges-filters-projects
  (let [r1 (p/make-rule {:id "m::R1" :label "R1"})
        e1 (p/make-event {:id "m::events::E1" :label "E1" :parameters []})
        model (-> (module-with r1)
                  (build/add-primitive e1)
                  (build/add-edge (r/make-edge :relation/emits
                                               (r/primitive-ref "m::R1")
                                               (r/primitive-ref "m::events::E1"))))
        bp (projector/project model (registry/make-registry)
                              "m::R1" :projection-kind/rule)
        rel (:related-edges (:context bp))]
    (is (= 1 (count rel)))
    (is (= :relation/emits (-> rel first :kind)))
    (is (= "E1" (-> rel first :to-label)))))
```

### Step 5.2: Implement

Add to `src/fukan/target/clojure/projector.clj`:

```clojure
(defn- outgoing-edges-of
  [model primitive-id]
  (filter (fn [e]
            (and (= :endpoint/primitive (-> e :from :case))
                 (= primitive-id (-> e :from :id))
                 (not= :relation/projects (:kind e))))
          (:edges model)))

(defn- edge->context-entry
  [model edge]
  (let [to (:to edge)
        target-id (case (:case to)
                    :endpoint/primitive (:id to)
                    :endpoint/substrate (:container to)
                    :endpoint/artifact  nil)
        target-label (when target-id
                       (or (get-in model [:primitives target-id :label]) target-id))]
    {:kind     (:kind edge)
     :to-label target-label}))

(defn- context-for
  [model primitive primitive-id]
  {:description   (:description primitive)
   :intent        (:intent primitive)
   :related-edges (mapv #(edge->context-entry model %)
                        (outgoing-edges-of model primitive-id))
   :host          (when-let [host-edge (first (filter (fn [e]
                                                        (and (= :relation/exposes (:kind e))
                                                             (= primitive-id (-> e :to :id))))
                                                      (:edges model)))]
                    (-> host-edge :from :id))})
```

Extend `project` to attach `:context`:

```clojure
;; ... after :signature step ...
context (context-for model primitive primitive-id)
;; pass :context context into bp/make
```

### Step 5.3: Run, expect pass + commit

Expected: 406 + 2 = **408/0/0**.

```bash
jj desc -m "feat(target-clojure): Projector — surrounding model context

Component 5 of six. Bundles :description, :intent, :related-edges
(outgoing non-projects edges), and :host (computed from :exposes
edges for now; future expansion may resolve Behaviour/Container hosts
via additional relation kinds).

Per-edge context entry is {:kind <relation> :to-label <target label>}
— denormalised so the LLM doesn't need full Model access.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: Idiom router

**Files:**
- Modify: `src/fukan/target/clojure/projector.clj`
- Create: `test/fukan/target/clojure/projector_idiom_test.clj`

Component 6. The project registry's `:idioms` field is a vector of `{:route :body}` entries. Each idiom's route is a map of optional predicates:

```clojure
{:route {:primitive-kind :primitive/rule          ; optional
         :projection-kind :projection-kind/test   ; optional
         :address-pattern #".*-handler$"}         ; optional regex
 :body "use defmulti dispatch for handlers"}
```

The router matches idioms where every present predicate matches the current projection. All matching idioms compose (order preserved from registry insertion). MVP: no severity, no priority — just collect all matches.

### Step 6.1: Test

Create `test/fukan/target/clojure/projector_idiom_test.clj`:

```clojure
(ns fukan.target.clojure.projector-idiom-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- module-with-rule [label]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive (p/make-rule {:id (str "m::" label) :label label}))))

(deftest idiom-matches-by-primitive-kind
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:primitive-kind :primitive/rule}
                   :body "rules use threading macros"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/rule)]
    (is (= ["rules use threading macros"] (:idioms bp)))))

(deftest idiom-no-match
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:primitive-kind :primitive/operation}
                   :body "ops use core.async"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/rule)]
    (is (= [] (:idioms bp)))))

(deftest idiom-composes-multiple-matches
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:primitive-kind :primitive/rule}
                   :body "rules use threading"})
                (registry/with-idiom
                  {:route {:projection-kind :projection-kind/rule}
                   :body "rules return :ok | :error"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/rule)]
    (is (= ["rules use threading" "rules return :ok | :error"] (:idioms bp)))))

(deftest idiom-address-pattern
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:address-pattern #".*-test$"}
                   :body "tests use kaocha"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/test)]
    (is (= ["tests use kaocha"] (:idioms bp)))))
```

### Step 6.2: Implement

Add to `src/fukan/target/clojure/projector.clj`:

```clojure
(defn- idiom-matches?
  [route ctx]
  (let [{:keys [primitive-kind projection-kind address-pattern]} route
        {:keys [primitive-kind-actual projection-kind-actual address-name]} ctx]
    (and (or (nil? primitive-kind)   (= primitive-kind primitive-kind-actual))
         (or (nil? projection-kind)  (= projection-kind projection-kind-actual))
         (or (nil? address-pattern)  (re-find address-pattern address-name)))))

(defn- select-idioms
  [registry primitive projection-kind address]
  (let [ctx {:primitive-kind-actual  (:kind primitive)
             :projection-kind-actual projection-kind
             :address-name           (:name address)}]
    (vec
      (keep (fn [idiom]
              (when (idiom-matches? (:route idiom) ctx)
                (:body idiom)))
            (:idioms registry)))))
```

Extend `project` to attach `:idioms`:

```clojure
;; ... after :context step ...
idioms (select-idioms registry primitive projection-kind address)
;; pass :idioms idioms into bp/make
```

### Step 6.3: Run, expect pass + commit

Expected: 408 + 4 = **412/0/0**.

```bash
jj desc -m "feat(target-clojure): Projector — idiom router

Component 6 of six. Match registry idioms by route predicate triple:
:primitive-kind, :projection-kind, :address-pattern (regex). All present
predicates must match. All matching idioms compose in registry order.

MVP scope: no severity, no priority, no conflict resolution. The
project layer's :idioms vector is the source of truth. Composition
mechanics (overrides, profiles, bundles) deferred per Plan 2 / DESIGN.md.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: Blueprint markdown serialisation

**Files:**
- Modify: `src/fukan/target/clojure/blueprint.clj`
- Modify: `test/fukan/target/clojure/blueprint_test.clj`

Per DESIGN.md "Concrete Blueprint serialisation" line 793: "whether it serialises as structured EDN, prompt-shaped markdown, or a hybrid is implementation-time detail. The LLM-prompting side may want markdown; the Analyzer's comparison side may want structured data. Likely both surfaces produced by one Projector call."

Add a markdown serialiser. Per-section structure:

```markdown
# Projecting `<primitive-id>` as `<projection-kind>`

## Canonical address

`<ns>/<name>`

## Artifact kind

`<:code/function | :code/data-structure>`

## Expected signature

```clojure
;; for Operations:
(defn <name> [<arglist>] ...)
;; for schemas:
(def <Name>
  [:map ...])
```

## Context

**Description:** ...

**Intent (clauses):**
- ...

**Related edges:**
- `<relation>` → `<target-label>`

## Idioms

- ...
```

`bp/to-markdown [bp]` returns a string. No assertion on byte-exactness — just shape (key sections present).

### Step 7.1: Test

Add to `test/fukan/target/clojure/blueprint_test.clj`:

```clojure
(deftest blueprint-to-markdown-includes-key-sections
  (let [b (bp/make {:primitive-id "m::Foo"
                    :projection-kind :projection-kind/rule
                    :address {:ns "m" :name "foo"}
                    :artifact-kind :code/function
                    :signature {:arglist [] :return-malli nil}
                    :context {:description "checks foo"
                              :intent nil
                              :related-edges []}
                    :idioms ["use threading macros"]})
        md (bp/to-markdown b)]
    (is (string? md))
    (is (re-find #"(?i)projecting `m::Foo`" md))
    (is (re-find #"(?i)canonical address" md))
    (is (re-find #"`m/foo`" md))
    (is (re-find #"(?i)artifact kind" md))
    (is (re-find #":code/function" md))
    (is (re-find #"(?i)expected signature" md))
    (is (re-find #"(?i)context" md))
    (is (re-find #"checks foo" md))
    (is (re-find #"(?i)idioms" md))
    (is (re-find #"use threading macros" md))))
```

### Step 7.2: Implement

Add to `src/fukan/target/clojure/blueprint.clj`:

```clojure
(defn- signature->code-block
  [signature artifact-kind name]
  (cond
    (:malli-shape signature)
    (str "```clojure\n(def " name "\n  " (pr-str (:malli-shape signature)) ")\n```\n")

    (= :code/function artifact-kind)
    (str "```clojure\n(defn " name " [" (clojure.string/join " " (:arglist signature)) "]\n  ...)\n```\n")

    :else "```clojure\n;; signature TBD\n```\n"))

(defn- context->markdown
  [context]
  (let [{:keys [description intent related-edges]} context]
    (str
      (when description (str "**Description:** " description "\n\n"))
      (when (and intent (seq (:clauses intent)))
        (str "**Intent (clauses):**\n"
             (clojure.string/join "\n" (map #(str "- " %) (:clauses intent)))
             "\n\n"))
      (when (seq related-edges)
        (str "**Related edges:**\n"
             (clojure.string/join "\n" (map #(str "- `" (name (:kind %))
                                                   "` → `" (:to-label %) "`")
                                            related-edges))
             "\n\n")))))

(defn to-markdown
  "Serialise a Blueprint to markdown — LLM prompt + human inspection form."
  [{:keys [primitive-id projection-kind address artifact-kind
           signature context idioms] :as bp}]
  (str
    "# Projecting `" primitive-id "` as `" projection-kind "`\n\n"
    "## Canonical address\n\n`" (:ns address) "/" (:name address) "`\n\n"
    "## Artifact kind\n\n`" artifact-kind "`\n\n"
    "## Expected signature\n\n"
    (signature->code-block signature artifact-kind (:name address))
    "\n## Context\n\n" (or (context->markdown context) "")
    "## Idioms\n\n"
    (if (seq idioms)
      (clojure.string/join "\n" (map #(str "- " %) idioms))
      "_(none registered)_")
    "\n"))
```

### Step 7.3: Wire to-edn and to-markdown into Projector's :rendered

Extend `project` in `projector.clj`:

```clojure
;; build the rest of the blueprint first, then add :rendered
(let [base (bp/make {:primitive-id ... :projection-kind ... :address ...
                     :artifact-kind ... :signature ... :context ... :idioms ...})]
  (assoc base :rendered {:edn      (bp/to-edn base)
                         :markdown (bp/to-markdown base)}))
```

### Step 7.4: Run, expect pass + commit

Expected: 412 + 1 = **413/0/0**.

```bash
jj desc -m "feat(target-clojure): Blueprint markdown serialisation + rendered wiring

Per DESIGN.md 'Concrete Blueprint serialisation' (line 793). Markdown
form is LLM-prompt-shaped: header, canonical address, artifact kind,
expected signature (defn / def malli template), context (description,
intent clauses, related edges), idioms.

Projector now produces both :rendered.edn and :rendered.markdown in a
single call. Inspection consumers pick whichever form they need.

Closes Projector half of Plan 6. Tasks 8-13 cover the Explorer + the
generation flow + Plan-5 carry-forwards.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 8: Projection layer — Model → graph

**Files:**
- Modify: `src/fukan/projection/core.clj`
- Create: `test/fukan/projection/core_test.clj` (extend existing smoke)

Build `project-model [model] → {:nodes :edges}` over the new Model. Replaces the OLD code-graph projection per DESIGN.md line 493.

**Nodes:**
- Every primitive becomes a node `{:id :kind :label :parent? :allium-kind?}` where:
  - `:kind` is the kernel kind keyword (`:primitive/container`, `:primitive/rule`, ...).
  - `:parent` resolves to the host module/container id when nesting is known (via `:children` set on Container or via `:intent/host` for Behaviour).
  - `:allium-kind` is the Allium tag (Entity / Surface / Rule / etc.) extracted from tag-applications — informs node-icon picking.
- Every artifact becomes a node `{:id (artifact-identity) :kind :artifact/code-function|code-data-structure :label (qualified-name) :source-location}`.

**Edges:**
- Every kernel edge becomes a UI edge `{:id :from :to :kind :metadata}`:
  - `:id` is a synthetic sequential `e0`, `e1`, ... for cytoscape.
  - `:from` / `:to` resolve to node ids — for substrate-address endpoints, use the container-id; for artifact endpoints, use a stringified artifact-identity (e.g., `"artifact:code/function:clojure:m/foo"`).
  - `:kind` is the relation kind keyword.
  - For `:relation/projects` edges, also include `:projection-kind` and `:validity` so the Explorer can style drift markers.

### Step 8.1: Test

Extend `test/fukan/projection/core_test.clj`:

```clojure
(ns fukan.projection.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.projection.core :as proj]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.relations :as r]))

(deftest project-model-on-empty-returns-empty-graph
  (let [g (proj/project-model (build/empty-model))]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))

(deftest project-model-includes-primitives-as-nodes
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))
        g (proj/project-model model)
        ids (set (map :id (:nodes g)))]
    (is (contains? ids "m"))
    (is (contains? ids "m::R"))
    (is (= 2 (count (:nodes g))))))

(deftest project-model-includes-artifacts-as-nodes
  (let [artifact {:case :artifact/code
                  :language "clojure"
                  :sub {:case :code/function :qualified-name "m/foo"}}
        model (-> (build/empty-model)
                  (assoc-in [:artifacts [:code/function "clojure" "m/foo"]] artifact))
        g (proj/project-model model)
        ids (set (map :id (:nodes g)))]
    (is (contains? ids "artifact:code/function:clojure:m/foo"))
    (is (= 1 (count (:nodes g))))))

(deftest project-model-edges-include-relation-kind
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-primitive (p/make-event {:id "m::E" :label "E" :parameters []}))
                  (build/add-edge (r/make-edge :relation/emits
                                               (r/primitive-ref "m::R")
                                               (r/primitive-ref "m::E"))))
        g (proj/project-model model)]
    (is (= 1 (count (:edges g))))
    (is (= :relation/emits (-> g :edges first :kind)))
    (is (= "m::R" (-> g :edges first :from)))
    (is (= "m::E" (-> g :edges first :to)))))
```

### Step 8.2: Implement

Replace `src/fukan/projection/core.clj`:

```clojure
(ns fukan.projection.core
  "Model → cytoscape-compatible graph projection.

   Replaces the OLD code-graph projection (per DESIGN.md line 493) with
   one that consumes the new Model (primitives + thirteen kernel
   relations + artifacts).

   Output shape:
     {:nodes [<node>...]
      :edges [<edge>...]}
   where node and edge keys are kebab-case Clojure data; the
   web/views/cytoscape transformer handles camelCase + JSON output.")

(defn- allium-kind-of
  [model id]
  (let [tags (filter (fn [ta]
                       (and (= "Allium" (-> ta :tag :namespace))
                            (= :target/primitive (-> ta :target :case))
                            (= id (-> ta :target :id))))
                     (:tag-apps model))]
    (some (comp keyword clojure.string/lower-case :name :tag) tags)))

(defn- primitive->node
  [model [id primitive]]
  (cond-> {:id    id
           :kind  (:kind primitive)
           :label (:label primitive)}
    (allium-kind-of model id) (assoc :allium-kind (allium-kind-of model id))))

(defn- artifact-node-id
  [aid]
  (let [[case-kw language qname] aid]
    (str "artifact:" (name case-kw) ":" language ":" qname)))

(defn- artifact->node
  [[aid artifact]]
  (let [[case-kw _ qname] aid]
    {:id    (artifact-node-id aid)
     :kind  (keyword "artifact" (name case-kw))
     :label qname
     :source-location (get-in artifact [:sub :source-location])}))

(defn- endpoint->node-id
  [endpoint]
  (case (:case endpoint)
    :endpoint/primitive (:id endpoint)
    :endpoint/substrate (:container endpoint)
    :endpoint/artifact  (artifact-node-id (:id endpoint))))

(defn- edge->ui-edge
  [idx edge]
  (cond-> {:id    (str "e" idx)
           :from  (endpoint->node-id (:from edge))
           :to    (endpoint->node-id (:to edge))
           :kind  (:kind edge)}
    (= :relation/projects (:kind edge))
    (assoc :projection-kind (:projection-kind edge)
           :validity        (:validity edge))))

(defn project-model
  "Project a Model into {:nodes :edges} in the new format."
  [model]
  {:nodes (vec (concat
                 (map #(primitive->node model %) (:primitives model))
                 (map artifact->node (:artifacts model))))
   :edges (vec (map-indexed edge->ui-edge (:edges model)))})
```

### Step 8.3: Run, expect pass + commit

Expected: 413 + 3 (the smoke from Task 0 stays + 3 new) = **416/0/0**. The Task-0 smoke test continues to pass since empty model still returns empty graph.

```bash
jj desc -m "feat(projection): Model → cytoscape-compatible graph projection

Replaces the OLD code-graph projection (per DESIGN.md line 493).
Surfaces primitives + artifacts as nodes; thirteen kernel relations
as edges; projects edges carry :projection-kind + :validity so the
Explorer can style drift markers.

Artifact node ids use the synthetic shape
'artifact:<case>:<language>:<qualified-name>' so cytoscape can
address them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 9: Cytoscape transformer + drift styling

**Files:**
- Modify: `src/fukan/web/views/cytoscape.clj`
- Modify: `src/fukan/web/views/graph.clj`
- Create: `test/fukan/web/views/cytoscape_new_test.clj`

Update the cytoscape transformer to handle new node/edge kinds. Key changes:
- Node `:kind` is now a kernel-primitive keyword OR `:artifact/code-function|code-data-structure` — `(name kind)` still works.
- Edge `:kind` is a relation keyword like `:relation/projects` — `(name kind)` works but produces `"projects"` (drop the `relation/` prefix in the UI).
- Drift styling: when an edge has `:validity :absent`, the cytoscape edge gets `:drift "absent"` (string for CSS class).
- Projection-kind goes on the edge as `:projectionKind` (camelCase).

### Step 9.1: Test

Create `test/fukan/web/views/cytoscape_new_test.clj`:

```clojure
(ns fukan.web.views.cytoscape-new-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.web.views.cytoscape :as cyto]))

(deftest transform-primitive-node
  (let [graph {:nodes [{:id "m::R" :kind :primitive/rule :label "R"}]
               :edges []}
        out (cyto/graph->cytoscape graph nil [])]
    (is (= "primitive/rule" (-> out :nodes first :kind))
        "kernel kind keyword renders verbatim")))

(deftest transform-projects-edge-with-drift
  (let [graph {:nodes []
               :edges [{:id "e0" :from "m::R" :to "artifact:code/function:clojure:m/r"
                        :kind :relation/projects
                        :projection-kind :projection-kind/rule
                        :validity :absent}]}
        out (cyto/graph->cytoscape graph nil [])
        edge (first (:edges out))]
    (is (= "relation/projects" (:edgeType edge)))
    (is (= "projection-kind/rule" (:projectionKind edge)))
    (is (= "absent" (:drift edge)))))

(deftest transform-projects-edge-valid-no-drift
  (let [graph {:nodes []
               :edges [{:id "e0" :from "a" :to "b"
                        :kind :relation/projects :validity :valid}]}
        out (cyto/graph->cytoscape graph nil [])
        edge (first (:edges out))]
    (is (nil? (:drift edge)) "valid edges carry no drift class")))
```

### Step 9.2: Implement

Replace `src/fukan/web/views/cytoscape.clj` (be careful — keep the existing schemas section intact; just widen the `node->cytoscape` and `edge->cytoscape` helpers):

The existing `node->cytoscape` and `edge->cytoscape` use the OLD field shapes (`:selected?`, `:expandable?`, `:edge-type`). Plan 6 replaces them with the new-model field shapes. Keep backward-compatible behaviour for any remaining OLD callers (none expected post-Task-10) but the primary path is the new shape.

Replace `node->cytoscape`:

```clojure
(defn- node->cytoscape
  "Transform a projection node to Cytoscape format.
   Node :kind is a keyword (kernel primitive or :artifact/*).
   Optional :allium-kind, :parent, :source-location, :selected? for UI state."
  [{:keys [id kind label parent allium-kind selected? source-location]}]
  (cond-> {:id       id
           :kind     (name kind)
           :label    (or label id)
           :selected (boolean selected?)}
    parent          (assoc :parent parent)
    allium-kind     (assoc :alliumKind (name allium-kind))
    source-location (assoc :sourceLocation source-location)))
```

Replace `edge->cytoscape`:

```clojure
(defn- edge->cytoscape
  "Transform a projection edge to Cytoscape format.
   Edge :kind is a relation keyword (:relation/X). :projection-kind +
   :validity are present for :relation/projects edges only."
  [{:keys [id from to kind projection-kind validity]}]
  (cond-> {:id       id
           :source   from
           :target   to
           :edgeType (name kind)
           :kind     (name kind)}
    projection-kind (assoc :projectionKind (name projection-kind))
    (= :absent validity) (assoc :drift "absent")))
```

`graph->cytoscape` signature stays. The internal `:highlightedEdges` continues to work.

### Step 9.3: Update graph.clj to consume the new projection

Replace the projection input in `src/fukan/web/views/graph.clj`'s `render-graph`. The function still takes `(graph-projection editor-state)`, but graph-projection is now produced by `fukan.projection.core/project-model`. The downstream `compute-highlighted-edges` uses `:from/:to` — keep as-is, the new format also uses these keys.

Drop the OLD `find-projection-root` logic — for MVP, the editor doesn't pick a root; the cytoscape view shows the entire graph and the user navigates.

```clojure
(defn render-graph
  "Render graph data for Cytoscape.
   Takes a graph projection (from projection.core/project-model) and editor-state.
   Returns Cytoscape-compatible {:nodes :edges :selectedId :highlightedEdges}."
  [graph-projection {:keys [selected-id] :as _editor-state}]
  (let [nodes (:nodes graph-projection)
        graph (-> graph-projection
                  (update :nodes (fn [ns]
                                   (mapv (fn [n] (assoc n :selected?
                                                        (= (:id n) selected-id)))
                                         ns))))
        highlighted-edges (compute-highlighted-edges nodes (:edges graph) selected-id)]
    (cytoscape/graph->cytoscape graph selected-id highlighted-edges)))
```

### Step 9.4: Run, expect pass + commit

Expected: 416 + 3 = **419/0/0**. Some pre-existing graph/cytoscape tests may fail because they assert on OLD field shapes; update them inline to the new shape OR delete them as no-longer-relevant. Document any deletions in the commit message.

```bash
jj desc -m "feat(web/views): cytoscape transformer for new Model shapes + drift styling

Node :kind is now a kernel primitive keyword (or :artifact/*); renders
verbatim via name. Edge :kind is a relation keyword (e.g. :relation/projects);
also renders via name.

:relation/projects edges carry :projection-kind + :validity. When
:validity is :absent, the cytoscape edge gets :drift 'absent' so the
frontend can style it as a red drift marker per VISION.md.

graph.clj's render-graph consumes the new projection.core/project-model
output. OLD code-graph rendering path removed.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 10: HTTP router rewrite — Phase-6 model + /projector endpoint

**Files:**
- Modify: `src/fukan/web/handler.clj`
- Create: `test/fukan/web/handler_test.clj`

Replace the Plan-1 stub handler (line 4: "Full router is rewritten in Plan 6") with the routes Plan 6 needs:

- `GET /` — existing shell (Datastar-driven SPA frame). Keep using `views.shell/render-shell`.
- `GET /graph` — returns cytoscape JSON via `projection.core/project-model` → `views.graph/render-graph`.
- `GET /projector?primitive-id=X&projection-kind=Y` — invokes Projector, returns Blueprint as JSON (both `:edn` string and `:markdown` string in the payload).
- `GET /sidebar?id=X` — returns entity detail HTML for the sidebar panel; MVP renders primitive id/label/intent OR artifact qualified-name/source-location.

Model fetch comes from `fukan.infra.model/get-model` (existing).

### Step 10.1: Test

Create `test/fukan/web/handler_test.clj`:

```clojure
(ns fukan.web.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.web.handler :as handler]
            [fukan.infra.model :as infra-model]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [cheshire.core :as json]))

(defn- swap-model! [m]
  ;; Test helper — set the infra model atom directly via reset!.
  ;; In production this is loaded via infra-model/load-model.
  (reset! @(resolve 'fukan.infra.model/state) {:model m :src "test"}))

(deftest get-graph-returns-cytoscape-json
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))]
    (swap-model! model)
    (let [h (handler/create-handler)
          response (h {:request-method :get :uri "/graph"})]
      (is (= 200 (:status response)))
      (is (str/includes? (get-in response [:headers "Content-Type"]) "json"))
      (let [body (json/parse-string (:body response) true)]
        (is (>= (count (:nodes body)) 2))))))

(deftest get-projector-returns-blueprint
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))]
    (swap-model! model)
    (let [h (handler/create-handler)
          response (h {:request-method :get
                       :uri "/projector"
                       :query-params {"primitive-id" "m::R"
                                      "projection-kind" "projection-kind/rule"}})]
      (is (= 200 (:status response)))
      (let [body (json/parse-string (:body response) true)]
        (is (= "m::R" (:primitiveId body)))
        (is (contains? (:rendered body) :markdown))))))

(deftest get-projector-missing-params-returns-400
  (let [h (handler/create-handler)
        response (h {:request-method :get :uri "/projector"})]
    (is (= 400 (:status response)))))
```

### Step 10.2: Implement

Replace `src/fukan/web/handler.clj`:

```clojure
(ns fukan.web.handler
  "HTTP routing — Phase-6 Model surface.

   Routes:
     GET /          — shell SPA frame
     GET /graph     — cytoscape JSON (projection.core/project-model)
     GET /projector — Blueprint JSON for primitive-id + projection-kind
     GET /sidebar   — entity detail HTML"
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]
            [cheshire.core :as json]
            [clojure.string :as str]
            [fukan.infra.model :as infra-model]
            [fukan.projection.core :as projection]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.defaults :as project-defaults]
            [fukan.web.views.shell :as views.shell]
            [fukan.web.views.graph :as views.graph]
            [fukan.web.views.sidebar :as views.sidebar]))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- bad-request [msg]
  {:status 400 :headers {"Content-Type" "text/plain"} :body msg})

(defn- handle-graph [_req]
  (if-let [m (infra-model/get-model)]
    (json-response (views.graph/render-graph (projection/project-model m)
                                             {:selected-id nil}))
    (bad-request "no model loaded; call infra-model/load-model")))

(defn- handle-projector [req]
  (let [{:strs [primitive-id projection-kind]} (or (:query-params req) {})
        m (infra-model/get-model)]
    (cond
      (nil? m) (bad-request "no model loaded")
      (or (str/blank? primitive-id) (str/blank? projection-kind))
      (bad-request "missing primitive-id and/or projection-kind query params")
      :else
      (json-response
        (projector/project m (project-defaults/fukan-on-fukan)
                           primitive-id (keyword projection-kind))))))

(defn- handle-sidebar [req]
  (let [id (get-in req [:query-params "id"])
        m (infra-model/get-model)]
    (if (and m id)
      (let [primitive (get-in m [:primitives id])]
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (views.sidebar/render-entity primitive)})
      (bad-request "missing id or model"))))

(defn create-handler
  []
  (ring/ring-handler
    (ring/router
      [["/"          {:get (fn [req]
                             {:status 200
                              :headers {"Content-Type" "text/html"}
                              :body (views.shell/render-shell req)})}]
       ["/graph"     {:get handle-graph}]
       ["/projector" {:get handle-projector}]
       ["/sidebar"   {:get handle-sidebar}]]
      {:data {:middleware [parameters/parameters-middleware]}})))
```

### Step 10.3: Stub views.sidebar/render-entity

If `render-entity` doesn't exist in `views/sidebar.clj`, add a minimal MVP:

```clojure
(defn render-entity
  "MVP sidebar: render primitive id/label/intent OR artifact label/source-location."
  [entity]
  (str
    "<div class='entity-panel'>"
    "<h3>" (or (:label entity) (:id entity)) "</h3>"
    "<p><strong>Kind:</strong> " (:kind entity) "</p>"
    (when (:description entity)
      (str "<p>" (:description entity) "</p>"))
    "</div>"))
```

### Step 10.4: Run, expect pass + commit

Expected: 419 + 3 = **422/0/0**.

```bash
jj desc -m "feat(web): Phase-6 router — /graph + /projector + /sidebar

Replaces Plan-1 stub handler (per its line-4 promise 'Full router
rewritten in Plan 6'). Adds:
- GET /graph — cytoscape JSON from projection.core/project-model
- GET /projector?primitive-id=X&projection-kind=Y — Blueprint JSON
- GET /sidebar?id=X — entity detail HTML (MVP renders kind/label/desc)

The /projector endpoint is the substrate for the generation flow per
VISION.md ('clicking a red absent drift marker summons the Projector').
LLM wiring is OUT of MVP — endpoint returns Blueprint for human
copy/paste.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 11: Frontend drift marker UI + Projector affordance

**Files:**
- Modify: `src/fukan/web/views/shell.clj` (likely — depends on existing shell shape)
- Create: `resources/public/css/drift.css` (or wherever existing CSS lives)
- Create or modify: client-side JS / Datastar handlers — note this is the LARGEST risk area in Plan 6

This task adds the visual affordances:
- `:relation/projects` edges with `:drift "absent"` → red dashed lines in cytoscape style.
- Clicking such an edge → calls `GET /projector?primitive-id=<from-id>&projection-kind=<projection-kind>` → renders the Blueprint markdown in the sidebar panel.

**Critical note for the implementer:** Inspect the EXISTING frontend wiring first. The shell + Datastar setup may have client-side handlers that need extending, or the existing cytoscape integration may need stylesheet patches. The plan author hasn't fully mapped the frontend; **read `resources/public/`, `src/fukan/web/views/shell.clj`, and any existing JS before designing the changes**.

For Plan 6 MVP: a single CSS class `.drift-absent` styling cytoscape edges, plus a click handler that fetches Blueprint markdown and renders it in a side panel. If implementation reveals deeper frontend gaps (e.g., no existing Datastar interactivity), defer richer UI to a follow-on plan and ship MVP as "edges are red; click an absent edge to copy the URL of the /projector endpoint" — this satisfies the inspection-only commitment.

### Step 11.1: Survey existing frontend

```bash
ls resources/public/
cat src/fukan/web/views/shell.clj
find resources/public -name "*.js" -o -name "*.css"
```

If the existing frontend is more limited than expected, **flag this and ship the MVP-minimum**: cytoscape stylesheet rules for drift edges + a help-text instructing users to hit `/projector?...` directly.

### Step 11.2: Add drift styling

Append to (or create) the cytoscape stylesheet (likely in `views/shell.clj` or `resources/public/`):

```css
edge[drift = "absent"] {
  line-color: #e74c3c;
  line-style: dashed;
  target-arrow-color: #e74c3c;
  width: 2;
}

edge[drift = "absent"]:selected {
  line-color: #c0392b;
  width: 3;
}
```

### Step 11.3: Add click handler (if existing JS infra supports it)

Plan-6 MVP target: when the user clicks a `[drift = "absent"]` edge, the client issues `GET /projector?primitive-id=<source-id>&projection-kind=<projectionKind-attr>` and displays the response's `:rendered.markdown` in the sidebar panel (rendered as preformatted text).

If existing Datastar handlers don't support this, ship **degraded MVP**: clicking the edge selects it (existing behavior); the help text in the sidebar reads "Press `Cmd+Shift+P` and enter `/projector?...` to inspect this Blueprint" or similar. The substrate Projector + endpoint works; the affordance can be added in a follow-on plan.

### Step 11.4: Smoke check

Manual browser smoke:

1. `clojure -M:dev` REPL → `(start)` → open http://localhost:8080.
2. Load model: `(load-model "src")`.
3. Confirm graph renders with red drift edges (the fukan corpus has 126 `:absent` edges; many should be visible).
4. Click one drift edge or open `/projector?primitive-id=fukan/model/spec::Container&projection-kind=projection-kind/schema` directly.
5. Verify Blueprint JSON returns 200 with non-empty `:rendered.markdown`.

### Step 11.5: Commit

```bash
jj desc -m "feat(web): drift-marker styling + Projector affordance

Cytoscape stylesheet rule: edges with [drift = 'absent'] render in red
dashed style per VISION.md ('clicking a red absent drift marker summons
the Projector').

Click-handler MVP: <document what landed — full Datastar wiring if
implementation supports, OR degraded help-text if frontend infra
needs follow-on work>.

The /projector endpoint substrate is solid; richer affordance UI
(modal, accept/reject, write-back) can land in a follow-on plan
once needs surface.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 12: Plan-5 carry-forward — duplicate-canonical-address lint

**Files:**
- Modify: `src/fukan/target/clojure/analyzer.clj`
- Create: `test/fukan/target/clojure/analyzer_duplicate_test.clj`

Per DESIGN.md "Strict enforcement" line 726: "Exactly one function per Rule, one function per Operation, one var per Entity / Value / Variant / Event must exist at the expected canonical address. Multiple definitions at the same address is a lint error."

Implementation: after building the source index, group by `[ns name kind]`. Emit one `:phase6/duplicate-canonical-address` violation per group of size > 1, severity `:error`.

### Step 12.1: Test

Create `test/fukan/target/clojure/analyzer_duplicate_test.clj`:

```clojure
(ns fukan.target.clojure.analyzer-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest duplicate-canonical-address-emits-error
  ;; Fixture trick: two files at the same ns with the same defn. Real Clojure
  ;; would refuse to load both, but the source walker just READS forms as data,
  ;; so it surfaces the duplicate.
  (let [model (-> (analyzer/run (build/empty-model)
                                (registry/make-registry)
                                "test/fixtures/clojure-projects/dup"))
        dup-violations (filter #(= :phase6/duplicate-canonical-address (:kind %))
                               (:violations model))]
    (is (>= (count dup-violations) 1)
        "at least one duplicate-canonical-address violation emitted")
    (is (= :error (-> dup-violations first :severity)))))
```

Create the fixture:

`test/fixtures/clojure-projects/dup/a.clj`:
```clojure
(ns dup)
(defn submit [] :a)
```

`test/fixtures/clojure-projects/dup/b.clj`:
```clojure
(ns dup)
(defn submit [] :b)
```

### Step 12.2: Implement

Add to `src/fukan/target/clojure/analyzer.clj`:

```clojure
(defn- detect-duplicate-addresses
  "Group source-index entries by [ns name kind]; emit one violation per
   group of size > 1."
  [files-symbols]
  (let [grouped (group-by (fn [s] [(:ns s) (:name s) (:kind s)])
                          files-symbols)]
    (for [[[ns nm kind] group] grouped
          :when (> (count group) 1)]
      {:severity :error :phase :phase6
       :kind :phase6/duplicate-canonical-address
       :location {:ns ns :name nm :kind kind :files (mapv :file group)}
       :message (str "multiple " (name kind) " at " ns "/" nm
                     " across " (count group) " files: "
                     (clojure.string/join ", " (mapv :file group)))})))
```

Modify `build-source-index` to return both the index and the raw symbol list (or accept the list once):

```clojure
(defn- walk-symbols
  [code-root]
  (if (and code-root (.exists (io/file code-root)))
    (let [files (source/find-clj-files code-root)]
      (vec (mapcat source/extract-symbols files)))
    []))

(defn- index-from-symbols
  [symbols]
  (into {} (map (fn [s] [[(:ns s) (:name s) (:kind s)] s]) symbols)))
```

Update `run` to compute both, then prepend duplicate violations to `:violations`:

```clojure
(defn run
  [model registry code-root]
  (let [symbols       (walk-symbols code-root)
        source-index  (index-from-symbols symbols)
        dup-violations (detect-duplicate-addresses symbols)
        ;; ... existing reduce chains ...
        m-final (-> ... (update :violations (fnil into []) dup-violations))]
    m-final))
```

### Step 12.3: Run, expect pass + commit

Expected: 422 + 1 = **423/0/0**.

```bash
jj desc -m "feat(target-clojure): duplicate-canonical-address lint (Plan 5 carry-forward)

Per DESIGN.md 'Strict enforcement' (line 726): exactly one defn / def
per canonical address; duplicates are a lint error.

The source walker groups extracted symbols by [ns name kind] after
walking; any group of size > 1 emits a :phase6/duplicate-canonical-address
violation with :severity :error and :location.files listing the
offending files.

Closes the first of two Plan-5 carry-forwards.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 13: Plan-5 carry-forward — unprojected Code.* discovery

**Files:**
- Modify: `src/fukan/target/clojure/analyzer.clj`
- Create: `test/fukan/target/clojure/analyzer_unprojected_test.clj`

Per DESIGN.md "Couplings" line 730: "Code that doesn't match any expected projection address appears in the model as an unprojected `Code.Function` / `Code.DataStructure` node — visible in the explorer, not bound to any spec primitive."

Implementation: after the per-kind analyzers have run and materialized canonical-address artifacts, walk the symbol list once more and add any `[ns name]` whose corresponding artifact-id ISN'T already in `:artifacts`. These unprojected artifacts have **no** incoming `:relation/projects` edge — they're just standalone nodes.

### Step 13.1: Test

Create `test/fukan/target/clojure/analyzer_unprojected_test.clj`:

```clojure
(ns fukan.target.clojure.analyzer-unprojected-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.artifact :as a]))

(deftest unprojected-defns-become-artifacts
  ;; The m-with-submit fixture has (defn submit []) AND (defn helper-fn []).
  ;; With no spec primitives, neither is canonical-projected. Plan 6 Task 13
  ;; surfaces both as unprojected Code.Function artifacts.
  (let [m (analyzer/run (build/empty-model) (registry/make-registry)
                        "test/fixtures/clojure-projects/m-with-submit")
        fn-artifacts (filter (fn [[_ a]]
                               (= :code/function (get-in a [:sub :case])))
                             (:artifacts m))]
    (is (= 2 (count fn-artifacts)) "submit + helper-fn both materialised")))

(deftest unprojected-artifacts-have-no-projects-edge
  (let [m (analyzer/run (build/empty-model) (registry/make-registry)
                        "test/fixtures/clojure-projects/m-with-submit")
        projects-edges (filter #(= :relation/projects (:kind %)) (:edges m))]
    (is (zero? (count projects-edges))
        "no primitives → no projects edges, even though artifacts materialised")))
```

### Step 13.2: Implement

Add to `src/fukan/target/clojure/analyzer.clj`:

```clojure
(defn- symbol->artifact
  [{:keys [ns name kind file]}]
  (let [qname (str ns "/" name)
        loc   {:file file}]
    (case kind
      :function       (a/make-code-function "clojure" qname loc)
      :data-structure (a/make-code-data-structure "clojure" qname loc)
      :function-private (a/make-code-function "clojure" qname loc)
      nil)))

(defn- materialize-unprojected
  "For every symbol whose canonical artifact-id isn't already in :artifacts,
   add the artifact (no projects edge — these are unbound code per
   DESIGN.md 'Couplings')."
  [model symbols]
  (reduce
    (fn [m sym]
      (if-let [art (symbol->artifact sym)]
        (let [aid (a/artifact-identity art)]
          (if (get-in m [:artifacts aid])
            m
            (assoc-in m [:artifacts aid] art)))
        m))
    model
    symbols))
```

Extend `run` to call `materialize-unprojected` after the per-kind reduces:

```clojure
m-final (-> ...
            (update :violations (fnil into []) dup-violations)
            (materialize-unprojected symbols))
```

### Step 13.3: Run, expect pass + commit

Expected: 423 + 2 = **425/0/0**. Earlier Task-8 (Plan 5) assertion `(zero? (count (:artifacts m1)))` for the empty-model+m-with-submit fixture **will now fail** — DELETE that assertion (Task 13's whole point is that those artifacts now DO materialise).

```bash
jj desc -m "feat(target-clojure): unprojected Code.* discovery (Plan 5 carry-forward)

Per DESIGN.md 'Couplings' (line 730): defns whose [ns name] doesn't match
any spec primitive's canonical address surface as unbound Code.Function /
Code.DataStructure artifacts in the Model.

After per-kind analyzers run, walk the symbol list once more and
materialize any artifact whose identity isn't already in :artifacts.
No :relation/projects edge — these are surfaced for explorer visibility,
not bound to spec.

Closes the second Plan-5 carry-forward. Plan 5's deferral test
analyzer_lint_test.clj's empty-model assertion is removed (its premise
was Task 13's deferral).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing Tasks 0–13, verify before declaring Plan 6 done:

1. **Projector covers all six components** per MODEL.md §7.7:
   - Component 1 (canonical address) — Task 2
   - Component 2 (artifact kind) — Task 2
   - Component 3 (expected signature) — Task 3
   - Component 4 (type renderings) — Task 3 (uses Plan 5's types/render)
   - Component 5 (surrounding model context) — Task 5
   - Component 6 (selected idioms) — Task 6
2. **Blueprint v1** record shape complete (Task 1); EDN + markdown serialisations work (Tasks 1, 7).
3. **Projection layer** replaces OLD code-graph (Task 8); cytoscape transformer handles new shapes (Task 9); drift styling present (Task 9).
4. **HTTP router** has `/graph`, `/projector`, `/sidebar` (Task 10); endpoint tests green.
5. **Frontend drift markers** render visibly in browser smoke (Task 11); click-handler MVP as shipped (full or degraded).
6. **Plan 5 carry-forwards closed**:
   - Duplicate-canonical-address lint emits `:error` violations (Task 12).
   - Unprojected `Code.*` discovery materialises unbound artifacts (Task 13).
7. **fukan-on-fukan loads through Phase 6** with zero errors. Browser smoke: red drift edges visible; clicking a primitive opens the sidebar with Blueprint affordance.
8. **Full test suite green**: `clj -M:test` zero failures.
9. **VCS state**: ~13 Plan-6 commits stack cleanly on top of Plan 5's tip.

If any check fails, fix in place — do NOT declare Plan 6 done with open issues.

---

## What this plan does NOT cover

Per the scope decision (Lean Plan 6), the following are explicitly out:

- **LLM API wiring.** Anthropic API client + key handling + diff preview + write-back → future plan.
- **Diff/accept-reject UI.** When LLM ships, the UX for previewing generated code and committing it.
- **Generation history / retry.** Cache of past Blueprint generations + their outcomes.
- **Stale drift detection.** Detecting that code at a canonical address has DRIFTED from its expected shape (vs. just present/absent). A future shape-comparator pass extends `:validity` to `:stale`.
- **Project layer composition mechanics.** Severity overrides, profiles, bundles — deferred to Plan 7+ per DESIGN.md MVP commitments.
- **`.infra` Allium content.** Stays architecturally seamed (per MODEL.md §10).
- **Documentation-flavour Artifact cases.** Travel with their producing analyzer; not in MVP.
- **Sidebar full rewrite.** MVP renders minimal entity details only. Richer EntityDetails-style sectioning lands when a forcing example arrives.
