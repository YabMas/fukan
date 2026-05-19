# Boundary Analyzer + Multi-Extension Build Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Boundary analyzer that translates `.boundary` ASTs (from Plan 3a's parser) into kernel content + `Boundary::*` tag applications per [MODEL.md §8.2](../MODEL.md#82-boundary--kernel-mapping). Wire it into a multi-extension build pipeline that runs Allium first (Plan 2b) and Boundary second on top, producing one unified Model. Cross-extension references (`fn Contract.op` / `fn alias/Contract.op`, `triggers: alias/Rule`, subsystem `contains:`) resolve against Allium-produced primitives. Phase 4 structural validation is deferred to Plan 3c.

**Architecture:** Three new files under `src/fukan/vocabulary/boundary/`: `tags.clj` (5 TagDefinitions), `analyzer.clj` (AST → kernel content), `pipeline.clj` (source walk + analyze). One new top-level orchestrator at `src/fukan/model/pipeline.clj` running Allium + Boundary in sequence. `infra/model.clj` swaps its call from `vocabulary.allium.pipeline/load-source` to the top-level orchestrator. Plan 2b's Allium pipeline is preserved as-is — Plan 3b composes on top, doesn't reshape.

**Tech Stack:** Existing Clojure 1.11, malli, instaparse. No new deps. The Plan 3a parser API (`parse-boundary`, `parse-file`) is the only Boundary surface this plan consumes.

---

## Plan-of-plans context

This is **Plan 3b of 9** in the next-chapter overhaul. The sequence:

1. **Kernel substrate** *(closed)*
2. **2a. Allium parser completion** *(closed)*
3. **2b. Allium analyzer** *(closed)*
4. **3a. Boundary parser** *(closed)* — PEG grammar; AST shape conventions; corpus migration.
5. **3b. Boundary analyzer + multi-extension build pipeline** *(this plan)* — Boundary AST → kernel content + `Boundary::*` tags; combined Allium+Boundary pipeline.
6. **3c. Phase 4 structural validation** — composition / event / binding / module-visibility / subsystem-visibility / export closure / cross-module reference visibility rules.
7. **4. Constraint language + Phase 5** — the §6 constraint language; project layer.
8. **5. Clojure Target extension** — Analyzer + Projector.
9. **6. Explorer rewrite + generation flow**.

Plan 3a closed with the Boundary parser producing a clean AST from all 4 corpus `.boundary` files (253 tests passing). The parser exposes `parse-boundary` (string) and `parse-file` (path). The new top-level pipeline in this plan will consume `parse-file`.

Authoritative refs:
- [MODEL.md §8.2](../MODEL.md#82-boundary--kernel-mapping) — `.boundary` → kernel mapping table. Drives the analyzer's emit logic.
- [DESIGN.md `.boundary responsibilities`](../DESIGN.md) — primitive semantics, binding lint rules, file-shape discriminator.
- [DECISIONS.md `## Boundary language`](../DECISIONS.md) — B1–B7 capture the design rationale.
- [Plan 2b: Allium analyzer](2026-05-18-allium-analyzer.md) — pattern this plan mirrors (analyzer namespace structure, helpers, pipeline orchestrator).
- [`src/fukan/vocabulary/allium/`](../../src/fukan/vocabulary/allium/) — the reference implementation. Boundary's analyzer is a thinner sibling.

---

## Repository conventions (jj over git)

Identical to Plans 1, 2a, 2b, 3a. Translate the plan's commit steps:

| In the plan | Run instead |
|---|---|
| `git add <paths>` | *(omit — jj snapshots the working copy automatically)* |
| `git commit -m "<message>"` (or heredoc form) | `jj desc -m "<message>"` followed by `jj new` to start the next change |

After each commit, verify with `jj st` and `jj log -r '::@' --limit 5`. One logical change per commit; `jj new` between tasks; **NEVER `jj squash -m "..."`** (silently collapses commits).

---

## Conventions used throughout this plan

- **Namespace structure** — mirror Allium: `src/fukan/vocabulary/boundary/{tags,analyzer,pipeline}.clj`. The top-level multi-extension orchestrator lives at `src/fukan/model/pipeline.clj` (sibling to the existing `src/fukan/model/pipeline.boundary` interface declaration).
- **Test layout** — `test/fukan/vocabulary/boundary/{analyzer_test,pipeline_test}.clj` for unit + integration tests.
- **AST consumption** — Plan 3a's AST shape is the input. Every declaration has `:type` ∈ `#{:use :fn :exports :subsystem}`; `:fn` carries `:form` ∈ `#{:declare-new :local-attach :foreign-attach}`. Refer to `test/fukan/libs/boundary/parser_test.clj` for concrete shapes.
- **Kernel API** — use `fukan.model.{build,primitives,relations,type,vocabulary}` only. Never reach into `model.build`'s internal maps directly except where Plan 2b's analyzer already does (e.g. `assoc-in [:primitives id]` for stub creation).
- **Cross-extension references are best-effort.** Phase 4 (Plan 3c) does the rigorous validation. For Plan 3b, unresolved references emit a warning to `*err*` and the analyzer continues; structural assertion violations (e.g. attach form with empty body) throw `ex-info` with `:type :boundary-shape-error`.
- **Tags applied at emission time, not post-pass.** Each analyzer emit produces both the kernel content and the matching tag application atomically. Mirrors Plan 2b's late-pass simplification.
- **Underscore-vs-kebab** — preserve `.boundary` snake_case identifiers as strings in tag payloads. Plan 3c will normalise as needed for validation rule input.

---

## File Structure

### Files to create

- `src/fukan/vocabulary/boundary/tags.clj` — 5 TagDefinitions for `Boundary::*` namespace.
- `src/fukan/vocabulary/boundary/analyzer.clj` — `analyze-file` + per-decl handlers + kernel landings.
- `src/fukan/vocabulary/boundary/pipeline.clj` — source walk + parse + analyze + use-path canonicalisation.
- `src/fukan/model/pipeline.clj` — top-level orchestrator running Allium + Boundary in sequence.
- `test/fukan/vocabulary/boundary/analyzer_test.clj` — per-construct unit tests.
- `test/fukan/vocabulary/boundary/pipeline_test.clj` — integration tests + fukan-on-fukan smoke.

### Files to modify

- `src/fukan/infra/model.clj` — replace `pipeline/load-source` call with the top-level orchestrator.
- `test/fukan/smoke_test.clj` — assertions update if Boundary additions change primitive/edge counts.
- `test/fukan/vocabulary/allium/pipeline_test.clj` — `pipeline-loads-fukan-corpus` may need update if the new combined pipeline changes its loader path; otherwise untouched.

### Files to leave untouched

- All Plan-1 substrate (`src/fukan/model/*.clj` except `pipeline.clj` which is new) — Plan 3b does not touch the kernel.
- The Allium analyzer / pipeline (`src/fukan/vocabulary/allium/*.clj`) — Plan 3b composes on top, doesn't modify.
- The Boundary parser (`src/fukan/libs/boundary/parser.clj`) — frozen at Plan 3a.

---

## Reading the canonical reference

[MODEL.md §8.2](../MODEL.md#82-boundary--kernel-mapping) carries the authoritative substrate-level commitments. The five tag definitions and the per-construct kernel landings live there. Read it before starting.

Each task references specific rows of MODEL.md §8.2's mapping table. The table maps:

| `.boundary` construct | Kernel landing | Tag |
|---|---|---|
| `fn name(params) -> R` *(declare-new, no body)* | Operation primitive in module-Container's `boundary.operations` | `Boundary::Function` |
| `fn name(params) -> R { triggers: Rule }` | + `triggers: Operation → Rule` edge (R4) | `Boundary::Binding` on edge |
| `fn name(params) -> R { triggers: Rule; returns: <expr> }` | as above | `Boundary::Binding` payload `{returns_expression: Text}` |
| `fn Contract.op { ... }` | `triggers` edge against existing Allium Operation | `Boundary::Binding` |
| `fn alias/Contract.op { ... }` | as local-attach but cross-module | `Boundary::Binding` |
| `exports: <ref> ...` *(module-bound)* | Tag application on module-Container | `Boundary::ModuleApi` |
| `subsystem <Name> { ... }` | Composite Container | `Boundary::Subsystem` |
| `contains: <path>` | populates composite's `children` | — |
| `exports: <ref> ...` *(inside subsystem)* | Tag on composite | `Boundary::Exports` |
| `rules: <ref>(...)` *(inside subsystem)* | PredicateRegistration | — |
| `use "<path>" as <alias>` | Analyzer-internal | — |

Plus the kernel relation R4 `triggers: Operation → Rule` is the one new edge kind this plan emits.

---

## Task 0: Scaffold + smoke target

**Files:**
- Create: `src/fukan/vocabulary/boundary/tags.clj` (stub — namespace + empty tag-definitions vector)
- Create: `src/fukan/vocabulary/boundary/analyzer.clj` (stub — namespace + `analyze-file` that returns model unchanged)
- Create: `src/fukan/vocabulary/boundary/pipeline.clj` (stub — namespace + `load-source` that returns the model unchanged)
- Create: `src/fukan/model/pipeline.clj` (stub — namespace + `load-source` that calls Allium pipeline only for now)
- Create: `test/fukan/vocabulary/boundary/pipeline_test.clj` (failing smoke test for the multi-extension load)

This task lays down the namespace skeleton and a failing smoke test that each subsequent task moves closer to passing.

- [ ] **Step 0.1: Create `src/fukan/vocabulary/boundary/tags.clj` stub**

```clojure
(ns fukan.vocabulary.boundary.tags
  "All Boundary::* TagDefinitions registered as a single vector for the
   analyzer to apply onto kernel content. Per MODEL.md §8.2."
  (:require [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

;; Task 1 fills this in with five tag definitions:
;;   Boundary::Function, Boundary::Binding, Boundary::ModuleApi,
;;   Boundary::Subsystem, Boundary::Exports.
(def boundary-tag-definitions
  [])
```

- [ ] **Step 0.2: Create `src/fukan/vocabulary/boundary/analyzer.clj` stub**

```clojure
(ns fukan.vocabulary.boundary.analyzer
  "Boundary AST → kernel content. Per MODEL.md §8.2.

   Two file shapes (Plan 3a parser AST):
   - Module-bound: declarations are mix of :use, :fn, :exports.
   - Subsystem-bound: declarations are use + one :subsystem.

   This namespace is built up across Tasks 2-7."
  (:require [fukan.model.build :as build]))

(defn analyze-file
  "Apply a parsed .boundary AST to the model. `coord` is the file's
   coordinate (relative path minus .boundary extension). `use-aliases`
   is the file-local map of alias → coord for cross-module resolution.

   Returns updated model. Tasks 2+ flesh out the handlers; for now,
   returns the model unchanged."
  [model _ast _coord _use-aliases]
  model)
```

- [ ] **Step 0.3: Create `src/fukan/vocabulary/boundary/pipeline.clj` stub**

```clojure
(ns fukan.vocabulary.boundary.pipeline
  "Source walk + parse + analyze for .boundary files.
   Mirrors fukan.vocabulary.allium.pipeline's shape.
   Built up across Task 8.")

(defn load-source
  "Walk source-root, parse every .boundary file, analyze each against
   the (already Allium-loaded) model. Returns the enriched model.

   Stub: not yet implemented (Plan 3b Task 8)."
  [model _source-root]
  model)
```

- [ ] **Step 0.4: Create `src/fukan/model/pipeline.clj` (top-level multi-extension orchestrator)**

```clojure
(ns fukan.model.pipeline
  "Multi-extension build pipeline (Phase 1-3 per DESIGN.md).

   Phase 1: per-extension parse (Allium + Boundary parse independently).
   Phase 2: cross-extension reference resolution (Boundary references
            Allium-produced Operations / Rules / Containers).
   Phase 3: merge (kernel content unioned by identity).

   Phase 4 (structural validation) is Plan 3c; Phase 5 (constraints) is
   Plan 4."
  (:require [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]))

(defn load-source
  "Top-level load: runs the Allium pipeline first (Plan 2b), then the
   Boundary pipeline on the resulting Model. Returns the combined Model.

   The Allium pipeline owns its own model construction (empty model →
   register Allium tags → walk + analyze). The Boundary pipeline
   accepts the Allium-produced model and enriches it."
  [source-root]
  (-> (allium/load-source source-root)
      (boundary/load-source source-root)))
```

- [ ] **Step 0.5: Create `test/fukan/vocabulary/boundary/pipeline_test.clj` with a failing smoke**

```clojure
(ns fukan.vocabulary.boundary.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.pipeline :as pipeline]
            [fukan.model.build :as build]
            [malli.core :as m]))

(deftest combined-pipeline-loads-fukan-corpus
  (testing "loading src/ through the multi-extension pipeline produces a
            validated Model carrying both Allium and Boundary content"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against build/Model schema")
      (testing "Boundary tags get registered"
        (let [tag-namespaces (->> (:tag-definitions model)
                                  (map (comp :namespace :tag))
                                  set)]
          (is (contains? tag-namespaces "Boundary")
              "expected at least one Boundary::* TagDefinition registered")))
      (testing "Boundary::Function tag applied to fn-declared Operations"
        (let [function-tag-apps (filter (fn [ta]
                                          (= {:namespace "Boundary" :name "Function"}
                                             (:tag ta)))
                                        (:tag-apps model))]
          (is (pos? (count function-tag-apps))
              "at least one fn declaration should produce a Boundary::Function tag"))))))
```

This test fails throughout Plan 3b until Task 10 wires the full pipeline. Each task moves the implementation closer.

- [ ] **Step 0.6: Run, see fail**

```
clj -M:test -n fukan.vocabulary.boundary.pipeline-test
```

Expected: `combined-pipeline-loads-fukan-corpus` fails with "expected at least one Boundary::* TagDefinition registered" (zero tag-definitions registered until Task 1) or "at least one fn declaration should produce a Boundary::Function tag" (no analyzer wiring until later tasks).

- [ ] **Step 0.7: Run full suite, confirm no regression**

```
clj -M:test
```

Expected: 253 baseline + 1 new failing test → 254 tests, 0–1 failures (the failing smoke is expected). Other suites continue to pass.

- [ ] **Step 0.8: Commit**

```bash
jj desc -m "scaffold(boundary): vocabulary/boundary/ namespace structure + smoke target

Plan 3b Task 0: lays down vocabulary/boundary/{tags,analyzer,pipeline}.clj
stubs plus model/pipeline.clj top-level orchestrator. Adds failing smoke
test combined-pipeline-loads-fukan-corpus that each subsequent task
brings closer to passing.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: TagDefinition registry — five Boundary::* tags

**Files:**
- Modify: `src/fukan/vocabulary/boundary/tags.clj`
- Create: `test/fukan/vocabulary/boundary/tags_test.clj`

Per MODEL.md §8.2: five tags in the `Boundary` namespace. Each `TagDefinition` carries `:namespace`, `:name`, `:applies-to`, and optional `:payload-schema`.

Read `src/fukan/vocabulary/allium/tags.clj` first to confirm the value-record idiom: `(v/make-tag-definition {:namespace "..." :name "..." :applies-to :target/... :payload-schema <Type>})`.

The five tags and where they apply:

| Tag | Applies to | Payload (schema) |
|---|---|---|
| `Boundary::Function` | `:target/primitive` (Operation) | none — bare marker |
| `Boundary::Binding` | `:target/edge` (R4 triggers edge) | `{returns_expression: Text?}` |
| `Boundary::ModuleApi` | `:target/primitive` (module-Container) | `{exported: List<String>}` |
| `Boundary::Subsystem` | `:target/primitive` (composite Container) | `{name: String}` |
| `Boundary::Exports` | `:target/primitive` (composite Container) | `{exported: List<String>}` |

- [ ] **Step 1.1: Test**

Create `test/fukan/vocabulary/boundary/tags_test.clj`:

```clojure
(ns fukan.vocabulary.boundary.tags-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.boundary.tags :as tags]))

(deftest registry-shape
  (testing "boundary-tag-definitions is a non-empty vector of TagDefinitions"
    (is (vector? tags/boundary-tag-definitions))
    (is (= 5 (count tags/boundary-tag-definitions)))))

(deftest expected-tags-present
  (testing "all five expected Boundary::* tags are registered"
    (let [tag-names (set (map (juxt :namespace :name)
                              (map :tag tags/boundary-tag-definitions)))]
      (is (= #{["Boundary" "Function"]
               ["Boundary" "Binding"]
               ["Boundary" "ModuleApi"]
               ["Boundary" "Subsystem"]
               ["Boundary" "Exports"]}
             tag-names)))))

(deftest applies-to-correct-targets
  (testing "each tag applies to the right kernel target kind"
    (let [by-name (->> tags/boundary-tag-definitions
                       (group-by (fn [td] [(-> td :tag :namespace)
                                           (-> td :tag :name)]))
                       (map (fn [[k [v]]] [k v]))
                       (into {}))]
      (is (= :target/primitive (-> by-name ["Boundary" "Function"] :applies-to)))
      (is (= :target/edge      (-> by-name ["Boundary" "Binding"]  :applies-to)))
      (is (= :target/primitive (-> by-name ["Boundary" "ModuleApi"] :applies-to)))
      (is (= :target/primitive (-> by-name ["Boundary" "Subsystem"] :applies-to)))
      (is (= :target/primitive (-> by-name ["Boundary" "Exports"]   :applies-to))))))
```

- [ ] **Step 1.2: Run, see fail**

```
clj -M:test -n fukan.vocabulary.boundary.tags-test
```

Expected: 3 tests fail (`registry-shape` finds 0 entries, others fail similarly).

- [ ] **Step 1.3: Implement**

Replace the content of `src/fukan/vocabulary/boundary/tags.clj` with:

```clojure
(ns fukan.vocabulary.boundary.tags
  "All Boundary::* TagDefinitions registered as a single vector for the
   analyzer to apply onto kernel content. Per MODEL.md §8.2."
  (:require [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

;; -- Payload schemas ----------------------------------------------------------

(def ^:private exported-list-schema
  (t/make-collection (t/make-scalar "String") :sequential))

(def ^:private binding-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "returns_expression" (t/make-scalar "Text") true)]))

(def ^:private module-api-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "exported" exported-list-schema false)]))

(def ^:private subsystem-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "name" (t/make-scalar "String") false)]))

(def ^:private exports-payload-schema
  (t/make-composite-inline
    [(t/make-field-spec "exported" exported-list-schema false)]))

;; -- Registry -----------------------------------------------------------------

(def boundary-tag-definitions
  [(v/make-tag-definition
     {:tag        {:namespace "Boundary" :name "Function"}
      :applies-to :target/primitive})

   (v/make-tag-definition
     {:tag           {:namespace "Boundary" :name "Binding"}
      :applies-to    :target/edge
      :payload-schema binding-payload-schema})

   (v/make-tag-definition
     {:tag           {:namespace "Boundary" :name "ModuleApi"}
      :applies-to    :target/primitive
      :payload-schema module-api-payload-schema})

   (v/make-tag-definition
     {:tag           {:namespace "Boundary" :name "Subsystem"}
      :applies-to    :target/primitive
      :payload-schema subsystem-payload-schema})

   (v/make-tag-definition
     {:tag           {:namespace "Boundary" :name "Exports"}
      :applies-to    :target/primitive
      :payload-schema exports-payload-schema})])
```

Read `vocabulary/allium/tags.clj` if `v/make-tag-definition`'s exact shape isn't clear — the `:tag` map key is the namespaced-name tuple, `:applies-to` selects the target kind, `:payload-schema` is a kernel `Type` value.

- [ ] **Step 1.4: Run, expect pass**

```
clj -M:test -n fukan.vocabulary.boundary.tags-test
```

Expected: 3 tests pass.

- [ ] **Step 1.5: Run full suite**

```
clj -M:test
```

Expected: 253 baseline + 3 new tag tests = 256 tests; the smoke test from Task 0 still fails (no analyzer wiring yet).

- [ ] **Step 1.6: Commit**

```bash
jj desc -m "feat(boundary): TagDefinition registry — 5 Boundary::* tags

Boundary::Function (on Operations), Boundary::Binding (on R4 edges),
Boundary::ModuleApi (on module-Containers), Boundary::Subsystem and
Boundary::Exports (on composite Containers). Payload schemas per
MODEL.md §8.2.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: Module-bound analyzer entrypoint

**Files:**
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj`
- Create: `test/fukan/vocabulary/boundary/analyzer_test.clj`

The `analyze-file` entrypoint dispatches by file shape (module-bound vs subsystem-bound), then routes each declaration to its handler. This task adds the dispatch skeleton + a `:use` no-op handler (use declarations are analyzer-internal, not kernel content). Subsequent tasks fill in the `fn` / `exports` / `subsystem` handlers.

A `.boundary` file is module-bound if its top-level declarations include any `:fn` or `:exports`. It's subsystem-bound if a `:subsystem` declaration is present. Mixed shape is a structural error (caught at AST shape level by Plan 3a's `module-bound-vs-subsystem-bound` test, but defensively re-checked here).

- [ ] **Step 2.1: Tests**

Create `test/fukan/vocabulary/boundary/analyzer_test.clj`:

```clojure
(ns fukan.vocabulary.boundary.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.libs.boundary.parser :as parser]
            [fukan.vocabulary.boundary.analyzer :as analyzer]
            [fukan.model.build :as build]))

(defn- ast [text]
  (parser/parse-boundary (str "-- boundary: 1\n" text)))

(defn- analyze [model decls]
  ;; Helper: build an AST that wraps `decls` and analyze with empty use-aliases.
  (analyzer/analyze-file model
                         {:boundary-version 1 :declarations decls}
                         "test/module"
                         {}))

(deftest empty-file-returns-model-unchanged
  (testing "an empty declarations vector produces no kernel changes"
    (let [m0    (build/empty-model)
          model (analyze m0 [])]
      (is (= (:primitives m0) (:primitives model)))
      (is (= (:edges m0) (:edges model)))
      (is (= (:tag-apps m0) (:tag-apps model))))))

(deftest use-decl-is-noop
  (testing "use declarations don't produce kernel content"
    (let [m0    (build/empty-model)
          model (analyze m0 [{:type :use :path "x.allium" :alias "x"}])]
      (is (= (:primitives m0) (:primitives model)))
      (is (= (:tag-apps m0) (:tag-apps model))))))

(deftest mixed-shape-throws
  (testing "a file mixing :fn and :subsystem at top level is rejected"
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :fn :form :declare-new :name "f"
                            :params [] :return-type nil :prose nil :body nil}
                           {:type :subsystem :name "X"
                            :contains [] :exports [] :rules []}])))))
```

- [ ] **Step 2.2: Run, see fail**

Expected: 3 new tests fail.

- [ ] **Step 2.3: Implement**

Replace `src/fukan/vocabulary/boundary/analyzer.clj` body with:

```clojure
(ns fukan.vocabulary.boundary.analyzer
  "Boundary AST → kernel content. Per MODEL.md §8.2."
  (:require [fukan.model.build :as build]))

;; ---------------------------------------------------------------------------
;; Shape detection
;; ---------------------------------------------------------------------------

(defn- shape-of
  "Returns :module-bound, :subsystem-bound, or :mixed (a structural error)."
  [declarations]
  (let [kinds (set (map :type declarations))
        module-kinds (#{:fn :exports} kinds)
        subsystem-kinds (kinds :subsystem)]
    (cond
      (and module-kinds subsystem-kinds) :mixed
      subsystem-kinds                    :subsystem-bound
      :else                              :module-bound)))

;; ---------------------------------------------------------------------------
;; Per-decl handlers (Tasks 3-7 fill these in)
;; ---------------------------------------------------------------------------

(defn- analyze-use [model _decl _coord _use-aliases]
  ;; use declarations are analyzer-internal (handled at the pipeline level
  ;; via use-aliases); no kernel content produced here.
  model)

(defn- analyze-fn [model _decl _coord _use-aliases]
  ;; Tasks 3-5 implement.
  model)

(defn- analyze-exports [model _decl _coord _use-aliases]
  ;; Task 6 implements.
  model)

(defn- analyze-subsystem [model _decl _coord _use-aliases]
  ;; Task 7 implements.
  model)

(defn- analyze-decl [model decl coord use-aliases]
  (case (:type decl)
    :use        (analyze-use       model decl coord use-aliases)
    :fn         (analyze-fn        model decl coord use-aliases)
    :exports    (analyze-exports   model decl coord use-aliases)
    :subsystem  (analyze-subsystem model decl coord use-aliases)
    (throw (ex-info "Unknown .boundary declaration type"
                    {:type :boundary-shape-error
                     :decl-type (:type decl)
                     :coord coord}))))

;; ---------------------------------------------------------------------------
;; Public entrypoint
;; ---------------------------------------------------------------------------

(defn analyze-file
  "Apply a parsed .boundary AST to the model. `coord` is the file's
   coordinate (root-relative, no extension). `use-aliases` is the
   file-local map of alias → canonical-coord for cross-module resolution.

   Returns updated model. Throws on mixed module/subsystem shape."
  [model ast coord use-aliases]
  (let [decls (:declarations ast)
        shape (shape-of decls)]
    (when (= shape :mixed)
      (throw (ex-info "mixed module-bound and subsystem-bound shapes in one file"
                      {:type :boundary-shape-error
                       :coord coord})))
    (reduce (fn [m decl] (analyze-decl m decl coord use-aliases))
            model
            decls)))
```

- [ ] **Step 2.4: Run, expect pass**

Expected: 3 new analyzer tests pass + tag tests + smoke (still failing, no Boundary::Function emit yet).

- [ ] **Step 2.5: Commit**

```bash
jj desc -m "feat(boundary): analyze-file entrypoint + shape dispatch

Module-bound vs subsystem-bound shape detection. Per-decl handlers
stubbed; use-decl no-op since use is analyzer-internal. Tasks 3-7 fill
in the fn/exports/subsystem handlers.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: `fn` declare-new → Operation primitive

**Files:**
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj`
- Modify: `test/fukan/vocabulary/boundary/analyzer_test.clj`

Per MODEL.md §8.2: a `fn name(params) -> R` declares a new Operation on the bearing module-Container's `boundary.operations`. The analyzer:

1. Looks up the module-Container by `coord`. If it doesn't exist (e.g., Allium ran first and didn't produce one — would be a structural error caught by Plan 3c), creates a stub Container.
2. Translates `params` and `return-type` from Plan 3a's AST type-ref shape to kernel `Type` values.
3. Creates an Operation primitive via `p/make-operation`. Operation id is `<coord>::<fn-name>`.
4. Updates the module-Container's `:boundary` slot — create a Boundary if absent, append the Operation to its `:operations` list.
5. Adds the Operation primitive to the model.
6. Applies a `Boundary::Function` tag application targeting the Operation primitive.

Type translation reuses Plan 2b's pattern. Read `src/fukan/vocabulary/allium/analyzer.clj` for the `translate-type-ref` function and adapt to Boundary's input shape. Boundary's type-ref AST shape (per Plan 3a) is identical to Allium's — same `:kind` discriminator and same shapes — so the helper is portable.

Body handling stays nil for this task; Task 4 adds `triggers:` → R4 edge.

- [ ] **Step 3.1: Tests**

Add to `test/fukan/vocabulary/boundary/analyzer_test.clj`:

```clojure
(deftest fn-declare-new-produces-operation
  (testing "fn name(params) -> R creates an Operation primitive on the module-Container's Boundary"
    (let [m0 (build/empty-model)
          fn-decl {:type :fn
                   :form :declare-new
                   :name "render_app_shell"
                   :params []
                   :return-type {:kind :simple :name "Html"}
                   :prose nil
                   :body nil}
          model (analyze m0 [fn-decl])
          op-id "test/module::render_app_shell"
          op    (build/get-primitive model op-id)]
      (is (some? op) "Operation primitive created")
      (is (= :primitive/operation (:kind op)))
      (is (= "render_app_shell" (:label op)))
      ;; Operation is referenced from the module-Container's Boundary
      (let [container (build/get-primitive model "test/module")]
        (is (some? container) "module-Container created or pre-existing")
        (is (some #(= op-id (:id %)) (-> container :boundary :operations))
            "Operation appears in module-Container.boundary.operations")))))

(deftest fn-declare-new-applies-Function-tag
  (testing "fn declare-new produces a Boundary::Function tag application on the Operation"
    (let [fn-decl {:type :fn :form :declare-new :name "f"
                   :params [] :return-type nil :prose nil :body nil}
          model   (analyze (build/empty-model) [fn-decl])
          fn-tags (filter (fn [ta]
                            (and (= "Boundary" (-> ta :tag :namespace))
                                 (= "Function" (-> ta :tag :name))))
                          (:tag-apps model))]
      (is (= 1 (count fn-tags)))
      (is (= :target/primitive (-> fn-tags first :target :case)))
      (is (= "test/module::f" (-> fn-tags first :target :id))))))

(deftest fn-declare-new-with-typed-params
  (testing "fn parameters land as Parameter records on the Operation"
    (let [fn-decl {:type :fn :form :declare-new :name "load_model"
                   :params [{:name "src" :type-ref {:kind :simple :name "FilePath"}}
                            {:name "analyzers"
                             :type-ref {:kind :generic :name "Set"
                                        :params [{:kind :simple :name "AnalyzerKey"}]}}]
                   :return-type {:kind :simple :name "Model"}
                   :prose nil :body nil}
          model (analyze (build/empty-model) [fn-decl])
          op    (build/get-primitive model "test/module::load_model")]
      (is (= 2 (count (:parameters op))))
      (is (= "src" (-> op :parameters first :name)))
      (is (= "analyzers" (-> op :parameters second :name)))
      (is (= :model (-> op :return-type :case))) ; quick existence check
      )))
```

(The `:return-type :case` check is a placeholder — adjust to the actual kernel `Type` value's discriminator key once you read `fukan.model.type`'s shape. The point is the return-type lands as a kernel Type value.)

- [ ] **Step 3.2: Run, see fail**

Expected: 3 new tests fail (no fn handler yet).

- [ ] **Step 3.3: Implement**

Add to `analyzer.clj` (after the shape-detection helpers, before the dispatch table):

```clojure
(require '[fukan.model.primitives :as p]
         '[fukan.model.type :as t]
         '[fukan.model.vocabulary :as v])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- qualify [coord local-name]
  (str coord "::" local-name))

(defn- translate-type-ref
  "Convert a Plan 3a parser type-ref into a kernel Type value.
   Cross-module refs (:qualified) resolve through use-aliases when possible;
   else fall through to a Scalar placeholder (Plan 3c will validate)."
  [tr coord use-aliases]
  (case (:kind tr)
    :simple
    (t/make-scalar (:name tr))

    :optional
    (translate-type-ref (:inner tr) coord use-aliases)

    :generic
    (case (:name tr)
      "List" (t/make-collection
               (translate-type-ref (first (:params tr)) coord use-aliases)
               :sequential)
      "Set"  (t/make-collection
               (translate-type-ref (first (:params tr)) coord use-aliases)
               :unique)
      "Map"  (let [[k v] (:params tr)]
               (t/make-collection
                 (translate-type-ref v coord use-aliases)
                 (t/keyed (translate-type-ref k coord use-aliases))))
      (t/make-scalar (:name tr)))

    :qualified
    (if-let [resolved-coord (get use-aliases (:ns tr))]
      (t/make-composite-named (qualify resolved-coord (:name tr)))
      (t/make-scalar (str (:ns tr) "/" (:name tr))))))

(defn- param->kernel [coord use-aliases param]
  (p/make-parameter
    {:name (:name param)
     :type (translate-type-ref (:type-ref param) coord use-aliases)}))

(defn- ensure-module-container
  "Find the module-Container at `coord`, or create a minimal stub if absent.
   Returns updated model."
  [model coord]
  (if (build/get-primitive model coord)
    model
    (build/add-primitive model
                         (p/make-container {:id coord :label coord
                                            :fields []}))))

(defn- add-operation-to-boundary
  "Append an Operation reference to the module-Container's
   boundary.operations slot. Creates the Boundary if absent."
  [model coord op-id op-label]
  (let [container (build/get-primitive model coord)
        boundary  (or (:boundary container)
                      (p/make-boundary {:operations [] :intent nil}))
        op-ref    {:id op-id :label op-label}
        updated   (assoc container :boundary
                          (update boundary :operations conj op-ref))]
    (assoc-in model [:primitives coord] updated)))
```

Replace the `analyze-fn` stub:

```clojure
(defn- analyze-fn-declare-new [model decl coord use-aliases]
  (let [fn-name  (:name decl)
        op-id    (qualify coord fn-name)
        params   (mapv #(param->kernel coord use-aliases %) (:params decl))
        return-t (when-let [tr (:return-type decl)]
                   (translate-type-ref tr coord use-aliases))
        op       (p/make-operation
                   (cond-> {:id op-id :label fn-name :parameters params}
                     return-t (assoc :return-type return-t)))
        m0       (-> model
                     (ensure-module-container coord)
                     (build/add-primitive op)
                     (add-operation-to-boundary coord op-id fn-name))
        tag-app  (v/make-tag-application
                   {:tag    {:namespace "Boundary" :name "Function"}
                    :target {:case :target/primitive :id op-id}})]
    (build/add-tag-application m0 tag-app)))

(defn- analyze-fn [model decl coord use-aliases]
  (case (:form decl)
    :declare-new (analyze-fn-declare-new model decl coord use-aliases)
    ;; Tasks 4-5 add :local-attach and :foreign-attach handlers.
    model))
```

You may need to consult `fukan.model.build`'s API for `add-primitive`, `add-tag-application`, `get-primitive` — read briefly to confirm signatures. The Allium analyzer uses the same calls.

- [ ] **Step 3.4: Run, expect pass**

Expected: 3 new tests + previous tests pass.

- [ ] **Step 3.5: Run full suite**

Expected: previous + 3 new = 259/0/1 (Task 0 smoke still fails because no fn declarations from fukan corpus are analyzed yet — pipeline wiring is Task 8+).

- [ ] **Step 3.6: Commit**

```bash
jj desc -m "feat(boundary): fn declare-new → Operation on module Boundary

fn name(params) -> R creates an Operation primitive at id
<coord>::<name>, references it from the module-Container's
boundary.operations, and applies Boundary::Function tag. Type-refs
translate via reused Allium-style helpers; cross-module :qualified
refs fall through to Scalar placeholder when alias unresolved.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: `fn` body `triggers:` → R4 edge + `Boundary::Binding`

**Files:**
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj`
- Modify: `test/fukan/vocabulary/boundary/analyzer_test.clj`

A `fn` body with a `triggers:` clause materialises a kernel R4 edge `triggers: Operation → Rule`. The Rule reference is either local (`:kind :local :name "RuleName"`) or alias-qualified (`:kind :qualified :ns "alias" :name "RuleName"`). The Plan 2b analyzer already adds Allium-side Rules under id `<module-coord>::<RuleName>`; resolution is just prepending the canonical coordinate.

The `returns:` clause, when present, becomes the `returns_expression` field of the `Boundary::Binding` tag's payload — opaque text until Plan 4 parses it.

Edge identity is `(operation_ref, rule_ref)` per R15; multiple `fn`s producing the same `(op, rule)` collapse to one edge. Use `r/make-edge` + `r/edge-identity` to get the canonical id.

If the Rule reference doesn't resolve (e.g. no use-alias for the foreign alias, or the local Rule wasn't created by Allium), emit a warning to `*err*` and continue — Plan 3c will catch as a Phase 4 binding-rule violation. Use a `try/catch` around the edge emission, mirroring Plan 2b's best-effort pattern.

- [ ] **Step 4.1: Tests**

```clojure
(deftest fn-declare-new-with-triggers
  (testing "fn body's triggers: clause produces an R4 edge + Boundary::Binding tag"
    (let [m0 (-> (build/empty-model)
                 ;; Pre-seed the local Rule the fn references — analyzer doesn't
                 ;; create it, just emits an edge against it (Allium runs first
                 ;; in the real pipeline).
                 (build/add-primitive
                   (fukan.model.primitives/make-rule
                     {:id "test/module::SelectNode" :label "SelectNode"})))
          fn-decl {:type :fn :form :declare-new :name "select_node"
                   :params [{:name "node_id"
                             :type-ref {:kind :simple :name "NodeId"}}]
                   :return-type nil :prose nil
                   :body {:triggers {:kind :local :name "SelectNode"}
                          :returns nil}}
          model (analyze m0 [fn-decl])
          triggers-edges (filter #(= :relation/triggers (:relation %))
                                 (:edges model))]
      (is (= 1 (count triggers-edges))
          "one R4 edge emitted")
      (let [edge (first triggers-edges)]
        (is (= "test/module::select_node" (-> edge :from :id)))
        (is (= "test/module::SelectNode"  (-> edge :to :id))))
      (let [binding-tags (filter (fn [ta]
                                   (and (= "Boundary" (-> ta :tag :namespace))
                                        (= "Binding"  (-> ta :tag :name))))
                                 (:tag-apps model))]
        (is (= 1 (count binding-tags)) "one Boundary::Binding tag on the edge")
        (is (= :target/edge (-> binding-tags first :target :case)))))))

(deftest fn-body-returns-captured-in-binding-payload
  (testing "returns: clause stored as Boundary::Binding payload"
    (let [m0 (-> (build/empty-model)
                 (build/add-primitive
                   (fukan.model.primitives/make-rule
                     {:id "test/module::ProcessOrder" :label "ProcessOrder"})))
          fn-decl {:type :fn :form :declare-new :name "submit_order"
                   :params [{:name "order"
                             :type-ref {:kind :simple :name "Order"}}]
                   :return-type {:kind :simple :name "SubmissionReceipt"}
                   :prose nil
                   :body {:triggers {:kind :local :name "ProcessOrder"}
                          :returns "SubmissionReceipt(order.id, post.order.created_at)"}}
          model (analyze m0 [fn-decl])
          binding-tag (->> (:tag-apps model)
                           (filter (fn [ta]
                                     (and (= "Boundary" (-> ta :tag :namespace))
                                          (= "Binding"  (-> ta :tag :name)))))
                           first)]
      (is (= "SubmissionReceipt(order.id, post.order.created_at)"
             (-> binding-tag :payload :returns_expression))))))

(deftest fn-body-without-triggers-no-edge
  (testing "fn with body but no triggers: emits no R4 edge"
    (let [fn-decl {:type :fn :form :declare-new :name "get_view_state"
                   :params [] :return-type {:kind :simple :name "ViewState"}
                   :prose nil
                   :body {:triggers nil :returns "current_view_state"}}
          model (analyze (build/empty-model) [fn-decl])]
      (is (empty? (filter #(= :relation/triggers (:relation %)) (:edges model)))
          "no triggers edge produced when :triggers is nil"))))
```

- [ ] **Step 4.2: Run, see fail**

Expected: 3 new tests fail.

- [ ] **Step 4.3: Implement**

Add to the `require` block at the top of `analyzer.clj`:

```clojure
(require '[fukan.model.relations :as r])
```

Add helpers (after the existing analyze-fn-declare-new):

```clojure
(defn- resolve-rule-ref
  "Translate a fn-body :triggers ref to a kernel Rule id.
   Local refs qualify against `coord`; qualified refs resolve through
   `use-aliases`. Returns nil if the alias is unknown — caller decides
   whether to emit a warning or skip."
  [trigger-ref coord use-aliases]
  (case (:kind trigger-ref)
    :local      (qualify coord (:name trigger-ref))
    :qualified  (when-let [resolved (get use-aliases (:ns trigger-ref))]
                  (qualify resolved (:name trigger-ref)))))

(defn- warn! [msg ctx]
  (binding [*out* *err*]
    (println (str "[boundary-analyzer] " msg " " (pr-str ctx)))))

(defn- emit-binding-edge
  "Best-effort emission of an R4 triggers edge + Boundary::Binding tag.
   Skips silently (with warning) on unresolved rule refs or kernel errors."
  [model op-id trigger-ref returns-text coord use-aliases]
  (if-let [rule-id (resolve-rule-ref trigger-ref coord use-aliases)]
    (let [edge      (r/make-edge :relation/triggers
                                 (r/primitive-ref op-id)
                                 (r/primitive-ref rule-id))
          edge-id   (r/edge-identity edge)
          binding-payload (cond-> {}
                            returns-text (assoc :returns_expression returns-text))
          tag-app   (v/make-tag-application
                      (cond-> {:tag    {:namespace "Boundary" :name "Binding"}
                               :target {:case :target/edge :edge-identity edge-id}}
                        (seq binding-payload) (assoc :payload binding-payload)))]
      (try
        (-> model
            (build/add-edge edge)
            (build/add-tag-application tag-app))
        (catch Exception e
          (warn! "binding edge emission failed"
                 {:op op-id :rule rule-id :ex (ex-message e)})
          model)))
    (do
      (warn! "unresolved trigger ref"
             {:op op-id :trigger trigger-ref :use-aliases (keys use-aliases)})
      model)))
```

Update `analyze-fn-declare-new` to emit the binding edge when the body has a `triggers:`:

```clojure
(defn- analyze-fn-declare-new [model decl coord use-aliases]
  (let [fn-name  (:name decl)
        op-id    (qualify coord fn-name)
        params   (mapv #(param->kernel coord use-aliases %) (:params decl))
        return-t (when-let [tr (:return-type decl)]
                   (translate-type-ref tr coord use-aliases))
        op       (p/make-operation
                   (cond-> {:id op-id :label fn-name :parameters params}
                     return-t (assoc :return-type return-t)))
        m0       (-> model
                     (ensure-module-container coord)
                     (build/add-primitive op)
                     (add-operation-to-boundary coord op-id fn-name))
        m1       (build/add-tag-application
                   m0
                   (v/make-tag-application
                     {:tag    {:namespace "Boundary" :name "Function"}
                      :target {:case :target/primitive :id op-id}}))
        body     (:body decl)]
    (if (and body (:triggers body))
      (emit-binding-edge m1 op-id (:triggers body) (:returns body)
                         coord use-aliases)
      m1)))
```

- [ ] **Step 4.4: Run, expect pass**

Expected: 3 new tests + previous tests pass.

- [ ] **Step 4.5: Run full suite**

Expected: previous + 3 = 262/0/1 (smoke still failing).

- [ ] **Step 4.6: Commit**

```bash
jj desc -m "feat(boundary): fn body triggers: → R4 edge + Boundary::Binding

fn declarations with body.triggers: emit triggers: Operation → Rule edge
(R4). returns: clause stored as Boundary::Binding payload's
returns_expression field — opaque text until Plan 4. Unresolved refs
(missing alias, missing Rule) warn and skip via try/catch.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 5: `fn` attach forms — resolve to Allium Operation

**Files:**
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj`
- Modify: `test/fukan/vocabulary/boundary/analyzer_test.clj`

The two attach forms (`fn Contract.op { ... }` and `fn alias/Contract.op { ... }`) DO NOT create new Operation primitives. They look up an existing Allium-declared Contract Operation and emit a `triggers` edge + `Boundary::Binding` tag. Per the design, attach-form `fn` with empty body is a structural error.

The local-attach form references a Contract that lives in the SAME module (this file's sibling `.allium`). The foreign-attach form references a Contract in another module via the file's `use` alias.

The Allium analyzer creates Operations under id `<module-coord>::<ContractName>.<op-name>` (per Plan 2b's `analyze-operation`). Resolution: build the id from the alias-qualified or local Contract.op reference, look it up, emit edge against it. If missing, warn and skip.

- [ ] **Step 5.1: Tests**

```clojure
(deftest fn-local-attach-emits-binding-against-existing-operation
  (testing "fn Contract.op { triggers: Rule } finds the local Operation and emits an edge"
    (let [m0 (-> (build/empty-model)
                 ;; Pre-seed the Allium-declared Operation (Plan 2b's id shape):
                 (build/add-primitive
                   (fukan.model.primitives/make-operation
                     {:id "test/module::OrderSubmission.submit"
                      :label "submit" :parameters []}))
                 (build/add-primitive
                   (fukan.model.primitives/make-rule
                     {:id "test/module::ProcessOrder" :label "ProcessOrder"})))
          fn-decl {:type :fn :form :local-attach
                   :contract "OrderSubmission" :op "submit"
                   :prose nil
                   :body {:triggers {:kind :local :name "ProcessOrder"}
                          :returns "Confirmation(post.order.id)"}}
          model (analyze m0 [fn-decl])
          edges (filter #(= :relation/triggers (:relation %)) (:edges model))]
      (is (= 1 (count edges)))
      (is (= "test/module::OrderSubmission.submit" (-> edges first :from :id)))
      (is (= "test/module::ProcessOrder"           (-> edges first :to :id))))))

(deftest fn-foreign-attach-resolves-through-use-alias
  (testing "fn alias/Contract.op { ... } resolves via use-aliases"
    (let [m0 (-> (build/empty-model)
                 (build/add-primitive
                   (fukan.model.primitives/make-operation
                     {:id "other/coord::PaymentRequested.charge"
                      :label "charge" :parameters []}))
                 (build/add-primitive
                   (fukan.model.primitives/make-rule
                     {:id "test/module::HandleCharge" :label "HandleCharge"})))
          fn-decl {:type :fn :form :foreign-attach
                   :alias "c" :contract "PaymentRequested" :op "charge"
                   :prose nil
                   :body {:triggers {:kind :local :name "HandleCharge"}
                          :returns nil}}
          model (analyzer/analyze-file m0
                                       {:boundary-version 1 :declarations [fn-decl]}
                                       "test/module"
                                       {"c" "other/coord"})
          edges (filter #(= :relation/triggers (:relation %)) (:edges model))]
      (is (= 1 (count edges)))
      (is (= "other/coord::PaymentRequested.charge" (-> edges first :from :id))))))

(deftest fn-attach-empty-body-throws
  (testing "attach form with no body or empty body is a structural error"
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :fn :form :local-attach
                            :contract "X" :op "y"
                            :prose nil :body nil}])))
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :fn :form :local-attach
                            :contract "X" :op "y" :prose nil
                            :body {:triggers nil :returns nil}}])))))
```

- [ ] **Step 5.2: Run, see fail**

Expected: 3 new tests fail.

- [ ] **Step 5.3: Implement**

Add attach-form handlers to `analyzer.clj`:

```clojure
(defn- attach-op-id
  "Build the kernel Operation id for an attach-form fn."
  [decl coord use-aliases]
  (let [contract (:contract decl)
        op       (:op decl)]
    (case (:form decl)
      :local-attach   (qualify coord (str contract "." op))
      :foreign-attach (when-let [resolved (get use-aliases (:alias decl))]
                        (qualify resolved (str contract "." op))))))

(defn- analyze-fn-attach [model decl coord use-aliases]
  ;; Attach forms MUST have a body with at least one clause (else structural error).
  (let [body (:body decl)]
    (when (or (nil? body)
              (and (nil? (:triggers body)) (nil? (:returns body))))
      (throw (ex-info "attach-form fn requires non-empty body (triggers: and/or returns:)"
                      {:type :boundary-shape-error
                       :coord coord
                       :form (:form decl)}))))
  (if-let [op-id (attach-op-id decl coord use-aliases)]
    ;; Emit binding edge if triggers: present; bare returns: would only land
    ;; the tag's payload — but without a triggers edge there's no edge to tag.
    ;; Plan 4 may revisit; for MVP, returns:-without-triggers is a no-op with
    ;; a diagnostic warning.
    (let [body (:body decl)]
      (if-let [trigger (:triggers body)]
        (emit-binding-edge model op-id trigger (:returns body) coord use-aliases)
        (do (warn! "attach-form fn has returns: but no triggers: — no edge to tag"
                   {:coord coord :form (:form decl)
                    :contract (:contract decl) :op (:op decl)})
            model)))
    (do (warn! "attach-form fn could not resolve op-id"
               {:coord coord :form (:form decl) :alias (:alias decl)
                :use-aliases (keys use-aliases)})
        model)))
```

Update the `analyze-fn` dispatch:

```clojure
(defn- analyze-fn [model decl coord use-aliases]
  (case (:form decl)
    :declare-new    (analyze-fn-declare-new model decl coord use-aliases)
    :local-attach   (analyze-fn-attach      model decl coord use-aliases)
    :foreign-attach (analyze-fn-attach      model decl coord use-aliases)))
```

- [ ] **Step 5.4: Run, expect pass**

Expected: 3 new tests pass.

- [ ] **Step 5.5: Run full suite**

Expected: previous + 3 = 265/0/1.

- [ ] **Step 5.6: Commit**

```bash
jj desc -m "feat(boundary): fn attach forms — resolve to existing Allium Operation

fn Contract.op { ... } and fn alias/Contract.op { ... } look up an
existing Allium-declared Operation (Plan 2b's <coord>::<Contract>.<op>
id shape) and emit an R4 edge against it. No new Operation primitive.
Empty body on attach form is a structural error.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: `exports:` → `Boundary::ModuleApi` tag

**Files:**
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj`
- Modify: `test/fukan/vocabulary/boundary/analyzer_test.clj`

A module-bound file's `exports:` clause applies a `Boundary::ModuleApi` tag application to the bearing module-Container with payload `{exported: [<list of strings>]}`. The parser captured each entry as a bare string (e.g., `"ViewState"`) or a dotted `Contract.op` string (e.g., `"OrderSubmission.submit"`); the analyzer passes them through verbatim.

Presence of the tag flips the module to "closed" per the design. Plan 3c (Phase 4d module-visibility) enforces that cross-module references target only listed items.

One `Boundary::ModuleApi` tag per module-Container. If the parser produces more than one `:exports` declaration in a file (shouldn't normally happen, but the parser accepts it), the analyzer either merges or rejects. Choose: **reject with a structural error** — one `exports:` clause per module-bound file.

- [ ] **Step 6.1: Tests**

```clojure
(deftest exports-applies-module-api-tag
  (testing "exports: produces a Boundary::ModuleApi tag on the module-Container"
    (let [decl {:type :exports
                :entries ["ViewState" "NavigationState" "OrderSubmission.submit"]}
          model (analyze (build/empty-model) [decl])
          tags (filter (fn [ta]
                         (and (= "Boundary" (-> ta :tag :namespace))
                              (= "ModuleApi" (-> ta :tag :name))))
                       (:tag-apps model))]
      (is (= 1 (count tags)))
      (let [ta (first tags)]
        (is (= :target/primitive (-> ta :target :case)))
        (is (= "test/module" (-> ta :target :id))
            "tag targets the module-Container at the file's coord")
        (is (= ["ViewState" "NavigationState" "OrderSubmission.submit"]
               (-> ta :payload :exported)))))))

(deftest multiple-exports-clauses-rejected
  (testing "more than one :exports declaration in one file is a structural error"
    (is (thrown? Exception
                 (analyze (build/empty-model)
                          [{:type :exports :entries ["A"]}
                           {:type :exports :entries ["B"]}])))))
```

- [ ] **Step 6.2: Run, see fail**

Expected: 2 new tests fail.

- [ ] **Step 6.3: Implement**

Replace the `analyze-exports` stub:

```clojure
(defn- analyze-exports [model decl coord _use-aliases]
  ;; Multi-exports check is handled at the file level in analyze-file's reduce —
  ;; we accumulate a count and throw if >1. See updated analyze-file below.
  (let [m0      (ensure-module-container model coord)
        tag-app (v/make-tag-application
                  {:tag     {:namespace "Boundary" :name "ModuleApi"}
                   :target  {:case :target/primitive :id coord}
                   :payload {:exported (vec (:entries decl))}})]
    (build/add-tag-application m0 tag-app)))
```

Update `analyze-file` to detect multiple `:exports` declarations:

```clojure
(defn analyze-file
  [model ast coord use-aliases]
  (let [decls (:declarations ast)
        shape (shape-of decls)]
    (when (= shape :mixed)
      (throw (ex-info "mixed module-bound and subsystem-bound shapes in one file"
                      {:type :boundary-shape-error :coord coord})))
    (let [exports-count (count (filter #(= :exports (:type %)) decls))]
      (when (> exports-count 1)
        (throw (ex-info "multiple exports: declarations in one .boundary file"
                        {:type :boundary-shape-error :coord coord
                         :count exports-count}))))
    (reduce (fn [m decl] (analyze-decl m decl coord use-aliases))
            model
            decls)))
```

- [ ] **Step 6.4: Run, expect pass**

Expected: 2 new tests pass.

- [ ] **Step 6.5: Run full suite**

Expected: previous + 2 = 267/0/1.

- [ ] **Step 6.6: Commit**

```bash
jj desc -m "feat(boundary): exports: → Boundary::ModuleApi tag

exports: clause produces a single Boundary::ModuleApi tag on the
module-Container, payload {:exported [<strings>]}. Presence flips the
module to closed (Plan 3c enforces visibility). Multiple exports:
declarations in one file is a structural error.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: `subsystem` block → composite Container + tags + rules

**Files:**
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj`
- Modify: `test/fukan/vocabulary/boundary/analyzer_test.clj`

Per MODEL.md §8.2: a `subsystem <Name> { ... }` block creates a composite Container with:
- `id = coord` (the .boundary file's coord; subsystem name lives in the tag payload)
- `:children` populated from `contains:` (each entry resolved to a module-Container coord)
- `Boundary::Subsystem` tag application with payload `{name: <Name>}`
- `Boundary::Exports` tag application with payload `{exported: [<entries>]}`
- One `PredicateRegistration` per `rules:` entry with `scope = TagScope` against the composite

The `contains:` paths are file-system paths (relative). Plan 2b's `canonicalise-use-path` helper in `vocabulary/allium/pipeline.clj` does the canonicalisation; for Plan 3b, replicate the function in the boundary analyzer or lift it out. **For MVP, replicate** — it's only ~15 lines and avoids a cross-vocabulary dependency. Plan 4 or later can factor it out.

The `rules:` clause produces `PredicateRegistration`s. The kernel has a `:predicate-registrations` slot on the model (per MODEL.md §5.3). For MVP, the registrations are captured as data — the constraint engine (Plan 4) wires them up.

- [ ] **Step 7.1: Tests**

```clojure
(deftest subsystem-creates-composite-container
  (testing "subsystem block produces a composite Container + Boundary::Subsystem tag"
    (let [decl {:type :subsystem :name "Auth"
                :contains ["./oauth/spec.allium" "./password/spec.allium"]
                :exports  ["oauth/OAuthLogin"]
                :rules    []}
          model (analyzer/analyze-file (build/empty-model)
                                       {:boundary-version 1 :declarations [decl]}
                                       "test/auth"
                                       {})
          composite (build/get-primitive model "test/auth")]
      (is (some? composite) "composite Container created at file coord")
      (is (= :primitive/container (:kind composite)))
      (is (= 2 (count (:children composite))))
      (let [sub-tag (->> (:tag-apps model)
                         (filter (fn [ta]
                                   (and (= "Boundary" (-> ta :tag :namespace))
                                        (= "Subsystem" (-> ta :tag :name)))))
                         first)]
        (is (some? sub-tag))
        (is (= "Auth" (-> sub-tag :payload :name)))))))

(deftest subsystem-exports-tag-applied
  (testing "subsystem's exports: produces a Boundary::Exports tag on the composite"
    (let [decl {:type :subsystem :name "Auth"
                :contains ["./oauth/spec.allium"]
                :exports  ["oauth/OAuthLogin" "oauth/SessionRevoked"]
                :rules    []}
          model (analyzer/analyze-file (build/empty-model)
                                       {:boundary-version 1 :declarations [decl]}
                                       "test/auth"
                                       {})
          exports-tag (->> (:tag-apps model)
                           (filter (fn [ta]
                                     (and (= "Boundary" (-> ta :tag :namespace))
                                          (= "Exports"  (-> ta :tag :name)))))
                           first)]
      (is (some? exports-tag))
      (is (= ["oauth/OAuthLogin" "oauth/SessionRevoked"]
             (-> exports-tag :payload :exported))))))

(deftest subsystem-rules-produce-predicate-registrations
  (testing "subsystem rules: clause produces PredicateRegistrations on the model"
    (let [decl {:type :subsystem :name "Auth"
                :contains ["./oauth/spec.allium"]
                :exports  ["oauth/OAuthLogin"]
                :rules    [{:name "no_dependency"
                            :args [{:key "from" :value "oauth"}
                                   {:key "to"   :value "password"}]}]}
          model (analyzer/analyze-file (build/empty-model)
                                       {:boundary-version 1 :declarations [decl]}
                                       "test/auth"
                                       {})
          regs (:predicate-registrations model)]
      (is (>= (count regs) 1))
      (is (= "no_dependency" (-> regs first :predicate)))
      (is (= "test/auth" (-> regs first :scope :container))))))
```

- [ ] **Step 7.2: Run, see fail**

Expected: 3 new tests fail.

- [ ] **Step 7.3: Implement**

Add helpers to `analyzer.clj`:

```clojure
(defn- canonicalise-contains-path
  "Resolve a `contains:` entry (relative path like \"./oauth/spec.allium\"
   or \"./inner.boundary\") to a canonical root-relative coord (no
   extension).

   Replicates the pattern from fukan.vocabulary.allium.pipeline. For
   Plan 3b MVP this is replicated; Plan 4+ may factor out."
  [host-coord raw-path]
  (let [;; strip the .allium or .boundary extension
        no-ext (cond
                 (str/ends-with? raw-path ".allium")
                 (subs raw-path 0 (- (count raw-path) 7))
                 (str/ends-with? raw-path ".boundary")
                 (subs raw-path 0 (- (count raw-path) 9))
                 :else raw-path)
        ;; if relative (./ or ../), resolve against host's directory
        host-dir (let [idx (.lastIndexOf ^String host-coord "/")]
                   (if (neg? idx) "" (subs host-coord 0 idx)))]
    (cond
      (str/starts-with? no-ext "./")
      (let [tail (subs no-ext 2)]
        (if (empty? host-dir) tail (str host-dir "/" tail)))

      (str/starts-with? no-ext "../")
      ;; walk up one level from host-dir
      (let [up-idx (.lastIndexOf ^String host-dir "/")
            parent (if (neg? up-idx) "" (subs host-dir 0 up-idx))
            tail (subs no-ext 3)]
        (if (empty? parent) tail (str parent "/" tail)))

      :else no-ext)))

(require '[clojure.string :as str])
```

(Move the `clojure.string` require to the ns form's `:require` block — don't leave it as a `(require ...)` form in the middle of the file. Same for other late requires.)

Replace `analyze-subsystem`:

```clojure
(defn- analyze-subsystem [model decl coord _use-aliases]
  (let [contains-coords (mapv #(canonicalise-contains-path coord %)
                              (:contains decl))
        composite       (p/make-container
                          {:id coord
                           :label (:name decl)
                           :fields []
                           :children (mapv (fn [c] {:id c}) contains-coords)})
        m0              (build/add-primitive model composite)
        sub-tag         (v/make-tag-application
                          {:tag    {:namespace "Boundary" :name "Subsystem"}
                           :target {:case :target/primitive :id coord}
                           :payload {:name (:name decl)}})
        exports-tag     (v/make-tag-application
                          {:tag     {:namespace "Boundary" :name "Exports"}
                           :target  {:case :target/primitive :id coord}
                           :payload {:exported (vec (:exports decl))}})
        m1              (-> m0
                            (build/add-tag-application sub-tag)
                            (build/add-tag-application exports-tag))
        rules           (:rules decl)]
    (reduce (fn [m rule-entry]
              (let [reg {:predicate (:name rule-entry)
                         :scope     {:case :scope/tag :container coord}
                         :args      (:args rule-entry)}]
                (update m :predicate-registrations
                        (fnil conj []) reg)))
            m1
            rules)))
```

If `build/empty-model` doesn't initialise `:predicate-registrations`, the `fnil` form handles it. Inspect `fukan.model.build` briefly to confirm — if the kernel doesn't have that slot yet, add it (it's in MODEL.md §5.3 already; Plan 1's substrate may or may not have included the slot). If absent, add to the empty-model map; this is a tiny additive change.

- [ ] **Step 7.4: Run, expect pass**

Expected: 3 new tests pass.

- [ ] **Step 7.5: Run full suite**

Expected: previous + 3 = 270/0/1.

- [ ] **Step 7.6: Commit**

```bash
jj desc -m "feat(boundary): subsystem block → composite Container + tags + rules

subsystem <Name> { contains:, exports:, rules: } creates a composite
Container at the .boundary file's coord. Boundary::Subsystem +
Boundary::Exports tags applied. rules: entries become
PredicateRegistrations with scope = TagScope against the composite.
contains: paths canonicalised via local helper.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 8: Boundary pipeline orchestrator

**Files:**
- Modify: `src/fukan/vocabulary/boundary/pipeline.clj`
- Modify: `test/fukan/vocabulary/boundary/pipeline_test.clj`

The Boundary pipeline:
1. Registers Boundary tag-definitions onto the (already Allium-loaded) model.
2. Walks the source root for `.boundary` files.
3. Per file: parses, builds use-alias map (canonicalised), calls `analyzer/analyze-file`.

The function signature is `(load-source [model source-root])` — taking the existing model (from the Allium pipeline) and enriching it. Mirrors the orchestrator pattern but ADDS to an existing model rather than starting from `build/empty-model`.

Use-alias canonicalisation: the same path normalisation as `canonicalise-contains-path` from Task 7 applies. Reuse the helper (move it to `boundary/pipeline.clj` if cleaner, or keep it in `boundary/analyzer.clj` and require both ways).

- [ ] **Step 8.1: Tests**

Add fixture-based tests to `test/fukan/vocabulary/boundary/pipeline_test.clj`. Create test fixtures under `test/fixtures/boundary/`:

- `test/fixtures/boundary/simple/module.boundary`:
  ```
  -- boundary: 1
  fn hello() -> Greeting
  exports:
      Greeting
  ```

- `test/fixtures/boundary/simple/module.allium` (Allium sibling — needed for `combined-pipeline` to find the module-Container):
  ```
  -- allium: 3
  entity Greeting {
      text: String
  }
  ```

Add tests:

```clojure
(deftest boundary-pipeline-loads-fixture
  (testing "running just the boundary pipeline (post-Allium) on a fixture"
    (let [;; Pre-load via Allium to get the module-Container.
          m1 (fukan.vocabulary.allium.pipeline/load-source "test/fixtures/boundary/simple")
          m2 (fukan.vocabulary.boundary.pipeline/load-source
               m1 "test/fixtures/boundary/simple")
          op (build/get-primitive m2 "module::hello")
          fn-tags (filter (fn [ta]
                            (and (= "Boundary" (-> ta :tag :namespace))
                                 (= "Function" (-> ta :tag :name))))
                          (:tag-apps m2))]
      (is (some? op) "fn hello() produced an Operation primitive")
      (is (= 1 (count fn-tags)) "Boundary::Function tag applied")
      (let [api-tags (filter (fn [ta]
                               (and (= "Boundary" (-> ta :tag :namespace))
                                    (= "ModuleApi" (-> ta :tag :name))))
                             (:tag-apps m2))]
        (is (= 1 (count api-tags)) "exports: produced Boundary::ModuleApi tag")))))
```

- [ ] **Step 8.2: Run, see fail**

Expected: the fixture test fails (pipeline is still the stub).

- [ ] **Step 8.3: Implement**

Replace `src/fukan/vocabulary/boundary/pipeline.clj` body with:

```clojure
(ns fukan.vocabulary.boundary.pipeline
  "Source walk + parse + analyze for .boundary files. Mirrors
   fukan.vocabulary.allium.pipeline. Runs after the Allium pipeline —
   takes an existing model (with Allium content) and enriches it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.libs.boundary.parser :as parser]
            [fukan.vocabulary.boundary.analyzer :as analyzer]
            [fukan.vocabulary.boundary.tags :as tags]
            [fukan.model.build :as build]))

(defn- find-boundary-files
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".boundary"))
       (sort-by #(.getCanonicalPath %))
       (mapv #(.getPath %))))

(defn- coordinate-of
  "Coord = relative path from source root, minus .boundary extension."
  [root abs-path]
  (-> abs-path
      (str/replace-first (str (.getCanonicalPath (io/file root)) "/") "")
      (str/replace-first #"\.boundary$" "")))

(defn- register-boundary-tags [model]
  (reduce build/add-tag-definition model tags/boundary-tag-definitions))

(defn- canonicalise-use-path
  "Normalise a use-decl :path to a root-relative coord (no extension).
   Same logic as boundary/analyzer's canonicalise-contains-path."
  [host-coord raw-path]
  ;; Replicated to keep boundary/pipeline.clj self-contained. Lifting
  ;; this to a shared utility is Plan 4+.
  (let [no-ext (cond
                 (str/ends-with? raw-path ".allium")
                 (subs raw-path 0 (- (count raw-path) 7))
                 (str/ends-with? raw-path ".boundary")
                 (subs raw-path 0 (- (count raw-path) 9))
                 :else raw-path)
        host-dir (let [idx (.lastIndexOf ^String host-coord "/")]
                   (if (neg? idx) "" (subs host-coord 0 idx)))]
    (cond
      (str/starts-with? no-ext "./")
      (let [tail (subs no-ext 2)]
        (if (empty? host-dir) tail (str host-dir "/" tail)))

      (str/starts-with? no-ext "../")
      (let [up-idx (.lastIndexOf ^String host-dir "/")
            parent (if (neg? up-idx) "" (subs host-dir 0 up-idx))
            tail (subs no-ext 3)]
        (if (empty? parent) tail (str parent "/" tail)))

      :else no-ext)))

(defn- extract-use-aliases
  [coord ast]
  (->> (:declarations ast)
       (filter #(= :use (:type %)))
       (map (fn [{:keys [alias path]}]
              [alias (canonicalise-use-path coord path)]))
       (into {})))

(defn load-source
  "Walk source-root, parse every .boundary file, analyze each. Takes the
   Allium-produced `model` as input and returns the enriched model.

   - Registers Boundary tag-definitions on the model.
   - For each .boundary file: parses, computes coord + use-aliases,
     calls analyzer/analyze-file."
  [model source-root]
  (let [files   (find-boundary-files source-root)
        m0      (register-boundary-tags model)]
    (reduce (fn [m f]
              (let [coord (coordinate-of source-root f)
                    ast   (parser/parse-file f)
                    aliases (extract-use-aliases coord ast)]
                (analyzer/analyze-file m ast coord aliases)))
            m0
            files)))
```

- [ ] **Step 8.4: Run, expect pass**

Expected: fixture test passes.

- [ ] **Step 8.5: Run full suite**

Expected: previous + 1 fixture test = ~271/0/1 (smoke still failing — Task 10's wire-up not done).

- [ ] **Step 8.6: Commit**

```bash
jj desc -m "feat(boundary): pipeline orchestrator — walk + parse + analyze

vocabulary/boundary/pipeline/load-source takes the Allium-produced
model, registers Boundary tag-definitions, walks source-root for
.boundary files, parses each, and runs analyzer/analyze-file with
canonicalised use-aliases. Path canonicalisation replicates the
allium/pipeline helper (Plan 4+ may factor out).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 9: Top-level multi-extension pipeline + infra wire-up

**Files:**
- Modify: `src/fukan/model/pipeline.clj` (the orchestrator already in scaffold from Task 0; no further work usually needed)
- Modify: `src/fukan/infra/model.clj` — swap the `load-model` call to use the new top-level pipeline.

The top-level pipeline in `src/fukan/model/pipeline.clj` was scaffolded in Task 0 with the correct composition (`-> (allium/load-source) (boundary/load-source)`). Task 9 is wiring `infra/model.clj`'s `load-model` to call it.

- [ ] **Step 9.1: Update `src/fukan/infra/model.clj`**

Current (from Plan 2b):

```clojure
(ns fukan.infra.model
  ;; ...
  (:require [fukan.model.build :as build]
            [fukan.vocabulary.allium.pipeline :as pipeline]))
;; ...
(defn load-model [src]
  (println "Loading model from" src "(Allium pipeline — Plan 2b)")
  (let [m (pipeline/load-source src)]
    (reset! state {:model m :src src})
    ;; ...
    m))
```

Replace the require alias and the call:

```clojure
(ns fukan.infra.model
  ;; ...
  (:require [fukan.model.build :as build]
            [fukan.model.pipeline :as pipeline]))
;; ...
(defn load-model [src]
  (println "Loading model from" src "(Allium + Boundary — Plan 3b)")
  (let [m (pipeline/load-source src)]
    (reset! state {:model m :src src})
    ;; ...
    m))
```

The signature stays `[src]` — the top-level `pipeline/load-source` already handles the Allium-then-Boundary composition.

- [ ] **Step 9.2: Run the existing test suite, confirm wire-up works**

```
clj -M:test
```

Expected: Plan 2b's `pipeline-loads-fukan-corpus` test in `vocabulary/allium/pipeline_test.clj` still passes (the Allium pipeline still works alone via its own load-source). Plan 3b's `combined-pipeline-loads-fukan-corpus` smoke now exercises the full path — it should pass IF Tasks 1-8 produced the right tag-defs + tag-apps.

If the smoke test still fails, inspect the actual failure: is it tag-definition registration (Task 1) or tag-application emission (Task 3+)? Most likely the assertion `(pos? (count function-tag-apps))` is the load-bearing one — the corpus has many `fn` declarations, so the count should be > 0.

If the existing `pipeline-loads-fukan-corpus` from Plan 2b is also asserting on counts that have changed because of the Boundary additions, update those tests too — primitive count for the corpus will be higher now (added Operations from `.boundary` `fn`s).

- [ ] **Step 9.3: Run REPL smoke**

```
clojure -M:nrepl -e "(require '[fukan.infra.model :as m]) (m/load-model \"src\") (def model (m/get-model)) (println :primitives (count (:primitives model)) :edges (count (:edges model)) :tag-apps (count (:tag-apps model)))"
```

(Or use `:dev` alias — adjust to the project's actual REPL entry per `deps.edn`.)

Expected: primitive count noticeably higher than the Plan 2b baseline (57 primitives); edge count similar or higher (R4 edges added wherever `.boundary` files declare bindings — corpus may have zero bindings, in which case the edge count stays the same).

- [ ] **Step 9.4: Commit**

```bash
jj desc -m "feat(infra): wire multi-extension pipeline into infra/model

infra/model/load-model now calls fukan.model.pipeline/load-source which
composes Allium + Boundary. Plan 2b's vocabulary/allium/pipeline retains
its standalone surface for tests; top-level orchestration is the new
canonical entry point.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 10: Smoke + close — fukan-on-fukan end-to-end

**Files:**
- Modify: `test/fukan/vocabulary/boundary/pipeline_test.clj` (tighten assertions)
- Modify: `test/fukan/smoke_test.clj` (update assertions if counts change)

The Plan 3b smoke from Task 0 (`combined-pipeline-loads-fukan-corpus`) should now pass after Task 9's wire-up. Tighten its assertions to assert real expected counts based on the corpus, then close the plan.

Expected corpus content after Plan 3b loads `src/`:
- Allium-produced: 6 module-Containers (per Plan 2b's `pipeline-loads-fukan-corpus` test asserting 6 Allium::Module tags).
- Boundary-produced Operations: count the `fn` declarations across the 4 corpus `.boundary` files:
  - `infra/spec.boundary` — 7 fns
  - `web/spec.boundary` — 1 fn
  - `web/views/spec.boundary` — 4 fns
  - `model/pipeline.boundary` — 3 fns
  - Total: ~15 Operations
- Boundary-produced ModuleApi tags: count `exports:` clauses — 2 (views and pipeline).
- Boundary-produced bindings: 0 (no corpus `fn` has a `triggers:` body — corpus is signature-only).

Adjust the test assertions to match.

- [ ] **Step 10.1: Tighten the smoke test**

Replace `combined-pipeline-loads-fukan-corpus` body with concrete assertions:

```clojure
(deftest combined-pipeline-loads-fukan-corpus
  (testing "loading src/ produces a model with Allium + Boundary content"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates")
      ;; Boundary tag-definitions registered:
      (let [boundary-tag-defs (filter #(= "Boundary" (-> % :tag :namespace))
                                      (:tag-definitions model))]
        (is (= 5 (count boundary-tag-defs))
            "all 5 Boundary::* tag-definitions registered"))
      ;; Allium output preserved:
      (let [allium-tag-defs (filter #(= "Allium" (-> % :tag :namespace))
                                    (:tag-definitions model))]
        (is (pos? (count allium-tag-defs))
            "Allium tag-definitions still registered"))
      ;; Boundary::Function tags applied to fn-declared Operations:
      (let [fn-tags (filter (fn [ta]
                              (and (= "Boundary" (-> ta :tag :namespace))
                                   (= "Function" (-> ta :tag :name))))
                            (:tag-apps model))]
        (is (>= (count fn-tags) 10)
            "at least 10 Boundary::Function tags (corpus has ~15 fn decls)"))
      ;; Boundary::ModuleApi tags on modules with exports::
      (let [api-tags (filter (fn [ta]
                               (and (= "Boundary" (-> ta :tag :namespace))
                                    (= "ModuleApi" (-> ta :tag :name))))
                             (:tag-apps model))]
        (is (= 2 (count api-tags))
            "exactly 2 Boundary::ModuleApi tags (views/spec, model/pipeline)")))))
```

(Tune the counts based on actual corpus output — run the pipeline once via REPL or smoke, observe actual counts, adjust assertions.)

- [ ] **Step 10.2: Run the boundary pipeline test**

```
clj -M:test -n fukan.vocabulary.boundary.pipeline-test
```

Expected: pass.

- [ ] **Step 10.3: Run the full suite**

```
clj -M:test
```

Expected: all tests pass, 0 failures. Total count: Plan 3a baseline (253) + ~17 Plan 3b tests = ~270. Adjust if numbers diverge.

- [ ] **Step 10.4: Update `test/fukan/smoke_test.clj` if needed**

Plan 2b's `smoke_test.clj` asserts on `>= 20 primitives` and `= 5 Allium::Module tag-apps`. The Allium count stays at 5 (no Allium changes). Primitive count goes UP (Boundary Operations added). Update if the assertion changed.

Actually the Allium pipeline only counts 5 modules with Allium::Module tag — Plan 2b reviewer noted Plan 3a's `web/views/projection.allium` adds a 6th. If `smoke_test.clj` was updated by Plan 3a's Task 0 to 6, leave at 6.

- [ ] **Step 10.5: Final REPL smoke**

```
clojure -M:nrepl -e "(require '[fukan.infra.model :as m]) (m/load-model \"src\") (def model (m/get-model)) (println :prim (count (:primitives model)) :edges (count (:edges model)) :tags (count (:tag-apps model)))"
```

Expected: substantially more primitives than Plan 2b's 57 (likely 70+); tag count includes Boundary tags.

- [ ] **Step 10.6: Commit**

```bash
jj desc -m "test(boundary): tighten combined-pipeline smoke + close Plan 3b

Plan 3b is complete: Boundary tag-definitions registered, fn declarations
produce Operations with Boundary::Function tags, exports: produce
Boundary::ModuleApi tags. Subsystem composition wired (corpus has none).
fukan-on-fukan loads through both extensions end-to-end.

Plan 3c (Phase 4 structural validation) is the next plan.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing all 11 tasks (0–10), verify before declaring Plan 3b done:

1. **Every row of MODEL.md §8.2's mapping table has an analyzer landing**:
   - `fn name(params) -> R` *(declare-new, no body)* → Task 3 (Operation + Function tag)
   - `fn name(...) { triggers: Rule }` → Task 4 (+ R4 edge + Binding tag)
   - `fn name(...) { triggers: Rule; returns: <expr> }` → Task 4 (+ payload)
   - `fn Contract.op { ... }` → Task 5 (local-attach)
   - `fn alias/Contract.op { ... }` → Task 5 (foreign-attach)
   - `exports: <list>` *(module-bound)* → Task 6 (ModuleApi tag)
   - `subsystem <Name> { ... }` → Task 7 (composite Container + Subsystem tag)
   - `contains: <path>` → Task 7 (composite children)
   - `exports: <list>` *(inside subsystem)* → Task 7 (Exports tag)
   - `rules: <ref>(...)` → Task 7 (PredicateRegistration)
   - `use "<path>" as <alias>` → Task 2 (no-op; analyzer-internal)

2. **All 5 Boundary::* tags emitted** by the analyzer: Function, Binding, ModuleApi, Subsystem, Exports. Grep `analyzer.clj` for each.

3. **Cross-extension references work**: attach-form `fn` resolves against Allium-produced Operations (Task 5 tests). `triggers:` clauses resolve against Allium-produced Rules (Task 4 tests).

4. **Best-effort tolerance for unresolved refs**: missing aliases / missing Rules emit `*err*` warnings and continue. Try/catch around `build/add-edge`. No exceptions thrown for cross-extension resolution failures (Plan 3c will validate).

5. **Top-level pipeline composes cleanly**: `model/pipeline/load-source` calls Allium first, Boundary second. `infra/model/load-model` calls the top-level pipeline.

6. **No filesystem-derived structural decisions in the analyzer**: filesystem only used in `pipeline/find-boundary-files` and `pipeline/coordinate-of` (coord = relative path, treated opaquely downstream).

7. **No Plan-1 substrate changes** *(except possibly initialising `:predicate-registrations` on the empty-model map)*. Grep for changes in `src/fukan/model/` outside `pipeline.clj` (the new file).

8. **No Allium pipeline / analyzer changes**. The Allium side is frozen at Plan 2b.

9. **No Boundary parser changes**. Frozen at Plan 3a.

10. **Full test suite green**. `clj -M:test` 0 failures.

11. **REPL boots and renders**: `(m/load-model "src")` succeeds, primitive count > 57 (Plan 2b baseline).

12. **VCS state**: 11 Plan-3b commits stack cleanly on top of Plan 3a's closing commit.

If any check fails, fix in place — do **not** start Plan 3c until Plan 3b's analyzer + pipeline are clean.
