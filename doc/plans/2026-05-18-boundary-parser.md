# Boundary Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a parser for the `.boundary` Structure-altitude language, producing a well-shaped AST that the Plan 3b analyzer will translate to kernel content. The language has one structural primitive (`fn`) with optional body, plus three list-clauses (`exports:`, `subsystem`'s `contains:` / `exports:` / `rules:`) and a shared import construct (`use`). Two file shapes: *module-bound* (sibling to a `.allium`; carries `fn` and `exports:`) and *subsystem-bound* (standalone; carries one `subsystem` block). After this plan lands, every `.boundary` construct documented in [MODEL.md §8.2](../MODEL.md#82-boundary--kernel-mapping) and [DESIGN.md `.boundary responsibilities`](../DESIGN.md) parses into a well-shaped AST, and fukan's own corpus `.boundary` files parse without warnings.

**Architecture:** New `src/fukan/libs/boundary/parser.clj` mirroring the existing `src/fukan/libs/allium/parser.clj` structure — instaparse PEG grammar + transform map + `parse-boundary` / `parse-file` public API. Each construct: grammar rule(s) + matching transform + tests (per-construct unit test + corpus regression). No new dependencies — `instaparse/instaparse 1.5.0` is already on the classpath via `deps.edn`. **Expression-language bodies stay captured as text** (the `triggers:` clause is a Rule reference, parsed as ident or qualified-ident; the `returns:` clause is captured verbatim as opaque text — Plan 4 builds the constraint-language expression parser).

**Tech Stack:** Existing `instaparse/instaparse 1.5.0`; existing `clojure.test` + `cognitect.test-runner` via the `:test` alias. No new deps.

---

## Plan-of-plans context

This is **Plan 3a of 8** in the next-chapter overhaul. The full sequence (revised — Plan 3 split into 3a + 3b mid-design when the `.boundary` language redesign expanded scope; downstream plan numbers shifted accordingly):

1. **Kernel substrate** *(closed)* — primitives, value records, Type, relations, Expression / Effect, vocabulary mechanism, Artifact ontology, fixture-only Model construction.
2. **2a. Allium parser completion** *(closed)* — structural grammar to canonical coverage; expression bodies text-captured.
3. **2b. Allium analyzer** *(closed)* — Allium AST → kernel content + `Allium::*` tags; embedded expression parser; Effect canonicalisation matcher fills the Plan-1 `fukan.model.effect/canonicalise` stub; pipeline orchestrator + `infra/model` wire-up.
4. **3a. Boundary parser** *(this plan)* — PEG grammar for the redesigned `.boundary` language; AST shape conventions; corpus migration to the new format.
5. **3b. Boundary analyzer + multi-extension build pipeline** — Boundary AST → kernel content + `Boundary::*` tags; multi-extension Phase 1–3 pipeline (Allium + Boundary, cross-extension reference resolution, merge).
6. **3c. Phase 4 structural validation** — composition / event / binding / module-visibility / subsystem-visibility / export closure / cross-module reference visibility rules (per [DESIGN.md §4a–§4g](../DESIGN.md#design-level-validation-rules)).
7. **4. Constraint language + Phase 5** — the §6 constraint language; project layer; constraint evaluation as outputs (not gates).
8. **5. Clojure Target extension** — Analyzer producing `projects` edges; Projector producing Implementation Blueprints.
9. **6. Explorer rewrite + generation flow** — UI for navigating the spec graph; on-demand Blueprint summoning.

Plan 2b closed with the Allium pipeline producing a 57-primitive, 29-edge, 99-tag-application model from fukan's own `src/`. The two doc commits preceding this plan (`90aefd24` redesigns `.boundary`; this plan implements the parser side of that redesign) bring the codebase into agreement with MODEL.md §8.2 and DESIGN.md `.boundary responsibilities`.

Authoritative refs:
- [MODEL.md §8.2](../MODEL.md#82-boundary--kernel-mapping) — `.boundary` → kernel mapping table that Plan 3b will realise; defines what the analyzer expects the parser to deliver.
- [DESIGN.md `.boundary responsibilities`](../DESIGN.md) — sketch syntax and primitive semantics. The parser must accept the syntax in both sketches (module-bound and subsystem-bound).
- [DECISIONS.md `## Boundary language`](../DECISIONS.md) — B1–B7 capture the design rationale; consult when a syntax ambiguity surfaces.
- [Plan 2a](2026-05-18-allium-parser.md) — pattern this plan mirrors (grammar conventions, transform-map idioms, test-as-spec discipline, AST-shape rules).
- [`src/fukan/libs/allium/parser.clj`](../../src/fukan/libs/allium/parser.clj) — the parser to model this one on (1027 lines; Boundary parser will be smaller, ~300–500 lines).

---

## Repository conventions (jj over git)

Identical to Plan 1 and Plan 2a. This is a **colocated jj/git repository** (`.jj/` and `.git/` both present). Translate the plan's commit steps:

| In the plan | Run instead |
|---|---|
| `git add <paths>` | *(omit — jj snapshots the working copy automatically)* |
| `git commit -m "<message>"` (or heredoc form) | `jj desc -m "<message>"` followed by `jj new` to start the next change |

After each commit, verify with `jj st` and `jj log -r '::@' --limit 5`. One logical change per commit; `jj new` between tasks; **NEVER `jj squash -m "..."`** (it silently collapses commits — past sessions have lost work this way).

---

## Conventions used throughout this plan

- **Instaparse grammar style** — match the Allium parser's idioms: hidden whitespace via `<_>`/`<__>` rules; literal tokens in `<'...'>` brackets; PEG ordered alternation; angle-bracket prefix `<...>` on rule names to hide them from the parse tree.
- **Transform map** — each new grammar rule with a name like `:foo-bar` gets a matching `:foo-bar` entry in the `transforms` map. Transforms return Clojure maps with `:type` (declaration kind), `:form` (for `fn`-variant discriminator), or a domain-specific keyword.
- **AST shape consistency** — every declaration map has `:type :<kind>` (`:use`, `:fn`, `:exports`, `:subsystem`); `:fn` declarations additionally carry `:form` (`:declare-new` | `:local-attach` | `:foreign-attach`); every type-ref has `:kind` (mirror Allium's shape — `:simple`, `:qualified`, `:optional`, `:generic`, `:union`, `:inline-obj`). The corpus-regression test in Task 7 enforces these.
- **Test-as-spec** — every new construct gets a per-construct deftest in `parser_test.clj` BEFORE the grammar rule lands. Each deftest exercises one positive case and (where ambiguity is possible) one negative case. Plus a final corpus-regression deftest that parses every `.boundary` file under `src/`.
- **Underscore-vs-kebab** — `.boundary` source text uses snake_case for identifiers (`render_app_shell`, `submit_order`). The parser preserves these as strings in the AST (`{:name "render_app_shell"}`); Plan 3b's analyzer is responsible for any underscore-to-kebab translation when producing kernel content. **Do not translate inside the parser.**
- **No expression parsing.** The `triggers:` clause body is a Rule reference (bare or alias-qualified identifier). The `returns:` clause body is captured as trimmed text — Plan 4 builds the expression parser. The `rules:` entries inside subsystems are parsed as `<constraint-name>(<args>)` with args captured as structured key=value pairs but values as opaque text. If you find yourself adding grammar for expression internals, stop — that's out of scope.
- **Type-ref grammar reuse.** Type references in `fn` parameter lists and return types follow the same shape Allium uses (`:simple`, `:qualified`, `:optional`, `:generic`, `:union`, `:inline-obj`). The Boundary grammar adopts the relevant subset; do not invent new type shapes.
- **Prose comments on `fn`.** A `-- prose text` line directly under a `fn` declaration (before the next top-level construct) is captured as the fn's `:prose` field. This matches the corpus convention and Allium's prose-after-decl pattern.

---

## File Structure

### Files to create

- `src/fukan/libs/boundary/parser.clj` — grammar + transforms + public API. New file.
- `test/fukan/libs/boundary/parser_test.clj` — unit tests for each construct + corpus regression. New file.
- `src/fukan/web/views/projection.allium` — minimal stub satisfying the broken `use "./projection.allium"` reference in `web/views/spec.allium` (Plan 2b's deferred corpus gap). Authored to match the existing projection module's shape.

### Files to modify (corpus migration to new `.boundary` format)

- `src/fukan/infra/spec.boundary` — rewrite header + fn declarations to new format.
- `src/fukan/web/spec.boundary` — rewrite header + fn declarations.
- `src/fukan/web/views/spec.boundary` — rewrite; rename `exposes <Name>` → `exports:` list.
- `src/fukan/model/pipeline.boundary` — rewrite; rename `exposes <Name>` → `exports:` list.

### Files to leave untouched

- All Plan-1 substrate (`src/fukan/model/*.clj`) — Plan 3a does not touch the kernel substrate.
- The Allium analyzer / pipeline (`src/fukan/vocabulary/allium/*.clj`) — Plan 3a is parser-only.
- `src/fukan/infra/model.clj` — Plan 3b wires the Boundary pipeline through.
- All `.allium` files except `web/views/spec.allium` (which gets one `use`-path fix).

---

## Reading the canonical reference

The authoritative spec for the `.boundary` language is [MODEL.md §8.2](../MODEL.md#82-boundary--kernel-mapping) (the substrate-level mapping table — what each construct lands as) plus [DESIGN.md `.boundary responsibilities`](../DESIGN.md) (the application-design framing — sketch syntax, primitive semantics, binding lint rules). Both were updated in commit `90aefd24` to reflect the redesigned language; read them both before starting.

The new `.boundary` language has **six top-level constructs** (across both file shapes):

| Construct | File shape | Purpose |
|---|---|---|
| `use "<path>" as <alias>` | both | Cross-module imports (same shape as Allium). |
| `fn name(params) -> R` *(+ optional body)* | module-bound | Declare a new Operation on the module's Boundary, optionally attached to a Rule. |
| `fn Contract.op { body }` | module-bound | Attach behaviour to an Allium-declared local Contract Operation. |
| `fn alias/Contract.op { body }` | module-bound | Attach behaviour to a foreign Allium-declared Contract Operation. |
| `exports: <list>` | both (different scope) | Module-API closure (module-bound) or subsystem export list (inside `subsystem`). |
| `subsystem <Name> { contains:, exports:, rules:? }` | subsystem-bound | Composite Container grouping multiple modules. |

The `fn` body has two optional clauses: `triggers: <RuleRef>` (single) and `returns: <expression>`. Both are captured verbatim — `triggers:` as a structured ref, `returns:` as trimmed text.

---

## Task 0: Scaffold + corpus migration + projection.allium gap

**Files:**
- Create: `src/fukan/libs/boundary/parser.clj` (scaffold only — namespace + minimal grammar shell with just the header rule)
- Create: `test/fukan/libs/boundary/parser_test.clj` (scaffold only — namespace + helper + one passing test for header parsing)
- Create: `src/fukan/web/views/projection.allium` (minimal stub)
- Modify: `src/fukan/web/views/spec.allium` (one `use` path fix — IF needed; see Step 0.7)
- Modify: 4 corpus `.boundary` files

This task lands the scaffolding and gets the corpus into the new format so subsequent tasks can use the corpus as a regression target. Each subsequent task adds one construct's grammar + tests.

- [ ] **Step 0.1: Create parser scaffold**

Write `src/fukan/libs/boundary/parser.clj`:

```clojure
(ns fukan.libs.boundary.parser
  "Instaparse-based parser for .boundary specification files.
   Converts Boundary text into a Clojure AST suitable for analyzer
   consumption (Plan 3b).

   Two file shapes:
   - Module-bound: sibling to a .allium file at the same coordinate;
     carries fn declarations, exports:, and use.
   - Subsystem-bound: standalone composite; carries one subsystem block
     plus use.

   Grammar follows the Allium parser's idioms. Expression bodies inside
   fn `triggers:` / `returns:` clauses are captured as text or simple
   references — the Plan 4 expression parser will type them."
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Grammar
;; ---------------------------------------------------------------------------

(def grammar
  "PEG grammar for the .boundary specification language."

  "
  (* ============ Top-level ============ *)

  boundary-file = _ header _ declarations _

  header = <'-- boundary:'> _ version-number
  version-number = #'[0-9]+'

  declarations = (declaration _)*
  declaration = use-decl   (* tasks 1+ add fn-decl, exports-decl, subsystem-decl *)

  (* ============ Use (placeholder — Task 1 implements) ============ *)

  use-decl = <'use'> __ <'\"'> #'[^\"]*' <'\"'> __ <'as'> __ #'[a-zA-Z_][a-zA-Z0-9_]*'

  (* ============ Whitespace / comments ============ *)

  <_> = (whitespace / comment)*
  <__> = (whitespace / comment)+
  <whitespace> = #'\\s+'
  <comment> = #'--[^\\n]*'
  ")

(def boundary-parser
  (insta/parser grammar))

;; ---------------------------------------------------------------------------
;; Transform map (built up across tasks)
;; ---------------------------------------------------------------------------

(def transforms
  {:version-number   #(Integer/parseInt %)
   :header           (fn [v] {:boundary-version v})
   :declarations     (fn [& ds] (vec ds))
   :declaration      identity
   :use-decl         (fn [path alias]
                       {:type :use :path path :alias alias})
   :boundary-file    (fn [header decls]
                       {:boundary-version (:boundary-version header)
                        :declarations decls})})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn parse-boundary
  \"Parse a .boundary specification string into an AST map.
   Returns a map with :boundary-version and :declarations on success,
   or an instaparse failure object on parse error.\"
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (let [tree (boundary-parser text)]
    (if (insta/failure? tree)
      tree
      (insta/transform transforms tree))))

(defn parse-file
  \"Parse a .boundary specification file into an AST map.\"
  {:malli/schema [:=> [:cat :string] :map]}
  [path]
  (parse-boundary (slurp path)))
```

The grammar's `declaration` rule starts with only `use-decl` as an alternative. Each subsequent task extends it.

- [ ] **Step 0.2: Create test scaffold**

Write `test/fukan/libs/boundary/parser_test.clj`:

```clojure
(ns fukan.libs.boundary.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.libs.boundary.parser :as parser]))

(defn- parse [text]
  (parser/parse-boundary text))

(deftest header-parses
  (testing "the version header is recognised and surfaced on the result"
    (let [result (parse "-- boundary: 1\n")]
      (is (map? result) "parse produced an AST, not a failure")
      (is (= 1 (:boundary-version result))))))

(deftest header-required
  (testing "a file without the header fails to parse"
    (let [result (parse "use \"x.allium\" as x\n")]
      (is (not (map? result)) "expected failure"))))
```

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: `Ran 2 tests`, all pass.

- [ ] **Step 0.3: Author `src/fukan/web/views/projection.allium` (stub)**

Plan 2b's deferred concern: `web/views/spec.allium` declares `use "./projection.allium" as projection` but `web/views/projection.allium` does not exist. Author it minimally. Read the existing `src/fukan/projection/spec.allium` to see what types `views/spec.allium` actually references through the `projection/` alias — at minimum, fields typed `projection/Projection`. Write a stub that declares what `views/spec.allium` references.

Create `src/fukan/web/views/projection.allium`:

```
-- allium: 3

-- Projection module's public types as seen by views/spec.
-- Authored to satisfy views/spec.allium's `use "./projection.allium"` import.
-- The real projection module lives at fukan/projection/spec.allium; this is
-- a views-side facade until Plan 3c (Phase 4 validation) introduces the
-- proper closed-module export mechanism.

entity Projection {
    nodes: List<Node>
    edges: List<Edge>
}

entity Node {
    id: NodeId
    label: String
}

entity Edge {
    source: NodeId
    target: NodeId
}

external entity NodeId
```

(Adjust field names if reading `src/fukan/projection/spec.allium` reveals the actual exported shape; this is illustrative. The goal is to make `views/spec.allium`'s alias resolve to *something* with the type names it references.)

- [ ] **Step 0.4: Verify projection.allium resolves cleanly via Plan-2b pipeline**

Run the Plan-2b corpus smoke and confirm the previously-dangling `Composite-named` refs now resolve:

```
clj -M:test -n fukan.vocabulary.allium.pipeline-test
```

Expected: `pipeline-loads-fukan-corpus` AND `corpus-cross-module-refs-resolve` both pass — these are existing tests from Plan 2b. If a ref still dangles, adjust `projection.allium`'s shape to match. If you find that fixing requires a different approach (e.g., changing the `use` path in `views/spec.allium` rather than authoring a stub), document the decision in the commit message.

- [ ] **Step 0.5: Migrate `src/fukan/infra/spec.boundary` to new format**

The existing file:

```
-- Infrastructure lifecycle — mutable state management for the
-- model and HTTP server.

use "../model/spec.allium" as model

fn load_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model
  -- Load and build the model from source.
...
```

The migration adds the `-- boundary: 1` header. The `fn` declarations are already syntactically close to the new grammar (the new spec mostly formalises what the corpus did informally). Replace the file's contents with:

```
-- boundary: 1

-- Infrastructure lifecycle — mutable state management for the
-- model and HTTP server.

use "../model/spec.allium" as model

fn load_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model
  -- Load and build the model from source.

fn get_model() -> Model?
  -- Return the current model snapshot.

fn refresh_model() -> Model?
  -- Rebuild the model from source.

fn get_src() -> FilePath?
  -- Return the configured source path.

fn start_server(opts: ServerOpts) -> ServerInfo?
  -- Start the HTTP server on a given port.

fn stop_server() -> Unit
  -- Stop the running HTTP server.

fn get_port() -> Integer?
  -- Return the port the server is listening on.
```

Header is the only structural addition; everything else is identical.

- [ ] **Step 0.6: Migrate `src/fukan/web/spec.boundary`**

Replace the file with:

```
-- boundary: 1

-- HTTP/SSE transport boundary — orchestration between shell and core.
-- Parses request params, calls core functions (projection + view),
-- and streams results to connected browser clients.

use "../model/spec.allium" as model
use "./views/spec.allium" as views
use "../projection/spec.allium" as projection

fn create_handler() -> Handler
  -- Create the Ring handler that serves the Fukan application.
```

- [ ] **Step 0.7: Migrate `src/fukan/web/views/spec.boundary` (rename `exposes` → `exports:`)**

The existing file uses `exposes <Name>` for type exports — that's the old non-grammar `.boundary` convention. The new grammar uses `exports:` as a single block listing items. Replace the file with:

```
-- boundary: 1

-- Pure view rendering — transforms projection data into HTML
-- and Cytoscape JSON. No data fetching, no side effects.

use "../projection/spec.allium" as projection

fn render_app_shell() -> Html
  -- Render the initial HTML shell for the application.

fn render_graph(projection: Projection, editor_state: EditorState) -> CytoscapeGraph
  -- Transform projection data into CytoscapeGraph output.

fn render_breadcrumb(path: EntityPath) -> Html
  -- Render the navigation breadcrumb trail.

fn render_sidebar_html(detail: EntityDetails) -> Html
  -- Render entity or edge details as sidebar HTML.

exports:
    ViewState
    NavigationState
    CytoscapeGraph
    CytoscapeNode
    CytoscapeEdge
```

The `exposes` keyword is gone; the five exported types collapse into a single `exports:` list.

- [ ] **Step 0.8: Migrate `src/fukan/model/pipeline.boundary`**

Replace with:

```
-- boundary: 1

-- Model construction and boundary contract compliance.

use "./spec.allium" as model

fn build_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model
  -- Build complete model from a source path and analyzer keys.

fn check_contracts(model: Model) -> LintReport
  -- Check all cross-module edges against declared boundaries.

fn format_report(report: LintReport) -> String
  -- Render a lint report as a human-readable string.

exports:
    AnalysisResult
    LintViolation
    LintReport
    LintStats
```

- [ ] **Step 0.9: Run the existing test suite, confirm no regression**

```
clj -M:test
```

Expected: all 223 existing Plan-1 / Plan-2a / Plan-2b tests still pass. The new `parser_test.clj` adds 2 tests → 225 total. The corpus `.boundary` files are now in the new format but no test currently parses them (the Boundary parser only handles the header at this point), so the migration is functionally inert until later tasks.

- [ ] **Step 0.10: Commit**

```bash
jj desc -m "scaffold(boundary): parser shell + corpus migration to new .boundary format

Plan 3a Task 0: lays down src/fukan/libs/boundary/parser.clj with header
recognition only, plus matching test scaffold. The 4 corpus .boundary files
are rewritten in the new format (header + fn + exports:); the projection.allium
gap deferred from Plan 2b is filled.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: `use` declarations

**Files:**
- Modify: `src/fukan/libs/boundary/parser.clj` (formalise the placeholder `use-decl` rule + transform)
- Modify: `test/fukan/libs/boundary/parser_test.clj`

The Task 0 scaffold inlined `use-decl` as a single line. Task 1 formalises it: parse path content as a separate `quoted-path` rule (mirroring Allium), support multi-segment paths, and add coverage tests. The shape matches Allium's `use-decl` exactly so the analyzer can share a downstream coordinate-resolution helper.

- [ ] **Step 1.1: Test**

Add to `parser_test.clj`:

```clojure
(deftest use-decl-basic
  (testing "a single-segment use declaration parses"
    (let [result (parse "-- boundary: 1\nuse \"x.allium\" as x\n")]
      (is (= [{:type :use :path "x.allium" :alias "x"}]
             (:declarations result))))))

(deftest use-decl-relative
  (testing "relative paths are preserved verbatim in :path"
    (let [result (parse "-- boundary: 1\nuse \"../model/spec.allium\" as model\n")]
      (is (= [{:type :use :path "../model/spec.allium" :alias "model"}]
             (:declarations result))))))

(deftest use-decl-multiple
  (testing "multiple use declarations parse in order"
    (let [result (parse (str "-- boundary: 1\n"
                             "use \"a.allium\" as a\n"
                             "use \"b.allium\" as b\n"
                             "use \"c.allium\" as c\n"))]
      (is (= [{:type :use :path "a.allium" :alias "a"}
              {:type :use :path "b.allium" :alias "b"}
              {:type :use :path "c.allium" :alias "c"}]
             (:declarations result))))))
```

- [ ] **Step 1.2: Run, see fail**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: the three new tests fail because the inlined `use-decl` from Task 0 produces a different shape than the assertions expect (it does not currently wrap the result with `:type :use`).

Actually — it depends on the Task 0 transform. If the Task 0 transform already produces `{:type :use :path :alias}` (per the snippet in Step 0.1), the basic test will pass. Run and inspect. If they all pass, the work for this task is just **adding the tests** plus refactoring the grammar to split out `quoted-path` for clarity (Step 1.3).

- [ ] **Step 1.3: Refactor grammar — split `quoted-path` into its own rule**

Replace the placeholder `use-decl` in the grammar with:

```
  (* ============ Use ============ *)

  use-decl = <'use'> __ quoted-path __ <'as'> __ ident

  quoted-path = <'\"'> path-content <'\"'>
  path-content = #'[^\"]*'

  ident = #'[a-zA-Z_][a-zA-Z0-9_]*'
```

And the corresponding transforms:

```clojure
   :path-content     identity
   :quoted-path      identity
   :ident            identity
   :use-decl         (fn [path alias]
                       {:type :use :path path :alias alias})
```

This mirrors Allium's split exactly; it'll make later tasks reuse `ident` and `quoted-path` cleanly.

- [ ] **Step 1.4: Run, expect pass**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: all 5 tests pass (the original 2 from Task 0 + 3 from Step 1.1).

- [ ] **Step 1.5: Commit**

```bash
jj desc -m "feat(boundary): use declarations — quoted-path + alias

Formalises use-decl with its own quoted-path rule (matching the Allium
parser's shape so Plan 3b can share coordinate-resolution helpers).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: `fn` declare-new — signature only

**Files:**
- Modify: `src/fukan/libs/boundary/parser.clj` (add `fn-decl` grammar + transform + type-ref grammar)
- Modify: `test/fukan/libs/boundary/parser_test.clj`

The most common `fn` form: `fn name(params) -> Return` with no body. Plus optional prose comment lines on the lines immediately following.

Type references reuse Allium's shape (simple, generic, optional, qualified). Plan 2a's parser is the reference for the type-ref grammar; copy the shape verbatim (don't reinvent).

- [ ] **Step 2.1: Tests**

```clojure
(deftest fn-decl-signature-only
  (testing "a fn declaration with simple param types and return type"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_app_shell() -> Html\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "render_app_shell"
               :params []
               :return-type {:kind :simple :name "Html"}
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-with-params
  (testing "a fn declaration with multiple typed params"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_graph(p: Projection, state: EditorState) -> CytoscapeGraph\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "render_graph"
               :params [{:name "p" :type-ref {:kind :simple :name "Projection"}}
                        {:name "state" :type-ref {:kind :simple :name "EditorState"}}]
               :return-type {:kind :simple :name "CytoscapeGraph"}
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-no-return
  (testing "a fn with no return type (implicit Unit)"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn stop_server()\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "stop_server"
               :params []
               :return-type nil
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-optional-return
  (testing "a fn whose return type is optional (Type?)"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn get_port() -> Integer?\n"))]
      (is (= [{:type :fn
               :form :declare-new
               :name "get_port"
               :params []
               :return-type {:kind :optional
                             :inner {:kind :simple :name "Integer"}}
               :prose nil
               :body nil}]
             (:declarations result))))))

(deftest fn-decl-with-prose
  (testing "a -- comment line directly under a fn becomes its prose"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_app_shell() -> Html\n"
                             "  -- Render the initial HTML shell.\n"))]
      (is (= "Render the initial HTML shell."
             (-> result :declarations first :prose))))))

(deftest fn-decl-qualified-param-type
  (testing "param types can be alias-qualified (alias/Type)"
    (let [result (parse (str "-- boundary: 1\n"
                             "use \"../projection/spec.allium\" as projection\n"
                             "fn render_graph(p: projection/Projection) -> CytoscapeGraph\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= {:kind :qualified :ns "projection" :name "Projection"}
               (-> fn-decl :params first :type-ref)))))))

(deftest fn-decl-generic-param-type
  (testing "param types can be generic (e.g. Set<X>)"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn load_model(src: FilePath, analyzers: Set<AnalyzerKey>) -> Model\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)
            analyzers-param (-> fn-decl :params second)]
        (is (= "analyzers" (:name analyzers-param)))
        (is (= :generic (-> analyzers-param :type-ref :kind)))
        (is (= "Set" (-> analyzers-param :type-ref :name)))))))
```

- [ ] **Step 2.2: Run, see fail**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: all 7 new tests fail with parse errors — the grammar has no `fn-decl` yet.

- [ ] **Step 2.3: Implement — extend grammar with `fn-decl` + type-ref**

Add the new rules to the grammar (before the whitespace section). Order in the `declaration` alternation matters — keep `use-decl` first, add `fn-decl`:

```
  declaration = use-decl / fn-decl

  (* ============ Fn — declare-new ============ *)

  fn-decl = <'fn'> __ ident _ <'('> _ params? _ <')'> _ return-type? _ prose?

  params = param (_ <','> _ param)*
  param = ident _ <':'> _ type-ref

  return-type = <'->'> _ type-ref

  (* Prose lines: indented '--' lines immediately following a fn.
     The leading-whitespace requirement disambiguates fn prose
     (indented) from top-level comments (flush-left, handled by
     the comment rule in whitespace). Mirrors the Allium parser's
     annotation-trailing-prose treatment. *)
  prose = (prose-line)+
  prose-line = <#'[ \\t]*\\n[ \\t]+--[ \\t]?'> #'[^\\n]*'

  (* ============ Type references ============ *)

  type-ref = generic-type / optional-type / qualified-type / simple-type

  simple-type = ident
  qualified-type = ident <'/'> ident
  optional-type = (simple-type / generic-type / qualified-type) <'?'>
  generic-type = ident _ <'<'> _ type-ref-list _ <'>'>
  type-ref-list = type-ref (_ <','> _ type-ref)*
```

And the transforms:

```clojure
   :param            (fn [name type-ref] {:name name :type-ref type-ref})
   :params           (fn [& ps] (vec ps))
   :simple-type      (fn [n] {:kind :simple :name n})
   :qualified-type   (fn [ns n] {:kind :qualified :ns ns :name n})
   :optional-type    (fn [inner] {:kind :optional :inner inner})
   :type-ref-list    (fn [& ts] (vec ts))
   :generic-type     (fn [name & params]
                       {:kind :generic :name name :params (vec params)})
   :type-ref         identity
   :return-type      identity
   :prose-line       identity
   :prose            (fn [& lines] (str/join "\n" (map str/trim lines)))
   :fn-decl          (fn [name & rest]
                       (let [ps (or (some #(when (vector? %) %) rest) [])
                             ret (some #(when (and (map? %)
                                                   (contains? % :kind))
                                          %) rest)
                             prose (some #(when (string? %) %) rest)]
                         {:type :fn
                          :form :declare-new
                          :name name
                          :params ps
                          :return-type ret
                          :prose prose
                          :body nil}))
```

The `fn-decl` transform pattern-matches on the variadic rest by shape: params is a vector, return-type is a map with `:kind`, prose is a string. This is a deliberate match-by-shape (rather than positional) because all three are optional.

- [ ] **Step 2.4: Run, expect pass**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: all 7 fn-decl tests pass + all previous tests still pass.

- [ ] **Step 2.5: Commit**

```bash
jj desc -m "feat(boundary): fn declare-new — signature + optional prose

fn name(params) -> Return declares a new Operation on the bearing
module-Container's Boundary. Type-ref grammar covers simple, qualified,
optional, and generic shapes (mirrors Allium parser). Prose comment
directly under a fn becomes its :prose field.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: `fn` body block — `triggers:` and `returns:` clauses

**Files:**
- Modify: `src/fukan/libs/boundary/parser.clj`
- Modify: `test/fukan/libs/boundary/parser_test.clj`

Add an optional brace-delimited body to `fn-decl`. The body has two optional clauses: `triggers: <RuleRef>` (single rule reference, optionally alias-qualified) and `returns: <expression>` (captured as trimmed text until Plan 4 parses it).

The `returns:` body uses the brace-balanced text-capture approach Allium uses for `requires:` / `ensures:` clauses — capture all characters up to the next clause keyword or the closing `}`.

- [ ] **Step 3.1: Tests**

```clojure
(deftest fn-body-triggers-only
  (testing "fn with body containing just a triggers: clause"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn select_node(node_id: NodeId) {\n"
                             "    triggers: SelectNode\n"
                             "}\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= {:triggers {:kind :local :name "SelectNode"}
                :returns nil}
               (:body fn-decl)))))))

(deftest fn-body-returns-only
  (testing "fn with body containing just a returns: clause (no triggers)"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn get_view_state() -> ViewState {\n"
                             "    returns: current_view_state\n"
                             "}\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= {:triggers nil
                :returns "current_view_state"}
               (:body fn-decl)))))))

(deftest fn-body-both-clauses
  (testing "fn with body containing both triggers: and returns:"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn submit_order(order: Order) -> SubmissionReceipt {\n"
                             "    triggers: ProcessOrder\n"
                             "    returns: SubmissionReceipt(order.id, post.order.created_at)\n"
                             "}\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= {:kind :local :name "ProcessOrder"}
               (-> fn-decl :body :triggers)))
        (is (= "SubmissionReceipt(order.id, post.order.created_at)"
               (-> fn-decl :body :returns)))))))

(deftest fn-body-empty
  (testing "fn with empty body { } parses as :body {:triggers nil :returns nil}"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_app_shell() -> Html { }\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= {:triggers nil :returns nil} (:body fn-decl)))))))

(deftest fn-body-triggers-qualified
  (testing "triggers: can reference a foreign rule via alias"
    (let [result (parse (str "-- boundary: 1\n"
                             "use \"../other.allium\" as other\n"
                             "fn local_fn(x: X) -> Y {\n"
                             "    triggers: other/SomeRule\n"
                             "}\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= {:kind :qualified :ns "other" :name "SomeRule"}
               (-> fn-decl :body :triggers)))))))
```

- [ ] **Step 3.2: Run, see fail**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: 5 new tests fail (no body grammar yet).

- [ ] **Step 3.3: Implement — add body grammar + transforms**

Extend `fn-decl` to accept an optional body:

```
  fn-decl = <'fn'> __ ident _ <'('> _ params? _ <')'> _ return-type? _ prose? _ fn-body?

  fn-body = <'{'> _ fn-body-clause* _ <'}'>
  fn-body-clause = triggers-clause / returns-clause
  triggers-clause = <'triggers:'> _ rule-ref _
  returns-clause = <'returns:'> _ returns-text _

  rule-ref = qualified-rule-ref / simple-rule-ref
  simple-rule-ref = ident
  qualified-rule-ref = ident <'/'> ident

  (* returns-text: single-line capture — the rest of the line after
     'returns:' is the expression. Multi-line expressions can be added
     in Plan 4 when the constraint-language expression parser arrives. *)
  returns-text = #'[^\\n}]+'
```

Single-line `returns-text` keeps the grammar simple and matches every shape currently in the design (`returns: SubmissionReceipt(order.id, post.order.created_at)` and similar single-line expressions). The regex `[^\n}]+` stops at the newline OR at a `}` (in case someone writes `returns: foo }` on one line). Multi-line `returns:` is out of scope for Plan 3a; if Plan 4 needs it, extend the rule then.

Transforms:

```clojure
   :simple-rule-ref      (fn [n] {:kind :local :name n})
   :qualified-rule-ref   (fn [ns n] {:kind :qualified :ns ns :name n})
   :rule-ref             identity
   :triggers-clause      (fn [ref] [:triggers ref])
   :returns-text         (fn [s] (str/trim s))
   :returns-clause       (fn [text] [:returns text])
   :fn-body-clause       identity
   :fn-body              (fn [& clauses]
                           (reduce (fn [body [k v]] (assoc body k v))
                                   {:triggers nil :returns nil}
                                   clauses))
```

And update the `fn-decl` transform to recognise the `fn-body` map and slot it into the result's `:body` field:

```clojure
   :fn-decl          (fn [name & rest]
                       (let [ps (or (some #(when (vector? %) %) rest) [])
                             ret (some #(when (and (map? %)
                                                   (contains? % :kind))
                                          %) rest)
                             body (some #(when (and (map? %)
                                                    (contains? % :triggers))
                                           %) rest)
                             prose (some #(when (string? %) %) rest)]
                         {:type :fn
                          :form :declare-new
                          :name name
                          :params ps
                          :return-type ret
                          :prose prose
                          :body body}))
```

- [ ] **Step 3.4: Run, expect pass**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: all previous tests + 5 new tests pass.

- [ ] **Step 3.5: Commit**

```bash
jj desc -m "feat(boundary): fn body — triggers: + returns: clauses

Optional brace-delimited body on fn declarations. triggers: parses a
RuleRef (bare or alias/qualified). returns: captures the expression as
trimmed text — Plan 4 will type it. Both clauses optional; empty body
yields {:triggers nil :returns nil}.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: `fn` attach forms — `Contract.op` and `alias/Contract.op`

**Files:**
- Modify: `src/fukan/libs/boundary/parser.clj`
- Modify: `test/fukan/libs/boundary/parser_test.clj`

The attach forms have no parenthesised parameter list — params and return type come from the Allium-declared Operation. They MUST have a body (else there's nothing to attach). Per the design: `fn Contract.op { body }` is local-attach; `fn alias/Contract.op { body }` is foreign-attach.

- [ ] **Step 4.1: Tests**

```clojure
(deftest fn-local-attach
  (testing "fn Contract.op { ... } attaches behaviour to a local Allium Operation"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn OrderSubmission.submit {\n"
                             "    triggers: ProcessOrder\n"
                             "    returns: Confirmation(post.order.id)\n"
                             "}\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= :local-attach (:form fn-decl)))
        (is (= "OrderSubmission" (:contract fn-decl)))
        (is (= "submit" (:op fn-decl)))
        (is (= {:kind :local :name "ProcessOrder"}
               (-> fn-decl :body :triggers)))
        (is (= "Confirmation(post.order.id)"
               (-> fn-decl :body :returns)))))))

(deftest fn-foreign-attach
  (testing "fn alias/Contract.op { ... } attaches to a foreign Allium Operation"
    (let [result (parse (str "-- boundary: 1\n"
                             "use \"../c.allium\" as c\n"
                             "fn c/PaymentRequested.charge {\n"
                             "    triggers: HandleCharge\n"
                             "    returns: Receipt(amount, post.txn.id)\n"
                             "}\n"))]
      (let [fn-decl (->> (:declarations result) (filter #(= :fn (:type %))) first)]
        (is (= :foreign-attach (:form fn-decl)))
        (is (= "c" (:alias fn-decl)))
        (is (= "PaymentRequested" (:contract fn-decl)))
        (is (= "charge" (:op fn-decl)))))))

(deftest fn-attach-discriminated-by-absence-of-parens
  (testing "the parser distinguishes attach (no parens) from declare-new (parens)"
    ;; declare-new — has parens
    (let [r1 (parse "-- boundary: 1\nfn submit(o: Order) -> R\n")]
      (is (= :declare-new (-> r1 :declarations first :form))))
    ;; local-attach — no parens, just Contract.op
    (let [r2 (parse "-- boundary: 1\nfn Sub.submit { triggers: R }\n")]
      (is (= :local-attach (-> r2 :declarations first :form))))))
```

- [ ] **Step 4.2: Run, see fail**

Expected: 3 new tests fail.

- [ ] **Step 4.3: Implement — extend `fn-decl` to dispatch declare-new vs attach**

In the grammar, split `fn-decl` into alternatives:

```
  fn-decl = fn-declare-new / fn-attach

  fn-declare-new = <'fn'> __ ident _ <'('> _ params? _ <')'> _ return-type? _ prose? _ fn-body?

  fn-attach = fn-foreign-attach / fn-local-attach

  fn-local-attach = <'fn'> __ ident <'.'> ident _ prose? _ fn-body

  fn-foreign-attach = <'fn'> __ ident <'/'> ident <'.'> ident _ prose? _ fn-body
```

The PEG ordering matters: try `fn-foreign-attach` before `fn-local-attach` because the foreign form has more discriminating tokens (a slash). And try declare-new last in `fn-decl` because it can be confused with the local-attach form if the local form's `Contract.op` looks like `name(`.

Actually wait — the alternation needs to prefer the LONGER/more-specific match first. Local-attach has TWO idents separated by a dot. Declare-new has ONE ident followed by `(`. The parser can distinguish by what comes after the first ident (`.` vs `(` vs `/`). Let me re-order:

```
  fn-decl = fn-foreign-attach / fn-local-attach / fn-declare-new
```

This tries foreign-attach (`fn ident/ident.ident`) first, then local-attach (`fn ident.ident`), then declare-new (`fn ident(`). PEG ordered alternation: the first matching rule wins.

Transforms:

```clojure
   :fn-local-attach   (fn [contract op & rest]
                        (let [body (some #(when (and (map? %)
                                                     (contains? % :triggers))
                                            %) rest)
                              prose (some #(when (string? %) %) rest)]
                          {:type :fn
                           :form :local-attach
                           :contract contract
                           :op op
                           :prose prose
                           :body body}))
   :fn-foreign-attach (fn [alias contract op & rest]
                        (let [body (some #(when (and (map? %)
                                                     (contains? % :triggers))
                                            %) rest)
                              prose (some #(when (string? %) %) rest)]
                          {:type :fn
                           :form :foreign-attach
                           :alias alias
                           :contract contract
                           :op op
                           :prose prose
                           :body body}))
   :fn-declare-new   ; keep the existing transform — rename from :fn-decl
   :fn-decl           identity   ; just passthrough; the variants do the work
```

(Rename the previous `:fn-decl` transform to `:fn-declare-new` to match the renamed grammar rule.)

- [ ] **Step 4.4: Run, expect pass**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: all previous tests + 3 new tests pass.

- [ ] **Step 4.5: Commit**

```bash
jj desc -m "feat(boundary): fn attach forms — Contract.op and alias/Contract.op

Three fn name shapes disambiguated by syntax: declare-new (with parens),
local-attach (Contract.op no parens), foreign-attach (alias/Contract.op).
Attach forms produce :form :local-attach / :foreign-attach AST nodes
carrying the contract + op reference and the body's clauses.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 5: `exports:` clause (module-bound)

**Files:**
- Modify: `src/fukan/libs/boundary/parser.clj`
- Modify: `test/fukan/libs/boundary/parser_test.clj`

A top-level `exports:` clause in a module-bound file. Entries are either bare names (re-exporting an Allium-defined Entity/Surface/Value/Variant/Event/Actor) or `Contract.op` qualified names (re-exporting an Operation). Cross-module entries (`alias/Name`) are not allowed at the module level — they only appear inside subsystem `exports:` (Task 6).

- [ ] **Step 5.1: Tests**

```clojure
(deftest exports-simple
  (testing "exports: with bare-name entries"
    (let [result (parse (str "-- boundary: 1\n"
                             "exports:\n"
                             "    ViewState\n"
                             "    NavigationState\n"
                             "    CytoscapeGraph\n"))]
      (is (= [{:type :exports
               :entries ["ViewState" "NavigationState" "CytoscapeGraph"]}]
             (:declarations result))))))

(deftest exports-mixed
  (testing "exports: with bare-name and Contract.op entries"
    (let [result (parse (str "-- boundary: 1\n"
                             "exports:\n"
                             "    OrderSubmission.submit\n"
                             "    Order\n"
                             "    OrderConfirmed\n"))]
      (is (= [{:type :exports
               :entries ["OrderSubmission.submit" "Order" "OrderConfirmed"]}]
             (:declarations result))))))

(deftest exports-after-fn-declarations
  (testing "exports: can follow fn declarations in the same file"
    (let [result (parse (str "-- boundary: 1\n"
                             "fn render_graph() -> CytoscapeGraph\n"
                             "exports:\n"
                             "    ViewState\n"))]
      (let [exports (->> (:declarations result)
                         (filter #(= :exports (:type %))))]
        (is (= [{:type :exports :entries ["ViewState"]}] exports))))))
```

- [ ] **Step 5.2: Run, see fail**

Expected: 3 new tests fail.

- [ ] **Step 5.3: Implement**

Add to the grammar:

```
  declaration = use-decl / fn-decl / exports-decl

  (* ============ Exports (module-bound) ============ *)

  exports-decl = <'exports:'> _ export-entry*
  export-entry = (qualified-export / simple-export) _
  simple-export = ident
  qualified-export = ident <'.'> ident
```

Note: the grammar above uses `ident.ident` for `Contract.op` exports. The captured AST string keeps the dot intact (`"OrderSubmission.submit"`), since Plan 3b will tokenize it during analysis.

Transforms:

```clojure
   :simple-export    identity
   :qualified-export (fn [c o] (str c "." o))
   :export-entry     identity
   :exports-decl     (fn [& entries] {:type :exports :entries (vec entries)})
```

- [ ] **Step 5.4: Run, expect pass**

Expected: all previous + 3 new tests pass.

- [ ] **Step 5.5: Commit**

```bash
jj desc -m "feat(boundary): exports: clause for module-bound files

exports: lists module-public items by bare name (Entity/Surface/Value/
Variant/Event/Actor) or Contract.op qualified name (Operation). Entries
captured as strings; Plan 3b resolves to kernel ids and enforces the
exportable-kinds rule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: `subsystem` block — `contains:` / `exports:` / `rules:`

**Files:**
- Modify: `src/fukan/libs/boundary/parser.clj`
- Modify: `test/fukan/libs/boundary/parser_test.clj`

Subsystem-bound file shape: one top-level `subsystem <Name> { ... }` block containing `contains:`, `exports:`, and optional `rules:`. Inside `exports:` the entries can be `alias/Name` qualified (where alias is the filename-stem of an entry in `contains:`).

The `rules:` clause body is `<constraint-name>(<key: value>, ...)` — capture constraint name as a string and args as a list of `{:key :value}` maps with `:value` as opaque text.

- [ ] **Step 6.1: Tests**

```clojure
(deftest subsystem-basic
  (testing "subsystem block with contains: and exports:"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Auth {\n"
                             "    contains:\n"
                             "        ./oauth/spec.allium\n"
                             "        ./password/spec.allium\n"
                             "\n"
                             "    exports:\n"
                             "        oauth/OAuthLogin\n"
                             "        password/PasswordLogin\n"
                             "}\n"))]
      (is (= [{:type :subsystem
               :name "Auth"
               :contains ["./oauth/spec.allium" "./password/spec.allium"]
               :exports ["oauth/OAuthLogin" "password/PasswordLogin"]
               :rules []}]
             (:declarations result))))))

(deftest subsystem-with-rules
  (testing "subsystem with optional rules: clause"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Auth {\n"
                             "    contains:\n"
                             "        ./oauth/spec.allium\n"
                             "        ./password/spec.allium\n"
                             "\n"
                             "    exports:\n"
                             "        oauth/OAuthLogin\n"
                             "\n"
                             "    rules:\n"
                             "        no_dependency(from: oauth, to: password)\n"
                             "}\n"))]
      (let [sub (-> result :declarations first)]
        (is (= "Auth" (:name sub)))
        (is (= 1 (count (:rules sub))))
        (is (= "no_dependency" (-> sub :rules first :name)))
        (is (= [{:key "from" :value "oauth"}
                {:key "to" :value "password"}]
               (-> sub :rules first :args)))))))

(deftest subsystem-name-stem-alias-in-exports
  (testing "exports: inside subsystem use the contains-entry filename stem as alias"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Auth {\n"
                             "    contains:\n"
                             "        ./oauth/spec.allium\n"
                             "\n"
                             "    exports:\n"
                             "        oauth/OAuthLogin\n"
                             "}\n"))]
      ;; The parser doesn't resolve aliases — it just captures the string.
      ;; Plan 3b's analyzer resolves the stem alias.
      (is (= ["oauth/OAuthLogin"] (-> result :declarations first :exports))))))

(deftest subsystem-nested-boundary-reference
  (testing "contains: can reference nested subsystem .boundary files"
    (let [result (parse (str "-- boundary: 1\n"
                             "subsystem Outer {\n"
                             "    contains:\n"
                             "        ./inner.boundary\n"
                             "\n"
                             "    exports:\n"
                             "        inner/InnerSurface\n"
                             "}\n"))]
      (is (= [{:type :subsystem
               :name "Outer"
               :contains ["./inner.boundary"]
               :exports ["inner/InnerSurface"]
               :rules []}]
             (:declarations result))))))
```

- [ ] **Step 6.2: Run, see fail**

Expected: 4 new tests fail.

- [ ] **Step 6.3: Implement**

Add to the grammar:

```
  declaration = use-decl / fn-decl / exports-decl / subsystem-decl

  (* ============ Subsystem ============ *)

  subsystem-decl = <'subsystem'> __ ident _ <'{'> _ subsystem-body _ <'}'>

  subsystem-body = contains-clause _ subsystem-exports-clause _ subsystem-rules-clause?

  contains-clause = <'contains:'> _ contains-entry*
  contains-entry = path _
  path = #'[^\\s]+'

  subsystem-exports-clause = <'exports:'> _ subsystem-export-entry*
  subsystem-export-entry = (qualified-subsystem-export / simple-subsystem-export) _
  simple-subsystem-export = ident
  qualified-subsystem-export = ident <'/'> (qualified-export / simple-export)

  subsystem-rules-clause = <'rules:'> _ rule-entry*
  rule-entry = ident _ <'('> _ rule-args? _ <')'> _
  rule-args = rule-arg (_ <','> _ rule-arg)*
  rule-arg = ident _ <':'> _ rule-arg-value
  rule-arg-value = #'[^,)\\n]+'
```

Transforms (each clause tags its result with a discriminator keyword so the body-reducer can route it without shape-matching — the same pattern `fn-body` uses in Task 3):

```clojure
   :path                       identity
   :contains-entry             identity
   :contains-clause            (fn [& paths] [:contains (vec paths)])
   :simple-subsystem-export    identity
   :qualified-subsystem-export (fn [alias rest] (str alias "/" rest))
   :subsystem-export-entry     identity
   :subsystem-exports-clause   (fn [& entries] [:exports (vec entries)])
   :rule-arg-value             (fn [v] (str/trim v))
   :rule-arg                   (fn [k v] {:key k :value v})
   :rule-args                  (fn [& args] (vec args))
   :rule-entry                 (fn [name & args]
                                 {:name name :args (or (first args) [])})
   :subsystem-rules-clause     (fn [& entries] [:rules (vec entries)])
   :subsystem-body             (fn [& clauses]
                                 (reduce (fn [body [k v]] (assoc body k v))
                                         {:contains [] :exports [] :rules []}
                                         clauses))
   :subsystem-decl             (fn [name body]
                                 (merge {:type :subsystem :name name} body))
```

- [ ] **Step 6.4: Run, expect pass**

Expected: all previous + 4 new tests pass.

- [ ] **Step 6.5: Commit**

```bash
jj desc -m "feat(boundary): subsystem block — contains, exports, rules

Subsystem-bound .boundary file declares one composite Container.
contains: lists module paths (relative). exports: list cross-module
qualified items. rules: optional, captures constraint-name + args
(structured key=value pairs with values as opaque text).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: Corpus smoke + structural-assertions

**Files:**
- Modify: `test/fukan/libs/boundary/parser_test.clj`

The parser now covers every construct. Final task: a corpus regression test that parses every `.boundary` file under `src/`, plus structural-shape assertions on the AST (mirroring Allium parser's `structural-assertions-test`).

- [ ] **Step 7.1: Tests**

Add to `parser_test.clj`:

```clojure
(deftest parses-fukan-corpus
  (testing "all .boundary files under src/ parse without warnings"
    (let [files (->> (file-seq (clojure.java.io/file "src"))
                     (filter #(.isFile %))
                     (filter #(.endsWith (.getName %) ".boundary")))]
      (is (>= (count files) 4)
          "expected at least 4 .boundary files in the corpus")
      (doseq [f files]
        (let [result (parser/parse-file (.getPath f))]
          (is (map? result)
              (str f " did not parse to a map (got: " (pr-str result) ")"))
          (is (= 1 (:boundary-version result))
              (str f " does not declare boundary version 1"))
          (is (every? map? (:declarations result))
              (str f " produced non-map declarations")))))))

(deftest structural-shape-assertions
  (testing "every declaration carries a :type with one of the known kinds"
    (let [valid-types #{:use :fn :exports :subsystem}
          files (->> (file-seq (clojure.java.io/file "src"))
                     (filter #(.isFile %))
                     (filter #(.endsWith (.getName %) ".boundary")))]
      (doseq [f files
              decl (:declarations (parser/parse-file (.getPath f)))]
        (is (contains? valid-types (:type decl))
            (str f ": unknown declaration :type — " (:type decl)))
        (when (= :fn (:type decl))
          (is (#{:declare-new :local-attach :foreign-attach} (:form decl))
              (str f ": fn declaration missing/invalid :form — "
                   (:form decl))))))))

(deftest module-bound-vs-subsystem-bound
  (testing "files have either fn/exports OR subsystem, not both"
    (let [files (->> (file-seq (clojure.java.io/file "src"))
                     (filter #(.isFile %))
                     (filter #(.endsWith (.getName %) ".boundary")))]
      (doseq [f files]
        (let [decls (:declarations (parser/parse-file (.getPath f)))
              kinds (set (map :type decls))
              has-fn-or-exports (or (contains? kinds :fn)
                                    (contains? kinds :exports))
              has-subsystem (contains? kinds :subsystem)]
          (is (not (and has-fn-or-exports has-subsystem))
              (str f " mixes module-bound and subsystem-bound shapes")))))))
```

- [ ] **Step 7.2: Run, expect pass**

Run: `clj -M:test -n fukan.libs.boundary.parser-test`
Expected: all corpus files parse cleanly; structural assertions hold. The 4 corpus files (`infra/spec.boundary`, `web/spec.boundary`, `web/views/spec.boundary`, `model/pipeline.boundary`) all conform.

If a corpus file fails, the parser is wrong (revisit the relevant task) OR the corpus is wrong (revisit Task 0's migration).

- [ ] **Step 7.3: Run the full test suite**

```
clj -M:test
```

Expected: all Plan-1 / Plan-2a / Plan-2b tests still pass + all Boundary parser tests pass. Total: 223 (Plan 2b baseline) + ~25 new Boundary tests = ~248 tests. 0 failures, 0 errors.

- [ ] **Step 7.4: Commit**

```bash
jj desc -m "test(boundary): corpus smoke + structural-shape assertions

All 4 fukan-corpus .boundary files parse cleanly; structural-shape
assertions verify :type values and fn :form values. Closes Plan 3a.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing all 8 tasks (0–7), verify before declaring Plan 3a done:

1. **Every construct in MODEL.md §8.2's mapping table has a corresponding grammar rule + transform**: `use-decl`, `fn-decl` (three forms: declare-new, local-attach, foreign-attach), `fn-body` (`triggers:`/`returns:`), `exports-decl`, `subsystem-decl` (`contains:`/`exports:`/`rules:`). One construct per task; eight tasks total.

2. **AST shape uniform across declarations**: every declaration map has `:type`; `fn` additionally carries `:form` with one of `:declare-new`/`:local-attach`/`:foreign-attach`. Type-refs have `:kind`. Confirm via `structural-shape-assertions` test.

3. **Corpus migration is clean**: 4 `.boundary` files in the new format; `web/views/projection.allium` stub authored; `web/views/spec.allium` references resolve.

4. **Test count**: ~25 new Boundary parser tests; total suite ~248. 0 failures, 0 errors.

5. **No grammar changes to Allium**: confirm by `git diff` (no changes to `src/fukan/libs/allium/parser.clj`).

6. **No expression-language parsing**: `returns:` bodies are captured as text; `triggers:` references are bare or qualified idents; `rules:` arg values are opaque text. Plan 4 will lift them.

7. **The Plan-2b deferred concern (projection.allium gap) is closed**: `web/views/spec.allium`'s `use "./projection.allium"` resolves to the new stub; `corpus-cross-module-refs-resolve` in pipeline_test passes.

8. **VCS state**: `jj log -r '::@' --limit 12` shows 8 Plan-3a commits stacked cleanly on top of the doc-rewrite commit (`90aefd24`).

If any check fails, fix in place — do **not** start Plan 3b until Plan 3a's parser is clean.
