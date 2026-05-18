# Allium Analyzer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Allium Vocabulary extension's analyzer — convert the Plan-2a AST into kernel content (primitives, edges, sub-substrate) plus `Allium::*` tag applications per [MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping). After this plan lands, `clj -M:run --src src` parses every `.allium` file under `src/` and produces a validated Plan-1 Model end-to-end; fukan-on-fukan loads with real module-Containers, Surfaces, Contracts, Rules, Events, and all 11 Allium-sourced kernel relations populated.

**Architecture:** A new `fukan.vocabulary.allium` namespace family. The analyzer reads Plan-2a's AST per `.allium` file, produces kernel content per [MODEL.md §8.1's mapping table](../MODEL.md#81-allium--kernel-mapping), and emits `Allium::*` tag applications via the Plan-1 vocabulary mechanism. A minimal Allium-expression parser converts text-captured rule bodies / invariant assertions into kernel `Expression` substrate; [MODEL.md §3.8.4](../MODEL.md#384-effect-canonicalisation-patterns)'s four canonicalisation patterns produce `Effect` records that source `writes`/`creates`/`destroys`/`emits` edges. A pipeline orchestrator walks the source root, runs the per-file analyzer, resolves cross-file `use`-coordinate references, and merges results into one Model. `.boundary` integration (composite Containers, Op↔Rule bindings, closed-module visibility, External enrichment) is deferred to Plan 3.

**Tech Stack:** Existing Clojure 1.11 + metosin/malli + instaparse + clojure.test. The Plan-2a parser at `src/fukan/libs/allium/parser.clj` produces the AST consumed by this plan. The Plan-1 substrate at `src/fukan/model/*` is the target kernel shape. No new deps.

---

## Plan-of-plans context

This is **Plan 2b of 7** in the next-chapter overhaul.

1. ✅ **Kernel substrate** *(closed)* — primitives, value records, Type, relations, Expression / Effect, vocabulary mechanism, Artifact ontology, fixture-only Model construction.
2. ✅ **2a. Allium parser completion** *(closed)* — canonical Allium grammar coverage; expression bodies text-captured.
3. **2b. Allium analyzer** *(this plan)* — AST → kernel content + `Allium::*` tags; minimal Allium-expression parser; fills the Plan-1 `fukan.model.effect/canonicalise` stub. **Allium-in-isolation:** the analyzer runs without any `.boundary` file; coordinate-as-module-identity is encapsulated within this plan.
4. **Boundary analyzer + build pipeline + validation rules** — `.boundary` token syntax, parser, analyzer, Phases 2–4 with gates G1/G2 + sub-phases 4a–4g.
5. **Constraint language + Phase 5** — stratified Datalog substrate, kernel-universal derivations, methodology-shipped predicates. Will replace this plan's minimal Allium-expression parser with the canonical Expression-language one.
6. **Clojure Target extension + project layer** — Analyzer (`projects` edges with validity) + Projector (Implementation Blueprints).
7. **Explorer rewrite + generation flow** — new views, sidebars, edge filtering, drift markers, click-to-generate UX.

Authoritative refs:
- [MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping) — the Allium → kernel mapping table this plan realises.
- [MODEL.md §3.6](../MODEL.md#36-derived--not-kernel-primitives) — external-entity stub semantics + unconditional-merge resolution.
- [MODEL.md §3.8](../MODEL.md#38-expression-and-effect-substrate) — Expression + Effect substrate + canonicalisation patterns.
- [Allium language reference](../../../.claude/plugins/cache/juxt-plugins/allium/3.3.0/references/language-reference.md) — Allium grammar + module model.

---

## Repository conventions (jj over git)

Identical to Plans 1 and 2a. **Colocated jj/git repository**. Translate the plan's commit steps:

| In the plan | Run instead |
|---|---|
| `git add <paths>` | *(omit — jj snapshots the working copy automatically)* |
| `git commit -m "<message>"` (or heredoc form) | `jj desc -m "<message>"` followed by `jj new` to start the next change |
| Implicit `git add -A` in commit steps | Same — `jj desc -m "<message>" && jj new` |

**Never** use `jj squash -m "..."` — the `-m` flag overrides the parent's description, collapsing commits unintentionally. For amend-in-place fix passes, use bare `jj squash` (no `-m`).

After each commit, verify with `jj log -r '::@' --limit 5`.

---

## Conventions used throughout this plan

- **Allium is external.** Per [feedback_allium_external](../../../.claude/projects/-Users-yabmas-Code-fukan/memory/feedback_allium_external.md): do not propose changes to Allium. Conform to the canonical reference.
- **Coordinate-as-identity stays encapsulated within Allium parsing.** Filepath appears only as the per-file coordinate used for module identity and cross-file reference resolution. Downstream consumers (the kernel Model, the explorer) treat module-Container ids as opaque strings — they do not interpret the filepath structure.
- **Filesystem walk is content discovery, not structure inference.** The pipeline discovers `.allium` files under the source root; no directory hierarchy is reflected in the kernel. All modules are flat top-level siblings unless `.boundary` (Plan 3) groups them.
- **Minimal expression parser, not canonical.** This plan ships an expression parser sufficient for: (a) recognising the §3.8.4 canonicalisation patterns; (b) producing structured Bool Expressions for `requires:`/`ensures:` predicates; (c) capturing literals, navigation, comparison, boolean logic. Expression-language features not recognised structurally (joins, `for` quantification in expressions, `with`/`where` keyword usage, implication, etc.) fall back to an opaque-text literal carrier so the AST still produces a valid kernel Expression. Plan 4 replaces this minimal parser with the full Datalog-substrate expression evaluator.
- **One module-Container per `.allium` file.** Module identity = canonicalised coordinate (relative path from the source root, stripped of trailing `.allium`). `Allium::Module` tag applied.
- **Source-clause tags on Expressions and Clauses.** Bool Expressions in `Rule.intent.assertions` carry `Allium::Requires` / `Allium::Ensures` / `Allium::Invariant` tags; Definitions in `Rule.body.definitions` carry `Allium::Let`; prose Clauses carry `Allium::ContractInvariant` / `Allium::SurfaceGuarantee` / `Allium::Guidance` per the §8.1 mapping rows.
- **Event identity per K16.** Events are owned by their qualifying Container — in Allium, that's the module-Container (`Allium::Module`). Identity = `(module-Container.id, event-local-name)`. All declaration sites for a given Event name within a module must agree on parameter shape (per-module shape consistency; validated by the analyzer at module-assembly time).
- **Stub resolution per §3.6.** `external entity Foo` at use site U produces a Container with `Allium::ExternalEntity` tag. At cross-file merge time, the stub is unified with the real Container if one exists in another module (the stub's id is rewritten to the real Container's id; all stub-referencing edges retarget; `Allium::ExternalEntity` does not propagate). If no real Container exists, the stub remains with restricted substrate.
- **Named-type registry per module.** §8.1 does not list `enum` as a separate mapping row because Allium `enum X { a | b | c }` declarations contribute only to the **Type vocabulary** (per K22) — they produce an `Enum(values)` Type value with no kernel primitive. The analyzer maintains a per-module name → Type map: enums become `Enum([...])`; entities/values/variants become `Composite(Named("<module>::<name>"))`. When a field's `:type-ref {:kind :simple :name "X"}` is encountered, the analyzer looks up `"X"` in the registry and uses the resolved Type. Cross-module type references (`alias/X`) resolve via the use-alias map (Task 13). The registry lives in `analyzer.clj` and is built incrementally as declarations are walked.

---

## File Structure

### Files to create

```
src/fukan/vocabulary/                                # New namespace family
src/fukan/vocabulary/allium/analyzer.clj             # Per-file AST → kernel content; main entry per file
src/fukan/vocabulary/allium/tags.clj                 # All Allium::* TagDefinitions registered as a vector
src/fukan/vocabulary/allium/expression.clj           # Minimal Allium-expression parser + Expression substrate output
src/fukan/vocabulary/allium/effect_canonicalise.clj  # §3.8.4 four-pattern matcher; fills Plan-1 effect/canonicalise stub
src/fukan/vocabulary/allium/pipeline.clj             # Source root walk + per-file dispatch + cross-file merge

test/fukan/vocabulary/allium/analyzer_test.clj
test/fukan/vocabulary/allium/tags_test.clj
test/fukan/vocabulary/allium/expression_test.clj
test/fukan/vocabulary/allium/effect_canonicalise_test.clj
test/fukan/vocabulary/allium/pipeline_test.clj
test/fukan/vocabulary/allium/fixtures/             # Small synthetic .allium snippets for analyzer tests
```

### Files to modify

```
src/fukan/model/effect.clj                           # canonicalise stub gains real body via require + delegation OR direct port (Task 3)
src/fukan/infra/model.clj                            # Plan-1 fixture loader → real Allium pipeline (Task 14)
```

### Files to leave untouched

```
src/fukan/model/*                                    # Kernel substrate stays as-is (except effect.clj per above)
src/fukan/libs/allium/parser.clj                     # Plan-2a parser consumed read-only
src/fukan/web/*                                      # Web shell unchanged in Plan 2b (explorer is Plan 7)
```

---

## Reading order for implementers

Before starting any task, the implementer should read:

1. [MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping) — the mapping table (~23 rows) that this plan realises.
2. [MODEL.md §3.8](../MODEL.md#38-expression-and-effect-substrate) — Expression substrate + canonicalisation patterns.
3. [MODEL.md §3.6](../MODEL.md#36-derived--not-kernel-primitives) — External-entity stub semantics.
4. [Plan 2a's parser AST shape](2026-05-18-allium-parser.md) — the AST shape this analyzer consumes (in particular: declaration `:type`, field-item `:field-kind`, rule clause `:clause-type`, trigger `:kind`, etc.).
5. [Plan 1 substrate](2026-05-18-kernel-substrate.md) — the kernel API the analyzer produces (primitive constructors, edge constructors, tag-application constructors, Model builder).

---

## Task 0: Scaffold + namespace structure + smoke target

**Files:**
- Create: `src/fukan/vocabulary/allium/pipeline.clj` (empty namespace stub)
- Create: `src/fukan/vocabulary/allium/analyzer.clj` (empty stub)
- Create: `src/fukan/vocabulary/allium/tags.clj` (empty stub)
- Create: `src/fukan/vocabulary/allium/expression.clj` (empty stub)
- Create: `src/fukan/vocabulary/allium/effect_canonicalise.clj` (empty stub)
- Create: `test/fukan/vocabulary/allium/pipeline_test.clj` (one failing smoke test that targets the eventual end state)

This task lays the namespace structure and adds a **target smoke test** that Tasks 1–13 progressively make pass. The test asserts: given fukan's own `src/` directory, the pipeline produces a validated Model containing the 5 expected module-Containers.

- [ ] **Step 0.1: Create the five empty source files**

For each of the five `src/fukan/vocabulary/allium/*.clj` files listed above, create with just the ns declaration:

```clojure
;; src/fukan/vocabulary/allium/pipeline.clj
(ns fukan.vocabulary.allium.pipeline
  "Pipeline orchestrator: source root walk → per-file analyzer → cross-file
   merge → validated Model. Plan 2b's top-level entry point.")
```

Similar pattern for `analyzer.clj`, `tags.clj`, `expression.clj`, `effect_canonicalise.clj`. Each gets a one-line docstring naming its responsibility per the File Structure section above.

- [ ] **Step 0.2: Create the target smoke test**

```clojure
;; test/fukan/vocabulary/allium/pipeline_test.clj
(ns fukan.vocabulary.allium.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.pipeline :as pipeline]
            [fukan.model.build :as build]
            [malli.core :as m]))

(deftest pipeline-loads-fukan-corpus
  (testing "loading src/ produces a validated Model with the 5 fukan module-Containers"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against fukan.model.build/Model schema")

      (testing "5 module-Containers exist"
        (let [module-containers (filter (fn [[_ p]]
                                          (= :primitive/container (:kind p)))
                                        (:primitives model))]
          ;; coordinates are: fukan/infra, fukan/web, fukan/web/views,
          ;;                  fukan/model, fukan/model/pipeline
          (is (>= (count module-containers) 5)
              "at least one Container per .allium file")))

      (testing "every module has an Allium::Module tag"
        (let [module-tag-apps (filter (fn [ta]
                                        (= {:namespace "Allium" :name "Module"}
                                           (:tag ta)))
                                      (:tag-apps model))]
          (is (>= (count module-tag-apps) 5)
              "Allium::Module tag applied to each module-Container"))))))
```

This test will fail throughout Plan 2b until Task 14 wires the pipeline. Each task moves the implementation closer.

- [ ] **Step 0.3: Verify the test fails for the expected reason**

Run:

```bash
clj -M:test -n fukan.vocabulary.allium.pipeline-test
```

Expected: error like `No such var: pipeline/load-source` (or namespace-not-found if the file paths aren't on classpath).

- [ ] **Step 0.4: Commit**

```bash
jj desc -m "scaffold(allium): namespace structure for Plan 2b analyzer

Five empty source files + one target smoke test. Tasks 1-14 progressively
make the smoke test pass; Task 15 closes Plan 2b.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: TagDefinition registry — all Allium::* tags

**Files:**
- Modify: `src/fukan/vocabulary/allium/tags.clj`
- Test: `test/fukan/vocabulary/allium/tags_test.clj`

[MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping) names 22 distinct `Allium::*` tag identities the analyzer applies to primitives and edges. This task registers them all as a single `allium-tag-definitions` vector usable by downstream tasks.

The full tag list (from §8.1):

| Tag | Applies to | Payload? |
|---|---|---|
| `Allium::Module` | Container | (none) |
| `Allium::Entity` | Container | (none) |
| `Allium::Value` | Container | (none) |
| `Allium::Variant` | Container | (none) |
| `Allium::ExternalEntity` | Container | (none) |
| `Allium::Actor` | Actor | `{identified_by: Text?, within: Text?}` |
| `Allium::Rule` | Rule | (none) |
| `Allium::Trigger` | edge `triggers` / `observes` | `{kind: Enum}` — see kinds below |
| `Allium::Surface` | Container | `{facing: Ref(Actor|Container)?, context: Ref(Container)?, related: List<Ref(Container)>, timeout: String?}` |
| `Allium::Contract` | Container | (none) |
| `Allium::Provides` | edge `provides` | optional methodology metadata |
| `Allium::Exposes` | edge `exposes` | optional methodology metadata |
| `Allium::Fulfils` | edge `realises` | optional methodology metadata |
| `Allium::Demands` | edge `uses` | optional methodology metadata |
| `Allium::Call` | Operation | (none) |
| `Allium::Event` | Event | (none) |
| `Allium::Invariant` | Expression (in Container.intent.assertions) | (none) — source-clause tag |
| `Allium::Requires` | Expression (in Rule.intent.assertions) | (none) — source-clause tag |
| `Allium::Let` | Definition (in Rule.body.definitions) | (none) — source-clause tag |
| `Allium::Ensures` | Expression (in Rule.intent.assertions) | (none) — source-clause tag |
| `Allium::ContractInvariant` | Clause (in Container.intent.clauses for Contract) | (none) — source-clause tag |
| `Allium::SurfaceGuarantee` | Clause (in boundary.intent.clauses for Surface) | (none) — source-clause tag |
| `Allium::Guidance` | Clause (in various intent.clauses) | (none) — source-clause tag |

`Allium::Trigger` kind values (per §8.1 row for `when:`): `external_stimulus`, `chained` (on `triggers` edges); `creation`, `state_transition`, `becomes`, `temporal`, `derived` (on `observes` edges).

- [ ] **Step 1.1: Write the failing test**

```clojure
;; test/fukan/vocabulary/allium/tags_test.clj
(ns fukan.vocabulary.allium.tags-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.tags :as tags]
            [fukan.model.vocabulary :as v]
            [malli.core :as m]))

(deftest all-allium-tags-registered
  (testing "the tags namespace exports all 22 Allium::* TagDefinitions"
    (is (= 22 (count tags/allium-tag-definitions)))
    (is (every? #(m/validate v/TagDefinition %) tags/allium-tag-definitions))))

(deftest tag-namespaces-are-allium
  (is (every? #(= "Allium" (:namespace %)) tags/allium-tag-definitions)))

(deftest required-tag-names-present
  (testing "every §8.1 tag is in the registry"
    (let [names (set (map :name tags/allium-tag-definitions))]
      (doseq [expected ["Module" "Entity" "Value" "Variant" "ExternalEntity"
                        "Actor" "Rule" "Trigger" "Surface" "Contract"
                        "Provides" "Exposes" "Fulfils" "Demands" "Call"
                        "Event" "Invariant" "Requires" "Let" "Ensures"
                        "ContractInvariant" "SurfaceGuarantee" "Guidance"]]
        (is (contains? names expected)
            (str "missing tag: Allium::" expected))))))

(deftest actor-tag-has-payload
  (testing "Allium::Actor declares identified_by/within payload"
    (let [actor-td (->> tags/allium-tag-definitions
                        (filter #(= "Actor" (:name %)))
                        first)]
      (is (some? (:payload-schema actor-td))
          "Allium::Actor must have payload-schema (identified_by + within)"))))

(deftest surface-tag-has-payload
  (testing "Allium::Surface declares facing/context/related/timeout payload"
    (let [surf-td (->> tags/allium-tag-definitions
                       (filter #(= "Surface" (:name %)))
                       first)]
      (is (some? (:payload-schema surf-td))))))

(deftest trigger-tag-has-payload
  (testing "Allium::Trigger declares kind payload"
    (let [trig-td (->> tags/allium-tag-definitions
                       (filter #(= "Trigger" (:name %)))
                       first)]
      (is (some? (:payload-schema trig-td))))))
```

- [ ] **Step 1.2: Run, see fail**

```bash
clj -M:test -n fukan.vocabulary.allium.tags-test
```

Expected: namespace-not-found or var-not-defined.

- [ ] **Step 1.3: Implement `tags.clj`**

The implementer:
- Builds `allium-tag-definitions` as a vector of TagDefinition maps using `fukan.model.vocabulary/make-tag-definition`.
- Uses `fukan.model.type` to construct payload schemas. Examples:
  - `Allium::Actor` payload: `Composite(Inline([FieldSpec("identified_by", Scalar("Text"), optional=true), FieldSpec("within", Scalar("Text"), optional=true)]))`
  - `Allium::Surface` payload: `Composite(Inline(...))` with the four fields listed in §8.1
  - `Allium::Trigger` payload: `Composite(Inline([FieldSpec("kind", Enum([...]), optional=false)]))`
- For `applies_to`, use `:target/container`, `:target/edge`, `:target/actor`, etc. per §5.2 of MODEL.md (already implemented in Plan 1's `fukan.model.vocabulary/AppliesTo`).

Skeleton:

```clojure
(ns fukan.vocabulary.allium.tags
  "All Allium::* TagDefinitions registered as a single vector for the
   analyzer to apply onto kernel content. Per MODEL.md §8.1."
  (:require [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]))

(def ^:private trigger-kinds-on-triggers-edge
  ["external_stimulus" "chained"])

(def ^:private trigger-kinds-on-observes-edge
  ["creation" "state_transition" "becomes" "temporal" "derived"])

;; Note: a Trigger tag applied to a `triggers` edge uses the first list;
;; on an `observes` edge, the second. The single Enum payload schema
;; accepts the union; runtime constraints (which kind on which edge) are
;; analyzer-enforced.

(def allium-tag-definitions
  [;; Container-tagged primitives
   (v/make-tag-definition
     {:namespace "Allium" :name "Module"
      :applies-to :target/container})
   (v/make-tag-definition
     {:namespace "Allium" :name "Entity"
      :applies-to :target/container})
   ;; ... (rest of the 22 entries)
   ])
```

The implementer fills in all 22 entries. See the table above for `applies-to` and payload requirements.

- [ ] **Step 1.4: Run tests, expect pass**

```bash
clj -M:test -n fukan.vocabulary.allium.tags-test
```

Expected: 6 tests / 0 failures.

- [ ] **Step 1.5: Commit**

```bash
jj desc -m "feat(allium): TagDefinition registry — 22 Allium::* tags

All §8.1 tags registered: 5 Container-classification (Module, Entity, Value,
Variant, ExternalEntity), 7 primitive-classification (Actor, Rule, Surface,
Contract, Call, Event + Trigger edge-tag), 4 edge-shading (Provides,
Exposes, Fulfils, Demands), 7 source-clause tags on Expressions/Clauses
(Invariant, Requires, Let, Ensures, ContractInvariant, SurfaceGuarantee,
Guidance).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: Minimal Allium-expression parser

**Files:**
- Modify: `src/fukan/vocabulary/allium/expression.clj`
- Test: `test/fukan/vocabulary/allium/expression_test.clj`

Plan 2a's parser captures rule bodies, invariant bodies, and trigger operands as **opaque text**. The analyzer needs to convert that text into kernel `Expression` substrate (per [MODEL.md §3.8.1](../MODEL.md#381-expression)) so that:
- `Rule.intent.assertions` carries Bool-typed Expressions
- §3.8.4 canonicalisation can recognise patterns structurally (Task 3)
- Invariants land as Bool Expressions in `Container.intent.assertions`

**Scope (minimal — not canonical):** this parser handles enough Allium expression syntax to support fukan's MVP and the §3.8.4 patterns. Out of scope (deferred to Plan 4): joins, expression-level `for` quantification, `with`/`where` keywords in expressions, implication, conditional expressions, entity collections.

**In scope:**
- Literals: strings (`"..."`), integers, booleans (`true`/`false`), `null`, `now`, time-unit literals (`24.hours`, `1.minute`)
- Variables / identifiers: `x`, `order`, `params.order`
- Dotted navigation: `order.status`, `pre.account.balance`, `post.X.f` (the canonical canonicalisation prefix forms)
- Optional navigation: `a?.b`
- Null coalescing: `a ?? b`
- Function calls: `T.created(arg1, arg2)`, `emitted(E, args)`, `tuple(a, b)`
- Boolean operators: `and`, `or`, `not`
- Existence: `exists X`, `not exists X`
- Comparison: `=`, `!=`, `<`, `<=`, `>`, `>=`
- Arithmetic: `+`, `-`, `*`, `/`
- Membership: `in`, `not in`

**Fallback:** when input doesn't match any known structure, the parser produces `(make-lit (make-scalar "AlliumText") raw-text)` — an opaque literal carrier. Plan 4's expression engine reparses opaque-text literals when it lands.

Implementation: use instaparse for grammar consistency with the surface-language parser. The grammar lives in this namespace.

- [ ] **Step 2.1: Write the failing tests**

```clojure
(ns fukan.vocabulary.allium.expression-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.expression :as ae]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest literal-integer
  (let [v (ae/parse "42")]
    (is (m/validate e/Expression v))
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= 42 (get-in v [:form :value])))))

(deftest literal-boolean
  (let [v (ae/parse "true")]
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= true (get-in v [:form :value])))))

(deftest literal-string
  (let [v (ae/parse "\"hello\"")]
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= "hello" (get-in v [:form :value])))))

(deftest variable
  (let [v (ae/parse "order")]
    (is (= :expr/var (get-in v [:form :case])))
    (is (= "order" (get-in v [:form :name])))))

(deftest dotted-navigation
  (testing "post.X.f navigation"
    (let [v (ae/parse "post.order.total")]
      (is (m/validate e/Expression v))
      ;; The shape is implementation-defined — but a downstream canonicaliser
      ;; must be able to recognise this. Common shape: nested Apply
      ;; with op="." or a dedicated :expr/navigation form. The implementer
      ;; chooses; the canonicaliser in Task 3 consumes whatever shape this
      ;; parser produces.
      (is (some? v)))))

(deftest function-call
  (let [v (ae/parse "Order.created(amount, status)")]
    (is (m/validate e/Expression v))
    (is (= :expr/apply (get-in v [:form :case])))))

(deftest comparison
  (let [v (ae/parse "x > 0")]
    (is (= :expr/apply (get-in v [:form :case])))
    (is (= ">" (get-in v [:form :op])))
    (is (= 2 (count (get-in v [:form :args]))))))

(deftest boolean-not
  (let [v (ae/parse "not exists Order")]
    (is (m/validate e/Expression v))))

(deftest unknown-falls-back-to-opaque-text-lit
  (testing "unparseable text becomes a Scalar('AlliumText') literal"
    (let [v (ae/parse "for x in Y where p: q")]
      (is (= :expr/lit (get-in v [:form :case])))
      (is (= "AlliumText" (get-in v [:form :type :name])))
      (is (string? (get-in v [:form :value]))))))
```

- [ ] **Step 2.2: Run, see failures**

```bash
clj -M:test -n fukan.vocabulary.allium.expression-test
```

- [ ] **Step 2.3: Implement**

The implementer:
- Writes an instaparse grammar covering the in-scope operators (precedence: logic < comparison < arithmetic < unary < navigation < literal/var).
- Writes transforms that produce `fukan.model.expression` constructor output (`make-var`, `make-lit`, `make-apply`).
- Wraps with `try/catch` so a parse failure produces the opaque-text fallback via `(e/make-lit (t/make-scalar "AlliumText") raw)`.
- Exposes the entry: `(ae/parse text-string)` → Expression.

Approximate grammar shape (the implementer iterates):

```
expr-or = expr-and (_ <'or'> _ expr-and)*
expr-and = expr-not (_ <'and'> _ expr-not)*
expr-not = (<'not'> _)? expr-cmp
expr-cmp = expr-add (_ cmp-op _ expr-add)?
cmp-op = '=' | '!=' | '<=' | '>=' | '<' | '>'
expr-add = expr-mul (_ add-op _ expr-mul)*
add-op = '+' | '-'
expr-mul = expr-nav (_ mul-op _ expr-nav)*
mul-op = '*' | '/'
expr-nav = primary (nav-op)*
nav-op = '.' ident
       | '.' ident '(' arglist? ')'
       | '?.' ident
primary = literal / func-call / ident / paren / exists
exists = ('not' _)? 'exists' _ expr-nav
literal = integer | string | boolean | now
func-call = ident '(' arglist? ')'
arglist = expr-or (_ ',' _ expr-or)*
```

Iterate until tests pass. PEG ordering matters — try more specific rules first.

- [ ] **Step 2.4: Run, expect pass**

```bash
clj -M:test -n fukan.vocabulary.allium.expression-test
```

- [ ] **Step 2.5: Commit**

```bash
jj desc -m "feat(allium): minimal expression parser → kernel Expression substrate

Covers literals, navigation, function-call, comparison, arithmetic, boolean
logic, existence. Unparseable text falls back to Scalar('AlliumText') literal
so all rule/invariant bodies produce valid Expression values. Plan 4 replaces
this with the canonical Datalog-substrate expression engine.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: Effect canonicalisation — §3.8.4 four-pattern matcher

**Files:**
- Modify: `src/fukan/vocabulary/allium/effect_canonicalise.clj` (new content)
- Modify: `src/fukan/model/effect.clj` — `canonicalise` stub now delegates to this namespace
- Test: `test/fukan/vocabulary/allium/effect_canonicalise_test.clj`

[MODEL.md §3.8.4](../MODEL.md#384-effect-canonicalisation-patterns) defines four patterns the analyzer materialises Effects from:

| Pattern (Bool Expression in TwoState) | Effect produced |
|---|---|
| `post.X.f = E` | `Effect(Write, SubstrateAddress(X, [{:slot "field" :key "f"}]), E, source)` |
| `post.X = T.created(field_bindings…)` (where `pre.X` denotes no existing instance) | `Effect(Create, PrimitiveRef(X), T-with-bindings, source)` |
| `not exists post.X` (where `pre.X` denoted an existing instance) | `Effect(Destroy, PrimitiveRef(X), nil, source)` |
| `emitted(E, args…)` | `Effect(Emit, PrimitiveRef(E), args, source)` |

This task implements the pattern recognizer over the kernel `Expression` substrate (Task 2's output) and fills in `fukan.model.effect/canonicalise` (which was a Plan-1 stub returning `nil`).

- [ ] **Step 3.1: Write the failing tests**

```clojure
(ns fukan.vocabulary.allium.effect-canonicalise-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.effect-canonicalise :as ec]
            [fukan.vocabulary.allium.expression :as ae]
            [fukan.model.effect :as fx]))

(deftest write-pattern
  (testing "post.X.f = E becomes Write effect"
    (let [expr (ae/parse "post.order.total = pre.order.total + amount")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/write (:kind fx)))
      (is (= :endpoint/substrate (get-in fx [:target :case])))
      (is (= "order" (get-in fx [:target :container])))
      (is (= "total" (get-in fx [:target :path 0 :key]))))))

(deftest create-pattern
  (testing "post.X = T.created(...) becomes Create effect"
    (let [expr (ae/parse "post.order = Order.created(amount: 100)")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/create (:kind fx)))
      (is (= :endpoint/primitive (get-in fx [:target :case]))))))

(deftest destroy-pattern
  (testing "not exists post.X becomes Destroy effect"
    (let [expr (ae/parse "not exists post.order")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/destroy (:kind fx)))
      (is (nil? (:value fx))))))

(deftest emit-pattern
  (testing "emitted(E, args...) becomes Emit effect"
    (let [expr (ae/parse "emitted(OrderShipped, order)")
          fx (ec/canonicalise expr "expr-1")]
      (is (= :effect/emit (:kind fx)))
      (is (= "OrderShipped" (get-in fx [:target :id]))))))

(deftest non-effect-bool-comparison-returns-nil
  (testing "post.X.total > 0 is a Bool assertion, not an effect"
    (let [expr (ae/parse "post.order.total > 0")
          fx (ec/canonicalise expr "expr-1")]
      (is (nil? fx)))))

(deftest non-effect-pure-comparison
  (let [expr (ae/parse "x = 5")
        fx (ec/canonicalise expr "expr-1")]
    (is (nil? fx))))

(deftest plan-1-stub-now-delegates
  (testing "fukan.model.effect/canonicalise calls into the analyzer module"
    (let [expr (ae/parse "post.order.total = 100")
          fx (fx/canonicalise expr)]
      (is (= :effect/write (:kind fx))
          "fx/canonicalise (Plan-1 entry) returns a real Effect, not nil"))))
```

- [ ] **Step 3.2: Run, see failures**

- [ ] **Step 3.3: Implement the canonicaliser**

The implementer:
- Inspects the Expression's `:form` shape to detect each of the four patterns
- For Write pattern: matches `Apply("=", [<post.X.f navigation>, <rhs>])`. Extracts X, f, and the rhs Expression.
- For Create pattern: matches `Apply("=", [<post.X navigation>, Apply(T-name+".created", [args])])`. Extracts X, T, and bindings.
- For Destroy pattern: matches `Apply("not", [Apply("exists", [<post.X navigation>])])`. Extracts X.
- For Emit pattern: matches `Apply("emitted", [<event-ref>, args...])`. Extracts E and args.
- Returns nil when no pattern matches.
- Detection of `post.` and `pre.` prefixes on the navigation root: the parser in Task 2 may represent these as a Var or a dotted-path with `post`/`pre` as the head. The implementer chooses a consistent shape and documents it inline.

Then add the delegation in `src/fukan/model/effect.clj`:

```clojure
;; src/fukan/model/effect.clj — replace the Plan-1 stub
(defn canonicalise
  "Per §3.8.4. Delegates to the Allium analyzer's canonicaliser. Other
   Vocabulary extensions (future Boundary/DDD/Hex) register their own
   canonicalisation handlers via the methodology-operator extension seam
   (deferred — see §3.8.1 TBDs)."
  [expression]
  ((requiring-resolve 'fukan.vocabulary.allium.effect-canonicalise/canonicalise)
   expression
   "anonymous-source"))
```

The `requiring-resolve` indirection avoids a circular load order: `fukan.model.effect` is a kernel module that can't statically depend on `fukan.vocabulary.allium.*` (which depends on it).

- [ ] **Step 3.4: Run, expect pass**

- [ ] **Step 3.5: Commit**

```bash
jj desc -m "feat(allium): Effect canonicalisation matcher (§3.8.4 four patterns)

Implements the Write / Create / Destroy / Emit pattern recognizer over
the kernel Expression substrate. Fills the Plan-1 fukan.model.effect/
canonicalise stub via requiring-resolve to avoid circular deps.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: Module-Container construction

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj` (initial content)
- Test: `test/fukan/vocabulary/allium/analyzer_test.clj` (new file)

The analyzer's main entry point is per-file: given a parsed AST and a coordinate (relative path from source root), produce kernel content for that file. The simplest unit: the module-Container itself.

Per §8.1's `module` row:
- One Container with `Allium::Module` tag
- `:id` = coordinate (e.g., `"web/views"`)
- `:label` = a readable derivation (e.g., last segment + uppercased — `"Views"`; or just the coordinate)
- `:description` = the leading-comment block of the file, if any
- No `:fields`, no `:boundary`
- `:children` populated by Tasks 5–9 (entities, surfaces, contracts, actors)
- `:events` populated by Task 12

This task ships the analyzer's `analyze-file` entry that produces the bare module-Container; Tasks 5–13 enrich the children and events.

- [ ] **Step 4.1: Write the failing test**

```clojure
(ns fukan.vocabulary.allium.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.analyzer :as analyzer]
            [fukan.libs.allium.parser :as parser]
            [fukan.model.build :as build]))

(defn- ast [text]
  (parser/parse-allium (str "-- allium: 1\n" text)))

(deftest module-container-from-empty-file
  (testing "an empty .allium file produces one module-Container"
    (let [model (build/empty-model)
          ast   (ast "")
          coord "test/module"
          model (analyzer/analyze-file model ast coord)]
      (is (some? (build/get-primitive model "test/module")))
      (is (= :primitive/container
             (:kind (build/get-primitive model "test/module"))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(= "Module" (-> % :tag :name)))
                         first)]
        (is (some? tag-app) "Allium::Module tag applied")
        (is (= "test/module" (-> tag-app :target :id)))))))

(deftest module-container-with-leading-prose
  (testing "leading -- comment block becomes :description"
    (let [model (build/empty-model)
          ast   (ast "-- This is the User module.\n-- It manages identity.\n")
          model (analyzer/analyze-file model ast "user")
          c (build/get-primitive model "user")]
      (is (.contains (:description c "") "This is the User module"))
      (is (.contains (:description c "") "It manages identity")))))
```

- [ ] **Step 4.2: Run, see fail**

- [ ] **Step 4.3: Implement `analyze-file`**

```clojure
(ns fukan.vocabulary.allium.analyzer
  "Per-file Allium AST → kernel content (with Allium::* tag applications).
   The entry point `analyze-file` takes a Model, an AST, and a coordinate;
   returns the Model extended with this file's content."
  (:require [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.build :as build]))

(defn- extract-leading-description
  "If the parsed AST carries a leading comment block (Task 2a annotations
   capture trailing prose, so leading is just a sequence of -- lines
   between header and first declaration), assemble it as :description.
   The Plan-2a parser hides line-comments via <_>; leading prose is lost.

   For Plan 2b's MVP, return nil — descriptions land via @guidance
   annotations in Tasks 11+. This helper is a placeholder for future
   refinement (e.g., extending the Plan-2a header rule to optionally
   capture a leading-comment block as the module's description)."
  [_ast]
  nil)

(defn analyze-file
  "Add this file's kernel content + Allium::* tag applications to `model`."
  [model ast coordinate]
  (let [module-c (p/make-container
                   (cond-> {:id coordinate
                            :label coordinate}
                     (extract-leading-description ast)
                     (assoc :description (extract-leading-description ast))))]
    (-> model
        (build/add-primitive module-c)
        (build/add-tag-application
          (v/make-tag-application
            {:tag {:namespace "Allium" :name "Module"}
             :target {:case :target/primitive :id coordinate}})))))
```

> **Note on leading-prose description:** Plan 2a's parser hides line-comments via `<_>`. To capture the leading comment block as the module's description, the parser would need a new rule. For Plan 2b's MVP, leave it as `nil`; the second test above passes because we set `(.contains (or x "") "...")` against an empty string (returns false — adjust the test if needed, or implement the leading-prose extraction as part of this task by extending the parser). The implementer chooses: (a) extend the Plan-2a parser to capture leading prose, OR (b) drop the second test and document as a Plan-2b-deferred feature.

- [ ] **Step 4.4: Run, expect pass**

- [ ] **Step 4.5: Commit**

```bash
jj desc -m "feat(allium): analyzer entry — module-Container per file

analyze-file [model ast coordinate] adds one Container with Allium::Module
tag to the Model. The Container's :id is the coordinate (relative path from
source root, stripped of .allium). Tasks 5+ populate :children and :events.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 5: Entity / Value / Variant Containers

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1's entity/value/variant rows:

| Allium | Kernel |
|---|---|
| `entity Name { ... }` | Container with `Allium::Entity` tag; fields populated from `:fields` AST entries |
| `value Name { ... }` | Container with `Allium::Value` tag; fields populated |
| `variant Name : Parent { ... }` | Container with `Allium::Variant` tag; fields populated *for branch-specific fields only* (no parent duplication); emits `specialises: Container → Container` edge to Parent |

Field-name collision between a variant child and its parent is a **structural error** in MVP (§8.1's variant row). The analyzer must detect and surface it.

For each declared Container, its `:id` is qualified by the module: `<coordinate>::<local-name>`. E.g., `web/views::CytoscapeGraph`.

- [ ] **Step 5.1: Write tests**

Add deftests for:
- `analyze-entity` produces a Container with `Allium::Entity` tag and populated fields
- `analyze-value` similar with `Allium::Value`
- `analyze-variant` produces Container with `Allium::Variant`, populated branch-only fields, AND a `specialises` edge to the parent (using `<module>::<parent-name>` qualified id)
- variant field-name collision (a variant declares `total: Integer` when parent already has `total: String`) is rejected (throws or returns error)
- The module-Container's `:children` set includes all declared Containers

- [ ] **Step 5.2: Run, see fail**

- [ ] **Step 5.3: Implement**

The implementer extends `analyze-file` to walk `(:declarations ast)` and dispatch on `(:type decl)`:
- `:entity` → `analyze-entity decl module-coord`
- `:value` → `analyze-value decl module-coord`
- `:variant` → `analyze-variant decl module-coord` (which also emits the `specialises` edge)
- Other decl types: dispatch is a no-op for this task (later tasks add them)

Each handler:
1. Builds a Container with id `(str module-coord "::" decl-name)`
2. Converts `:fields` AST entries to `fukan.model.primitives/make-field` records (Type values constructed via `fukan.model.type`)
3. Adds the appropriate `Allium::Entity` / `Value` / `Variant` tag application
4. For variants: adds a `specialises` edge from the variant Container to the parent Container (also `<module>::<parent-name>` qualified)
5. For variants: checks parent's already-known fields against the variant's fields; throws on collision

The handler also updates the module-Container's `:children` to include the new Container's id.

- [ ] **Step 5.4: Run, expect pass**

- [ ] **Step 5.5: Commit**

```bash
jj desc -m "feat(allium): entity/value/variant Container analysis

Maps Allium entity/value/variant declarations to kernel Containers per
§8.1: Allium::Entity/Value/Variant tags, fields as Field value records,
:children populated on module-Container, specialises edges for variants.
Variant field-name collision with parent is a structural error.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: External-entity + Actor

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Two simple primitive forms per §8.1:

| Allium | Kernel |
|---|---|
| `external entity Foo { ... }` | Container with `Allium::ExternalEntity` tag; stub semantics per §3.6 |
| `actor Name { identified_by: T [where pred] within: T? }` | Actor primitive with `Allium::Actor` payload `{identified_by: Text?, within: Text?}` |

The actor's payload carries opaque text (per §8.1 row): both `identified_by` and `within` are stored as text — the type-ref and where-predicate are flattened into `identified_by` text for now (Plan 4 may decompose into typed slots later).

- [ ] **Step 6.1: Tests**

Add tests for:
- `external entity Foo {}` produces a Container with `Allium::ExternalEntity` tag and empty fields
- `actor Author { identified_by: User }` produces an Actor primitive with `Allium::Actor` tag application carrying `{identified_by: "User", within: nil}`
- Actor with `where` predicate captures it in `identified_by` (text-concatenated): `identified_by: User where role = admin` → text `"User where role = admin"`
- Actor with `within` populates that payload slot

- [ ] **Step 6.2: Run, see fail**

- [ ] **Step 6.3: Implement**

Extend the dispatch in `analyze-file` for `:external-entity` and `:actor` declarations. Build Containers / Actors via Plan-1 constructors. Apply tag applications.

Actor payload assembly: synthesize a text representation from the AST's `:identified-by` (type-ref) + optional `:identified-by-where` (text). Format: `"<type-ref-text>" if no where; "<type-ref-text> where <where-text>"` if present. The `:within` field similarly: type-ref text or nil.

- [ ] **Step 6.4: Run, expect pass**

- [ ] **Step 6.5: Commit**

```bash
jj desc -m "feat(allium): external-entity + actor declarations

External entities become Containers with Allium::ExternalEntity tag (stub
unification at merge per §3.6). Actors land as Actor primitives with
Allium::Actor payload {identified_by, within} carrying opaque text.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: Surface Containers

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1's surface row:

```
surface Name { facing actor: T  context entity: T  related: ... timeout: R
    exposes: <fields>
    provides: <events>
    contracts: demands/fulfils <Contracts>
}
```

Maps to:
- Container with empty `fields`, with `:boundary` populated with empty `:operations` (Surface's Boundary is empty — Operations live on fulfilled Contracts, reached via outgoing `realises` edges per §9.1)
- `Allium::Surface` tag application with payload `{facing, context, related, timeout}` per §8.1
- For each `exposes: path` clause: an `exposes` kernel edge from this Container to a Field (substrate address) on a target Container; `Allium::Exposes` tag on the edge
- For each `provides: EventCall(args)` clause: a `provides` kernel edge from this Container to the Event; `Allium::Provides` tag on the edge
- For each `contracts: fulfils <Contract>`: a `realises` edge to the Contract Container; `Allium::Fulfils` on the edge
- For each `contracts: demands <Contract>`: a `uses` edge to the Contract Container; `Allium::Demands` on the edge

**Cross-module references** in `exposes:` (e.g., `views/CytoscapeGraph.something`), `provides:` event args, `contracts: fulfils other/X`, etc., are resolved during Task 13. For this task, the analyzer can emit edges with placeholder ids that Task 13 retargets.

- [ ] **Step 7.1: Tests**

Add deftests for:
- `analyze-surface` produces a Container with `Allium::Surface` tag and the expected payload slots
- `exposes:` clauses emit `exposes` edges with `Allium::Exposes` edge-tags
- `provides:` clauses emit `provides` edges with `Allium::Provides` edge-tags
- `contracts: fulfils X` emits `realises` edge with `Allium::Fulfils`
- `contracts: demands X` emits `uses` edge with `Allium::Demands`

- [ ] **Step 7.2: Run, see fail**

- [ ] **Step 7.3: Implement**

Add the `:surface` dispatch case. Walk the AST's `:fields` for each surface-level construct (the Plan-2a parser produces structured field-items: `:facing`, `:context`, `:related`, `:timeout`, `:provides-block`, `:exposes-block`, `:contracts-block`, plus `:annotation` for `@guarantee`).

For each kernel edge emission, use `fukan.model.relations/make-edge` with appropriate endpoints (primitive-ref for Container/Event/Contract targets; substrate-address for Field targets). Add tag applications on stored edges via `fukan.model.vocabulary/make-tag-application` with `:target/edge` target (and the edge-identity tuple as `:edge-identity`).

- [ ] **Step 7.4: Run, expect pass**

- [ ] **Step 7.5: Commit**

```bash
jj desc -m "feat(allium): Surface Container analysis

Maps Allium surface declarations to Boundary-only Containers with
Allium::Surface payload. exposes/provides/contracts clauses emit kernel
edges (exposes, provides, realises, uses) with Allium::* edge-tags.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 8: Contract Containers + Operations

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1's contract row:

```
contract Name {
    op_name(param: Type, ...) -> ReturnType
    op_name2(...)
}
```

Maps to:
- Container with `Allium::Contract` tag, Boundary populated with Operations (one Operation per signature)
- Each Operation has `:parameters` (Parameter records) and `:return-type`
- `Allium::Call` tag application on each Operation

Currently the Plan-2a parser captures contract bodies as a flat `field-list` with structured operation signatures inside the `provides-block`-shaped capture (verify by inspecting an AST of a contract from `model/pipeline.allium`). The analyzer needs to walk these and produce Operations.

- [ ] **Step 8.1: Tests**

Add tests for:
- `contract X { foo(a: String) -> Result }` produces a Container with `Allium::Contract` tag whose Boundary has one Operation `foo` with one Parameter `a` (typed String) and return type `Result`
- `Allium::Call` tag applied to each Operation
- Multiple operations on one Contract land correctly

- [ ] **Step 8.2: Run, see fail**

- [ ] **Step 8.3: Implement**

Add `:contract` dispatch. Walk the contract's operations from the AST. Build Operation primitives (`fukan.model.primitives/make-operation`) and add them to a Boundary attached to the Contract Container.

- [ ] **Step 8.4: Run, expect pass**

- [ ] **Step 8.5: Commit**

```bash
jj desc -m "feat(allium): Contract Container + Operations

Maps Allium contracts to Boundary-only Containers (Allium::Contract) whose
Boundary holds Operation primitives — one per signature. Each Operation
gets an Allium::Call tag application.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 9: Rule primitives — intent.assertions + body.definitions

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1's rule row + clause sub-rows:

| Allium | Kernel |
|---|---|
| `rule Name { ... }` | Rule primitive with `Allium::Rule` tag |
| `requires: <expr>` | Bool Expression in `Rule.intent.assertions`; `Allium::Requires` source-clause tag |
| `let x = <expr>` | Definition in `Rule.body.definitions`; `Allium::Let` source-clause tag |
| `ensures: <expr>` (non-effect-shape) | Bool Expression in `Rule.intent.assertions`; `Allium::Ensures` source-clause tag |
| `ensures: <expr>` (effect-shape per §3.8.4) | Bool Expression + matching Effect via canonicaliser (Task 10) |

This task ships:
- Rule primitive construction with `Allium::Rule` tag
- `requires:` clause parsing → Bool Expression with `Allium::Requires` source-clause tag
- `let` clause parsing → Definition with `Allium::Let` source-clause tag
- `ensures:` Bool Expressions with `Allium::Ensures` source-clause tag

Task 10 adds Effect canonicalisation; Task 11 adds triggers.

The source-clause tag on an Expression is applied via the Plan-1 vocabulary mechanism with target `:target/substrate` pointing at the Expression's position within the Rule's intent.assertions list. For Plan-2b MVP, use a simpler target: a `:target/primitive` with id pointing at the Rule, OR a custom target shape. The implementer chooses; document inline.

- [ ] **Step 9.1: Tests**

- [ ] **Step 9.2: Run, see fail**

- [ ] **Step 9.3: Implement**

Add `:rule` dispatch. Walk the Plan-2a-parsed `:clauses` vector and for each clause:
- `:when` → defer to Task 11
- `:requires` → parse `:body` text via `ae/parse`; produce Bool Expression; add to `Rule.intent.assertions`; apply `Allium::Requires` source-clause tag
- `:let` → parse the RHS via `ae/parse`; build Definition; add to `Rule.body.definitions`; apply `Allium::Let` tag
- `:ensures` → parse `:body` via `ae/parse`; produce Bool Expression; add to `Rule.intent.assertions`; apply `Allium::Ensures` source-clause tag (the matching Effect comes in Task 10)

- [ ] **Step 9.4: Run, expect pass**

- [ ] **Step 9.5: Commit**

```bash
jj desc -m "feat(allium): Rule primitive — intent.assertions + body.definitions

Rule construction with Allium::Rule tag, plus requires/let/ensures clause
processing into Bool Expressions (intent.assertions) and Definitions
(body.definitions) with Allium::Requires/Let/Ensures source-clause tags.
Effect canonicalisation follows in Task 10.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 10: Rule effects — §3.8.4 canonicalisation

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

For each Bool Expression in `Rule.intent.assertions` tagged `Allium::Ensures`, attempt canonicalisation via `fukan.vocabulary.allium.effect-canonicalise/canonicalise` (Task 3). If it produces an Effect:
- Add the Effect to `Rule.body.effects`
- Emit the corresponding kernel edge (`writes` / `creates` / `destroys` / `emits`) from this Rule to the target Container/Field/Event
- Edge identifying metadata: `condition` (from the Effect's source Expression), `scope` (currently not extracted — leave absent for MVP)

If canonicalisation returns nil, the Expression stays as a Bool assertion only (no effect).

- [ ] **Step 10.1: Tests**

```clojure
(deftest rule-write-effect
  (testing "ensures: post.account.balance = pre.account.balance + amount produces writes edge + Effect"
    (let [model (analyze-test-snippet
                  "entity Account { balance: Integer }\nrule Deposit { when: D(amount: Integer) ensures: post.account.balance = pre.account.balance + amount }")]
      (let [edges (filter #(= :relation/writes (:kind %)) (:edges model))]
        (is (pos? (count edges)))
        (is (= :endpoint/substrate (-> edges first :to :case)))
        (is (= "balance" (-> edges first :to :path 0 :key)))))))

(deftest rule-create-effect
  (testing "ensures: post.order = Order.created(...) produces creates edge"
    (let [model (analyze-test-snippet
                  "entity Order { total: Money }\nrule PlaceOrder { when: P(total: Money) ensures: post.order = Order.created(total: total) }")]
      (let [edges (filter #(= :relation/creates (:kind %)) (:edges model))]
        (is (pos? (count edges)))))))

(deftest rule-destroy-effect
  (testing "ensures: not exists post.order produces destroys edge"
    (let [model (analyze-test-snippet
                  "entity Order { total: Money }\nrule CancelOrder { when: C() ensures: not exists post.order }")]
      (let [edges (filter #(= :relation/destroys (:kind %)) (:edges model))]
        (is (pos? (count edges)))))))

(deftest rule-emit-effect
  (testing "ensures: emitted(OrderShipped, order) produces emits edge"
    (let [model (analyze-test-snippet
                  "entity Order {}\nrule Ship { when: S(order: Order) ensures: emitted(OrderShipped, order) }")]
      (let [edges (filter #(= :relation/emits (:kind %)) (:edges model))]
        (is (pos? (count edges)))))))
```

- [ ] **Step 10.2: Run, see fail**

- [ ] **Step 10.3: Implement**

After Task 9's `:ensures` Bool Expression is added, also call `(ec/canonicalise expr rule-id)` on it. If it returns an Effect, add it to `Rule.body.effects` and emit the corresponding kernel edge.

- [ ] **Step 10.4: Run, expect pass**

- [ ] **Step 10.5: Commit**

```bash
jj desc -m "feat(allium): Rule effects — Effect records + write/create/destroy/emit edges

ensures: Bool Expressions are canonicalised through §3.8.4 patterns into
Effect records (Rule.body.effects) sourcing the corresponding kernel edges.
Non-effect-shaped ensures: stays as a Bool assertion only.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 11: Rule triggers — `when:` → triggers/observes edges

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1's `when:` row, Allium triggers split:

| Trigger shape (Plan-2a AST) | Kernel edge | Allium::Trigger kind |
|---|---|---|
| `R(params: Type, ...)` (event-shaped call) | `triggers: Event → Rule` | `external_stimulus` (when sourced by a `provides:` edge), `chained` (when sourced by another Rule's `emits` edge) |
| `var: T.created` (lifecycle-creation) | `observes: Rule → Container` | `creation` |
| `var: T.f transitions_to value` | `observes: Rule → Field` | `state_transition` |
| `var: T.f becomes value` | `observes: Rule → Field` | `becomes` |
| `var: T.expires_at <= now` (temporal) | `observes: Rule → Field` | `temporal` |
| `var: T.derived-condition` (derived) | `observes: Rule → Field` | `derived` |

For each `when:` clause in a rule:
- Determine trigger shape from the AST (Plan-2a structures: `:kind :call`, `:kind :binding-op`, `:kind :binding-created`, `:kind :binding-derived`)
- Build the kernel edge accordingly
- Apply `Allium::Trigger {kind: <category>}` tag to the edge
- For event-shaped: also ensure the referenced Event exists (synthesizing it if needed — see Task 12 for the synthesis pattern); for now emit a placeholder ref that Task 12 binds.

Distinguishing `external_stimulus` vs `chained` for event-shaped triggers requires cross-rule analysis (does another rule's `emits` produce this Event?). For MVP, default the kind to `external_stimulus`; Task 13 (cross-module resolution) can refine to `chained` when an `emits` edge to the same Event exists.

- [ ] **Step 11.1: Tests**

- [ ] **Step 11.2: Run, see fail**

- [ ] **Step 11.3: Implement**

Walk each Rule's `:when` clauses (each clause has `:trigger` with Plan-2a-structured shape). For each, build the appropriate kernel edge + Allium::Trigger tag application.

- [ ] **Step 11.4: Run, expect pass**

- [ ] **Step 11.5: Commit**

```bash
jj desc -m "feat(allium): Rule triggers — triggers/observes edges with Allium::Trigger kind

when: clauses on Rules produce kernel edges per their shape:
- Event-shaped calls → triggers: Event → Rule (kind external_stimulus/chained)
- Lifecycle/state/temporal/derived bindings → observes: Rule → Container/Field
  with kind creation/state_transition/becomes/temporal/derived

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 12: Invariants + annotations → Bool Expressions + Clauses

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1 invariant + annotation rows:

| Allium | Lands as | Tag |
|---|---|---|
| Top-level `invariant Name { expr }` | Bool Expression in module-Container's `intent.assertions` | `Allium::Invariant` source-clause |
| Entity-level `invariant Name { expr }` | Bool Expression in entity-Container's `intent.assertions` | `Allium::Invariant` |
| `@invariant Name` on contract (prose) | Clause in contract-Container's `intent.clauses` | `Allium::ContractInvariant` |
| `@guarantee Name` on surface (prose) | Clause in surface-Container's `boundary.intent.clauses` | `Allium::SurfaceGuarantee` |
| `@guidance` (anywhere) | Clause in the appropriate host's `intent.clauses` | `Allium::Guidance` |

For invariants with `for`-quantification body (per Plan-2a Task 7), the body shape is `{:kind :for-quantification ...}` — wrap the assertion text via Task 2's expression parser (falls back to opaque-text literal for unparseable cases).

For annotations, the prose body is captured by Plan-2a Task 10. Land it as the Clause's `:body` text.

- [ ] **Step 12.1: Tests**

Add tests for:
- Top-level invariant → Bool Expression in module's intent.assertions with Allium::Invariant
- Entity-level invariant → Bool Expression in entity's intent.assertions
- `@guarantee` on a surface → Clause in boundary.intent.clauses with Allium::SurfaceGuarantee
- `@invariant` on a contract → Clause in contract's intent.clauses with Allium::ContractInvariant
- `@guidance` annotation → Clause with Allium::Guidance

- [ ] **Step 12.2: Run, see fail**

- [ ] **Step 12.3: Implement**

For invariant declarations: when walking `:declarations`, dispatch `:invariant` to add a Bool Expression to the module-Container's intent.assertions. When walking entity/value/variant fields, dispatch `:field-kind :invariant` to add to the entity-Container's intent.assertions.

For annotations: when walking field-items of a Container's body, dispatch `:field-kind :annotation` to add a Clause to the appropriate Intent's clauses list, choosing the host slot per the annotation's kind:
- `@guarantee` on a Surface → `Container.boundary.intent.clauses`
- `@invariant` on a Contract → `Container.intent.clauses`
- `@guidance` (anywhere) → host's `intent.clauses`

- [ ] **Step 12.4: Run, expect pass**

- [ ] **Step 12.5: Commit**

```bash
jj desc -m "feat(allium): invariants + annotations → Bool Expressions + Clauses

Top-level + entity-level invariants land as Bool Expressions in
intent.assertions (Allium::Invariant tag). @invariant/@guarantee/@guidance
annotations land as Clauses in intent.clauses with corresponding source
tags (Allium::ContractInvariant / SurfaceGuarantee / Guidance).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 13: Event synthesis from declaration sites

**Files:**
- Modify: `src/fukan/vocabulary/allium/analyzer.clj`
- Test: extend `analyzer_test.clj`

Per §8.1's Event row: Events are synthetic — declared at `when:`, `provides:`, and `emits:` sites. Identity is `(module-Container, local-name)` per K16. All declaration sites within a module must agree on parameter shape; cross-site disagreement is a structural error.

For each Rule's event-shaped `when:` clause: synthesize an Event in this module if not already present. Same for `provides:` clauses on Surfaces and `emits:` ensures clauses on Rules (the canonicaliser surfaces emit-target names in Task 10).

The Event's parameters come from the declaration site. Plan-2a's parser captures trigger params and provides params as structured lists.

- [ ] **Step 13.1: Tests**

- [ ] **Step 13.2: Run, see fail**

- [ ] **Step 13.3: Implement**

After Tasks 7, 10, and 11 have produced their content, do a second pass over the model:
- Collect Event-name references from: `provides:` block entries, Rule `when:` event-call clauses, and emit-target endpoints
- For each unique `(module, event-name)`, synthesize an Event primitive with `Allium::Event` tag
- Add it to module-Container's `:events` set
- Verify parameter-shape agreement across all sites for the same `(module, event-name)`; throw on disagreement
- Retarget all edges referencing the placeholder event-name to the synthesized Event's id

For MVP, this happens at end of `analyze-file` per file. Cross-file Event references (via `alias/EventName`) are handled in Task 14.

- [ ] **Step 13.4: Run, expect pass**

- [ ] **Step 13.5: Commit**

```bash
jj desc -m "feat(allium): Event synthesis from when/provides/emits sites

Events are synthesized per module from their declaration sites. Identity
(module, local-name) per K16. Parameter-shape agreement across sites
validated; disagreement is a structural error.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 14: Cross-module reference resolution

**Files:**
- Modify: `src/fukan/vocabulary/allium/pipeline.clj` (final form)
- Test: `test/fukan/vocabulary/allium/pipeline_test.clj` (extend)

The pipeline orchestrates:
1. Walk source root → find all `.allium` files
2. Parse each → AST
3. For each file, compute its coordinate (relative path minus `.allium` extension)
4. Per file, run `analyze-file` → enrich the in-progress Model
5. Build a coordinate→use-aliases-in-this-file map (from each file's `use ... as` declarations)
6. Walk all primitives + edges; retarget cross-module references through the use-alias map
7. Stub resolution per §3.6: unify `Allium::ExternalEntity`-tagged stubs with real Containers across modules

The cross-module reference shape in Plan-2a AST: `:type-ref {:kind :qualified, :ns "alias", :name "Foo"}` and qualified names in expressions / triggers. Each file's `use ... as alias` produces a `:type :use` declaration with `:path` (coordinate) and `:alias`.

For ref resolution:
- In file F, `alias/Foo` resolves to `<canonicalize(F's use-alias-coordinate-for-alias)>::Foo`
- For unresolved aliases or unknown coordinates: warn + leave the ref as-is (it stays a stub)

- [ ] **Step 14.1: Test**

Use a fixture directory with 2 .allium files: `a.allium` declares `entity Foo`, `b.allium` uses `a` and references `a/Foo`. Verify the b's reference resolves to a's Foo Container.

- [ ] **Step 14.2: Run, see fail**

- [ ] **Step 14.3: Implement `pipeline/load-source`**

```clojure
(ns fukan.vocabulary.allium.pipeline
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fukan.libs.allium.parser :as parser]
            [fukan.vocabulary.allium.analyzer :as analyzer]
            [fukan.vocabulary.allium.tags :as tags]
            [fukan.model.build :as build]))

(defn- find-allium-files
  "Recursively walk `root` and return absolute paths to all .allium files."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".allium"))
       (map #(.getPath %))))

(defn- coordinate-of
  "Coordinate = relative path from source root, minus the .allium extension."
  [root abs-path]
  (-> abs-path
      (str/replace-first (str (.getCanonicalPath (io/file root)) "/") "")
      (str/replace-first #"\.allium$" "")))

(defn- register-allium-tags [model]
  (reduce build/add-tag-definition model tags/allium-tag-definitions))

(defn- resolve-cross-module-refs
  "Second pass: walk every edge + tag-application + primitive that carries
   a qualified-reference shape; resolve through each file's use-alias map."
  [model coord->use-aliases]
  ;; implementer fills in
  model)

(defn load-source
  "Load all .allium files under `root` and return a validated Model."
  [root]
  (let [allium-files (find-allium-files root)
        initial (register-allium-tags (build/empty-model))]
    (loop [model initial
           coord->use-aliases {}
           files allium-files]
      (if (empty? files)
        (resolve-cross-module-refs model coord->use-aliases)
        (let [f (first files)
              coord (coordinate-of root f)
              ast (parser/parse-file f)
              use-aliases (->> (:declarations ast)
                               (filter #(= :use (:type %)))
                               (map (juxt :alias :path))
                               (into {}))]
          (recur (analyzer/analyze-file model ast coord)
                 (assoc coord->use-aliases coord use-aliases)
                 (rest files)))))))
```

`resolve-cross-module-refs` is the tricky function; the implementer iterates against the test until it correctly retargets references through the alias map.

- [ ] **Step 14.4: Run, expect pass**

- [ ] **Step 14.5: Commit**

```bash
jj desc -m "feat(allium): pipeline — source walk + cross-module reference resolution

pipeline/load-source [root] discovers all .allium files, parses each,
runs the per-file analyzer, then resolves qualified cross-module
references through each file's use-alias map. External-entity stubs
unified with real Containers when found in another module per §3.6.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 15: Wire into infra/model.clj + fukan-on-fukan smoke

**Files:**
- Modify: `src/fukan/infra/model.clj` — replace Plan-1 fixture loader with the Allium pipeline
- Test: the Task 0 smoke test `pipeline-loads-fukan-corpus` now passes

Replace `fukan.infra.model/load-model`'s body:

```clojure
(defn load-model
  [src]
  (println "Loading model from" src "(Allium pipeline — Plan 2b)")
  (let [m (pipeline/load-source src)]
    (reset! state {:model m :src src})
    (println "Loaded:" (count (:primitives m)) "primitives,"
                       (count (:edges m)) "edges,"
                       (count (:tag-apps m)) "tag applications")
    m))
```

The implementer adds `[fukan.vocabulary.allium.pipeline :as pipeline]` to the require form and drops the Plan-1 `fixture-model` helper.

- [ ] **Step 15.1: Update infra/model.clj**

- [ ] **Step 15.2: Run the corpus smoke**

```bash
clj -M:test -n fukan.vocabulary.allium.pipeline-test
```

Expected: `pipeline-loads-fukan-corpus` PASSES. 5+ module-Containers, 5+ `Allium::Module` tag applications, Model validates.

- [ ] **Step 15.3: Run the full test suite**

```bash
clj -M:test
```

Expected: every Plan-1 test, every Plan-2a test, every Plan-2b unit test, plus the corpus smoke — all pass. Total count: Plan-1 (113) + Plan-2a (~47 parser) + Plan-2b (~50 analyzer + smoke) ≈ 210 tests.

- [ ] **Step 15.4: Run the REPL smoke**

```bash
clojure -M:dev -e "(require '[fukan.infra.model :as m]) (m/load-model \"src\") (println :primitives (count (:primitives (m/get-model)))) (println :edges (count (:edges (m/get-model))))"
```

Expected output: `:primitives <N>` (much greater than the 2 from the Plan-1 fixture) and `:edges <M>` (positive number — kernel edges from Surfaces, Rules, etc.).

- [ ] **Step 15.5: Commit**

```bash
jj desc -m "feat(infra): wire Allium pipeline into infra/model

infra/model/load-model now runs the Allium pipeline on src and returns
a real Model populated from .allium files. Plan-1 fixture-model helper
dropped. The fukan-on-fukan corpus smoke test passes end-to-end.

Closes Plan 2b (Allium analyzer).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing all 16 tasks (0–15), verify before declaring Plan 2b done:

1. **§8.1 coverage.** Walk every row of [MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping) and confirm a task implements it:
   - module → Task 4
   - entity/value/variant → Task 5
   - external entity → Task 6
   - actor → Task 6
   - entity field declaration (Field value records) → Task 5
   - rule → Tasks 9–11
   - when: → Task 11
   - requires: → Task 9
   - where: (now `let`) → Task 9
   - ensures: → Tasks 9 + 10
   - invariant (top-level + entity-level) → Task 12
   - surface → Task 7
   - contract → Task 8
   - provides: / exposes: / contracts: → Task 7
   - Contract operations → Task 8
   - @invariant / @guarantee / @guidance → Task 12
   - Event synthesis → Task 13

2. **Allium tag coverage.** All 22 `Allium::*` tags from Task 1's registry appear in the analyzer's tag-application sites (at least one analyzer-emit point per tag). Run a grep for each tag-name in `analyzer.clj` to confirm.

3. **Effect canonicalisation.** All four §3.8.4 patterns recognized: Write, Create, Destroy, Emit. Each has at least one passing test in `effect_canonicalise_test.clj`.

4. **Expression parser fallback.** Unparseable text becomes `:expr/lit` with `Scalar("AlliumText")` — Plan 4 will reparse these.

5. **Cross-module references resolved.** Every `alias/Name` reference in any `.allium` file in `src/` resolves to a real Container's id in the final Model. External-entity stubs unified per §3.6.

6. **Plan-1 substrate unchanged except `effect/canonicalise`.** Grep `src/fukan/model/` for changes; only `effect.clj` should be touched (the `canonicalise` body delegating to the analyzer).

7. **Plan-1 fixture loader is gone.** `fukan.infra.model/fixture-model` should not exist anymore; `load-model` calls the pipeline.

8. **Full test suite green.** `clj -M:test` runs to completion with 0 failures.

9. **REPL boots and renders.** `clojure -M:dev -e "(require '[user :as u]) (u/go {}) (u/status) (u/halt)"` succeeds; `(status)` shows a substantially populated Model.

10. **No filepath structure leakage.** Grep `src/fukan/model/` and the analyzer namespaces for any filesystem-derived structural decisions. The only filepath use should be in `pipeline.clj`'s `find-allium-files` + `coordinate-of` (coordinate = relative path, but treated as opaque downstream).

11. **VCS state.** `jj log -r '::@' --limit 18` shows the 16 Plan-2b commits stacked cleanly on top of Plan-2a's closing commit. Each task = one commit.

If any check fails, fix in place — do **not** start Plan 3 until Plan 2b's analyzer is clean.
