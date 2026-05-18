# Kernel Substrate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the old code-graph Model with the next-chapter **kernel substrate** — 9 primitives, value records, the Type vocabulary, the Expression / Effect sub-substrate, 13 kernel relations with multi-edge identity, the vocabulary mechanism (TagDefinition / TagApplication / PredicateRegistration / renderer seam), and the v0 projection vocabulary (Code Artifact ontology + projection_kind enum). No analyzer logic, no parsers, no constraint engine — just the substrate data shapes with structural invariants and a fixture-only Model construction API.

**Architecture:** Gut-and-rebuild of `src/fukan/model/*` and `src/fukan/projection/*`. The functional core is rewritten from the kernel substrate up; the imperative shell (`infra/`, `web/handler.clj`, `web/sse.clj`, `web/views/shell.clj`, `dev/user.clj`, `libs/allium/parser.clj`) is preserved with minimal stubbing — the web UI renders a placeholder page until Plan 6 rebuilds the explorer. Substrate is defined as malli schemas + plain-map constructors; identity tuples are pure functions. Tests encode substrate invariants from [`doc/MODEL.md`](../MODEL.md) §§3–7 — not implementation behaviour.

**Tech Stack:** Clojure 1.11, [metosin/malli](https://github.com/metosin/malli) (schemas), cognitect test-runner + clojure.test (already in `:test` alias of `deps.edn`), no new deps.

---

## Plan-of-plans context

This is **Plan 1 of 6** for the next-chapter overhaul. The full sequence:

1. **Kernel substrate** *(this plan)* — primitives, value records, Type, relations, Expression / Effect, vocabulary mechanism shells, v0 Artifact ontology, fixture-only Model construction.
2. **Allium analyzer** — Allium AST → kernel content + `Allium::*` tags ([MODEL.md §8.1](../MODEL.md#81-allium--kernel-mapping)). Reuses `libs/allium/parser.clj`.
3. **Boundary analyzer + build pipeline + validation rules** — settles `.boundary` token syntax; new parser; Phases 1–4 with gates G1/G2; sub-phases 4a–4g ([DESIGN.md "Build pipeline"](../DESIGN.md)).
4. **Constraint language + Phase 5** — stratified Datalog substrate, kernel-universal derivations, methodology-shipped predicates.
5. **Clojure Target extension + project layer** — Analyzer (`projects` edges with validity) + Projector (Implementation Blueprints).
6. **Explorer rewrite + generation flow** — new views, sidebars, edge filtering, drift markers, click-to-generate UX.

Authoritative substrate spec: [doc/MODEL.md](../MODEL.md). Application design: [doc/DESIGN.md](../DESIGN.md). Decision trace: [doc/DECISIONS.md](../DECISIONS.md). Framing: [doc/VISION.md](../VISION.md).

---

## Repository conventions (jj over git)

This is a **colocated jj/git repository** (`.jj/` and `.git/` both present). The plan's commit steps use `git add` + `git commit -m` for portability of the prose; in this repo, translate them as follows:

| In the plan | Run instead |
|---|---|
| `git add <paths>` | *(omit — jj snapshots the working copy automatically before every command)* |
| `git commit -m "<message>"` (or the heredoc form) | `jj desc -m "<message>"` *followed by* `jj new` to start the next change |
| Implicit `git add -A` in commit steps | Same — `jj desc -m "<message>" && jj new` |

After each commit, verify with `jj st` (clean working copy in the freshly-`jj new`'d change) or `jj log -r '::@' --limit 5` (your describe message appears as `@-`'s description). Per the user's global CLAUDE.md: one logical change per commit; finish a change, describe it, `jj new`, move to the next.

Each task in this plan ends with one commit step — that means one `jj desc + jj new` cycle. Do not skip the `jj new` between tasks; without it, subsequent file edits land in the previous change's working copy.

---

## File Structure

### Files to delete (Plan 1 demolition)

```
src/fukan/model/schema.clj                                  # Old Model: 3 node kinds, 3 edge kinds — replaced
src/fukan/model/build.clj                                   # Old build pipeline — rewritten in Plan 3
src/fukan/model/lint.clj                                    # Old lint — replaced by Phase 4 in Plan 3
src/fukan/model/analyzers.clj                               # Old multimethod hub — replaced in Plans 2/3/5
src/fukan/model/analyzers/                                  # Entire old analyzer tree — replaced in Plans 2/3/5
src/fukan/projection/                                       # Old projection layer — rewritten in Plan 6
src/fukan/libs/boundary/parser.clj                          # Old `.boundary` parser — rewritten in Plan 3
test/fukan/model/analyzers/                                 # Tests for deleted analyzers
test/fukan/model/build_test.clj                             # Tests for deleted build
test/fukan/model/lint_test.clj                              # Tests for deleted lint
test/fukan/projection/                                      # Tests for deleted projection
test/fukan/test_support/                                    # Old fixtures/generators/invariants — written for old Model
test/fukan/libs/boundary/                                   # If present — old boundary parser tests
test/fukan/web/views/                                       # Old view tests (views stubbed in Plan 1, rewritten in Plan 6)
```

Keep co-located `.allium` / `.boundary` spec files under `src/` untouched — they describe the *current* system and get re-aligned in Plans 2/3 when the new analyzers exist.

### Files to create

```
src/fukan/model/type.clj             # §3.3 Type vocabulary (6 cases)
src/fukan/model/primitives.clj       # §3.1–3.2 nine primitives + value records
src/fukan/model/expression.clj       # §3.8.1, §3.8.5–§3.8.7 Expression + Environment + identity
src/fukan/model/effect.clj           # §3.8.2 Effect + identity
src/fukan/model/relations.clj        # §4 13 relations, endpoint addressing, edge identity
src/fukan/model/vocabulary.clj       # §5.2–§5.5 TagDefinition, TagApplication, PredicateRegistration, RendererRegistration
src/fukan/model/artifact.clj         # §7.2–§7.4 v0 Artifact ontology + projection_kind
src/fukan/model/build.clj            # Model record + fixture-only construction API

test/fukan/model/type_test.clj
test/fukan/model/primitives_test.clj
test/fukan/model/expression_test.clj
test/fukan/model/effect_test.clj
test/fukan/model/relations_test.clj
test/fukan/model/vocabulary_test.clj
test/fukan/model/artifact_test.clj
test/fukan/model/build_test.clj
```

### Files to modify (imperative shell)

```
src/fukan/infra/model.clj            # Drop analyzer requires; use fixture-only Model loader
src/fukan/core.clj                   # Drop analyzer args; load fixture Model
src/fukan/web/handler.clj            # Routes for placeholder page (rewritten in Plan 6)
src/fukan/web/views/shell.clj        # Placeholder page; full re-skin in Plan 6
dev/user.clj                         # Drop :analyzers arg
```

### Files to leave untouched

```
src/fukan/infra/server.clj           # HTTP server lifecycle
src/fukan/web/sse.clj                # SSE plumbing
src/fukan/web/views/breadcrumb.clj   # Or stub if it imports old projection; verify in Task 0
src/fukan/web/views/cytoscape.clj    # Likewise — verify in Task 0; rewrite in Plan 6 if needed
src/fukan/web/views/graph.clj        # Likewise
src/fukan/web/views/sidebar.clj      # Likewise
src/fukan/web/views/schema.clj       # Likewise
src/fukan/libs/allium/parser.clj     # Allium grammar parser — reused by Plan 2 analyzer
src/fukan/utils/files.clj            # File utilities
test/fukan/libs/allium/parser_test.clj  # Allium parser tests
```

> **Task 0 reality check:** every `web/views/*.clj` that requires `fukan.model.schema` or `fukan.projection.*` either gets a stub or gets deleted-and-replaced-in-Plan-6. Task 0 grep determines which.

---

## Conventions used throughout this plan

- **Namespaced keywords for case discriminators:** `:type/scalar`, `:expr/apply`, `:effect/write`, `:artifact/code-function`, etc. Keeps the substrate self-documenting at the value level and avoids collisions across sum types.
- **Constructors as `make-X`** returning plain maps. No `defrecord`, no `defprotocol` (substrate is data, not behaviour). Schemas are malli values per [DECISIONS.md P8](../DECISIONS.md#projection-vocabulary).
- **Malli schemas registered as vars,** referenced by `[:ref ::Name]` for self / mutual recursion. Each module exposes a `schemas` map for the build module to compose into one global registry.
- **Identity helpers are pure functions** named `X-identity` returning a vector tuple — directly usable as a map key.
- **No I/O in `model/*`.** Side-effecting code stays in `infra/` and the analyzers (later plans).
- **Bare-bones constraint-language deferred to Plan 4.** This plan defines the `Predicate` slot on `PredicateRegistration` as an opaque value (passes through unchecked); Plan 4 replaces the slot's value type with the Datalog AST.
- **Renderer seam committed; rendering deferred to Plan 6.** `RendererRegistration` is a data shape only — no rendering machinery here ([MODEL.md §5.5](../MODEL.md#55-renderers)).

---

## Task 0: Demolition & imperative-shell stub

**Files:**
- Delete: see "Files to delete" list above.
- Modify: `src/fukan/infra/model.clj`, `src/fukan/core.clj`, `src/fukan/web/handler.clj`, `src/fukan/web/views/shell.clj`, `dev/user.clj`.
- Test: none — Task 0 is structural; subsequent tasks add tests.

The goal: after Task 0, `clj -M:test` succeeds with zero tests (Plan 1 hasn't added any yet), `clj -M:run` boots, the browser shows a placeholder page, and the REPL helpers (`(go)`, `(halt)`, `(refresh)`, `(reset)`, `(status)`) work.

- [ ] **Step 0.1: Delete the old functional core and dead tests**

Run from repo root:

```bash
rm -rf \
  src/fukan/model/schema.clj \
  src/fukan/model/build.clj \
  src/fukan/model/lint.clj \
  src/fukan/model/analyzers.clj \
  src/fukan/model/analyzers/ \
  src/fukan/projection/ \
  src/fukan/libs/boundary/ \
  test/fukan/model/analyzers/ \
  test/fukan/model/build_test.clj \
  test/fukan/model/lint_test.clj \
  test/fukan/projection/ \
  test/fukan/test_support/ \
  test/fukan/libs/boundary/ \
  test/fukan/web/views/
```

Verify:

```bash
git status --short
# Expect a long list of deletions; no surprises in src/fukan/infra/, src/fukan/web/, src/fukan/libs/allium/, dev/.
```

- [ ] **Step 0.2: Grep web views for references to deleted code**

Run:

```bash
grep -nE 'fukan\.(model\.schema|model\.build|model\.lint|model\.analyzers|projection)' \
  src/fukan/web/views/*.clj src/fukan/web/handler.clj src/fukan/core.clj src/fukan/infra/*.clj || \
  echo 'No references to deleted code'
```

Every match indicates a file that needs stubbing or deletion. If a view file ONLY consumes the old model, replace its body with a placeholder hiccup snippet (Step 0.5) — do **not** delete it, since Plan 6 will repopulate it. Note the files for Step 0.5.

- [ ] **Step 0.3: Rewrite `src/fukan/infra/model.clj` as fixture-only loader**

Replace the entire file with:

```clojure
(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value and offers load / refresh / get. In Plan 1 the loader is
   fixture-only — Plans 2/3/5 swap in real analyzers without changing this API.")

(defonce ^:private state (atom {:model nil :src nil}))

(defn- empty-model
  "Placeholder Model. Replaced in Task 8 by build/empty-model."
  []
  {:primitives {} :edges [] :tags [] :predicates [] :renderers [] :artifacts {}})

(defn load-model
  "Build (or reload) the Model for the given src path. In Plan 1 this returns
   an empty Model; later plans wire real analyzers through here."
  [src]
  (println "Loading model from" src "(Plan 1 fixture-only; no analyzers yet)")
  (let [m (empty-model)]
    (reset! state {:model m :src src})
    m))

(defn get-model
  "Current Model, or nil if not loaded."
  []
  (:model @state))

(defn get-src
  "Current src path, or nil."
  []
  (:src @state))

(defn refresh-model
  "Rebuild from the last src path."
  []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))
```

> Task 8 replaces `empty-model` with `fukan.model.build/empty-model` and `load-model` learns to accept a Model value directly for fixture tests. Step 0.3 keeps the API stable so the REPL keeps working through every intervening task.

- [ ] **Step 0.4: Simplify `src/fukan/core.clj`**

Replace the entire file with:

```clojure
(ns fukan.core
  "Application entry point. In Plan 1 the model loader is a stub; analyzers are
   reintroduced in Plans 2/3/5.

   Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"
  (:require [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

(defn- parse-args [args]
  (loop [args args, result {:port 8080}]
    (if (empty? args)
      result
      (let [[flag value & remaining] args]
        (case flag
          "--src"  (recur remaining (assoc result :src value))
          "--port" (recur remaining (assoc result :port (Integer/parseInt value)))
          (recur (rest args) (assoc result :src flag)))))))

(defn -main [& args]
  (let [{:keys [src port]} (parse-args args)]
    (when-not src
      (binding [*out* *err*]
        (println "Error: --src argument is required")
        (println "Usage: clj -M -m fukan.core --src /path/to/src [--port 8080]"))
      (System/exit 1))
    (infra-model/load-model src)
    (infra-server/start-server {:port port})
    (println "Press Ctrl+C to stop")))
```

- [ ] **Step 0.5: Stub `src/fukan/web/views/shell.clj` and any view file from Step 0.2**

For `shell.clj` and every other view file flagged in Step 0.2, replace the body with this placeholder:

```clojure
(ns fukan.web.views.shell
  "Plan 1 placeholder page. The full explorer is rewritten in Plan 6."
  (:require [hiccup2.core :as h]))

(defn render-shell
  "Render the placeholder page."
  [_request]
  (str
    (h/html
      [:html
       [:head [:title "fukan — kernel substrate v0"]]
       [:body
        [:h1 "fukan"]
        [:p "Kernel substrate v0 is live. Analyzers and the explorer are pending."]
        [:p "See "
         [:a {:href "https://github.com/yabmas/fukan/blob/main/doc/plans/2026-05-18-kernel-substrate.md"}
          "Plan 1"]
         " for the current scope."]]])))
```

If `web/handler.clj` references functions that no longer exist (e.g. graph / sidebar endpoints), trim those routes — leave only `GET /` returning `render-shell`. Other routes return 404 by default.

- [ ] **Step 0.6: Update `dev/user.clj` to drop the `:analyzers` argument**

Replace the entire file with:

```clojure
(ns user
  "Development helpers for REPL-driven workflow."
  (:require [clj-reload.core :as reload]
            [fukan.infra.model :as infra-model]
            [fukan.infra.server :as infra-server]))

(defonce ^:private _reload-init
  (reload/init {:dirs ["src" "dev"], :no-reload '#{user}}))

(defn- reload-code! []
  (let [result (reload/reload {:only :loaded})]
    (when (seq (:loaded result))
      (println "Reloaded:" (count (:loaded result)) "namespaces")
      (doseq [ns-sym (:loaded result)] (println " " ns-sym)))
    (when (seq (:unloaded result)) (println "Unloaded:" (:unloaded result)))
    result))

(defn go
  "Start the system. Options: :src (default \"src\"), :port (default 8080)."
  [{:keys [src port] :or {src "src" port 8080}}]
  (if (infra-server/running?)
    (println "Server already running on port" (infra-server/get-port))
    (do
      (infra-model/load-model src)
      (infra-server/start-server {:port port}))))

(defn halt [] (infra-server/stop-server))

(defn reset []
  (if-let [src (infra-model/get-src)]
    (let [port (or (infra-server/get-port) 8080)]
      (halt)
      (reload-code!)
      (go {:src src :port port}))
    (println "No previous configuration. Use (go) first.")))

(defn refresh []
  (if (infra-server/running?)
    (do (reload-code!) (infra-model/refresh-model)
        (println "Refreshed. Browser will see changes on next request."))
    (println "Server not running. Use (go) first.")))

(defn status []
  (println "Server:" (if (infra-server/running?)
                       (str "running on port " (infra-server/get-port))
                       "stopped"))
  (println "Model:" (if-let [m (infra-model/get-model)]
                      (str (count (:primitives m)) " primitives, "
                           (count (:edges m)) " edges"
                           " (src: " (infra-model/get-src) ")")
                      "not loaded")))

(comment
  (go {})
  (halt)
  (reset)
  (refresh)
  (status))
```

- [ ] **Step 0.7: Run the test suite, verify zero tests / zero failures**

Run:

```bash
clj -M:test
```

Expected: `Ran 0 tests containing 0 assertions. 0 failures, 0 errors.` (or equivalent — zero failures is what matters).

- [ ] **Step 0.8: Boot the system and verify the placeholder page renders**

Run:

```bash
clj -M:run --src src --port 8080 &
sleep 2
curl -s http://localhost:8080/ | head -20
kill %1 2>/dev/null
```

Expected: HTML containing `<h1>fukan</h1>` and the "kernel substrate v0 is live" text.

- [ ] **Step 0.9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(model): demolish old code-graph; stub imperative shell

Plan 1 / 6 (kernel substrate). Removes Function/Schema/function_call
machinery, the old analyzers tree, and the projection layer. Stubs the
web shell to a placeholder page so REPL and HTTP boot work through the
intervening tasks; the explorer is rewritten in Plan 6.

See doc/plans/2026-05-18-kernel-substrate.md.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 1: Type vocabulary

**Files:**
- Create: `src/fukan/model/type.clj`
- Test: `test/fukan/model/type_test.clj`
- Reference: [MODEL.md §3.3](../MODEL.md#33-type-vocabulary)

Six cases: `Scalar | Enum | Composite | Collection | Union | Ref`. `Composite` carries a `CompositeShape` (`Named` reuses a value-typed Container's fields; `Inline` declares anonymous slots). `Collection` carries `CollectionSemantics` (`Sequential | Unique | Keyed(K)`). `Ref` carries a `RefTarget` (`KernelPrimitive | Substrate`) and optional `TagConstraint` (set of TagRefs).

- [ ] **Step 1.1: Write the failing test file**

Create `test/fukan/model/type_test.clj`:

```clojure
(ns fukan.model.type-test
  "Tests for the Type vocabulary (MODEL.md §3.3). Substrate stays generic at the
   target-language level: Types commit to structural shape, not target-language
   rendering. Identity is structural equality (case + key fields)."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest scalar
  (testing "Scalar carries an opaque methodology-supplied name"
    (let [v (t/make-scalar "Money")]
      (is (= :type/scalar (:case v)))
      (is (= "Money" (:name v)))
      (is (m/validate t/Type v)))))

(deftest enum
  (testing "Enum is a closed set of literal values (distinct from Union)"
    (let [v (t/make-enum ["pending" "shipped" "cancelled"])]
      (is (= :type/enum (:case v)))
      (is (= ["pending" "shipped" "cancelled"] (:values v)))
      (is (m/validate t/Type v)))))

(deftest composite-named
  (testing "Composite Named reuses a value-typed Container's fields"
    (let [v (t/make-composite-named "address-1")]
      (is (= :type/composite (:case v)))
      (is (= :shape/named (get-in v [:shape :case])))
      (is (= "address-1" (get-in v [:shape :container]))))))

(deftest composite-inline
  (testing "Composite Inline declares anonymous fields; optionality on FieldSpec"
    (let [v (t/make-composite-inline
              [(t/make-field-spec "amount" (t/make-scalar "Integer") false)
               (t/make-field-spec "memo"   (t/make-scalar "String")  true)])]
      (is (= :type/composite (:case v)))
      (is (= :shape/inline (get-in v [:shape :case])))
      (is (= 2 (count (get-in v [:shape :fields]))))
      (is (true? (get-in v [:shape :fields 1 :optional]))))))

(deftest collection-sequential
  (let [v (t/make-collection (t/make-scalar "String") :sequential)]
    (is (= :type/collection (:case v)))
    (is (= :sequential (:semantics v)))))

(deftest collection-unique
  (let [v (t/make-collection (t/make-scalar "String") :unique)]
    (is (= :unique (:semantics v)))))

(deftest collection-keyed
  (testing "Keyed semantics carries a key Type"
    (let [v (t/make-collection (t/make-composite-named "ShippingMethod")
                                (t/keyed (t/make-scalar "String")))]
      (is (= :type/collection (:case v)))
      (is (map? (:semantics v)))
      (is (= :keyed (get-in v [:semantics :case])))
      (is (= "String" (get-in v [:semantics :key-type :name]))))))

(deftest union
  (testing "Union is a sum of distinct Types"
    (let [v (t/make-union [(t/make-scalar "String")
                            (t/make-scalar "Integer")])]
      (is (= :type/union (:case v)))
      (is (= 2 (count (:types v)))))))

(deftest ref-kernel-primitive
  (testing "Ref reaches kernel primitive kinds with optional tag constraint"
    (let [v (t/make-ref-kernel-primitive #{:container} {:where #{"Allium::Entity"}})]
      (is (= :type/ref (:case v)))
      (is (= :ref-target/kernel-primitive (get-in v [:target :case])))
      (is (= #{:container} (get-in v [:target :kinds])))
      (is (= #{"Allium::Entity"} (:where v))))))

(deftest ref-substrate
  (testing "Ref Substrate addresses a specific Field/Parameter on a Container"
    (let [v (t/make-ref-substrate :container #{:field})]
      (is (= :type/ref (:case v)))
      (is (= :ref-target/substrate (get-in v [:target :case])))
      (is (= :container (get-in v [:target :within-kind])))
      (is (= #{:field} (get-in v [:target :slot-kinds]))))))

(deftest identity-is-structural
  (testing "Two Types with the same case + key fields are equal"
    (is (= (t/make-scalar "Money") (t/make-scalar "Money")))
    (is (not= (t/make-scalar "Money") (t/make-scalar "Currency")))
    (is (= (t/make-collection (t/make-scalar "String") :sequential)
           (t/make-collection (t/make-scalar "String") :sequential)))
    (is (not= (t/make-collection (t/make-scalar "String") :sequential)
              (t/make-collection (t/make-scalar "String") :unique)))))

(deftest schema-rejects-malformed-values
  (testing "malli schema rejects non-Type maps"
    (is (false? (m/validate t/Type {})))
    (is (false? (m/validate t/Type {:case :type/bogus})))))
```

- [ ] **Step 1.2: Run the test to verify it fails (namespace not found)**

Run:

```bash
clj -M:test -n fukan.model.type-test
```

Expected: failure with `Could not locate fukan/model/type__init.class or fukan/model/type.clj on classpath`.

- [ ] **Step 1.3: Implement `src/fukan/model/type.clj`**

Create `src/fukan/model/type.clj`:

```clojure
(ns fukan.model.type
  "Type vocabulary (MODEL.md §3.3). Six cases:

     Scalar    — atomic leaf with methodology-supplied name (opaque to substrate)
     Enum      — closed set of literal values
     Composite — Named(container) | Inline(fields)
     Collection — of: Type, semantics: Sequential | Unique | Keyed(K)
     Union     — sum of distinct Types
     Ref       — KernelPrimitive(kinds, where) | Substrate(within-kind, slot-kinds)

   Substrate stays generic at the target-language level. Per-target-language
   translation lives in the project layer (Plan 5)."
  (:require [malli.core :as m]))

;; -- FieldSpec ----------------------------------------------------------------

(defn make-field-spec
  "FieldSpec lives on Composite Inline shapes. Optionality is at slot level
   (not a Type case)."
  [name type-value optional?]
  {:name name, :type type-value, :optional (boolean optional?)})

;; -- Constructors -------------------------------------------------------------

(defn make-scalar
  "Atomic leaf. `name` is methodology-supplied and opaque to substrate."
  [name]
  {:case :type/scalar, :name name})

(defn make-enum
  "Closed set of literal string values."
  [values]
  {:case :type/enum, :values (vec values)})

(defn make-composite-named
  "Composite whose shape comes from a value-typed Container's fields."
  [container-id]
  {:case :type/composite, :shape {:case :shape/named, :container container-id}})

(defn make-composite-inline
  "Composite with anonymous inline fields (List<FieldSpec>)."
  [field-specs]
  {:case :type/composite, :shape {:case :shape/inline, :fields (vec field-specs)}})

(defn keyed
  "CollectionSemantics constructor for Keyed(K)."
  [key-type]
  {:case :keyed, :key-type key-type})

(defn make-collection
  "Collection of T with one of Sequential | Unique | Keyed(K) semantics.
   For Sequential / Unique pass the keyword; for Keyed pass (keyed K)."
  [of semantics]
  {:case :type/collection, :of of, :semantics semantics})

(defn make-union
  "Sum of distinct Types. Cases stay structurally distinct (no coercion)."
  [types]
  {:case :type/union, :types (vec types)})

(defn make-ref-kernel-primitive
  "Reference to a kernel primitive. `kinds` is a set of kernel-kind keywords:
   #{:container :event :actor :rule :operation :behaviour :boundary :intent :clause}.
   `opts` may include {:where #{TagRef…}} — admissible targets must carry all tags."
  ([kinds] (make-ref-kernel-primitive kinds {}))
  ([kinds {:keys [where]}]
   (cond-> {:case   :type/ref
            :target {:case :ref-target/kernel-primitive, :kinds (set kinds)}}
     (seq where) (assoc :where (set where)))))

(defn make-ref-substrate
  "Reference to a sub-substrate slot on a primitive of `within-kind`. `slot-kinds`
   is a set of substrate slot keywords (#{:field} in v0; :parameter deferred)."
  [within-kind slot-kinds]
  {:case   :type/ref
   :target {:case        :ref-target/substrate
            :within-kind within-kind
            :slot-kinds  (set slot-kinds)}})

;; -- Malli schemas ------------------------------------------------------------

(def ^:private kernel-kinds
  #{:container :event :actor :rule :operation :behaviour :boundary :intent :clause})

(def ^:private substrate-slot-kinds #{:field :parameter})

(def collection-semantics
  [:or {:description "Sequential, Unique, or Keyed(K)"}
   [:enum :sequential :unique]
   [:map [:case [:= :keyed]] [:key-type [:ref ::Type]]]])

(def ref-target
  [:multi {:dispatch :case}
   [:ref-target/kernel-primitive
    [:map [:case [:= :ref-target/kernel-primitive]]
     [:kinds [:set (into [:enum] kernel-kinds)]]]]
   [:ref-target/substrate
    [:map [:case [:= :ref-target/substrate]]
     [:within-kind (into [:enum] kernel-kinds)]
     [:slot-kinds  [:set (into [:enum] substrate-slot-kinds)]]]]])

(def composite-shape
  [:multi {:dispatch :case}
   [:shape/named  [:map [:case [:= :shape/named]]  [:container :string]]]
   [:shape/inline [:map [:case [:= :shape/inline]] [:fields [:vector [:ref ::FieldSpec]]]]]])

(def Type
  [:schema {:registry
            {::Type
             [:multi {:dispatch :case}
              [:type/scalar      [:map [:case [:= :type/scalar]]      [:name :string]]]
              [:type/enum        [:map [:case [:= :type/enum]]        [:values [:vector :string]]]]
              [:type/composite   [:map [:case [:= :type/composite]]   [:shape composite-shape]]]
              [:type/collection  [:map [:case [:= :type/collection]]  [:of [:ref ::Type]] [:semantics collection-semantics]]]
              [:type/union       [:map [:case [:= :type/union]]       [:types [:vector [:ref ::Type]]]]]
              [:type/ref         [:map [:case [:= :type/ref]]         [:target ref-target] [:where {:optional true} [:set :string]]]]]
             ::FieldSpec
             [:map
              [:name :string]
              [:type [:ref ::Type]]
              [:optional :boolean]]}}
   [:ref ::Type]])

(def FieldSpec [:schema {:registry (-> Type second :registry)} [:ref ::FieldSpec]])
```

- [ ] **Step 1.4: Run the tests; expect all to pass**

```bash
clj -M:test -n fukan.model.type-test
```

Expected: `Ran 12 tests containing N assertions. 0 failures, 0 errors.`

- [ ] **Step 1.5: Commit**

```bash
git add src/fukan/model/type.clj test/fukan/model/type_test.clj
git commit -m "$(cat <<'EOF'
feat(model): Type vocabulary (six cases)

Implements MODEL.md §3.3: Scalar / Enum / Composite / Collection / Union /
Ref. Substrate stays generic at the target-language level; per-language
translation lives in the project layer (Plan 5).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Primitives and value records

**Files:**
- Create: `src/fukan/model/primitives.clj`
- Test: `test/fukan/model/primitives_test.clj`
- Reference: [MODEL.md §3.1](../MODEL.md#31-primitives), [§3.2](../MODEL.md#32-substrate-value-records), [§3.4](../MODEL.md#34-cardinality-patterns), [§3.5](../MODEL.md#35-asymmetries--intentional).

Nine primitives: `Container`, `Actor`, `Behaviour`, `Rule`, `Boundary`, `Operation`, `Intent`, `Clause`, `Event`. Value records: `Field`, `Parameter`, `Definition`, `RuleBody`. Substrate-level invariants (encoded as tests):

- Container's optional faces: `description`, `intent`, `children`, `fields`, `events`, `behaviour`, `boundary` — all populatable independently.
- No `parent` slot on owned primitives; ownership stored on the owner (per K17).
- Field identity is `(container-id, field-name)`; Parameter identity is `(parent-id, parameter-name)`; Definition identity is `(rule-id, definition-name)`; RuleBody has no independent identity (reduces to host Rule's id).
- Clause is a kernel primitive (per K15a) — moving a Clause between Intents preserves its `id`.
- Container's `events` slot owns Events declared on this Container; the qualifying-Container rule (per K16) is encoded at edge-construction time, not at primitive construction.

The substrate does **not** validate that referenced ids exist — that is the analyzer / build-pipeline's job (Plan 3 Phase 4). Primitives are pure data shapes.

- [ ] **Step 2.1: Write the failing test file**

Create `test/fukan/model/primitives_test.clj`:

```clojure
(ns fukan.model.primitives-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.primitives :as p]
            [fukan.model.type :as t]
            [malli.core :as m]))

;; -- Container ---------------------------------------------------------------

(deftest container-minimal
  (testing "Container needs only :id and :label; all faces are optional"
    (let [c (p/make-container {:id "c1" :label "Order"})]
      (is (= :primitive/container (:kind c)))
      (is (m/validate p/Container c)))))

(deftest container-with-all-faces
  (let [c (p/make-container
            {:id "order-aggregate"
             :label "Order"
             :description "Customer order aggregate root"
             :intent {:id "i1" :clauses [] :assertions []}
             :children #{"order-line"}
             :fields [(p/make-field "id" (t/make-scalar "String") false)
                      (p/make-field "total" (t/make-scalar "Money") false)]
             :events #{"order-placed"}
             :behaviour {:id "b1" :label "OrderBehaviour" :rules []}
             :boundary  {:id "bd1" :label "OrderBoundary" :operations []}})]
    (is (m/validate p/Container c))
    (is (= 2 (count (:fields c))))
    (is (= #{"order-line"} (:children c)))))

(deftest container-children-depth-unconstrained
  (testing "Substrate imposes no depth limit on Container.children"
    (let [root (p/make-container
                 {:id "root" :label "Root" :children #{"a"}})
          a    (p/make-container
                 {:id "a" :label "A" :children #{"b"}})
          b    (p/make-container
                 {:id "b" :label "B" :children #{"c"}})
          c    (p/make-container {:id "c" :label "C"})]
      (is (every? #(m/validate p/Container %) [root a b c])))))

;; -- Field -------------------------------------------------------------------

(deftest field-identity
  (testing "Field identity is (container-id, field-name)"
    (let [f (p/make-field "priority" (t/make-scalar "Integer") true)]
      (is (= ["order-1" "priority"] (p/field-identity "order-1" f))))))

;; -- Actor -------------------------------------------------------------------

(deftest actor
  (let [a (p/make-actor {:id "candidate" :label "Candidate"})]
    (is (= :primitive/actor (:kind a)))
    (is (m/validate p/Actor a))))

;; -- Event -------------------------------------------------------------------

(deftest event
  (testing "Event is owned by its qualifying Container; the qualification edge
            lives in (relation), not in Event substrate"
    (let [e (p/make-event
              {:id "interview/slot-confirmed"
               :label "SlotConfirmed"
               :parameters [(p/make-parameter "viewer"
                                              (t/make-ref-kernel-primitive #{:actor})
                                              false 0)
                            (p/make-parameter "slot"
                                              (t/make-ref-kernel-primitive #{:container})
                                              false 1)]})]
      (is (= :primitive/event (:kind e)))
      (is (m/validate p/Event e))
      (is (= 2 (count (:parameters e)))))))

;; -- Parameter ---------------------------------------------------------------

(deftest parameter-identity
  (testing "Parameter identity is (parent-id, parameter-name)"
    (let [pr (p/make-parameter "order" (t/make-ref-kernel-primitive #{:container}) false 0)]
      (is (= ["place-order" "order"] (p/parameter-identity "place-order" pr))))))

;; -- Behaviour & Rule --------------------------------------------------------

(deftest behaviour
  (let [b (p/make-behaviour {:id "b" :label "OrderBehaviour" :rules ["r1" "r2"]})]
    (is (= :primitive/behaviour (:kind b)))
    (is (m/validate p/Behaviour b))))

(deftest rule-with-body
  (let [r (p/make-rule
            {:id "process-submission"
             :label "ProcessSubmission"
             :description "Handles an inbound submission event"
             :intent {:id "ri" :clauses [] :assertions []}
             :body   {:definitions [] :effects []}})]
    (is (= :primitive/rule (:kind r)))
    (is (m/validate p/Rule r))))

(deftest definition-identity
  (let [d (p/make-definition "fee" {:case :expr/var :name "rate"})]
    (is (= ["rule-1" "fee"] (p/definition-identity "rule-1" d)))))

;; -- Boundary & Operation ----------------------------------------------------

(deftest boundary
  (let [b (p/make-boundary {:id "bd" :label "OrderBoundary" :operations []})]
    (is (= :primitive/boundary (:kind b)))
    (is (m/validate p/Boundary b))))

(deftest operation-with-return
  (let [o (p/make-operation
            {:id "submit"
             :label "submit"
             :parameters [(p/make-parameter "key"
                                            (t/make-scalar "String") false 0)]
             :return-type (t/make-composite-named "EventSubmission")})]
    (is (= :primitive/operation (:kind o)))
    (is (some? (:return-type o)))
    (is (m/validate p/Operation o))))

(deftest operation-fire-and-forget
  (testing "Operation with absent return-type is fire-and-forget (command-shaped)"
    (let [o (p/make-operation
              {:id "ping" :label "ping" :parameters []})]
      (is (nil? (:return-type o)))
      (is (m/validate p/Operation o)))))

;; -- Intent & Clause ---------------------------------------------------------

(deftest intent
  (let [i (p/make-intent
            {:id "i" :clauses [{:id "c1" :body "Order must be paid in full"}]
             :assertions []})]
    (is (= :primitive/intent (:kind i)))
    (is (m/validate p/Intent i))))

(deftest clause-label-optional-not-identity
  (testing "Clause label is optional; identity is the id only (K15a)"
    (let [c1 (p/make-clause {:id "c1" :body "X holds"})
          c2 (p/make-clause {:id "c2" :label "labelled-one" :body "X holds"})]
      (is (every? #(m/validate p/Clause %) [c1 c2])))))

;; -- Kind discriminator ------------------------------------------------------

(deftest every-primitive-carries-kind
  (testing ":kind is a closed enum; constructors set it; nothing else does"
    (is (= #{:primitive/container :primitive/actor :primitive/behaviour
             :primitive/rule :primitive/boundary :primitive/operation
             :primitive/intent :primitive/clause :primitive/event}
           p/primitive-kinds))))
```

- [ ] **Step 2.2: Run the test, see it fail**

```bash
clj -M:test -n fukan.model.primitives-test
```

Expected: `Could not locate fukan/model/primitives__init.class …`.

- [ ] **Step 2.3: Implement `src/fukan/model/primitives.clj`**

```clojure
(ns fukan.model.primitives
  "Kernel primitives and value records (MODEL.md §3.1–3.5).

   Nine primitives:
     Container, Actor, Behaviour, Rule, Boundary, Operation, Intent, Clause, Event.

   Value records (substrate, not primitives):
     Field, Parameter, Definition, RuleBody.

   Every primitive carries :kind from `primitive-kinds`. The substrate does not
   validate referenced ids — that is the analyzer / build pipeline's job."
  (:require [fukan.model.type :as t]
            [malli.core :as m]))

(def primitive-kinds
  #{:primitive/container :primitive/actor :primitive/behaviour
    :primitive/rule :primitive/boundary :primitive/operation
    :primitive/intent :primitive/clause :primitive/event})

;; -- Value records ------------------------------------------------------------

(defn make-field
  "Field substrate: name + Type + optionality. Identity is composed by
   field-identity using the owning Container's id."
  [name type-value optional?]
  {:name name, :type type-value, :optional (boolean optional?)})

(defn field-identity
  "(container-id, Field.name) per MODEL.md §3.2."
  [container-id field]
  [container-id (:name field)])

(defn make-parameter
  "Parameter substrate: name + Type + optionality + ordinal."
  [name type-value optional? ordinal]
  {:name name, :type type-value, :optional (boolean optional?), :ordinal ordinal})

(defn parameter-identity
  "(parent-id, Parameter.name) per MODEL.md §3.2. Parent is Operation or Event."
  [parent-id parameter]
  [parent-id (:name parameter)])

(defn make-definition
  "Definition is a typed `where:` binding inside Rule.body."
  [name expression]
  {:name name, :expression expression})

(defn definition-identity
  "(rule-id, Definition.name) per MODEL.md §3.2."
  [rule-id definition]
  [rule-id (:name definition)])

(defn make-rule-body
  "RuleBody bundles definitions + effects. No independent identity (reduces to
   host Rule's id)."
  ([] (make-rule-body [] []))
  ([definitions effects]
   {:definitions (vec definitions), :effects (vec effects)}))

;; -- Primitives ---------------------------------------------------------------

(defn- with-kind [kind m] (assoc m :kind kind))

(defn make-container
  "Container substrate. All faces optional except :id and :label."
  [{:keys [id label description intent children fields events behaviour boundary]}]
  (with-kind :primitive/container
    (cond-> {:id id, :label label}
      description (assoc :description description)
      intent      (assoc :intent intent)
      children    (assoc :children (set children))
      fields      (assoc :fields (vec fields))
      events      (assoc :events (set events))
      behaviour   (assoc :behaviour behaviour)
      boundary    (assoc :boundary boundary))))

(defn make-actor
  [{:keys [id label description]}]
  (with-kind :primitive/actor
    (cond-> {:id id, :label label}
      description (assoc :description description))))

(defn make-behaviour
  [{:keys [id label rules intent]}]
  (with-kind :primitive/behaviour
    (cond-> {:id id, :label label, :rules (vec rules)}
      intent (assoc :intent intent))))

(defn make-rule
  [{:keys [id label description intent body]}]
  (with-kind :primitive/rule
    (cond-> {:id id, :label label}
      description (assoc :description description)
      intent      (assoc :intent intent)
      body        (assoc :body body))))

(defn make-boundary
  [{:keys [id label operations intent]}]
  (with-kind :primitive/boundary
    (cond-> {:id id, :label label, :operations (vec operations)}
      intent (assoc :intent intent))))

(defn make-operation
  [{:keys [id label description parameters return-type intent]}]
  (with-kind :primitive/operation
    (cond-> {:id id, :label label, :parameters (vec parameters)}
      description (assoc :description description)
      return-type (assoc :return-type return-type)
      intent      (assoc :intent intent))))

(defn make-intent
  [{:keys [id label clauses assertions]}]
  (with-kind :primitive/intent
    (cond-> {:id id, :clauses (vec clauses), :assertions (vec assertions)}
      label (assoc :label label))))

(defn make-clause
  [{:keys [id label body]}]
  (with-kind :primitive/clause
    (cond-> {:id id, :body body}
      label (assoc :label label))))

(defn make-event
  [{:keys [id label description parameters]}]
  (with-kind :primitive/event
    (cond-> {:id id, :label label, :parameters (vec parameters)}
      description (assoc :description description))))

;; -- Malli schemas ------------------------------------------------------------

(def Field
  [:map
   [:name :string]
   [:type t/Type]
   [:optional :boolean]])

(def Parameter
  [:map
   [:name :string]
   [:type t/Type]
   [:optional :boolean]
   [:ordinal :int]])

(def Intent
  [:map
   [:kind [:= :primitive/intent]]
   [:id :string]
   [:label {:optional true} :string]
   [:clauses [:vector :any]]                   ;; clause values; closed in Clause schema
   [:assertions [:vector :any]]])              ;; Expression values; closed in expression.clj

(def Clause
  [:map
   [:kind [:= :primitive/clause]]
   [:id :string]
   [:label {:optional true} :string]
   [:body :string]])

(def Container
  [:map
   [:kind [:= :primitive/container]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:intent {:optional true} Intent]
   [:children {:optional true} [:set :string]]
   [:fields {:optional true} [:vector Field]]
   [:events {:optional true} [:set :string]]
   [:behaviour {:optional true} [:map
                                 [:kind [:= :primitive/behaviour]]
                                 [:id :string]
                                 [:label :string]
                                 [:rules [:vector :string]]
                                 [:intent {:optional true} Intent]]]
   [:boundary  {:optional true} [:map
                                 [:kind [:= :primitive/boundary]]
                                 [:id :string]
                                 [:label :string]
                                 [:operations [:vector :string]]
                                 [:intent {:optional true} Intent]]]])

(def Actor
  [:map
   [:kind [:= :primitive/actor]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]])

(def Behaviour
  [:map
   [:kind [:= :primitive/behaviour]]
   [:id :string]
   [:label :string]
   [:rules [:vector :string]]
   [:intent {:optional true} Intent]])

(def Rule
  [:map
   [:kind [:= :primitive/rule]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:intent {:optional true} Intent]
   [:body {:optional true}
    [:map
     [:definitions [:vector :any]]             ;; Definition values
     [:effects [:vector :any]]]]])             ;; Effect values

(def Boundary
  [:map
   [:kind [:= :primitive/boundary]]
   [:id :string]
   [:label :string]
   [:operations [:vector :string]]
   [:intent {:optional true} Intent]])

(def Operation
  [:map
   [:kind [:= :primitive/operation]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:parameters [:vector Parameter]]
   [:return-type {:optional true} t/Type]
   [:intent {:optional true} Intent]])

(def Event
  [:map
   [:kind [:= :primitive/event]]
   [:id :string]
   [:label :string]
   [:description {:optional true} :string]
   [:parameters [:vector Parameter]]])

(def Primitive
  [:multi {:dispatch :kind}
   [:primitive/container Container]
   [:primitive/actor     Actor]
   [:primitive/behaviour Behaviour]
   [:primitive/rule      Rule]
   [:primitive/boundary  Boundary]
   [:primitive/operation Operation]
   [:primitive/intent    Intent]
   [:primitive/clause    Clause]
   [:primitive/event     Event]])
```

- [ ] **Step 2.4: Run the tests; expect all to pass**

```bash
clj -M:test -n fukan.model.primitives-test
```

Expected: 0 failures.

- [ ] **Step 2.5: Commit**

```bash
git add src/fukan/model/primitives.clj test/fukan/model/primitives_test.clj
git commit -m "$(cat <<'EOF'
feat(model): nine kernel primitives + value records

Implements MODEL.md §3.1–3.5. Constructors return plain maps; identity
tuples are pure functions. The substrate does not validate referenced
ids — that lives in the build pipeline (Plan 3).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Expression substrate

**Files:**
- Create: `src/fukan/model/expression.clj`
- Test: `test/fukan/model/expression_test.clj`
- Reference: [MODEL.md §3.8.1, §3.8.5–§3.8.7](../MODEL.md#38-expression-and-effect-substrate)

Expression is the single sub-substrate sort for predicates, named bindings, and computed values. **Plan 1 ships the data shape, the operator vocabulary, the Environment record, and structural identity.** Type-checking against the Environment is deferred to Plan 4 (the constraint engine).

ExpressionForm cases: `Var | Ref | Lit | Apply | Let | If | Match | Forall | Exists | Aggregate`.

Substrate invariants (encoded as tests):

- `Expression` record is `{:label? Maybe String, :form ExpressionForm}`. `label?` is not part of structural identity (per K31, parallels Clause's `label?` per K15a).
- The kernel-core operator set is: `+`, `-`, `*`, `/`, `=`, `!=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`, `in`, `contains`, `is-present`, `is-absent` (per [§3.8.1](../MODEL.md#381-expression)). Methodology operators register additively in Plan 4.
- Three Environment flavours: `OneState`, `TwoState`, `ModelIntrospection` ([§3.8.5](../MODEL.md#385-environment)).
- `expression-identity` is the recursive structural shape of `:form` (label stripped).

- [ ] **Step 3.1: Write the failing test file**

Create `test/fukan/model/expression_test.clj`:

```clojure
(ns fukan.model.expression-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest var-form
  (let [x (e/make-var "x")]
    (is (= :expr/var (get-in x [:form :case])))
    (is (= "x" (get-in x [:form :name])))
    (is (m/validate e/Expression x))))

(deftest ref-form
  (let [r (e/make-ref (t/make-ref-kernel-primitive #{:container}))]
    (is (= :expr/ref (get-in r [:form :case])))
    (is (m/validate e/Expression r))))

(deftest lit-form
  (let [v (e/make-lit (t/make-scalar "Integer") 42)]
    (is (= :expr/lit (get-in v [:form :case])))
    (is (= 42 (get-in v [:form :value])))))

(deftest apply-form
  (testing "Apply takes operator name + ordered args"
    (let [v (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 1)])]
      (is (= "+" (get-in v [:form :op])))
      (is (= 2 (count (get-in v [:form :args])))))))

(deftest let-form
  (let [v (e/make-let "y" (e/make-var "x") (e/make-var "y"))]
    (is (= :expr/let (get-in v [:form :case])))))

(deftest if-form
  (let [v (e/make-if (e/make-var "p") (e/make-lit (t/make-scalar "Integer") 1)
                                       (e/make-lit (t/make-scalar "Integer") 0))]
    (is (= :expr/if (get-in v [:form :case])))))

(deftest forall-and-exists
  (let [f (e/make-forall "x" (e/make-var "S") (e/make-var "p"))
        x (e/make-exists "x" (e/make-var "S") (e/make-var "p"))]
    (is (= :expr/forall (get-in f [:form :case])))
    (is (= :expr/exists (get-in x [:form :case])))))

(deftest aggregate-form
  (let [v (e/make-aggregate :count (e/make-var "S") (e/make-var "x"))]
    (is (= :expr/aggregate (get-in v [:form :case])))
    (is (= :count (get-in v [:form :kind])))
    (is (m/validate e/Expression v))))

(deftest match-form
  (testing "Match arms each carry a TypePattern and a body"
    (let [v (e/make-match (e/make-var "v")
              [(e/make-match-arm {:case :pattern/scalar :name :literal/string} (e/make-lit (t/make-scalar "Integer") 1))
               (e/make-match-arm {:case :pattern/scalar :name :literal/integer} (e/make-lit (t/make-scalar "Integer") 0))])]
      (is (= :expr/match (get-in v [:form :case])))
      (is (= 2 (count (get-in v [:form :arms])))))))

(deftest label-not-in-identity
  (testing "Per K31, label? is addressability-only — not part of structural identity"
    (let [a (e/make-var "x")
          b (assoc a :label "guarantee-1")]
      (is (= (e/expression-identity a) (e/expression-identity b)))
      (is (not= a b)))))

(deftest identity-is-recursive
  (testing "Structural identity compares :form recursively, ignoring labels at every level"
    (let [a (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 1)])
          b (assoc-in
              (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 1)])
              [:form :args 0 :label] "named")
          c (e/make-apply "+" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 2)])]
      (is (= (e/expression-identity a) (e/expression-identity b)))
      (is (not= (e/expression-identity a) (e/expression-identity c))))))

(deftest core-operators
  (testing "Kernel-core operator names are registered for documentation / discovery"
    (is (contains? e/core-operators "+"))
    (is (contains? e/core-operators "and"))
    (is (contains? e/core-operators "is-present"))))

(deftest environment-onestate
  (let [env (e/make-environment-onestate {"self" (t/make-ref-kernel-primitive #{:container})})]
    (is (= :env/onestate (:case env)))
    (is (m/validate e/Environment env))))

(deftest environment-twostate
  (let [env (e/make-environment-twostate
              {"X" (t/make-ref-kernel-primitive #{:container})}
              {"X" (t/make-ref-kernel-primitive #{:container})}
              {"order" (t/make-ref-kernel-primitive #{:container})})]
    (is (= :env/twostate (:case env)))
    (is (m/validate e/Environment env))))

(deftest environment-model-introspection
  (let [env (e/make-environment-model-introspection {})]
    (is (= :env/model-introspection (:case env)))
    (is (m/validate e/Environment env))))
```

- [ ] **Step 3.2: Run the tests; see them fail**

```bash
clj -M:test -n fukan.model.expression-test
```

Expected: file-not-found errors.

- [ ] **Step 3.3: Implement `src/fukan/model/expression.clj`**

```clojure
(ns fukan.model.expression
  "Expression sub-substrate (MODEL.md §3.8.1, §3.8.5–§3.8.7).

   One calculus, three callsites:
     - Rule bodies (TwoState environment)
     - Intent assertions on any host (OneState env for Container/Behaviour/
       Boundary/Operation; TwoState for Rule)
     - §6 constraint predicates (ModelIntrospection env, Plan 4)

   Expression record: { label?, form }. Identity is structural over :form;
   label? is addressability-only (parallels Clause's label? per K15a).

   Type-checking and Environment-bound evaluation are deferred to Plan 4 (the
   constraint engine). This module defines the data shape and structural id."
  (:require [fukan.model.type :as t]
            [malli.core :as m]))

;; -- Kernel-core operator vocabulary -----------------------------------------

(def core-operators
  "Kernel-committed core operators per MODEL.md §3.8.1. Methodologies add
   operators in Plan 4 via the registration shape parallel to §5.3."
  #{"+" "-" "*" "/"
    "=" "!=" "<" "<=" ">" ">="
    "and" "or" "not"
    "in" "contains"
    "is-present" "is-absent"})

;; -- Form constructors -------------------------------------------------------

(defn- expr
  ([form] {:form form})
  ([label form] (cond-> {:form form} (some? label) (assoc :label label))))

(defn make-var
  ([name] (expr {:case :expr/var, :name name}))
  ([label name] (expr label {:case :expr/var, :name name})))

(defn make-ref
  ([ref-target] (expr {:case :expr/ref, :target ref-target}))
  ([label ref-target] (expr label {:case :expr/ref, :target ref-target})))

(defn make-lit
  ([type-value value] (expr {:case :expr/lit, :type type-value, :value value}))
  ([label type-value value] (expr label {:case :expr/lit, :type type-value, :value value})))

(defn make-apply
  ([op args] (expr {:case :expr/apply, :op op, :args (vec args)}))
  ([label op args] (expr label {:case :expr/apply, :op op, :args (vec args)})))

(defn make-let
  ([name source body]
   (expr {:case :expr/let, :name name, :source source, :body body}))
  ([label name source body]
   (expr label {:case :expr/let, :name name, :source source, :body body})))

(defn make-if
  ([cond then else]
   (expr {:case :expr/if, :cond cond, :then then, :else else}))
  ([label cond then else]
   (expr label {:case :expr/if, :cond cond, :then then, :else else})))

(defn make-forall
  ([var-name source body]
   (expr {:case :expr/forall, :var var-name, :source source, :body body})))

(defn make-exists
  ([var-name source body]
   (expr {:case :expr/exists, :var var-name, :source source, :body body})))

(def aggregate-kinds #{:count :sum :min :max})

(defn make-aggregate
  ([kind source projection]
   (assert (aggregate-kinds kind) (str "Unknown aggregate kind: " kind))
   (expr {:case :expr/aggregate, :kind kind, :source source, :projection projection})))

(defn make-match-arm
  "MatchArm: { pattern: TypePattern, body: Expression }."
  [pattern body]
  {:pattern pattern, :body body})

(defn make-match
  ([scrutinee arms]
   (expr {:case :expr/match, :scrutinee scrutinee, :arms (vec arms)})))

;; -- Structural identity ------------------------------------------------------

(defn- strip-labels
  "Recursively drop :label from this Expression and every nested Expression."
  [x]
  (cond
    (and (map? x) (contains? x :form))
    {:form (strip-labels (:form x))}

    (map? x)
    (into {} (map (fn [[k v]] [k (strip-labels v)]) x))

    (vector? x)
    (mapv strip-labels x)

    :else x))

(defn expression-identity
  "Structural shape of :form, recursively stripped of :label slots. Two
   Expressions with the same identity are the same Expression value."
  [expression]
  (strip-labels (:form expression)))

;; -- Environment --------------------------------------------------------------

(defn make-environment-onestate [bindings]
  {:case :env/onestate, :bindings bindings})

(defn make-environment-twostate [pre post params]
  {:case :env/twostate, :pre pre, :post post, :params params})

(defn make-environment-model-introspection [bindings]
  {:case :env/model-introspection, :bindings bindings})

;; -- Malli schemas ------------------------------------------------------------

(def Bindings [:map-of :string t/Type])

(def Environment
  [:multi {:dispatch :case}
   [:env/onestate
    [:map [:case [:= :env/onestate]] [:bindings Bindings]]]
   [:env/twostate
    [:map [:case [:= :env/twostate]]
     [:pre Bindings] [:post Bindings] [:params Bindings]]]
   [:env/model-introspection
    [:map [:case [:= :env/model-introspection]] [:bindings Bindings]]]])

(def Expression
  "Recursive schema; uses a local registry."
  [:schema
   {:registry
    {::Expression
     [:map
      [:label {:optional true} :string]
      [:form [:ref ::ExpressionForm]]]
     ::ExpressionForm
     [:multi {:dispatch :case}
      [:expr/var       [:map [:case [:= :expr/var]] [:name :string]]]
      [:expr/ref       [:map [:case [:= :expr/ref]] [:target :any]]]
      [:expr/lit       [:map [:case [:= :expr/lit]] [:type t/Type] [:value :any]]]
      [:expr/apply     [:map [:case [:= :expr/apply]] [:op :string] [:args [:vector [:ref ::Expression]]]]]
      [:expr/let       [:map [:case [:= :expr/let]]
                        [:name :string]
                        [:source [:ref ::Expression]]
                        [:body   [:ref ::Expression]]]]
      [:expr/if        [:map [:case [:= :expr/if]]
                        [:cond [:ref ::Expression]]
                        [:then [:ref ::Expression]]
                        [:else [:ref ::Expression]]]]
      [:expr/forall    [:map [:case [:= :expr/forall]]
                        [:var :string]
                        [:source [:ref ::Expression]]
                        [:body   [:ref ::Expression]]]]
      [:expr/exists    [:map [:case [:= :expr/exists]]
                        [:var :string]
                        [:source [:ref ::Expression]]
                        [:body   [:ref ::Expression]]]]
      [:expr/aggregate [:map [:case [:= :expr/aggregate]]
                        [:kind (into [:enum] aggregate-kinds)]
                        [:source [:ref ::Expression]]
                        [:projection [:ref ::Expression]]]]
      [:expr/match     [:map [:case [:= :expr/match]]
                        [:scrutinee [:ref ::Expression]]
                        [:arms [:vector [:map
                                         [:pattern :any]
                                         [:body [:ref ::Expression]]]]]]]]}}
   [:ref ::Expression]])
```

- [ ] **Step 3.4: Run the tests; expect all to pass**

```bash
clj -M:test -n fukan.model.expression-test
```

Expected: 0 failures.

- [ ] **Step 3.5: Commit**

```bash
git add src/fukan/model/expression.clj test/fukan/model/expression_test.clj
git commit -m "$(cat <<'EOF'
feat(model): Expression sub-substrate

Implements MODEL.md §3.8.1, §3.8.5–§3.8.7. Data shape + structural
identity + Environment record. Type-checking and bound evaluation
deferred to Plan 4 (constraint engine).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Effect substrate

**Files:**
- Create: `src/fukan/model/effect.clj`
- Test: `test/fukan/model/effect_test.clj`
- Reference: [MODEL.md §3.8.2–§3.8.4, §3.8.7](../MODEL.md#38-expression-and-effect-substrate)

Effect record: `{ kind: EffectKind, target: SubstrateAddress | PrimitiveRef, value: Expression?, source: ExprId }`. Identity is `(rule-id, kind, target)` — stable across semantically-equivalent rewrites of the source Expression. Plan 1 ships the data shape and identity function. **Canonicalisation from Expression patterns (§3.8.4) is deferred to Plan 2** where the Allium analyzer needs it; the four-pattern matcher lives in `fukan.model.effect/canonicalise` as a stub function returning `nil` in Plan 1 — Plan 2 fills the body.

EffectKind: `:effect/create | :effect/write | :effect/destroy | :effect/emit` (per [§3.8.2](../MODEL.md#382-effect)).

- [ ] **Step 4.1: Write the failing test file**

Create `test/fukan/model/effect_test.clj`:

```clojure
(ns fukan.model.effect-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.effect :as fx]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest write-effect
  (let [tgt {:case :endpoint/substrate
             :container "order"
             :path [{:slot "field" :key "total"}]}
        v   (e/make-apply "+" [(e/make-var "pre.order.total")
                                (e/make-var "delta")])
        fx  (fx/make-effect :effect/write tgt v "expr-1")]
    (is (= :effect/write (:kind fx)))
    (is (= tgt (:target fx)))
    (is (= "expr-1" (:source fx)))
    (is (m/validate fx/Effect fx))))

(deftest create-effect
  (let [fx (fx/make-effect :effect/create
                            {:case :endpoint/primitive :id "order"}
                            (e/make-var "params.order")
                            "expr-1")]
    (is (= :effect/create (:kind fx)))
    (is (m/validate fx/Effect fx))))

(deftest destroy-effect-has-no-value
  (let [fx (fx/make-effect :effect/destroy
                            {:case :endpoint/primitive :id "order"}
                            nil
                            "expr-1")]
    (is (= :effect/destroy (:kind fx)))
    (is (nil? (:value fx)))
    (is (m/validate fx/Effect fx))))

(deftest emit-effect
  (let [fx (fx/make-effect :effect/emit
                            {:case :endpoint/primitive :id "interview/slot-confirmed"}
                            (e/make-apply "tuple" [(e/make-var "params.viewer")
                                                    (e/make-var "params.slot")])
                            "expr-2")]
    (is (= :effect/emit (:kind fx)))
    (is (m/validate fx/Effect fx))))

(deftest identity-stable-over-value-rewrite
  (testing "(rule-id, kind, target) — independent of :value content"
    (let [tgt {:case :endpoint/substrate
               :container "order"
               :path [{:slot "field" :key "total"}]}
          a (fx/make-effect :effect/write tgt
              (e/make-apply "+" [(e/make-var "pre.order.total") (e/make-lit (t/make-scalar "Integer") 1)])
              "expr-A")
          b (fx/make-effect :effect/write tgt
              (e/make-apply "+" [(e/make-lit (t/make-scalar "Integer") 1) (e/make-var "pre.order.total")])
              "expr-B")]
      (is (= (fx/effect-identity "rule-1" a)
             (fx/effect-identity "rule-1" b)))
      (is (not= (fx/effect-identity "rule-1" a)
                (fx/effect-identity "rule-2" a))))))

(deftest identity-differs-on-target
  (let [a (fx/make-effect :effect/write
            {:case :endpoint/substrate :container "order" :path [{:slot "field" :key "total"}]}
            (e/make-var "x") "e1")
        b (fx/make-effect :effect/write
            {:case :endpoint/substrate :container "order" :path [{:slot "field" :key "status"}]}
            (e/make-var "x") "e1")]
    (is (not= (fx/effect-identity "rule-1" a) (fx/effect-identity "rule-1" b)))))

(deftest canonicalise-is-deferred-to-plan-2
  (testing "Plan 1 ships only the data shape; canonicalisation lives in Plan 2"
    (is (nil? (fx/canonicalise (e/make-var "post.X.f"))))))
```

- [ ] **Step 4.2: Run the tests; see them fail**

```bash
clj -M:test -n fukan.model.effect-test
```

- [ ] **Step 4.3: Implement `src/fukan/model/effect.clj`**

```clojure
(ns fukan.model.effect
  "Effect sub-substrate (MODEL.md §3.8.2–§3.8.4).

   Effect = { kind, target, value?, source: ExprId }.
     - kind   ∈ :effect/create | :effect/write | :effect/destroy | :effect/emit
     - target ∈ PrimitiveRef | SubstrateAddress (per §4.3)
     - value  Optional Expression — absent for Destroy
     - source ExprId addressing the originating Expression in
              `Rule.intent.assertions` (per the §3.8 kernel invariant)

   Identity is (rule-id, kind, target). Plan 1 ships the data shape and
   identity. Canonicalisation (Expression → Effect, per §3.8.4 patterns) is
   deferred to Plan 2 where the Allium analyzer drives it."
  (:require [fukan.model.expression :as e]
            [malli.core :as m]))

(def effect-kinds
  #{:effect/create :effect/write :effect/destroy :effect/emit})

(defn make-effect
  "Construct an Effect. `value` may be nil for Destroy."
  [kind target value source-expr-id]
  (assert (effect-kinds kind) (str "Unknown effect kind: " kind))
  (cond-> {:kind kind, :target target, :source source-expr-id}
    (some? value) (assoc :value value)))

(defn effect-identity
  "(rule-id, kind, target) per MODEL.md §3.8.7. Stable across semantically-
   equivalent rewrites of the source Expression."
  [rule-id effect]
  [rule-id (:kind effect) (:target effect)])

(defn canonicalise
  "Plan-2 stub. The Allium analyzer (Plan 2) replaces this body with the
   §3.8.4 four-pattern matcher: post.X.f = E ⇒ Write; post.X = T.created(…)
   ⇒ Create; not exists post.X ⇒ Destroy; emitted(E, args…) ⇒ Emit."
  [_expression]
  nil)

;; -- Malli schema -------------------------------------------------------------

(def EndpointPrimitive
  [:map [:case [:= :endpoint/primitive]] [:id :string]])

(def EndpointSubstrate
  [:map
   [:case [:= :endpoint/substrate]]
   [:container :string]
   [:path [:vector [:map [:slot :string] [:key {:optional true} :string]]]]])

(def Endpoint
  [:multi {:dispatch :case}
   [:endpoint/primitive EndpointPrimitive]
   [:endpoint/substrate EndpointSubstrate]])

(def Effect
  [:map
   [:kind (into [:enum] effect-kinds)]
   [:target Endpoint]
   [:value {:optional true} e/Expression]
   [:source :string]])
```

- [ ] **Step 4.4: Run the tests; expect all to pass**

```bash
clj -M:test -n fukan.model.effect-test
```

- [ ] **Step 4.5: Commit**

```bash
git add src/fukan/model/effect.clj test/fukan/model/effect_test.clj
git commit -m "$(cat <<'EOF'
feat(model): Effect sub-substrate

Implements MODEL.md §3.8.2–§3.8.4. Data shape + (rule-id, kind, target)
identity. Canonicalisation from Expression patterns deferred to Plan 2
where the Allium analyzer drives it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Kernel relations + endpoint addressing + edge identity

**Files:**
- Create: `src/fukan/model/relations.clj`
- Test: `test/fukan/model/relations_test.clj`
- Reference: [MODEL.md §4](../MODEL.md#4-kernel-relations)

Thirteen relations. Each grounded by a substrate push and a uniform cross-methodology semantic. Endpoints are a sum (`PrimitiveRef | SubstrateAddress`). Edges are values; identity is `(from, to, kind, identifying-metadata)`; multi-edges are allowed iff identifying metadata differs.

The 13 relations with their endpoint kinds (per [§4.1](../MODEL.md#41-the-relation-set)):

| Relation | From | To | Identifying metadata |
|---|---|---|---|
| `:relation/triggers` | event\|operation | rule | — |
| `:relation/observes` | rule | container\|field | condition |
| `:relation/reads` | rule | container\|field | condition |
| `:relation/writes` | rule | container\|field | condition, scope |
| `:relation/creates` | rule | container | condition, scope |
| `:relation/destroys` | rule | container | condition, scope |
| `:relation/emits` | rule | event | condition, scope |
| `:relation/realises` | container\|operation | container\|operation | — |
| `:relation/specialises` | container | container | — |
| `:relation/uses` | container | container | — |
| `:relation/exposes` | container | field | — |
| `:relation/provides` | container | event | — |
| `:relation/projects` | container\|operation\|rule\|event\|clause\|expression | artifact | projection-kind |

Endpoint addressing (per [§4.3](../MODEL.md#43-endpoint-addressing--primitive-references-and-substrate-addresses)):

```
PrimitiveRef     = { :case :endpoint/primitive,  :id PrimitiveId }
SubstrateAddress = { :case :endpoint/substrate,
                     :container ContainerId,
                     :path [{ :slot SlotName, :key? String }] }
```

- [ ] **Step 5.1: Write the failing test file**

Create `test/fukan/model/relations_test.clj`:

```clojure
(ns fukan.model.relations-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.relations :as r]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest the-thirteen-relations
  (is (= #{:relation/triggers :relation/observes :relation/reads
            :relation/writes :relation/creates :relation/destroys
            :relation/emits :relation/realises :relation/specialises
            :relation/uses :relation/exposes :relation/provides
            :relation/projects}
         r/relation-kinds)))

(deftest endpoint-primitive
  (let [ep (r/primitive-ref "order")]
    (is (= :endpoint/primitive (:case ep)))
    (is (= "order" (:id ep)))
    (is (m/validate r/Endpoint ep))))

(deftest endpoint-substrate-field
  (testing "Field address — single-segment path"
    (let [ep (r/substrate-address "order" [{:slot "field" :key "total"}])]
      (is (= :endpoint/substrate (:case ep)))
      (is (= "order" (:container ep)))
      (is (= [{:slot "field" :key "total"}] (:path ep)))
      (is (m/validate r/Endpoint ep)))))

(deftest endpoint-substrate-parameter
  (testing "Parameter address — multi-segment path (deferred but kernel-shaped)"
    (let [ep (r/substrate-address "order"
                                  [{:slot "boundary"}
                                   {:slot "operation" :key "place_order"}
                                   {:slot "parameter" :key "order"}])]
      (is (m/validate r/Endpoint ep)))))

(deftest triggers-edge
  (let [edge (r/make-edge
               :relation/triggers
               (r/primitive-ref "order-placed-event")
               (r/primitive-ref "process-submission-rule"))]
    (is (= :relation/triggers (:kind edge)))
    (is (m/validate r/Edge edge))))

(deftest writes-edge-with-condition
  (let [cond-expr (e/make-apply ">"
                                [(e/make-var "pre.order.total")
                                 (e/make-lit (t/make-scalar "Integer") 0)])
        edge (r/make-edge :relation/writes
                          (r/primitive-ref "process-submission")
                          (r/substrate-address "order" [{:slot "field" :key "total"}])
                          {:condition cond-expr})]
    (is (= cond-expr (:condition edge)))
    (is (m/validate r/Edge edge))))

(deftest projects-edge-with-projection-kind
  (let [edge (r/make-edge :relation/projects
                          (r/primitive-ref "process-submission-rule")
                          (r/primitive-ref "code/auth/process-submission")
                          {:projection-kind :projection-kind/rule})]
    (is (= :projection-kind/rule (:projection-kind edge)))
    (is (m/validate r/Edge edge))))

(deftest edge-identity
  (let [from (r/primitive-ref "rule-1")
        to   (r/substrate-address "order" [{:slot "field" :key "total"}])
        c1   (e/make-apply ">" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 0)])
        c2   (e/make-apply "<" [(e/make-var "x") (e/make-lit (t/make-scalar "Integer") 0)])
        a (r/make-edge :relation/writes from to {:condition c1})
        b (r/make-edge :relation/writes from to {:condition c1})
        c (r/make-edge :relation/writes from to {:condition c2})]
    (is (= (r/edge-identity a) (r/edge-identity b))
        "Same identifying metadata ⇒ same edge identity")
    (is (not= (r/edge-identity a) (r/edge-identity c))
        "Different :condition ⇒ different edges (multi-edge per §4.4)")))

(deftest edge-identity-drops-non-identifying
  (testing "Non-identifying metadata mutates without changing identity"
    (let [from (r/primitive-ref "rule-1")
          to   (r/primitive-ref "container-x")
          a (r/make-edge :relation/uses from to {:source-file "a.allium"})
          b (r/make-edge :relation/uses from to {:source-file "b.allium"})]
      (is (= (r/edge-identity a) (r/edge-identity b))))))

(deftest identifying-slots-table
  (testing "Per-relation identifying metadata per §4.4"
    (is (= #{}                                  (r/identifying-slots :relation/triggers)))
    (is (= #{:condition}                        (r/identifying-slots :relation/observes)))
    (is (= #{:condition}                        (r/identifying-slots :relation/reads)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/writes)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/creates)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/destroys)))
    (is (= #{:condition :scope}                 (r/identifying-slots :relation/emits)))
    (is (= #{}                                  (r/identifying-slots :relation/realises)))
    (is (= #{}                                  (r/identifying-slots :relation/specialises)))
    (is (= #{}                                  (r/identifying-slots :relation/uses)))
    (is (= #{}                                  (r/identifying-slots :relation/exposes)))
    (is (= #{}                                  (r/identifying-slots :relation/provides)))
    (is (= #{:projection-kind}                  (r/identifying-slots :relation/projects)))))
```

- [ ] **Step 5.2: Run; see failures**

```bash
clj -M:test -n fukan.model.relations-test
```

- [ ] **Step 5.3: Implement `src/fukan/model/relations.clj`**

```clojure
(ns fukan.model.relations
  "The thirteen kernel relations and edge machinery (MODEL.md §4).

   Endpoints are PrimitiveRef | SubstrateAddress. Edges are values; identity
   is (from, to, kind, identifying-metadata). Multi-edges are allowed iff the
   identifying-metadata subset differs.

   Per-relation identifying-metadata slots per §4.4."
  (:require [fukan.model.expression :as e]
            [malli.core :as m]))

(def relation-kinds
  #{:relation/triggers :relation/observes :relation/reads
    :relation/writes :relation/creates :relation/destroys
    :relation/emits :relation/realises :relation/specialises
    :relation/uses :relation/exposes :relation/provides
    :relation/projects})

(def ^:private identifying-slots-table
  {:relation/triggers    #{}
   :relation/observes    #{:condition}
   :relation/reads       #{:condition}
   :relation/writes      #{:condition :scope}
   :relation/creates     #{:condition :scope}
   :relation/destroys    #{:condition :scope}
   :relation/emits       #{:condition :scope}
   :relation/realises    #{}
   :relation/specialises #{}
   :relation/uses        #{}
   :relation/exposes     #{}
   :relation/provides    #{}
   :relation/projects    #{:projection-kind}})

(defn identifying-slots
  "Per-relation identifying-metadata slot names, per MODEL.md §4.4."
  [relation-kind]
  (identifying-slots-table relation-kind))

(defn primitive-ref [id] {:case :endpoint/primitive, :id id})

(defn substrate-address [container-id path]
  {:case :endpoint/substrate, :container container-id, :path (vec path)})

(defn make-edge
  "Construct a kernel edge.

     :kind - relation kind keyword (see relation-kinds)
     :from - Endpoint
     :to   - Endpoint
     :metadata (optional) - map; identifying slots per §4.4 plus any
       non-identifying methodology metadata"
  ([kind from to] (make-edge kind from to {}))
  ([kind from to metadata]
   (assert (relation-kinds kind) (str "Unknown relation: " kind))
   (merge {:kind kind, :from from, :to to} metadata)))

(defn edge-identity
  "(from, to, kind, identifying-subset) per §4.4. The identifying subset is
   the per-relation slots from §4.4; non-identifying slots are dropped."
  [edge]
  (let [slots (identifying-slots-table (:kind edge))]
    [(:from edge)
     (:to edge)
     (:kind edge)
     (select-keys edge slots)]))

;; -- Malli schemas ------------------------------------------------------------

(def Endpoint
  [:multi {:dispatch :case}
   [:endpoint/primitive
    [:map [:case [:= :endpoint/primitive]] [:id :string]]]
   [:endpoint/substrate
    [:map [:case [:= :endpoint/substrate]]
     [:container :string]
     [:path [:vector [:map [:slot :string] [:key {:optional true} :string]]]]]]])

(def projection-kinds
  #{:projection-kind/rule :projection-kind/operation
    :projection-kind/invariant :projection-kind/schema
    :projection-kind/test})

(def Edge
  [:map
   [:kind (into [:enum] relation-kinds)]
   [:from Endpoint]
   [:to Endpoint]
   [:condition       {:optional true} e/Expression]
   [:scope           {:optional true} e/Expression]
   [:projection-kind {:optional true} (into [:enum] projection-kinds)]])
```

- [ ] **Step 5.4: Run tests; expect all to pass**

```bash
clj -M:test -n fukan.model.relations-test
```

- [ ] **Step 5.5: Commit**

```bash
git add src/fukan/model/relations.clj test/fukan/model/relations_test.clj
git commit -m "$(cat <<'EOF'
feat(model): 13 kernel relations + edge identity + endpoint addressing

Implements MODEL.md §4. Edges are values; identity is (from, to, kind,
identifying-metadata) per §4.4. Multi-edges allowed iff identifying
metadata differs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Vocabulary mechanism

**Files:**
- Create: `src/fukan/model/vocabulary.clj`
- Test: `test/fukan/model/vocabulary_test.clj`
- Reference: [MODEL.md §5.2–§5.5](../MODEL.md#52-tagged-content-attachment)

Four data shapes:

- `TagDefinition` — namespace + name + applies-to + optional payload schema + optional parent-tag + optional relational spec.
- `TagApplication` — `(tag-ref, target, payload)`. Targets may be primitive ids, stored kernel-edge identities, or substrate addresses (per V10).
- `PredicateRegistration` — namespace + name + severity (`:error | :warning`) + kind (open string identifier; well-known kernel-shipped: `"integrity"`, `"drift"`) + scope (`:scope/model | :scope/tag`) + message template + opaque predicate value (concrete shape lands in Plan 4) + optional `applies-to` tag-ref.
- `RendererRegistration` — tag-ref + optional node/sidebar/edge treatment + optional layout hint. All treatments are opaque data in Plan 1; concrete shape lands in Plan 6.

Substrate invariants (tests):

- Inheritance v0 (per V9): payload-schema extension ✅, tag-presence implication ✅, field-override ❌, multi-parent ❌. Plan 1 ships the data shape; the `has-tag-with-ancestors?` helper exercises the implication rule.
- Severity is `:error | :warning`; kind is an open string; the two are orthogonal axes.
- A `TagApplication.target` may be a primitive id, an edge identity tuple (per §4.4), or a `SubstrateAddress` map.

- [ ] **Step 6.1: Write the failing test file**

Create `test/fukan/model/vocabulary_test.clj`:

```clojure
(ns fukan.model.vocabulary-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.vocabulary :as v]
            [fukan.model.type :as t]
            [malli.core :as m]))

(deftest tag-definition-pure-label
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Module"
              :applies-to :target/container})]
    (is (= "Allium" (:namespace td)))
    (is (= "Module" (:name td)))
    (is (= :target/container (:applies-to td)))
    (is (nil? (:payload-schema td)))
    (is (m/validate v/TagDefinition td))))

(deftest tag-definition-with-payload
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Surface"
              :applies-to :target/container
              :payload-schema
              (t/make-composite-inline
                [(t/make-field-spec
                   "facing"
                   (t/make-union [(t/make-ref-kernel-primitive #{:actor})
                                  (t/make-ref-kernel-primitive #{:container})])
                   true)])})]
    (is (m/validate v/TagDefinition td))))

(deftest tag-definition-relational
  (testing "RelationalSpec: directed, canonical_side, optional coherence_query"
    (let [td (v/make-tag-definition
               {:namespace "DDD" :name "CustomerSupplier"
                :applies-to :target/container
                :payload-schema (t/make-composite-inline
                                  [(t/make-field-spec "upstream"
                                                       (t/make-ref-kernel-primitive #{:container})
                                                       false)
                                   (t/make-field-spec "downstream"
                                                       (t/make-ref-kernel-primitive #{:container})
                                                       false)])
                :relational {:endpoints ["upstream" "downstream"]
                             :symmetry :directed
                             :canonical-side "upstream"}})]
      (is (= :directed (get-in td [:relational :symmetry])))
      (is (m/validate v/TagDefinition td)))))

(deftest tag-application
  (let [ta (v/make-tag-application
             {:tag {:namespace "Allium" :name "Module"}
              :target {:case :target/primitive :id "user-module"}
              :payload {}})]
    (is (m/validate v/TagApplication ta))))

(deftest tag-application-on-edge
  (testing "Per V10, stored kernel edges accept tag applications"
    (let [edge-id [{:case :endpoint/primitive :id "r"} {:case :endpoint/primitive :id "e"} :relation/emits {}]
          ta (v/make-tag-application
               {:tag {:namespace "Allium" :name "Trigger"}
                :target {:case :target/edge :edge-identity edge-id}
                :payload {:kind "external_stimulus"}})]
      (is (m/validate v/TagApplication ta)))))

(deftest tag-presence-implication
  (testing "V9: carrying child tag implies parent tag (`has-tag-with-ancestors?`)"
    (let [parent (v/make-tag-definition
                   {:namespace "DDD" :name "Aggregate" :applies-to :target/container})
          child  (v/make-tag-definition
                   {:namespace "DDD" :name "EventSourcedAggregate"
                    :applies-to :target/container
                    :parent-tag {:namespace "DDD" :name "Aggregate"}})
          registry {:tag-definitions [parent child]
                    :tag-applications [(v/make-tag-application
                                         {:tag {:namespace "DDD" :name "EventSourcedAggregate"}
                                          :target {:case :target/primitive :id "order"}})]}]
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "Aggregate"})))
      (is (true? (v/has-tag-with-ancestors? registry "order"
                                              {:namespace "DDD" :name "EventSourcedAggregate"}))))))

(deftest predicate-registration
  (let [pr (v/make-predicate-registration
             {:namespace "Allium" :name "VR30-trigger-defined"
              :severity :error :kind "integrity"
              :scope :scope/model
              :message-template "Trigger {{event}} referenced in Surface {{surface}} is not defined."
              :predicate {:opaque true :note "Datalog AST in Plan 4"}})]
    (is (= :error (:severity pr)))
    (is (= "integrity" (:kind pr)))
    (is (m/validate v/PredicateRegistration pr))))

(deftest renderer-registration-seam
  (testing "Plan 1 ships the seam shape only; treatments are opaque"
    (let [rr (v/make-renderer-registration
               {:tag {:namespace "Allium" :name "Surface"}
                :node-treatment {:shape :pill :colour-family "blue"}
                :sidebar-treatment {:component :allium/surface-sidebar}})]
      (is (m/validate v/RendererRegistration rr)))))
```

- [ ] **Step 6.2: Run; see failures**

```bash
clj -M:test -n fukan.model.vocabulary-test
```

- [ ] **Step 6.3: Implement `src/fukan/model/vocabulary.clj`**

```clojure
(ns fukan.model.vocabulary
  "Vocabulary mechanism — TagDefinition, TagApplication, PredicateRegistration,
   RendererRegistration (MODEL.md §5.2–§5.5).

   Plan 1 ships the data shapes and v0 inheritance semantics (V9): payload-
   schema extension ✅, tag-presence implication ✅, field-override ❌,
   multi-parent ❌. Concrete predicate-language semantics arrive in Plan 4;
   renderer treatments arrive in Plan 6."
  (:require [fukan.model.type :as t]
            [malli.core :as m]))

;; -- TagDefinition ------------------------------------------------------------

(defn make-tag-definition
  [{:keys [namespace name applies-to payload-schema parent-tag relational]}]
  (cond-> {:namespace namespace, :name name, :applies-to applies-to}
    payload-schema (assoc :payload-schema payload-schema)
    parent-tag     (assoc :parent-tag parent-tag)
    relational     (assoc :relational relational)))

;; -- TagApplication -----------------------------------------------------------

(defn make-tag-application
  ([tag-app] (make-tag-application tag-app {}))
  ([{:keys [tag target payload]} _opts]
   {:tag tag, :target target, :payload (or payload {})}))

(defn- tag-ref-equal? [a b]
  (and (= (:namespace a) (:namespace b)) (= (:name a) (:name b))))

(defn- definitions-by-ref [registry]
  (into {} (map (juxt #(select-keys % [:namespace :name]) identity)
                (:tag-definitions registry))))

(defn- ancestor-chain
  "All tag-refs from `tag-ref` up through every :parent-tag."
  [defs-by-ref tag-ref]
  (loop [current tag-ref, acc []]
    (if (nil? current)
      acc
      (let [td (defs-by-ref current)]
        (recur (:parent-tag td) (conj acc current))))))

(defn has-tag-with-ancestors?
  "True iff `target-id` carries `tag-ref` *or* any descendant tag whose
   ancestor chain includes `tag-ref` (V9 tag-presence implication)."
  [registry target-id tag-ref]
  (let [defs (definitions-by-ref registry)
        applications (->> (:tag-applications registry)
                          (filter (fn [ta]
                                    (let [tgt (:target ta)]
                                      (and (= :target/primitive (:case tgt))
                                           (= target-id (:id tgt)))))))
        applied-refs (map :tag applications)
        all-implied  (mapcat (fn [r] (ancestor-chain defs r)) applied-refs)]
    (boolean (some #(tag-ref-equal? % tag-ref) all-implied))))

;; -- PredicateRegistration ---------------------------------------------------

(defn make-predicate-registration
  [{:keys [namespace name severity kind scope message-template predicate applies-to]}]
  (cond-> {:namespace namespace, :name name
           :severity severity
           :kind kind
           :scope (or scope :scope/model)
           :message-template (or message-template "")
           :predicate predicate}
    applies-to (assoc :applies-to applies-to)))

;; -- RendererRegistration ----------------------------------------------------

(defn make-renderer-registration
  [{:keys [tag node-treatment sidebar-treatment edge-treatment layout-hint]}]
  (cond-> {:tag tag}
    node-treatment    (assoc :node-treatment node-treatment)
    sidebar-treatment (assoc :sidebar-treatment sidebar-treatment)
    edge-treatment    (assoc :edge-treatment edge-treatment)
    layout-hint       (assoc :layout-hint layout-hint)))

;; -- Malli schemas ------------------------------------------------------------

(def TagRef
  [:map [:namespace :string] [:name :string]])

(def TagTarget
  [:multi {:dispatch :case}
   [:target/primitive [:map [:case [:= :target/primitive]] [:id :string]]]
   [:target/edge      [:map [:case [:= :target/edge]] [:edge-identity :any]]]
   [:target/substrate [:map [:case [:= :target/substrate]]
                       [:container :string]
                       [:path [:vector [:map [:slot :string] [:key {:optional true} :string]]]]]]])

(def RelationalSpec
  [:map
   [:endpoints [:vector :string]]
   [:symmetry [:enum :directed :symmetric]]
   [:canonical-side {:optional true} :string]
   [:coherence-query {:optional true} :any]])

(def AppliesTo
  [:enum :target/container :target/actor :target/behaviour :target/rule
   :target/boundary :target/operation :target/intent :target/clause :target/event
   :target/edge :target/substrate])

(def TagDefinition
  [:map
   [:namespace :string]
   [:name :string]
   [:applies-to AppliesTo]
   [:payload-schema {:optional true} t/Type]
   [:parent-tag {:optional true} TagRef]
   [:relational {:optional true} RelationalSpec]])

(def TagApplication
  [:map
   [:tag TagRef]
   [:target TagTarget]
   [:payload :map]])

(def PredicateRegistration
  [:map
   [:namespace :string]
   [:name :string]
   [:severity [:enum :error :warning]]
   [:kind :string]
   [:scope [:enum :scope/model :scope/tag]]
   [:message-template :string]
   [:predicate :any]
   [:applies-to {:optional true} TagRef]])

(def RendererRegistration
  [:map
   [:tag TagRef]
   [:node-treatment    {:optional true} :any]
   [:sidebar-treatment {:optional true} :any]
   [:edge-treatment    {:optional true} :any]
   [:layout-hint       {:optional true} :any]])
```

- [ ] **Step 6.4: Run tests; expect all to pass**

```bash
clj -M:test -n fukan.model.vocabulary-test
```

- [ ] **Step 6.5: Commit**

```bash
git add src/fukan/model/vocabulary.clj test/fukan/model/vocabulary_test.clj
git commit -m "$(cat <<'EOF'
feat(model): vocabulary mechanism (Tag*, Predicate*, Renderer* registrations)

Implements MODEL.md §5.2–§5.5. Tag inheritance v0 semantics (V9):
payload-schema extension + tag-presence implication. Predicate-language
content stays opaque until Plan 4; renderer treatments stay opaque
until Plan 6.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Projection vocab v0 — Artifact ontology

**Files:**
- Create: `src/fukan/model/artifact.clj`
- Test: `test/fukan/model/artifact_test.clj`
- Reference: [MODEL.md §7.2–§7.4](../MODEL.md#72-v0-artifact-ontology-per-p4)

V0 Artifact ontology: `Artifact = Code(language, sub)` where `sub = Function(qualified_name, source_location?) | DataStructure(qualified_name, source_location?)`. `Infra` and `Documentation` cases come back with their producing analyzers (§10.1, §10.5).

Identity per case (per §7.3): `(language, qualified-name)`. `source-location` is non-identity.

`projection_kind` enum (per §7.4): `:projection-kind/rule | :projection-kind/operation | :projection-kind/invariant | :projection-kind/schema | :projection-kind/test`. Already referenced from `relations.clj`; this module defines the enum and the artifact-side data.

- [ ] **Step 7.1: Write the failing test file**

Create `test/fukan/model/artifact_test.clj`:

```clojure
(ns fukan.model.artifact-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.artifact :as a]
            [malli.core :as m]))

(deftest code-function
  (let [art (a/make-code-function "clojure" "fukan.auth/process-submission"
                                  {:file "src/fukan/auth.clj" :line 42})]
    (is (= :artifact/code (:case art)))
    (is (= "clojure" (:language art)))
    (is (= :code/function (get-in art [:sub :case])))
    (is (= "fukan.auth/process-submission" (get-in art [:sub :qualified-name])))
    (is (m/validate a/Artifact art))))

(deftest code-data-structure
  (let [art (a/make-code-data-structure "clojure" "fukan.orders/Order")]
    (is (= :code/data-structure (get-in art [:sub :case])))
    (is (m/validate a/Artifact art))))

(deftest identity-per-case
  (let [a1 (a/make-code-function "clojure" "fukan.auth/x" {:file "a.clj"})
        a2 (a/make-code-function "clojure" "fukan.auth/x" {:file "b.clj"})]
    (is (= (a/artifact-identity a1) (a/artifact-identity a2))
        "source-location is non-identity")))

(deftest identity-discriminates-by-case
  (let [f  (a/make-code-function       "clojure" "ns/X" {:file "x.clj"})
        ds (a/make-code-data-structure "clojure" "ns/X")]
    (is (not= (a/artifact-identity f) (a/artifact-identity ds))
        "Function vs DataStructure share qualified-name but differ on case")))

(deftest identity-discriminates-by-language
  (let [a (a/make-code-function "clojure"    "ns/X")
        b (a/make-code-function "typescript" "ns/X")]
    (is (not= (a/artifact-identity a) (a/artifact-identity b)))))

(deftest projection-kinds-enum
  (is (= #{:projection-kind/rule :projection-kind/operation
            :projection-kind/invariant :projection-kind/schema
            :projection-kind/test}
         a/projection-kinds)))
```

- [ ] **Step 7.2: Run; see failures**

```bash
clj -M:test -n fukan.model.artifact-test
```

- [ ] **Step 7.3: Implement `src/fukan/model/artifact.clj`**

```clojure
(ns fukan.model.artifact
  "V0 projection vocabulary — Artifact ontology and projection_kind enum
   (MODEL.md §7.2–§7.4).

   V0 commits one category: Code, with two leaf cases — Function and
   DataStructure. Infra and Documentation cases come back with their producing
   analyzers (§10.1, §10.5).

   Identity per §7.3: (language, qualified-name). source-location is
   non-identity."
  (:require [malli.core :as m]))

(def projection-kinds
  #{:projection-kind/rule :projection-kind/operation
    :projection-kind/invariant :projection-kind/schema
    :projection-kind/test})

(defn make-code-function
  ([language qualified-name] (make-code-function language qualified-name nil))
  ([language qualified-name source-location]
   (cond-> {:case :artifact/code, :language language
            :sub {:case :code/function, :qualified-name qualified-name}}
     source-location (assoc-in [:sub :source-location] source-location))))

(defn make-code-data-structure
  ([language qualified-name] (make-code-data-structure language qualified-name nil))
  ([language qualified-name source-location]
   (cond-> {:case :artifact/code, :language language
            :sub {:case :code/data-structure, :qualified-name qualified-name}}
     source-location (assoc-in [:sub :source-location] source-location))))

(defn artifact-identity
  "(case-discriminator, language, qualified-name) per §7.3. Comparators
   dispatch on case (per P3) — different cases get different identity tuples."
  [artifact]
  [(get-in artifact [:sub :case])
   (:language artifact)
   (get-in artifact [:sub :qualified-name])])

(def SourceLocation
  [:map [:file :string] [:line {:optional true} :int]])

(def Artifact
  [:multi {:dispatch :case}
   [:artifact/code
    [:map
     [:case [:= :artifact/code]]
     [:language :string]
     [:sub
      [:multi {:dispatch :case}
       [:code/function
        [:map [:case [:= :code/function]]
         [:qualified-name :string]
         [:source-location {:optional true} SourceLocation]]]
       [:code/data-structure
        [:map [:case [:= :code/data-structure]]
         [:qualified-name :string]
         [:source-location {:optional true} SourceLocation]]]]]]]])
```

- [ ] **Step 7.4: Run; expect pass**

```bash
clj -M:test -n fukan.model.artifact-test
```

- [ ] **Step 7.5: Commit**

```bash
git add src/fukan/model/artifact.clj test/fukan/model/artifact_test.clj
git commit -m "$(cat <<'EOF'
feat(model): v0 projection vocab — Code Artifact ontology + projection_kind

Implements MODEL.md §7.2–§7.4. Code(Function|DataStructure) with
(language, qualified-name) identity. Infra and Documentation cases stay
seamed (§10.1, §10.5).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Model record + construction API

**Files:**
- Create: `src/fukan/model/build.clj`
- Test: `test/fukan/model/build_test.clj`
- Reference: [MODEL.md §3](../MODEL.md#3-the-kernel) (whole-of-kernel composition), [§5.6](../MODEL.md#56-cross-cutting-commitments), [§7](../MODEL.md#7-the-projection-vocabulary-output-side)

The Model is the substrate's top-level container:

```
Model = {
  :primitives    Map<PrimitiveId, Primitive>
  :edges         List<Edge>
  :tag-defs      List<TagDefinition>
  :tag-apps      List<TagApplication>
  :predicates    List<PredicateRegistration>
  :renderers     List<RendererRegistration>
  :artifacts     Map<ArtifactIdentity, Artifact>
}
```

Fixture-only construction API:

- `empty-model` — returns the empty Model record.
- `add-primitive` / `add-edge` / `add-tag-definition` / `add-tag-application` / `add-predicate` / `add-renderer` / `add-artifact` — return the new Model.
- `get-primitive` / `edges-by-kind` / `edges-from` / `edges-to` — Plan-1 query primitives covering the basic fixture-test needs.

Invariants validated at construction (Plan 1 scope):

- Primitive ids unique.
- Edge `:from` / `:to` endpoint kinds compatible with the relation's allowed endpoints (Container endpoints check against `(get-primitive m id)` → `:kind`; Field substrate addresses are valid against `(:fields container)`).
- Tag-application target ids resolve to real primitives or edge identities present in the Model.
- Artifact identity is unique.

> Cross-altitude reference rules ([K23/K24](../DECISIONS.md#altitudes)) are *not* enforced at primitive-construction time — they live in Plan 3's Phase 4 sub-phases. Plan 1's invariants are purely about identity uniqueness and endpoint-kind compatibility.

- [ ] **Step 8.1: Write the failing test file**

Create `test/fukan/model/build_test.clj`:

```clojure
(ns fukan.model.build-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.build :as b]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.expression :as e]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]
            [fukan.model.artifact :as a]
            [malli.core :as m]))

(deftest empty-model
  (let [m (b/empty-model)]
    (is (m/validate b/Model m))
    (is (zero? (count (:primitives m))))
    (is (zero? (count (:edges m))))))

(deftest add-primitive
  (let [m (-> (b/empty-model)
              (b/add-primitive (p/make-container {:id "order" :label "Order"})))]
    (is (= 1 (count (:primitives m))))
    (is (= :primitive/container (:kind (b/get-primitive m "order"))))))

(deftest duplicate-primitive-id-rejected
  (let [c1 (p/make-container {:id "order" :label "Order"})
        c2 (p/make-container {:id "order" :label "OrderTwo"})
        m  (b/add-primitive (b/empty-model) c1)]
    (is (thrown? Exception (b/add-primitive m c2)))))

(deftest add-edge-with-valid-endpoints
  (let [order  (p/make-container {:id "order" :label "Order"
                                  :fields [(p/make-field "total"
                                                         (t/make-scalar "Integer")
                                                         false)]})
        rule   (p/make-rule {:id "rule-1" :label "AddOne"})
        edge   (r/make-edge :relation/writes
                            (r/primitive-ref "rule-1")
                            (r/substrate-address "order" [{:slot "field" :key "total"}]))
        m (-> (b/empty-model)
              (b/add-primitive order)
              (b/add-primitive rule)
              (b/add-edge edge))]
    (is (= 1 (count (:edges m))))
    (is (= [edge] (b/edges-from m (r/primitive-ref "rule-1"))))
    (is (= [edge] (b/edges-by-kind m :relation/writes)))))

(deftest add-edge-rejects-unknown-endpoint
  (let [edge (r/make-edge :relation/uses
                          (r/primitive-ref "ghost-a")
                          (r/primitive-ref "ghost-b"))]
    (is (thrown? Exception (b/add-edge (b/empty-model) edge)))))

(deftest multi-edge-allowed-with-distinct-identifying-metadata
  (let [a (p/make-container {:id "a" :label "A"})
        b (p/make-container {:id "b" :label "B"})
        from (r/primitive-ref "a")
        to   (r/primitive-ref "b")
        e1 (r/make-edge :relation/projects from to
              {:projection-kind :projection-kind/rule})
        e2 (r/make-edge :relation/projects from to
              {:projection-kind :projection-kind/test})
        m (-> (b/empty-model)
              (b/add-primitive a) (b/add-primitive b)
              (b/add-edge e1) (b/add-edge e2))]
    (is (= 2 (count (:edges m)))
        "Different :projection-kind on `projects` ⇒ two distinct edges")))

(deftest duplicate-edge-collapses
  (testing "Same identity ⇒ one edge regardless of non-identifying metadata"
    (let [a (p/make-container {:id "a" :label "A"})
          b (p/make-container {:id "b" :label "B"})
          e1 (r/make-edge :relation/uses (r/primitive-ref "a") (r/primitive-ref "b")
                          {:source-file "x.allium"})
          e2 (r/make-edge :relation/uses (r/primitive-ref "a") (r/primitive-ref "b")
                          {:source-file "y.allium"})
          m (-> (b/empty-model)
                (b/add-primitive a) (b/add-primitive b)
                (b/add-edge e1) (b/add-edge e2))]
      (is (= 1 (count (:edges m)))))))

(deftest tag-definition-application
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Module" :applies-to :target/container})
        m  (-> (b/empty-model)
               (b/add-primitive (p/make-container {:id "auth" :label "Auth"}))
               (b/add-tag-definition td)
               (b/add-tag-application
                 (v/make-tag-application
                   {:tag {:namespace "Allium" :name "Module"}
                    :target {:case :target/primitive :id "auth"}})))]
    (is (= 1 (count (:tag-defs m))))
    (is (= 1 (count (:tag-apps m))))))

(deftest tag-application-rejects-missing-target
  (let [td (v/make-tag-definition
             {:namespace "Allium" :name "Module" :applies-to :target/container})
        m (-> (b/empty-model) (b/add-tag-definition td))]
    (is (thrown? Exception
                 (b/add-tag-application m
                   (v/make-tag-application
                     {:tag {:namespace "Allium" :name "Module"}
                      :target {:case :target/primitive :id "ghost"}}))))))

(deftest artifact-registry-by-identity
  (let [m (-> (b/empty-model)
              (b/add-artifact (a/make-code-function "clojure" "ns/foo")))]
    (is (= 1 (count (:artifacts m))))
    (is (some? (b/get-artifact m [:code/function "clojure" "ns/foo"])))))

(deftest artifact-duplicate-identity-rejected
  (let [m (b/add-artifact (b/empty-model) (a/make-code-function "clojure" "ns/foo"))]
    (is (thrown? Exception
                 (b/add-artifact m (a/make-code-function "clojure" "ns/foo"))))))

(deftest validates-against-Model-schema
  (let [m (-> (b/empty-model)
              (b/add-primitive (p/make-container {:id "x" :label "X"}))
              (b/add-primitive (p/make-actor     {:id "y" :label "Y"})))]
    (is (m/validate b/Model m))))
```

- [ ] **Step 8.2: Run; see failures**

```bash
clj -M:test -n fukan.model.build-test
```

- [ ] **Step 8.3: Implement `src/fukan/model/build.clj`**

```clojure
(ns fukan.model.build
  "Model record + fixture-only construction API.

   The Model is the substrate's top-level container. Plan 1 ships an in-process
   API for building Models from primitive constructors directly — analyzers
   land in Plans 2/3/5.

   Plan-1 invariants enforced at construction time:
     - Primitive ids are unique
     - Edge endpoint primitives exist in the Model
     - Field substrate addresses point at real Fields on the named Container
     - Tag application targets exist
     - Artifact identities are unique

   Cross-altitude reference rules (K23/K24) and pipeline validation
   (Phase 4 sub-phases) land in Plan 3."
  (:require [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.artifact :as a]
            [fukan.model.vocabulary :as v]
            [malli.core :as m]))

(defn empty-model
  "An empty Model — no primitives, no edges, no vocabulary, no artifacts."
  []
  {:primitives {}
   :edges      []
   :tag-defs   []
   :tag-apps   []
   :predicates []
   :renderers  []
   :artifacts  {}})

;; -- Primitive registry ------------------------------------------------------

(defn get-primitive [model id] (get-in model [:primitives id]))

(defn add-primitive
  "Add a primitive to the Model. Throws if its id collides with an existing
   primitive."
  [model primitive]
  (let [id (:id primitive)]
    (when (get-primitive model id)
      (throw (ex-info "Duplicate primitive id" {:id id})))
    (assoc-in model [:primitives id] primitive)))

;; -- Edge registry -----------------------------------------------------------

(defn- endpoint-resolves? [model endpoint]
  (case (:case endpoint)
    :endpoint/primitive (some? (get-primitive model (:id endpoint)))
    :endpoint/substrate
    (let [{:keys [container path]} endpoint
          c (get-primitive model container)
          [seg & rest-path] path]
      (and (some? c)
           ;; V0 trivial Field case only
           (= (:slot seg) "field")
           (some #(= (:name %) (:key seg)) (:fields c))
           ;; deeper paths (parameter etc.) are kernel-shaped but not v0-validated
           (or (empty? rest-path) true)))))

(defn add-edge
  "Add an edge to the Model. Validates endpoints resolve to real primitives /
   substrate addresses. Multi-edges allowed iff identity differs (per §4.4);
   identity collisions are no-ops (preserve the existing edge)."
  [model edge]
  (when-not (endpoint-resolves? model (:from edge))
    (throw (ex-info "Unknown :from endpoint" {:edge edge})))
  (when-not (endpoint-resolves? model (:to edge))
    (throw (ex-info "Unknown :to endpoint" {:edge edge})))
  (let [existing-id-set (into #{} (map r/edge-identity (:edges model)))]
    (if (existing-id-set (r/edge-identity edge))
      model
      (update model :edges conj edge))))

(defn edges-by-kind [model relation-kind]
  (filter #(= relation-kind (:kind %)) (:edges model)))

(defn edges-from [model endpoint]
  (filter #(= endpoint (:from %)) (:edges model)))

(defn edges-to [model endpoint]
  (filter #(= endpoint (:to %)) (:edges model)))

;; -- Vocabulary --------------------------------------------------------------

(defn add-tag-definition [model td]
  (update model :tag-defs conj td))

(defn add-tag-application
  "Append a TagApplication. Validates the target resolves to a real primitive
   when the target is :target/primitive. Edge / substrate target resolution
   stays Plan-2-or-later concern (the analyzer is the only realistic source)."
  [model ta]
  (when (= :target/primitive (get-in ta [:target :case]))
    (when-not (get-primitive model (get-in ta [:target :id]))
      (throw (ex-info "TagApplication target not found"
                      {:target (:target ta)}))))
  (update model :tag-apps conj ta))

(defn add-predicate [model pr] (update model :predicates conj pr))

(defn add-renderer [model rr] (update model :renderers conj rr))

;; -- Artifacts ---------------------------------------------------------------

(defn get-artifact [model identity-tuple]
  (get-in model [:artifacts identity-tuple]))

(defn add-artifact [model artifact]
  (let [id (a/artifact-identity artifact)]
    (when (get-artifact model id)
      (throw (ex-info "Duplicate artifact identity" {:identity id})))
    (assoc-in model [:artifacts id] artifact)))

;; -- Malli schema ------------------------------------------------------------

(def Model
  [:map
   [:primitives [:map-of :string p/Primitive]]
   [:edges      [:vector r/Edge]]
   [:tag-defs   [:vector v/TagDefinition]]
   [:tag-apps   [:vector v/TagApplication]]
   [:predicates [:vector v/PredicateRegistration]]
   [:renderers  [:vector v/RendererRegistration]]
   [:artifacts  [:map-of :any a/Artifact]]])
```

- [ ] **Step 8.4: Run tests; expect all to pass**

```bash
clj -M:test -n fukan.model.build-test
```

- [ ] **Step 8.5: Commit**

```bash
git add src/fukan/model/build.clj test/fukan/model/build_test.clj
git commit -m "$(cat <<'EOF'
feat(model): Model record + fixture-only construction API

Top-level Model holds primitives, edges, vocabulary, artifacts.
Construction-time invariants: unique ids, endpoints resolve,
tag-application targets exist, artifact identities unique. Cross-
altitude reference rules and pipeline validation come in Plan 3.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: REPL wiring & end-to-end smoke

**Files:**
- Modify: `src/fukan/infra/model.clj`
- Test: extend `test/fukan/model/build_test.clj` (or add `test/fukan/smoke_test.clj`)

The point of Task 9: prove that **the imperative shell speaks the new substrate**. `(infra-model/load-model "src")` now returns the new Model record; `(get-model)` exposes it; the REPL prints sensible status; the placeholder HTTP page still renders.

A small fixture Model — three primitives, two edges, one tag application — gives the REPL something visible without an analyzer.

- [ ] **Step 9.1: Update `src/fukan/infra/model.clj` to use `fukan.model.build/empty-model`**

Replace the body with:

```clojure
(ns fukan.infra.model
  "Model lifecycle management.
   Holds a Model value (per fukan.model.build/Model) and offers load /
   refresh / get. In Plan 1 the loader returns a small fixture Model;
   Plans 2/3/5 swap in real analyzers without changing this API."
  (:require [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.relations :as r]
            [fukan.model.type :as t]
            [fukan.model.vocabulary :as v]))

(defonce ^:private state (atom {:model nil :src nil}))

(defn- fixture-model
  "A trivial Model that exercises the substrate end-to-end without an
   analyzer: one Container with one Field, one Rule, one writes edge, and
   one Allium::Module tag application."
  []
  (let [order-c (p/make-container
                  {:id "order" :label "Order"
                   :fields [(p/make-field "total" (t/make-scalar "Integer") false)]})
        rule   (p/make-rule
                  {:id "increment-total" :label "IncrementTotal"})
        td     (v/make-tag-definition
                  {:namespace "Allium" :name "Entity"
                   :applies-to :target/container})
        ta     (v/make-tag-application
                  {:tag {:namespace "Allium" :name "Entity"}
                   :target {:case :target/primitive :id "order"}})]
    (-> (build/empty-model)
        (build/add-primitive order-c)
        (build/add-primitive rule)
        (build/add-tag-definition td)
        (build/add-tag-application ta)
        (build/add-edge
          (r/make-edge :relation/writes
                       (r/primitive-ref "increment-total")
                       (r/substrate-address "order"
                                            [{:slot "field" :key "total"}]))))))

(defn load-model
  "Build (or reload) the Model for the given src path. Plan 1 ignores `src`
   and returns a tiny fixture; later plans wire real analyzers through here."
  [src]
  (println "Loading model from" src "(Plan 1 fixture only)")
  (let [m (fixture-model)]
    (reset! state {:model m :src src})
    (println "Loaded:" (count (:primitives m)) "primitives,"
                       (count (:edges m)) "edges,"
                       (count (:tag-apps m)) "tag applications")
    m))

(defn get-model [] (:model @state))

(defn get-src [] (:src @state))

(defn refresh-model []
  (if-let [src (:src @state)]
    (load-model src)
    (do (println "No src path set. Use load-model first.") nil)))
```

- [ ] **Step 9.2: Add a smoke test**

Append to `test/fukan/model/build_test.clj` (or add a new file `test/fukan/smoke_test.clj`):

```clojure
(deftest fixture-loader-end-to-end
  (testing "infra-model/load-model returns a Model that validates"
    (let [m (require 'fukan.infra.model)
          load (resolve 'fukan.infra.model/load-model)
          model (load "src")]
      (is (m/validate b/Model model))
      (is (some? (b/get-primitive model "order")))
      (is (= 1 (count (b/edges-by-kind model :relation/writes)))))))
```

- [ ] **Step 9.3: Run the full test suite**

```bash
clj -M:test
```

Expected output (representative):

```
Running tests in #{"test"}

Testing fukan.model.artifact-test
Testing fukan.model.build-test
Testing fukan.model.effect-test
Testing fukan.model.expression-test
Testing fukan.model.primitives-test
Testing fukan.model.relations-test
Testing fukan.model.type-test
Testing fukan.model.vocabulary-test

Ran N tests containing M assertions. 0 failures, 0 errors.
```

- [ ] **Step 9.4: Run the REPL smoke test by hand**

Open a REPL and verify the loop:

```bash
clj -M:dev:nrepl &
# In another terminal:
clj -M:dev -e "(require '[user :as u]) (u/go {}) (u/status) (u/halt)"
```

Expected (the `go` step prints the fixture-load summary; `status` prints `Model: 2 primitives, 1 edges (src: src)`).

- [ ] **Step 9.5: Verify the placeholder HTTP page**

```bash
clj -M:run --src src --port 8080 &
sleep 2
curl -s http://localhost:8080/ | grep 'kernel substrate v0'
kill %1 2>/dev/null
```

Expected: a line of HTML containing `kernel substrate v0 is live`.

- [ ] **Step 9.6: Commit**

```bash
git add src/fukan/infra/model.clj test/fukan/model/build_test.clj
git commit -m "$(cat <<'EOF'
feat(infra): wire imperative shell to new kernel substrate

infra/model loads a fixture Model built from the Plan-1 construction API.
REPL helpers print substrate-aware status; HTTP placeholder unchanged.
Closes Plan 1 (kernel substrate).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

After completing Task 9, verify before declaring Plan 1 done:

1. **Spec coverage.** Skim [MODEL.md §3, §4, §5, §7](../MODEL.md):
   - §3.1 — nine primitives present in `fukan.model.primitives`? ✓ (Container, Actor, Behaviour, Rule, Boundary, Operation, Intent, Clause, Event)
   - §3.2 — Field, Parameter, Definition, RuleBody value records? ✓
   - §3.3 — Type with six cases? ✓
   - §3.4 / §3.5 — cardinality patterns / asymmetries encoded as schema shape? ✓
   - §3.6 — derived (External, View / Signal / Call protocols) explicitly *not* present as substrate? ✓ (no kernel primitives for them)
   - §3.7 — substrate principle followed (Rule's trigger is a relation, not substrate; Operation's signature is substrate; Effect substrate is per K29)? ✓
   - §3.8 — Expression + Environment + Effect implemented; canonicalisation pattern matcher stubbed for Plan 2? ✓
   - §4 — 13 relations enumerated, endpoint sum, edge identity with per-relation identifying-metadata? ✓
   - §5 — TagDefinition / TagApplication / PredicateRegistration / RendererRegistration with v0 inheritance semantics? ✓
   - §7 — v0 Code Artifact ontology + projection_kind enum? ✓
2. **Placeholder scan.** Re-grep the plan for placeholder phrases:

   ```bash
   grep -nE 'TBD|TODO|fill in|implement later|similar to' doc/plans/2026-05-18-kernel-substrate.md
   ```

   Expected: no matches outside of the explicit `:predicate :any` opaque slot (Plan 4) and the renderer treatment opaque slots (Plan 6).

3. **Type / name consistency.** Spot-check that:
   - `:relation/projects` accepts `:projection-kind` identifying metadata, and the enum values match between `relations.clj` and `artifact.clj` (`:projection-kind/rule | operation | invariant | schema | test`).
   - `Effect.target` and `Edge` endpoints both use the same Endpoint sum (`:endpoint/primitive | :endpoint/substrate`).
   - `Container.behaviour.rules` is a vector of rule ids; the analyzer (Plan 2) is responsible for cross-linking.
4. **Deferred items confirmed.**
   - `effect/canonicalise` returns `nil` (Plan 2 fills the four-pattern matcher).
   - `PredicateRegistration.predicate` is `:any` (Plan 4 fills the constraint AST).
   - Renderer treatments are `:any` (Plan 6 fills the rendering machinery).
   - Cross-altitude reference rules ([K23 / K24](../DECISIONS.md#altitudes)) are *not* enforced at primitive-construction time — they live in Plan 3 Phase 4 sub-phases.
5. **Full test suite green.**

   ```bash
   clj -M:test
   ```

6. **Server still boots; placeholder page renders.**

   ```bash
   clj -M:run --src src --port 8080
   ```

If any of the above fail, fix in place — do **not** start Plan 2 until the substrate is clean.
