# LLM Agent Surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a sandboxed Clojure eval surface over the existing Fukan daemon, plus a thin CLI, so LLM agents can query the loaded Model through a curated, layered API instead of reading source files.

**Architecture:** New HTTP endpoints `/agent/eval` and `/agent/status` on the existing Jetty/Ring server hand expressions to an SCI evaluator. SCI is locked to a frozen ns-map containing only `fukan.agent.api` (L0 Datalog + L1 Model probes + L2 composite views) and `fukan.agent.system` (status/refresh/help/source). A `.fukan/agent-views.clj` file in the target project provides accretable L2. A small Babashka CLI (`bin/fukan`) posts to the loopback endpoints. An `AGENTS.md` primer at the Fukan repo root teaches the mental model; live `(help)`/`(source)` cover the reference catalog.

**Tech Stack:** Clojure 1.11, SCI (`org.babashka/sci`), cognitect.test-runner, http-kit + reitit (existing), Babashka for the CLI binary, cheshire for JSON (existing).

**Spec:** [`doc/specs/2026-05-20-llm-agent-surface-design.md`](../specs/2026-05-20-llm-agent-surface-design.md)

---

## File Structure

### New files

```
src/fukan/agent/
  edb.clj             — Model → EDB tuples projection (predicate-form)
  query.clj           — Datalog DSL `[:find … :where …]` parser
  api.clj             — fukan.agent.api: L0 q + L1 probes + L2 views
  system.clj          — fukan.agent.system: status, refresh, help, source
  sci.clj             — SCI sandbox: bound ns map, evaluator, error envelope
  views_loader.clj    — .fukan/agent-views.clj discovery + load into SCI env

src/fukan/web/
  agent_handlers.clj  — Ring handlers for /agent/eval, /agent/status

bin/
  fukan               — Babashka CLI script (executable)

test/fukan/agent/
  edb_test.clj
  query_test.clj
  api_test.clj
  system_test.clj
  sci_test.clj
  views_loader_test.clj
  integration_test.clj

test/fukan/fixtures/agent/
  small_model.edn                 — hand-built fixture Model
  agent-views-good.clj            — load-OK fixture
  agent-views-syntax-error.clj    — partial-load fixture
  agent-views-unbound.clj         — partial-load fixture
  agent-views-conflict.clj        — name-conflict fixture (redefines `drift`)

AGENTS.md             — primer at repo root
```

### Modified files

```
deps.edn                       — add org.babashka/sci dependency; add :agent-cli alias
src/fukan/web/handler.clj      — wire /agent/eval and /agent/status into the router
```

### File responsibilities

- **`fukan.agent.edb`** owns Model→EDB only. No queries, no DSL. Output: `{predicate-keyword → #{[tuple …] …}}`.
- **`fukan.agent.query`** owns DSL parsing only. Pure data: `[:find …]` form → `{:find …, :where-atoms […], :in …}`. No evaluation.
- **`fukan.agent.api`** owns the agent-facing vocabulary. Calls into `edb` + `query` + the constraint evaluator under the hood. Houses L0 `q`, all L1 fns, and built-in L2 (`drift`, `neighborhood`). Carries var metadata used by `(help)`/`(source)` (docstrings, examples, layer keyword).
- **`fukan.agent.system`** owns operating Fukan: `status`, `refresh`, `help`, `source`. References `agent.api` for introspection only; no Model queries.
- **`fukan.agent.sci`** owns the sandbox. Builds the SCI context, applies the timeout, formats the error envelope, decides what to return on success. Single entry point: `(eval-string s)` → success or error envelope.
- **`fukan.agent.views_loader`** owns the `.fukan/agent-views.clj` lifecycle. Reads the file, evaluates inside SCI with `def` permitted, captures per-form errors, returns load-report.
- **`fukan.web.agent-handlers`** owns the HTTP edge: parse request, call `sci/eval-string` or `system/status`, format JSON response. No business logic.

---

## Task list

### Phase 0 — Setup

#### Task 1: Add SCI dependency

**Files:**
- Modify: `deps.edn`

- [ ] **Step 1: Add SCI to `:deps`**

Edit `deps.edn`. In the top-level `:deps` map, after the `instaparse` entry, add:

```clojure
        org.babashka/sci {:mvn/version "0.8.41"}
```

- [ ] **Step 2: Verify deps resolve**

Run: `clojure -P`
Expected: no errors; SCI artifact pulled.

- [ ] **Step 3: Verify SCI loads**

Run: `clojure -e "(require '[sci.core :as sci]) (sci/eval-string \"(+ 1 2)\")"`
Expected: `3`

- [ ] **Step 4: Commit**

```bash
git add deps.edn
git commit -m "build: add SCI dependency for agent eval sandbox"
```

---

### Phase 1 — Model → EDB projection

#### Task 2: Define fixture Model for tests

**Files:**
- Create: `test/fukan/fixtures/agent/small_model.edn`

- [ ] **Step 1: Write the fixture**

Create `test/fukan/fixtures/agent/small_model.edn` with a tiny hand-built Model that exercises the shapes downstream tests need. Includes primitives of kinds `:primitive/behaviour`, `:primitive/boundary`, `:primitive/container`; edges including a `:projects` edge with `:validity :absent`; one artifact:

```clojure
{:primitives
 {"container:hex/core"
  {:kind :primitive/container
   :id "container:hex/core"
   :label "hex/core"
   :description "Core module of hex"}

  "behaviour:hex/core/r-mint"
  {:kind :primitive/behaviour
   :id "behaviour:hex/core/r-mint"
   :label "mint"
   :rules ["intent:mint"]}

  "behaviour:hex/core/r-burn"
  {:kind :primitive/behaviour
   :id "behaviour:hex/core/r-burn"
   :label "burn"
   :rules ["intent:burn"]}

  "boundary:hex/core/api"
  {:kind :primitive/boundary
   :id "boundary:hex/core/api"
   :label "api"
   :operations ["op:mint" "op:burn"]}}

 :edges
 [{:kind :projects
   :from {:endpoint/primitive "behaviour:hex/core/r-mint"}
   :to   nil
   :validity :absent
   :projection-kind :clojure
   :expected-address "hex.core/mint"}
  {:kind :projects
   :from {:endpoint/primitive "behaviour:hex/core/r-burn"}
   :to   {:endpoint/artifact ["clojure" "hex.core" "burn"]}
   :validity :valid
   :projection-kind :clojure}
  {:kind :owns
   :from {:endpoint/primitive "container:hex/core"}
   :to   {:endpoint/primitive "behaviour:hex/core/r-mint"}}
  {:kind :owns
   :from {:endpoint/primitive "container:hex/core"}
   :to   {:endpoint/primitive "behaviour:hex/core/r-burn"}}
  {:kind :owns
   :from {:endpoint/primitive "container:hex/core"}
   :to   {:endpoint/primitive "boundary:hex/core/api"}}]

 :artifacts
 {["clojure" "hex.core" "burn"]
  {:kind :artifact/clojure-fn
   :id ["clojure" "hex.core" "burn"]
   :namespace "hex.core"
   :name "burn"}}

 :tag-apps []}
```

- [ ] **Step 2: Write a smoke test that loads it**

Create `test/fukan/agent/edb_test.clj`:

```clojure
(ns fukan.agent.edb-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(deftest fixture-loads
  (testing "fixture model parses and has expected shape"
    (let [m (load-fixture)]
      (is (= 4 (count (:primitives m))))
      (is (= 5 (count (:edges m))))
      (is (= 1 (count (:artifacts m)))))))
```

Note: the fixture lives under `test/` so `clojure.java.io/resource` needs the test path. The `:test` alias already adds `test` to `:extra-paths`.

- [ ] **Step 3: Run the test**

Run: `clojure -X:test :includes '[:fukan.agent.edb-test]'`

(If the project uses cognitect test-runner the simpler form is `clojure -M:test -n fukan.agent.edb-test`.)

Expected: 1 test, 3 assertions, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add test/fukan/fixtures/agent/small_model.edn test/fukan/agent/edb_test.clj
git commit -m "test(agent): fixture Model for agent surface tests"
```

#### Task 3: Implement Model→EDB projection

**Files:**
- Create: `src/fukan/agent/edb.clj`
- Modify: `test/fukan/agent/edb_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/fukan/agent/edb_test.clj`:

```clojure
(deftest model->edb-projects-primitives
  (testing "every primitive becomes a :primitive/kind tuple"
    (let [m   (load-fixture)
          edb (fukan.agent.edb/model->edb m)]
      (is (contains? edb :primitive/kind))
      (is (contains? (get edb :primitive/kind)
                     ["behaviour:hex/core/r-mint" :primitive/behaviour]))
      (is (= 4 (count (get edb :primitive/kind)))))))

(deftest model->edb-projects-edges
  (testing "every edge becomes a :relation/kind tuple"
    (let [m   (load-fixture)
          edb (fukan.agent.edb/model->edb m)]
      (is (contains? edb :relation/kind))
      (is (some #(= [:projects (-> % first second) (-> % second)]
                    [(first %) (second %) (nth % 2)])
                (get edb :relation/kind))))))

(deftest model->edb-projects-validity
  (testing "projects edges carry :validity tuples"
    (let [m   (load-fixture)
          edb (fukan.agent.edb/model->edb m)]
      (is (contains? edb :relation/validity)))))
```

Add the require at the top of the ns:

```clojure
[fukan.agent.edb :as edb]
```

(adjust the test calls to use the alias).

- [ ] **Step 2: Run; expect failure (ns missing)**

Run: `clojure -M:test -n fukan.agent.edb-test`
Expected: namespace `fukan.agent.edb` not found.

- [ ] **Step 3: Implement `fukan.agent.edb/model->edb`**

Create `src/fukan/agent/edb.clj`:

```clojure
(ns fukan.agent.edb
  "Project a Model into an EDB suitable for the constraint Datalog evaluator.

   EDB shape: {predicate-keyword #{[tuple-arg ...] ...}}.

   Predicates currently emitted:
     :primitive/kind        [id kind]
     :primitive/label       [id label]
     :primitive/owner       [id owner-id]            ; via :owns edges
     :relation/kind         [edge-id kind from-id to-id]
     :relation/validity     [edge-id validity]
     :relation/projection-kind [edge-id projection-kind]
     :artifact/kind         [artifact-id kind]")

(defn- endpoint->id [endpoint]
  (cond
    (nil? endpoint) nil
    (:endpoint/primitive endpoint) (:endpoint/primitive endpoint)
    (:endpoint/artifact  endpoint) (str "artifact:" (vec (:endpoint/artifact endpoint)))
    :else nil))

(defn- edge-id [edge idx]
  (str "edge:" idx))

(defn- primitive-tuples [primitives]
  (let [kinds  (into #{} (map (fn [[id p]] [id (:kind p)]) primitives))
        labels (into #{}
                     (keep (fn [[id p]] (when (:label p) [id (:label p)])))
                     primitives)]
    {:primitive/kind  kinds
     :primitive/label labels}))

(defn- owner-tuples [edges]
  (->> edges
       (filter #(= :owns (:kind %)))
       (map (fn [e] [(endpoint->id (:to e)) (endpoint->id (:from e))]))
       (filter #(and (first %) (second %)))
       (into #{})))

(defn- edge-tuples [edges]
  (let [indexed (map-indexed (fn [i e] [(edge-id e i) e]) edges)
        kind   (into #{}
                     (map (fn [[id e]]
                            [id (:kind e) (endpoint->id (:from e)) (endpoint->id (:to e))]))
                     indexed)
        valid  (into #{}
                     (keep (fn [[id e]] (when-let [v (:validity e)] [id v])))
                     indexed)
        pk     (into #{}
                     (keep (fn [[id e]]
                             (when-let [pk (:projection-kind e)] [id pk])))
                     indexed)]
    {:relation/kind            kind
     :relation/validity        valid
     :relation/projection-kind pk}))

(defn- artifact-tuples [artifacts]
  (into #{}
        (map (fn [[id a]] [(str "artifact:" (vec id)) (:kind a)]))
        artifacts))

(defn model->edb
  "Project a Model value into an EDB map."
  [model]
  (merge (primitive-tuples (:primitives model))
         {:primitive/owner (owner-tuples (:edges model))}
         (edge-tuples (:edges model))
         {:artifact/kind (artifact-tuples (:artifacts model))}))
```

- [ ] **Step 4: Run tests; expect pass**

Run: `clojure -M:test -n fukan.agent.edb-test`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/edb.clj test/fukan/agent/edb_test.clj
git commit -m "feat(agent): Model→EDB projection for Datalog query surface"
```

---

### Phase 2 — Datalog DSL parser

#### Task 4: Parse `[:find … :where …]` form

**Files:**
- Create: `src/fukan/agent/query.clj`
- Create: `test/fukan/agent/query_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/fukan/agent/query_test.clj`:

```clojure
(ns fukan.agent.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.agent.query :as q]))

(deftest parse-simple-find-where
  (testing "single find var, single where atom"
    (let [parsed (q/parse '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])]
      (is (= [:?p] (:find parsed)))
      (is (= 1 (count (:where parsed))))
      (let [atm (first (:where parsed))]
        (is (= :primitive/kind (:predicate atm)))
        (is (= [:?p :primitive/behaviour] (:args atm)))))))

(deftest parse-multi-where
  (testing "multiple where atoms"
    (let [parsed (q/parse '[:find ?p ?m
                             :where
                               [?p :primitive/kind :primitive/behaviour]
                               [?p :primitive/owner ?m]])]
      (is (= [:?p :?m] (:find parsed)))
      (is (= 2 (count (:where parsed)))))))

(deftest parse-rejects-malformed
  (testing "missing :where raises"
    (is (thrown? clojure.lang.ExceptionInfo (q/parse '[:find ?p])))))
```

- [ ] **Step 2: Run; expect failure (ns missing)**

Run: `clojure -M:test -n fukan.agent.query-test`
Expected: namespace not found.

- [ ] **Step 3: Implement the parser**

Create `src/fukan/agent/query.clj`:

```clojure
(ns fukan.agent.query
  "Parse the agent-facing Datalog DSL into a small AST.

   DSL shape:
     [:find  <var-or-aggregation> ...
      :where <atom-vector> ...]

   AST shape:
     {:find  [:?var ...]
      :where [{:predicate <kw> :args [...]} ...]}")

(defn- variable? [x] (and (symbol? x) (.startsWith (name x) "?")))

(defn- ->var-key [sym]
  (keyword (name sym)))

(defn- parse-atom [v]
  (when-not (and (vector? v) (>= (count v) 2))
    (throw (ex-info "where atom must be a vector of at least 2 elements"
                    {:type :malformed-atom :form v})))
  (let [[subject predicate & rest-args] v]
    {:predicate predicate
     :args      (mapv (fn [a] (if (variable? a) (->var-key a) a))
                      (cons subject rest-args))}))

(defn- split-sections [form]
  (loop [remaining form section nil sections {}]
    (cond
      (empty? remaining) sections
      (#{:find :where :in} (first remaining))
      (recur (rest remaining) (first remaining) sections)
      :else
      (recur (rest remaining)
             section
             (update sections section (fnil conj []) (first remaining))))))

(defn parse
  "Parse a `[:find … :where …]` form."
  [form]
  (when-not (vector? form)
    (throw (ex-info "query must be a vector" {:form form})))
  (let [sections (split-sections form)
        find-clauses (or (:find sections)
                         (throw (ex-info "missing :find clause" {:form form})))
        where-clauses (or (:where sections)
                          (throw (ex-info "missing :where clause" {:form form})))]
    {:find  (mapv (fn [s] (if (variable? s) (->var-key s) s)) find-clauses)
     :where (mapv parse-atom where-clauses)
     :in    (mapv ->var-key (:in sections []))}))
```

- [ ] **Step 4: Run tests; expect pass**

Run: `clojure -M:test -n fukan.agent.query-test`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/query.clj test/fukan/agent/query_test.clj
git commit -m "feat(agent): Datalog DSL parser for agent query surface"
```

#### Task 5: Evaluate a parsed query against an EDB

**Files:**
- Modify: `src/fukan/agent/query.clj`
- Modify: `test/fukan/agent/query_test.clj`

- [ ] **Step 1: Write failing test**

Append to `query_test.clj`:

```clojure
(deftest evaluate-against-edb
  (testing "find primitives of kind :primitive/behaviour"
    (let [edb {:primitive/kind #{["behaviour:a" :primitive/behaviour]
                                  ["behaviour:b" :primitive/behaviour]
                                  ["container:x" :primitive/container]}}
          parsed (q/parse '[:find ?p
                            :where [?p :primitive/kind :primitive/behaviour]])
          rows (q/evaluate parsed edb)]
      (is (= 2 (count rows)))
      (is (every? #(string? (get % :?p)) rows)))))

(deftest evaluate-with-join
  (testing "join two atoms via shared variable"
    (let [edb {:primitive/kind  #{["b1" :primitive/behaviour]
                                   ["b2" :primitive/behaviour]
                                   ["c1" :primitive/container]}
               :primitive/owner #{["b1" "c1"]
                                   ["b2" "c1"]}}
          parsed (q/parse '[:find ?p ?m
                            :where
                              [?p :primitive/kind :primitive/behaviour]
                              [?p :primitive/owner ?m]])
          rows (q/evaluate parsed edb)]
      (is (= 2 (count rows)))
      (is (every? #(and (:?p %) (:?m %)) rows)))))
```

- [ ] **Step 2: Run; expect failure (`evaluate` undefined)**

Run: `clojure -M:test -n fukan.agent.query-test`
Expected: `Unable to resolve symbol: evaluate`.

- [ ] **Step 3: Implement `evaluate`**

Append to `src/fukan/agent/query.clj`:

```clojure
(defn- unify-arg [pattern-arg tuple-val binding]
  (cond
    (and (keyword? pattern-arg)
         (.startsWith (name pattern-arg) "?"))
    (if-let [existing (get binding pattern-arg)]
      (when (= existing tuple-val) binding)
      (assoc binding pattern-arg tuple-val))
    (= pattern-arg tuple-val) binding
    :else nil))

(defn- unify-tuple [args tuple binding]
  (reduce (fn [b [pa tv]]
            (if-let [b' (unify-arg pa tv b)]
              b'
              (reduced nil)))
          binding
          (map vector args tuple)))

(defn- match-atom [atm edb binding]
  (let [tuples (get edb (:predicate atm) #{})]
    (keep (fn [tup] (unify-tuple (:args atm) tup binding)) tuples)))

(defn evaluate
  "Evaluate a parsed query against an EDB. Returns a vector of result rows
   where each row is a map from :find var keyword → value."
  [parsed edb]
  (let [bindings (reduce (fn [bs atm]
                           (mapcat #(match-atom atm edb %) bs))
                         [{}]
                         (:where parsed))]
    (mapv (fn [b]
            (into {} (map (fn [v] [v (get b v)]) (:find parsed))))
          bindings)))
```

- [ ] **Step 4: Run tests; expect pass**

Run: `clojure -M:test -n fukan.agent.query-test`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/query.clj test/fukan/agent/query_test.clj
git commit -m "feat(agent): evaluate parsed queries against EDB"
```

> **Note on the constraint evaluator.** The spec says L0 reuses the constraint engine's Datalog AST. The above is a tighter agent-only evaluator that operates on the same EDB shape and the same conceptual query model, but skips stratification/aggregation features the agent surface doesn't need yet. Promoting agent queries to use `fukan.constraint.evaluator/query` directly is a post-MVP refactor; the EDB shape is identical, so the swap is mechanical when it's worth doing.

---

### Phase 3 — `fukan.agent.api`: L0 + L1 + L2

#### Task 6: Stub `fukan.agent.api` with `q` (L0)

**Files:**
- Create: `src/fukan/agent/api.clj`
- Create: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing test**

Create `test/fukan/agent/api_test.clj`:

```clojure
(ns fukan.agent.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.api :as api]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest q-finds-primitives-by-kind
  (testing "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]]) returns 2 rows"
    (let [rows (api/q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])]
      (is (= 2 (count rows))))))
```

- [ ] **Step 2: Run; expect failure**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: namespace not found.

- [ ] **Step 3: Implement L0 `q`**

Create `src/fukan/agent/api.clj`:

```clojure
(ns fukan.agent.api
  "The agent's layered Model interface. The ONLY namespace the SCI sandbox
   exposes alongside fukan.agent.system. See AGENTS.md for orientation;
   call (help) for the live catalog."
  (:require [fukan.agent.edb :as edb]
            [fukan.agent.query :as query]
            [fukan.infra.model :as infra-model]))

;; -- L0 Kernel ----------------------------------------------------------------

(defn ^{:agent/layer :L0
        :agent/doc "Datalog over the loaded Model. Form: [:find … :where …]."
        :agent/example "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])"}
  q
  "Evaluate a Datalog query against the loaded Model.
   Returns a vector of binding maps keyed by :find variables."
  [form]
  (let [m (or (infra-model/get-model)
              (throw (ex-info "no model loaded" {:type :model-not-loaded})))]
    (query/evaluate (query/parse form) (edb/model->edb m))))
```

- [ ] **Step 4: Run tests; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L0 — q Datalog kernel"
```

#### Task 7: L1 — `primitives` with kw-args filtering

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `api_test.clj`:

```clojure
(deftest primitives-all
  (testing "(primitives) returns the standard listing envelope"
    (let [r (api/primitives)]
      (is (= 4 (count (:rows r))))
      (is (= 4 (:total r)))
      (is (false? (:truncated? r)))
      (is (every? #(contains? % :id) (:rows r)))
      (is (every? #(contains? % :kind) (:rows r))))))

(deftest primitives-by-kind
  (testing "(primitives :kind :primitive/behaviour) filters"
    (let [r (api/primitives :kind :primitive/behaviour)]
      (is (= 2 (count (:rows r))))
      (is (every? #(= :primitive/behaviour (:kind %)) (:rows r))))))

(deftest primitives-truncation
  (testing ":truncated? true and :total N when limit exceeded"
    (let [r (api/primitives :limit 2)]
      (is (= 2 (count (:rows r))))
      (is (true? (:truncated? r)))
      (is (= 4 (:total r))))))
```

- [ ] **Step 2: Run; expect failure**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: `primitives` unresolved.

- [ ] **Step 3: Implement `primitives`**

Append to `src/fukan/agent/api.clj`:

```clojure
;; -- L1 Probes ----------------------------------------------------------------

(def ^:private default-limit 1000)

(defn- summary [p]
  (select-keys p [:id :kind :label]))

(defn- apply-filters [p filters]
  (reduce-kv
    (fn [keep? k v]
      (and keep?
           (case k
             :kind  (= v (:kind p))
             :label (= v (:label p))
             true)))
    true filters))

(defn- envelope [rows limit]
  (let [total (count rows)]
    {:rows (vec (take limit rows))
     :truncated? (> total limit)
     :total total}))

(defn ^{:agent/layer :L1
        :agent/doc "List Model primitive summaries. Optional filters: :kind :label.
                    Returns {:rows … :truncated? bool :total N}."
        :agent/example "(primitives :kind :primitive/behaviour)"}
  primitives
  [& {:keys [limit] :or {limit default-limit} :as opts}]
  (let [m       (or (infra-model/get-model)
                    (throw (ex-info "no model loaded" {:type :model-not-loaded})))
        filters (dissoc opts :limit :offset)
        rows    (->> (vals (:primitives m))
                     (filter #(apply-filters % filters))
                     (map summary))]
    (envelope rows limit)))
```

- [ ] **Step 4: Run tests; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L1 — primitives listing with filters and pagination"
```

#### Task 8: L1 — `get-primitive`

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing test**

Append to `api_test.clj`:

```clojure
(deftest get-primitive-returns-full-detail
  (testing "get-primitive returns the full primitive map, not the summary"
    (let [p (api/get-primitive "behaviour:hex/core/r-mint")]
      (is (= :primitive/behaviour (:kind p)))
      (is (contains? p :rules))
      (is (= "mint" (:label p))))))

(deftest get-primitive-missing-returns-nil
  (testing "missing id returns nil"
    (is (nil? (api/get-primitive "behaviour:does-not-exist")))))
```

- [ ] **Step 2: Run; expect failure**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: `get-primitive` unresolved.

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/api.clj`:

```clojure
(defn ^{:agent/layer :L1
        :agent/doc "Return the full primitive map for an id, or nil if absent."
        :agent/example "(get-primitive \"behaviour:hex/core/r-mint\")"}
  get-primitive
  [id]
  (when-let [m (infra-model/get-model)]
    (get-in m [:primitives id])))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L1 — get-primitive"
```

#### Task 9: L1 — `relations` with filters

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `api_test.clj`:

```clojure
(deftest relations-all
  (testing "(relations) returns all edges in the model"
    (let [r (api/relations)]
      (is (= 5 (count (:rows r))))
      (is (= 5 (:total r))))))

(deftest relations-by-kind
  (testing "(relations :kind :projects) filters to projects edges"
    (let [r (api/relations :kind :projects)]
      (is (= 2 (count (:rows r)))))))

(deftest relations-by-validity
  (testing "(relations :kind :projects :validity :absent) finds drift candidates"
    (let [r (api/relations :kind :projects :validity :absent)]
      (is (= 1 (count (:rows r))))
      (is (= "behaviour:hex/core/r-mint"
             (-> r :rows first :from :endpoint/primitive))))))

(deftest relations-by-from
  (testing "(relations :from id) filters edges originating at id"
    (let [r (api/relations :from "container:hex/core")]
      (is (every? #(= "container:hex/core"
                      (-> % :from :endpoint/primitive)) (:rows r))))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/api.clj`:

```clojure
(defn- relation-matches? [edge {:keys [kind from to validity projection-kind]}]
  (and (or (nil? kind) (= kind (:kind edge)))
       (or (nil? from) (= from (-> edge :from :endpoint/primitive)))
       (or (nil? to)   (= to   (-> edge :to   :endpoint/primitive)))
       (or (nil? validity) (= validity (:validity edge)))
       (or (nil? projection-kind) (= projection-kind (:projection-kind edge)))))

(defn ^{:agent/layer :L1
        :agent/doc "List Model relations (edges). Filters: :kind :from :to :validity
                    :projection-kind. Returns {:rows … :truncated? bool :total N}.
                    Each row is a full edge map (edges are compact)."
        :agent/example "(relations :kind :projects :validity :absent)"}
  relations
  [& {:keys [limit] :or {limit default-limit} :as opts}]
  (let [m       (or (infra-model/get-model)
                    (throw (ex-info "no model loaded" {:type :model-not-loaded})))
        filters (dissoc opts :limit :offset)
        rows    (->> (:edges m)
                     (filter #(relation-matches? % filters)))]
    (envelope rows limit)))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L1 — relations with kind/from/to/validity filters"
```

#### Task 10: L1 — `vocabulary` and `schema`

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `api_test.clj`:

```clojure
(deftest vocabulary-returns-kinds-in-use
  (testing "vocabulary surfaces primitive-kinds and relation-kinds present in the loaded Model"
    (let [v (api/vocabulary)]
      (is (contains? (set (:primitive-kinds v)) :primitive/behaviour))
      (is (contains? (set (:primitive-kinds v)) :primitive/container))
      (is (contains? (set (:relation-kinds v)) :projects))
      (is (contains? (set (:relation-kinds v)) :owns)))))

(deftest schema-for-kind
  (testing "(schema :kind :primitive/behaviour) surfaces attribute keys observed in fixture"
    (let [s (api/schema :kind :primitive/behaviour)]
      (is (contains? (set (:attributes s)) :rules))
      (is (contains? (set (:attributes s)) :label)))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/api.clj`:

```clojure
(defn ^{:agent/layer :L1
        :agent/doc "Surface the primitive-kinds and relation-kinds present in the
                    loaded Model. Optional :altitude filter (post-MVP)."
        :agent/example "(vocabulary)"}
  vocabulary
  [& _opts]
  (let [m  (or (infra-model/get-model)
               (throw (ex-info "no model loaded" {:type :model-not-loaded})))
        pk (into [] (distinct (map :kind (vals (:primitives m)))))
        rk (into [] (distinct (map :kind (:edges m))))]
    {:primitive-kinds (sort pk)
     :relation-kinds  (sort rk)}))

(defn ^{:agent/layer :L1
        :agent/doc "Surface the attribute keys observed on primitives of a given :kind,
                    plus the relation kinds they participate in. Empirical — read from
                    the loaded Model, not the static schema."
        :agent/example "(schema :kind :primitive/behaviour)"}
  schema
  [& {:keys [kind]}]
  (let [m (or (infra-model/get-model)
              (throw (ex-info "no model loaded" {:type :model-not-loaded})))
        matched (filter #(= kind (:kind %)) (vals (:primitives m)))
        attrs   (into #{} (mapcat keys) matched)
        ids     (into #{} (map :id) matched)
        rels    (into #{}
                      (keep (fn [e]
                              (when (or (ids (-> e :from :endpoint/primitive))
                                        (ids (-> e :to :endpoint/primitive)))
                                (:kind e))))
                      (:edges m))]
    {:kind kind
     :attributes (sort attrs)
     :relations  (sort rels)
     :count      (count matched)}))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L1 — vocabulary and schema introspection"
```

#### Task 11: L1 — `idioms`, `constraints`, `violations`

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `api_test.clj`:

```clojure
(deftest idioms-empty-on-fixture
  (testing "idioms returns empty vec when project layer has no entries"
    (is (vector? (api/idioms)))))

(deftest constraints-empty-on-fixture
  (testing "constraints returns empty vec on fixture (no constraint defs)"
    (is (vector? (api/constraints)))))

(deftest violations-empty-on-fixture
  (testing "violations returns empty vec on fixture"
    (is (vector? (api/violations)))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/api.clj`:

```clojure
(defn ^{:agent/layer :L1
        :agent/doc "Project-layer idiom entries. Returns a vector of entry maps."
        :agent/example "(idioms)"}
  idioms
  [& _opts]
  (or (when-let [m (infra-model/get-model)]
        (vec (or (:idioms m) (-> m :project-layer :idioms))))
      []))

(defn ^{:agent/layer :L1
        :agent/doc "Project-layer constraint definitions. Returns a vector."
        :agent/example "(constraints)"}
  constraints
  [& _opts]
  (or (when-let [m (infra-model/get-model)]
        (vec (or (:constraints m) (-> m :project-layer :constraints))))
      []))

(defn ^{:agent/layer :L1
        :agent/doc "Current constraint violations. Filters: :severity."
        :agent/example "(violations :severity :error)"}
  violations
  [& {:keys [severity]}]
  (let [m (infra-model/get-model)
        all (vec (or (:violations m) []))]
    (if severity
      (filterv #(= severity (:severity %)) all)
      all)))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 15 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L1 — idioms, constraints, violations"
```

#### Task 12: L2 — `drift`

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing tests, including L1+L2 equivalence**

Append to `api_test.clj`:

```clojure
(deftest drift-finds-absent-projections
  (testing "(drift) returns absent projections with their source primitive"
    (let [d (api/drift)]
      (is (= 1 (count d)))
      (is (= "behaviour:hex/core/r-mint" (-> d first :from :endpoint/primitive)))
      (is (= :primitive/behaviour (-> d first :primitive :kind))))))

(deftest drift-equivalent-to-l1-form
  (testing "drift result set matches the L1 composition it documents"
    (let [drift-l2 (set (map (juxt :validity #(-> % :from :endpoint/primitive))
                             (api/drift)))
          drift-l1 (set (map (juxt :validity #(-> % :from :endpoint/primitive))
                             (:rows (api/relations :kind :projects :validity :absent))))]
      (is (= drift-l1 drift-l2)))))

(deftest drift-filter-by-projection-kind
  (testing "(drift :projection-kind :clojure) returns only clojure-target drift"
    (is (= 1 (count (api/drift :projection-kind :clojure))))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/api.clj`:

```clojure
;; -- L2 Views -----------------------------------------------------------------

(defn ^{:agent/layer :L2
        :agent/origin :built-in
        :agent/doc "Absent projections, joined with their source primitive.
                    Optional :projection-kind filter. Returns a vector (not a
                    listing envelope) because L2 views are pre-shaped for the
                    question they answer."
        :agent/example "(drift) (drift :projection-kind :clojure)"}
  drift
  [& {:keys [projection-kind]}]
  (let [rows (if projection-kind
               (:rows (relations :kind :projects :validity :absent :projection-kind projection-kind))
               (:rows (relations :kind :projects :validity :absent)))]
    (mapv (fn [e]
            (assoc e :primitive (get-primitive (-> e :from :endpoint/primitive))))
          rows)))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 18 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L2 — drift composite view + L1 equivalence test"
```

#### Task 13: L2 — `neighborhood`

**Files:**
- Modify: `src/fukan/agent/api.clj`
- Modify: `test/fukan/agent/api_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `api_test.clj`:

```clojure
(deftest neighborhood-returns-primitive-and-one-hop
  (testing "(neighborhood id) returns primitive + outgoing + incoming + neighbor summaries"
    (let [n (api/neighborhood "container:hex/core")]
      (is (= "container:hex/core" (-> n :primitive :id)))
      (is (= 3 (count (:outgoing n))))
      (is (= 0 (count (:incoming n))))
      (is (= 3 (count (:neighbors n)))))))

(deftest neighborhood-missing-returns-nil
  (is (nil? (api/neighborhood "behaviour:does-not-exist"))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/api.clj`:

```clojure
(defn ^{:agent/layer :L2
        :agent/origin :built-in
        :agent/doc "Primitive + its one-hop outgoing and incoming edges + summaries
                    of the directly-connected neighbors. Multi-hop is the caller's
                    job at L0."
        :agent/example "(neighborhood \"container:hex/core\")"}
  neighborhood
  [id]
  (when-let [p (get-primitive id)]
    (let [out (:rows (relations :from id))
          in  (:rows (relations :to id))
          neighbor-ids (->> (concat out in)
                            (mapcat (juxt #(-> % :from :endpoint/primitive)
                                          #(-> % :to   :endpoint/primitive)))
                            (remove #{id nil})
                            distinct)
          neighbors (mapv #(let [np (get-primitive %)]
                             (select-keys np [:id :kind :label]))
                          neighbor-ids)]
      {:primitive p
       :outgoing  out
       :incoming  in
       :neighbors neighbors})))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.api-test`
Expected: 20 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/api.clj test/fukan/agent/api_test.clj
git commit -m "feat(agent): L2 — neighborhood one-hop view"
```

---

### Phase 4 — `fukan.agent.system`

#### Task 14: `status` and `refresh`

**Files:**
- Create: `src/fukan/agent/system.clj`
- Create: `test/fukan/agent/system_test.clj`

- [ ] **Step 1: Write failing tests**

Create `test/fukan/agent/system_test.clj`:

```clojure
(ns fukan.agent.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.system :as system]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest status-returns-snapshot
  (testing "status surfaces counts and target"
    (let [s (system/status)]
      (is (= 4 (:primitive-count s)))
      (is (= 5 (:relation-count s)))
      (is (true? (:model-loaded? s)))
      (is (contains? s :target)))))

(deftest status-no-model
  (testing "status reflects unloaded state"
    (infra-model/set-model-for-test! nil)
    (let [s (system/status)]
      (is (false? (:model-loaded? s)))
      (is (zero? (:primitive-count s))))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Create `src/fukan/agent/system.clj`:

```clojure
(ns fukan.agent.system
  "Operating Fukan: status, refresh, help, source. Flat namespace.
   Sandbox surface alongside fukan.agent.api."
  (:require [fukan.infra.model :as infra-model]))

(defn ^{:agent/doc "Snapshot of the daemon and loaded Model."
        :agent/example "(status)"}
  status
  []
  (let [m (infra-model/get-model)]
    {:model-loaded?   (some? m)
     :target          (infra-model/get-src)
     :primitive-count (if m (count (:primitives m)) 0)
     :relation-count  (if m (count (:edges m)) 0)
     :artifact-count  (if m (count (:artifacts m)) 0)
     :violation-count (if m (count (or (:violations m) [])) 0)}))

(defn ^{:agent/doc "Rebuild the loaded Model. Blocks; returns the new status."
        :agent/example "(refresh)"}
  refresh
  []
  (infra-model/refresh-model)
  (status))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.system-test`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/system.clj test/fukan/agent/system_test.clj
git commit -m "feat(agent): system — status, refresh"
```

#### Task 15: `help` and `source`

**Files:**
- Modify: `src/fukan/agent/system.clj`
- Modify: `test/fukan/agent/system_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `system_test.clj`:

```clojure
(deftest help-lists-surface
  (testing "(help) groups fns by namespace and layer"
    (let [h (system/help)]
      (is (contains? h 'fukan.agent.api))
      (is (contains? h 'fukan.agent.system))
      (let [api (get h 'fukan.agent.api)]
        (is (contains? api :L0))
        (is (contains? api :L1))
        (is (contains? api :L2))
        (is (some #(= 'primitives (:name %)) (:L1 api)))
        (is (some #(= 'q (:name %)) (:L0 api)))
        (is (some #(= 'drift (:name %)) (:L2 api)))))))

(deftest help-for-single-fn
  (testing "(help 'primitives) returns docstring + signatures + examples"
    (let [h (system/help 'primitives)]
      (is (= 'primitives (:name h)))
      (is (= :L1 (:layer h)))
      (is (string? (:doc h)))
      (is (string? (:example h))))))

(deftest source-returns-implementation
  (testing "(source 'drift) returns the L2 implementation as a string"
    (let [s (system/source 'drift)]
      (is (= 'drift (:name s)))
      (is (string? (:source s)))
      (is (re-find #"defn drift" (:source s))))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Append to `src/fukan/agent/system.clj`:

```clojure
(require '[clojure.repl :as repl] '[clojure.string :as str])

(def ^:private surface-namespaces ['fukan.agent.api 'fukan.agent.system])

(defn- collect-var-meta [ns-sym]
  (require ns-sym)
  (->> (ns-publics ns-sym)
       vals
       (map (fn [v]
              (let [m (meta v)]
                {:name      (:name m)
                 :layer     (:agent/layer m)
                 :doc       (or (:agent/doc m) (:doc m) "")
                 :example   (or (:agent/example m) "")
                 :origin    (:agent/origin m)
                 :var       v})))))

(defn ^{:agent/doc "List the surface. Without args: nested map grouped by namespace
                    and (for fukan.agent.api) by layer. With a symbol: docstring +
                    signatures + example for that single fn."
        :agent/example "(help) (help 'primitives)"}
  help
  ([]
   (let [api-meta (collect-var-meta 'fukan.agent.api)
         sys-meta (collect-var-meta 'fukan.agent.system)]
     {'fukan.agent.api
      (reduce (fn [acc {:keys [layer] :as m}]
                (let [k (or layer :other)]
                  (update acc k (fnil conj []) (dissoc m :var))))
              {:L0 [] :L1 [] :L2 []}
              api-meta)
      'fukan.agent.system
      (mapv #(dissoc % :var) sys-meta)}))
  ([fn-sym]
   (some (fn [ns-sym]
           (when-let [v (resolve (symbol (str ns-sym) (str fn-sym)))]
             (let [m (meta v)]
               {:name      (:name m)
                :ns        ns-sym
                :layer     (:agent/layer m)
                :doc       (or (:agent/doc m) (:doc m) "")
                :example   (or (:agent/example m) "")
                :arglists  (str (:arglists m))})))
         surface-namespaces)))

(defn ^{:agent/doc "Return the source text of an L1/L2 fn so the agent can read
                    built-in views as templates."
        :agent/example "(source 'drift)"}
  source
  [fn-sym]
  (some (fn [ns-sym]
          (when-let [v (resolve (symbol (str ns-sym) (str fn-sym)))]
            {:name fn-sym
             :ns   ns-sym
             :source (with-out-str (repl/source-fn (symbol (str ns-sym) (str fn-sym))))}))
        surface-namespaces))
```

> **Note:** `clojure.repl/source-fn` returns nil in some environments where source paths aren't available. The wrapping `with-out-str (repl/source …)` pattern is more robust but requires the actual `source` macro. For MVP, use `repl/source-fn` which returns the string directly when sources are on the classpath; fall back to the var's metadata `:doc` if not.

Adjust the implementation if `repl/source-fn` returns nil during testing — wrap and check:

```clojure
(defn source [fn-sym]
  (some (fn [ns-sym]
          (when-let [v (resolve (symbol (str ns-sym) (str fn-sym)))]
            (let [src (try (clojure.repl/source-fn (symbol (str ns-sym) (str fn-sym)))
                           (catch Exception _ nil))]
              {:name fn-sym
               :ns   ns-sym
               :source (or src "<source unavailable>")})))
        surface-namespaces))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.system-test`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/system.clj test/fukan/agent/system_test.clj
git commit -m "feat(agent): system — help, source introspection"
```

---

### Phase 5 — SCI sandbox

#### Task 16: SCI eval with frozen ns map (happy path)

**Files:**
- Create: `src/fukan/agent/sci.clj`
- Create: `test/fukan/agent/sci_test.clj`

- [ ] **Step 1: Write failing test**

Create `test/fukan/agent/sci_test.clj`:

```clojure
(ns fukan.agent.sci-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.sci :as agent-sci]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest eval-arithmetic
  (testing "trivial expression"
    (let [r (agent-sci/eval-string "(+ 1 2)")]
      (is (true? (:ok? r)))
      (is (= 3 (:result r))))))

(deftest eval-primitives
  (testing "agent can call (primitives) without namespace prefix"
    (let [r (agent-sci/eval-string "(primitives :kind :primitive/behaviour)")]
      (is (true? (:ok? r)))
      (is (= 2 (count (:result r)))))))

(deftest eval-status
  (testing "agent can call (status) without namespace prefix"
    (let [r (agent-sci/eval-string "(status)")]
      (is (true? (:ok? r)))
      (is (true? (-> r :result :model-loaded?))))))
```

- [ ] **Step 2: Run; expect failure**

- [ ] **Step 3: Implement**

Create `src/fukan/agent/sci.clj`:

```clojure
(ns fukan.agent.sci
  "SCI sandbox for agent eval. Locks the evaluator to the fukan.agent.api and
   fukan.agent.system surfaces. No Java interop, no IO, no thread spawning."
  (:require [sci.core :as sci]
            [fukan.agent.api :as api]
            [fukan.agent.system :as system]))

(def ^:private api-bindings
  (into {} (map (fn [[sym v]] [sym v]) (ns-publics 'fukan.agent.api))))

(def ^:private system-bindings
  (into {} (map (fn [[sym v]] [sym v]) (ns-publics 'fukan.agent.system))))

(def ^:private merged-bindings
  (merge api-bindings system-bindings))

(defn- make-ctx []
  (sci/init
    {:namespaces {'user merged-bindings}
     :deny       '[def defn defmacro defprotocol deftype defrecord
                   loop* fn* recur]}))

(defn eval-string
  "Evaluate the given expression string in the agent sandbox.
   Returns either {:ok? true :result …} or {:ok? false :error/kind … :error/message …}."
  [s]
  (try
    (let [ctx    (make-ctx)
          result (sci/eval-string* ctx s)]
      {:ok? true :result result})
    (catch Exception e
      (let [data (ex-data e)]
        {:ok? false
         :error/kind (or (:type data) :runtime)
         :error/message (.getMessage e)}))))
```

> **Note:** The `:deny` list above is a starting point. SCI's default bindings already exclude `java.*` interop, IO, `System/*`, threads — explicit deny is belt-and-braces for `def`/`defn`/etc. at eval time. Phase 6 (sandbox refusals) tightens this; Phase 8 (views loader) re-permits `def`/`defn` *inside* a file-load context only.

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.sci-test`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/sci.clj test/fukan/agent/sci_test.clj
git commit -m "feat(agent): SCI sandbox with locked ns map (happy path)"
```

#### Task 17: Per-eval timeout

**Files:**
- Modify: `src/fukan/agent/sci.clj`
- Modify: `test/fukan/agent/sci_test.clj`

- [ ] **Step 1: Write failing test**

Append to `sci_test.clj`:

```clojure
(deftest eval-timeout
  (testing "infinite loop returns :timeout, daemon still healthy"
    (let [r (agent-sci/eval-string "(loop [] (recur))" {:timeout-ms 100})]
      (is (false? (:ok? r)))
      (is (= :timeout (:error/kind r)))
      (is (number? (:error/elapsed-ms r))))))
```

- [ ] **Step 2: Run; expect failure (option ignored)**

- [ ] **Step 3: Implement timeout**

Replace `eval-string` in `src/fukan/agent/sci.clj`:

```clojure
(def ^:private default-timeout-ms 5000)

(defn eval-string
  ([s] (eval-string s {}))
  ([s {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
   (let [ctx       (make-ctx)
         start     (System/currentTimeMillis)
         fut       (future
                     (try {:ok? true :result (sci/eval-string* ctx s)}
                          (catch Throwable e
                            {:ok? false
                             :error/kind (or (some-> e ex-data :type) :runtime)
                             :error/message (.getMessage e)})))
         result    (deref fut timeout-ms ::timeout)
         elapsed   (- (System/currentTimeMillis) start)]
     (cond
       (= result ::timeout)
       (do (future-cancel fut)
           {:ok? false
            :error/kind :timeout
            :error/elapsed-ms elapsed
            :error/message (str "eval exceeded " timeout-ms "ms")})

       :else
       (assoc result :elapsed-ms elapsed)))))
```

- [ ] **Step 4: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.sci-test`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/sci.clj test/fukan/agent/sci_test.clj
git commit -m "feat(agent): per-eval SCI timeout with structured error"
```

#### Task 18: Sandbox refusals

**Files:**
- Modify: `test/fukan/agent/sci_test.clj`

- [ ] **Step 1: Write failing tests**

Append to `sci_test.clj`:

```clojure
(deftest sandbox-refuses-system-exit
  (testing "(System/exit 0) is refused; daemon stays up"
    (let [r (agent-sci/eval-string "(System/exit 0)")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-java-io
  (testing "(slurp \"/etc/passwd\") is refused"
    (let [r (agent-sci/eval-string "(slurp \"/etc/passwd\")")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-shell
  (testing "no access to clojure.java.shell"
    (let [r (agent-sci/eval-string "(require '[clojure.java.shell]) (clojure.java.shell/sh \"echo\" \"hi\")")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-internal-ns
  (testing "no access to fukan.* internal namespaces"
    (let [r (agent-sci/eval-string "(fukan.model.build/empty-model)")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-runtime-def
  (testing "def is refused at eval-time"
    (let [r (agent-sci/eval-string "(def x 1)")]
      (is (false? (:ok? r))))))
```

- [ ] **Step 2: Run; some may pass already (SCI's defaults), some may fail**

Run: `clojure -M:test -n fukan.agent.sci-test`
Note which fail.

- [ ] **Step 3: Tighten `make-ctx` until all refusals hold**

If `(System/exit 0)`, `(slurp …)`, or `clojure.java.shell` slip through, add explicit class-access bans to `make-ctx`:

```clojure
(defn- make-ctx []
  (sci/init
    {:namespaces {'user merged-bindings}
     :deny       '[def defn defmacro defprotocol deftype defrecord
                   loop* fn* recur
                   ;; runtime mutation
                   alter-var-root intern]
     :classes    {}                          ; no Java classes at all
     :ns-aliases {}                          ; agent uses unqualified names only
     }))
```

If `(fukan.model.build/empty-model)` slips through, ensure no other namespaces are reachable — SCI by default does not expose external Clojure namespaces unless added to `:namespaces`. Verify by reading `(sci/init …)` returns no implicit clojure namespaces beyond `clojure.core`.

- [ ] **Step 4: Run; expect all refusal tests to pass**

Run: `clojure -M:test -n fukan.agent.sci-test`
Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/sci.clj test/fukan/agent/sci_test.clj
git commit -m "feat(agent): SCI sandbox refusals — System/exit, IO, shell, def"
```

---

### Phase 6 — HTTP endpoints

#### Task 19: `/agent/eval` handler

**Files:**
- Create: `src/fukan/web/agent_handlers.clj`
- Modify: `src/fukan/web/handler.clj`

- [ ] **Step 1: Implement handler**

Create `src/fukan/web/agent_handlers.clj`:

```clojure
(ns fukan.web.agent-handlers
  "HTTP handlers for /agent/eval and /agent/status.
   Sandboxed eval surface — see fukan.agent.sci."
  (:require [cheshire.core :as json]
            [fukan.agent.sci :as agent-sci]
            [fukan.agent.system :as agent-system]))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- bad-request [msg]
  {:status 400
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:ok? false :error/kind :bad-request :error/message msg})})

(def ^:private response-byte-cap (* 8 1024 1024))  ; 8 MB

(defn- with-byte-cap [body]
  (let [serialised (json/generate-string body)
        n (count (.getBytes serialised "UTF-8"))]
    (if (> n response-byte-cap)
      (json/generate-string
        {:ok? false
         :error/kind :exceeded-cap
         :error/message (str "response body " n " bytes exceeds " response-byte-cap " byte cap")})
      serialised)))

(defn handle-eval [req]
  (let [body (some-> req :body slurp)
        parsed (try (json/parse-string body true) (catch Exception _ nil))
        expr (:expr parsed)]
    (cond
      (nil? parsed) (bad-request "request body must be JSON {\"expr\":\"…\"}")
      (not (string? expr)) (bad-request "missing :expr")
      :else
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (with-byte-cap (agent-sci/eval-string expr))})))

(defn handle-status [_req]
  (json-response {:ok? true :result (agent-system/status)}))
```

- [ ] **Step 2: Wire into the router**

Modify `src/fukan/web/handler.clj`. Add to the `:require` list:

```clojure
            [fukan.web.agent-handlers :as agent-handlers]
```

In `create-handler`, add two routes to the vector:

```clojure
       ["/agent/eval"   {:post agent-handlers/handle-eval}]
       ["/agent/status" {:get  agent-handlers/handle-status}]
```

- [ ] **Step 3: Write the integration test**

Create `test/fukan/agent/integration_test.clj`:

```clojure
(ns fukan.agent.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [fukan.infra.model :as infra-model]
            [fukan.web.handler :as handler]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(defn- post-eval [expr]
  (let [h (handler/create-handler)
        req {:request-method :post
             :uri "/agent/eval"
             :body (java.io.ByteArrayInputStream.
                     (.getBytes (json/generate-string {:expr expr}) "UTF-8"))
             :headers {"content-type" "application/json"}}
        resp (h req)]
    (json/parse-string (:body resp) true)))

(defn- get-status []
  (let [h (handler/create-handler)
        resp (h {:request-method :get :uri "/agent/status"})]
    (json/parse-string (:body resp) true)))

(deftest http-eval-roundtrip
  (testing "POST /agent/eval evaluates and returns JSON"
    (let [r (post-eval "(+ 1 2)")]
      (is (true? (:ok? r)))
      (is (= 3 (:result r))))))

(deftest http-eval-primitives
  (testing "POST /agent/eval can list primitives"
    (let [r (post-eval "(count (:rows (primitives)))")]
      (is (= 4 (:result r))))))

(deftest http-status-snapshot
  (testing "GET /agent/status returns the current snapshot"
    (let [r (get-status)]
      (is (true? (-> r :result :model-loaded?)))
      (is (= 4 (-> r :result :primitive-count))))))
```

- [ ] **Step 4: Run**

Run: `clojure -M:test -n fukan.agent.integration-test`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/web/agent_handlers.clj src/fukan/web/handler.clj test/fukan/agent/integration_test.clj
git commit -m "feat(web): /agent/eval + /agent/status endpoints"
```

---

### Phase 7 — agent-views.clj loader

#### Task 20: Load `.fukan/agent-views.clj` into the SCI surface

**Files:**
- Create: `src/fukan/agent/views_loader.clj`
- Create: `test/fukan/fixtures/agent/agent-views-good.clj`
- Create: `test/fukan/fixtures/agent/agent-views-syntax-error.clj`
- Create: `test/fukan/fixtures/agent/agent-views-unbound.clj`
- Create: `test/fukan/agent/views_loader_test.clj`

- [ ] **Step 1: Write fixture files**

`test/fukan/fixtures/agent/agent-views-good.clj`:

```clojure
;; Project-local agent views fixture
(defn unrealised-by-altitude
  "Group absent projections by source primitive kind."
  []
  (->> (relations :kind :projects :validity :absent)
       (map (fn [e] (assoc e :primitive (get-primitive (-> e :from :endpoint/primitive)))))
       (group-by (comp :kind :primitive))))
```

`test/fukan/fixtures/agent/agent-views-syntax-error.clj`:

```clojure
(defn broken [
```

`test/fukan/fixtures/agent/agent-views-unbound.clj`:

```clojure
(defn refers-to-unknown []
  (totally-not-bound-fn))
```

- [ ] **Step 2: Write failing tests**

Create `test/fukan/agent/views_loader_test.clj`:

```clojure
(ns fukan.agent.views-loader-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.sci :as agent-sci]
            [fukan.agent.views-loader :as loader]
            [fukan.infra.model :as infra-model]))

(defn load-fixture-model []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture-model))
  (loader/reset!)
  (f))

(use-fixtures :each with-fixture-model)

(deftest load-good-file
  (testing "good views file loads; defs callable via eval"
    (let [path (.getPath (io/resource "fukan/fixtures/agent/agent-views-good.clj"))
          report (loader/load-file! path)]
      (is (= 1 (count (:loaded report))))
      (is (empty? (:errors report)))
      (let [r (agent-sci/eval-string "(unrealised-by-altitude)")]
        (is (true? (:ok? r)))))))

(deftest partial-load-on-syntax-error
  (testing "syntax error reports; other defs still load if present"
    (let [path (.getPath (io/resource "fukan/fixtures/agent/agent-views-syntax-error.clj"))
          report (loader/load-file! path)]
      (is (seq (:errors report)))
      (is (= :syntax (-> report :errors first :error/kind))))))

(deftest unbound-var-reported
  (testing "form referring to an unbound var loads as a def but errors when called"
    (let [path (.getPath (io/resource "fukan/fixtures/agent/agent-views-unbound.clj"))
          _ (loader/load-file! path)
          r (agent-sci/eval-string "(refers-to-unknown)")]
      (is (false? (:ok? r))))))
```

- [ ] **Step 3: Implement the loader**

Create `src/fukan/agent/views_loader.clj`:

```clojure
(ns fukan.agent.views-loader
  "Load .fukan/agent-views.clj into the SCI agent surface.

   Strategy: re-init the SCI ctx in fukan.agent.sci with the same base bindings
   plus the file's defs. Errors per-form are captured into a load report.
   The agent sees defs as if they were built-in L2."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [fukan.agent.sci :as agent-sci]))

(defonce ^:private load-report (atom {:loaded [] :errors []}))

(defn reset! []
  (reset! load-report {:loaded [] :errors []}))

(defn last-report [] @load-report)

(defn load-file! [path]
  (reset!)
  (let [forms (try
                {:ok (read-string (str "[" (slurp path) "]"))}
                (catch Exception e
                  {:err {:error/kind :syntax
                         :error/message (.getMessage e)
                         :error/path path}}))]
    (cond
      (:err forms)
      (do (swap! load-report update :errors conj (:err forms))
          @load-report)

      :else
      (do
        (doseq [form (:ok forms)]
          (let [r (agent-sci/eval-string-as-view (pr-str form))]
            (if (:ok? r)
              (when-let [sym (and (seq? form) (= 'defn (first form)) (second form))]
                (swap! load-report update :loaded conj sym))
              (swap! load-report update :errors conj
                     (merge r {:error/form form})))))
        @load-report))))
```

- [ ] **Step 4: Add `eval-string-as-view` to `fukan.agent.sci`**

Modify `src/fukan/agent/sci.clj`. Add a separate evaluator that permits `def`/`defn` for the views-file load path:

```clojure
(def ^:private view-ctx (atom nil))

(defn- make-view-ctx []
  (sci/init
    {:namespaces {'user merged-bindings}
     :deny       '[]
     :classes    {}}))

(defn- ensure-view-ctx! []
  (when-not @view-ctx
    (reset! view-ctx (make-view-ctx)))
  @view-ctx)

(defn eval-string-as-view
  "Evaluate a single form in the view-loading context (def permitted).
   Defs land in the shared view ctx and become reachable from regular eval."
  [s]
  (try
    (let [ctx (ensure-view-ctx!)]
      {:ok? true :result (sci/eval-string* ctx s)})
    (catch Exception e
      {:ok? false
       :error/kind (or (some-> e ex-data :type) :runtime)
       :error/message (.getMessage e)})))
```

> **Note:** This task introduces a shared view ctx (held in an atom) so view defs accumulate across forms. The main `eval-string` should also use this same ctx so view-defined fns are reachable from agent eval. Update `eval-string` (Task 17 version) to use `ensure-view-ctx!` instead of `make-ctx`:
>
> ```clojure
> (defn eval-string
>   ([s] (eval-string s {}))
>   ([s {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
>    (let [ctx (ensure-view-ctx!)  ; ← changed
>          …]
>      …)))
> ```
>
> Re-run sandbox refusal tests from Task 18 to confirm refusals still hold (view ctx has same restrictions except `def`). If a refusal fails, tighten `make-view-ctx`.

- [ ] **Step 5: Run; expect pass**

Run: `clojure -M:test -n fukan.agent.views-loader-test`
Expected: 3 tests pass.

Run: `clojure -M:test -n fukan.agent.sci-test`
Expected: 9 tests still pass.

- [ ] **Step 6: Commit**

```bash
git add src/fukan/agent/views_loader.clj src/fukan/agent/sci.clj test/fukan/agent/views_loader_test.clj test/fukan/fixtures/agent/agent-views-*.clj
git commit -m "feat(agent): .fukan/agent-views.clj loader with partial-load reporting"
```

#### Task 21: Discover and load `.fukan/agent-views.clj` on refresh

**Files:**
- Modify: `src/fukan/agent/views_loader.clj`
- Modify: `src/fukan/agent/system.clj`
- Modify: `test/fukan/agent/system_test.clj`

- [ ] **Step 1: Implement discovery**

Append to `src/fukan/agent/views_loader.clj`:

```clojure
(defn discover [target-src]
  (let [candidate (clojure.java.io/file target-src ".fukan" "agent-views.clj")]
    (when (.exists candidate) (.getCanonicalPath candidate))))

(defn auto-load! [target-src]
  (if-let [path (discover target-src)]
    (load-file! path)
    {:loaded [] :errors []}))
```

- [ ] **Step 2: Hook into `(refresh)` and `(status)`**

Modify `src/fukan/agent/system.clj`. Add a require:

```clojure
            [fukan.agent.views-loader :as views-loader]
```

Update `refresh`:

```clojure
(defn refresh []
  (infra-model/refresh-model)
  (views-loader/auto-load! (infra-model/get-src))
  (status))
```

Update `status` to surface load-report:

```clojure
(defn status []
  (let [m (infra-model/get-model)]
    {:model-loaded?   (some? m)
     :target          (infra-model/get-src)
     :primitive-count (if m (count (:primitives m)) 0)
     :relation-count  (if m (count (:edges m)) 0)
     :artifact-count  (if m (count (:artifacts m)) 0)
     :violation-count (if m (count (or (:violations m) [])) 0)
     :views           (views-loader/last-report)}))
```

- [ ] **Step 3: Add test**

Append to `system_test.clj`:

```clojure
(deftest status-includes-views-report
  (let [s (system/status)]
    (is (contains? s :views))
    (is (contains? (:views s) :loaded))
    (is (contains? (:views s) :errors))))
```

- [ ] **Step 4: Run**

Run: `clojure -M:test -n fukan.agent.system-test fukan.agent.views-loader-test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/fukan/agent/views_loader.clj src/fukan/agent/system.clj test/fukan/agent/system_test.clj
git commit -m "feat(agent): auto-load .fukan/agent-views.clj on refresh; surface in status"
```

---

### Phase 8 — CLI

#### Task 22: CLI skeleton (Babashka)

**Files:**
- Create: `bin/fukan`

- [ ] **Step 1: Implement the CLI**

Create `bin/fukan` as an executable Babashka script:

```bash
#!/usr/bin/env bb

(ns fukan.cli
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def daemon-url
  (or (System/getenv "FUKAN_URL") "http://127.0.0.1:8080"))

(defn die [msg & {:keys [code] :or {code 1}}]
  (binding [*out* *err*] (println msg))
  (System/exit code))

(defn- get-status []
  (try
    (let [resp (http/get (str daemon-url "/agent/status")
                         {:throw false :timeout 2000})]
      (when (= 200 (:status resp))
        (json/parse-string (:body resp) true)))
    (catch Exception _ nil)))

(defn cmd-status [_args]
  (if-let [s (get-status)]
    (println (json/generate-string s {:pretty true}))
    (die "fukan daemon not reachable at " daemon-url
         ". Start it with `clj -M:run` from the fukan repo.")))

(defn cmd-eval [args]
  (when (empty? args)
    (die "usage: fukan eval '<expression>'"))
  (let [expr (str/join " " args)
        resp (http/post (str daemon-url "/agent/eval")
                        {:headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:expr expr})
                         :throw false})]
    (println (:body resp))
    (let [parsed (json/parse-string (:body resp) true)]
      (when-not (:ok? parsed) (System/exit 2)))))

(defn cmd-primer [_args]
  ;; Implemented in Task 24
  (die "primer not yet implemented"))

(defn cmd-init [_args]
  ;; Implemented in Task 25
  (die "init not yet implemented"))

(defn -main [& argv]
  (let [[cmd & args] argv]
    (case cmd
      "status" (cmd-status args)
      "eval"   (cmd-eval args)
      "primer" (cmd-primer args)
      "init"   (cmd-init args)
      (die (str "usage: fukan {status|eval|primer|init} …")))))

(apply -main *command-line-args*)
```

- [ ] **Step 2: Make executable**

Run: `chmod +x bin/fukan`

- [ ] **Step 3: Manual smoke (requires Babashka installed)**

Start the daemon in another terminal: `clj -M:run`
Then: `./bin/fukan status`
Expected: JSON status output.

`./bin/fukan eval '(+ 1 2)'`
Expected: `{"ok?":true,"result":3,…}`

If Babashka is not installed, the user installs it: `brew install borkdude/brew/babashka`.

- [ ] **Step 4: Commit**

```bash
git add bin/fukan
git commit -m "feat(cli): fukan CLI skeleton — status, eval"
```

#### Task 23: CLI — error when daemon is down (no eval attempt)

**Files:**
- Modify: `bin/fukan`

- [ ] **Step 1: Pre-flight check in `cmd-eval`**

Add a pre-flight ping before posting the expression in `cmd-eval`:

```clojure
(defn cmd-eval [args]
  (when (empty? args)
    (die "usage: fukan eval '<expression>'"))
  (when-not (get-status)
    (die (str "fukan daemon not reachable at " daemon-url ". Start it with `clj -M:run`.")))
  (let [expr (str/join " " args)
        resp (http/post (str daemon-url "/agent/eval")
                        {:headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:expr expr})
                         :throw false})]
    (println (:body resp))
    (let [parsed (json/parse-string (:body resp) true)]
      (when-not (:ok? parsed) (System/exit 2)))))
```

- [ ] **Step 2: Manual test**

With daemon DOWN: `./bin/fukan eval '(+ 1 2)'`
Expected: stderr message about daemon, exit code 1.

- [ ] **Step 3: Commit**

```bash
git add bin/fukan
git commit -m "feat(cli): pre-flight check on eval; clean error when daemon down"
```

---

### Phase 9 — AGENTS.md primer

#### Task 24: Write `AGENTS.md` + `fukan primer` reads it

**Files:**
- Create: `AGENTS.md`
- Modify: `bin/fukan`

- [ ] **Step 1: Write the primer**

Create `AGENTS.md` at the repo root. Use this structure (target 200-400 lines of dense markdown):

```markdown
# Fukan — agent primer

> This is your mental-model guide. For the **live catalog** of fns, signatures,
> and examples, call `(help)` from inside `fukan eval '(...)'`. Don't memorise
> signatures from this primer — they may drift; `(help)` is current.

## 1. What Fukan is

A spec graph that knows about code. You query a Model whose primitives are
behavioural rules, surfaces, contracts, types, modules, subsystems — *not*
functions and call edges. Code joins as projections, and an absent projection
is drift.

(One paragraph. Be tight.)

## 2. The Model in one minute

- **Three altitudes.** Behaviour (rules + events + invariants), Structure
  (types, surfaces, contracts, the binding between contract operations and
  rules, module composition), Infra (deferred for MVP).
- **Primitives + relations + project layer.** Each primitive has a `:kind`
  (e.g. `:primitive/behaviour`, `:primitive/container`, …); see `(vocabulary)`.
- **`:projects` edges.** Spec primitive → realising artifact (Clojure fn,
  test, doc, etc.). Each has a `:validity` of `:valid`, `:absent`, or
  `:invalid`. Drift = absent projections.
- **The project layer** carries idioms (the project's design vocabulary) and
  constraints (Datalog rules over the Model). See `(idioms)` and `(constraints)`.

Full substrate: `doc/MODEL.md`. Design: `doc/DESIGN.md`. Vision: `doc/VISION.md`.

## 3. The two namespaces

You have two flat namespaces available, both referred-in by default — no
`ns/fn` prefix needed:

- **`fukan.agent.system`** (operating Fukan) — `status`, `refresh`, `help`, `source`.
- **`fukan.agent.api`** (querying the Model) — layered (next section).

## 4. The L0 / L1 / L2 layering of `fukan.agent.api`

| Layer | What lives here | Used for |
|-------|------------------|----------|
| **L0** | `q` — Datalog over the Model. | Joins, aggregations, anything ad-hoc. |
| **L1** | `primitives`, `get-primitive`, `relations`, `vocabulary`, `schema`, `idioms`, `constraints`, `violations`. | Daily driver. Filter by kw-args. |
| **L2** | `drift`, `neighborhood` (built-in); whatever the project has accreted in `.fukan/agent-views.clj`. | Recurring questions, named. |

**Principle.** Higher layers are *convenience*, not *capability*. L1 is sugar
over L0; L2 is named compositions. The agent learns L1 first, reaches for L0
when needed, and promotes recurring patterns to L2.

## 5. Three worked examples

### (a) Orientation: "what is module hex/core?"

```clojure
;; What kinds of things live in this Model?
(vocabulary)

;; List containers
(primitives :kind :primitive/container)

;; Get one in full
(get-primitive "container:hex/core")

;; Walk outward
(relations :from "container:hex/core")
```

### (b) Derivation: "what rules have no Clojure realisation yet?"

```clojure
;; Compose at L1 — listing fns return {:rows … :truncated? … :total …};
;; reach into :rows to operate on the data.
(->> (relations :kind :projects :validity :absent :projection-kind :clojure)
     :rows
     (map :from)
     (map (comp get-primitive :endpoint/primitive)))

;; Same thing at L0
(q '[:find ?p
     :where
       [?e :relation/kind :projects]
       [?e :relation/validity :absent]
       [?e :relation/projection-kind :clojure]])
```

If this becomes a recurring question, promote it (see §6).

### (c) Editing loop

1. Read `(drift)` to find what's missing.
2. Open the spec file (`src/.../*.allium` or `*.boundary`) and edit it.
3. Call `(refresh)` to rebuild.
4. Re-query; iterate.

The Model is your source of truth. Edits happen on disk; the loop is
edit → refresh → query.

## 6. Persisted views — `.fukan/agent-views.clj`

When a query recurs, name it and commit it. Open
`<target-project>/.fukan/agent-views.clj` and add a `defn`:

```clojure
(defn unrealised-clojure-behaviours
  "Behavioural rules with no :clojure projection."
  []
  (->> (relations :kind :projects :validity :absent :projection-kind :clojure)
       :rows
       (map :from)
       (map (comp get-primitive :endpoint/primitive))
       (filter #(= :primitive/behaviour (:kind %)))))
```

Then `(refresh)`. The fn is now in the project-local L2 bucket; `(help)`
shows it; `(source 'unrealised-clojure-behaviours)` returns the text. Commit
the file; humans and other agents inherit it.

Read `(source 'drift)` for the built-in canonical pattern.

## 7. The reference catalog is live

```
(help)                  ; full surface, grouped by ns and layer
(help 'primitives)      ; single-fn signature + doc + example
(source 'drift)         ; full source text of an L2 view
```

Use these aggressively. Don't guess fn shapes from this primer.

## 8. Constraints on what eval can do

- No Java interop, file IO, shell-out, or thread spawning. The sandbox refuses.
- Per-eval timeout (default 5s). Long-running loops die with `:timeout`.
- Result caps (default 1000 rows + 8MB body). Paginate with `:limit`/`:offset`.
- `def` only inside `.fukan/agent-views.clj` (load-time); refused at eval-time.

If you need something not currently exposed, propose adding to
`fukan.agent.api` rather than reaching around — the surface is the contract.

## 9. Pointers

- `doc/VISION.md` — why Fukan exists
- `doc/DESIGN.md` — system design
- `doc/MODEL.md` — Model substrate, kernel, vocabulary, constraints
- `doc/specs/2026-05-20-llm-agent-surface-design.md` — this surface's design
- `doc/plans/2026-05-20-llm-agent-surface.md` — this surface's plan
```

- [ ] **Step 2: Implement `fukan primer`**

Modify `bin/fukan`. Replace `cmd-primer`:

```clojure
(defn cmd-primer [_args]
  (let [primer-path (str (System/getenv "FUKAN_HOME") "/AGENTS.md")
        ;; Fallback: look at the daemon's expected repo root.
        primer-path (if (.exists (java.io.File. primer-path))
                      primer-path
                      "AGENTS.md")]
    (if (.exists (java.io.File. primer-path))
      (print (slurp primer-path))
      (die "primer not found; set FUKAN_HOME or run from the Fukan repo root"))))
```

> Future improvement: have the daemon expose `/agent/primer` and have the CLI fetch it, so the primer is always the running daemon's version. MVP just reads the file.

- [ ] **Step 3: Verify**

Run: `./bin/fukan primer | head -30`
Expected: prints the primer header.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md bin/fukan
git commit -m "doc(agent): AGENTS.md primer; fukan primer subcommand"
```

#### Task 25: `fukan init` — add Fukan section to target's AGENTS.md

**Files:**
- Modify: `bin/fukan`

- [ ] **Step 1: Implement `cmd-init`**

Replace `cmd-init` in `bin/fukan`:

```clojure
(def ^:private fukan-section-marker "<!-- fukan:agents-section -->")
(def ^:private fukan-section
  (str fukan-section-marker "\n"
       "## Fukan\n\n"
       "Fukan is available — a structural workbench for the spec graph.\n\n"
       "- Get the agent primer: `fukan primer`\n"
       "- Quick status:         `fukan status`\n"
       "- Query the Model:      `fukan eval '(primitives :kind :primitive/behaviour)'`\n"
       "- Open the explorer:    http://127.0.0.1:8080/\n\n"
       fukan-section-marker))

(defn cmd-init [_args]
  (let [agents-md (java.io.File. "AGENTS.md")]
    (cond
      (not (.exists agents-md))
      (do (spit agents-md (str "# Project agents guide\n\n" fukan-section "\n"))
          (println "Created AGENTS.md with Fukan section."))

      (str/includes? (slurp agents-md) fukan-section-marker)
      (let [existing (slurp agents-md)
            updated  (str/replace existing
                                  (re-pattern (str fukan-section-marker
                                                   "[\\s\\S]*?"
                                                   fukan-section-marker))
                                  fukan-section)]
        (spit agents-md updated)
        (println "Updated Fukan section in AGENTS.md."))

      :else
      (do (spit agents-md (str (slurp agents-md) "\n" fukan-section "\n"))
          (println "Added Fukan section to existing AGENTS.md.")))))
```

- [ ] **Step 2: Manual test in a scratch directory**

```bash
mkdir /tmp/fukan-init-test && cd /tmp/fukan-init-test
fukan init
cat AGENTS.md
# Should show a Fukan section between markers.
fukan init  # second run — should update, not append
grep -c "fukan:agents-section" AGENTS.md
# Should be 2 (one open, one close).
```

- [ ] **Step 3: Commit**

```bash
git add bin/fukan
git commit -m "feat(cli): fukan init — add/update Fukan section in AGENTS.md"
```

---

### Phase 10 — End-to-end smoke

#### Task 26: Full HTTP-level smoke against the fixture

**Files:**
- Modify: `test/fukan/agent/integration_test.clj`

- [ ] **Step 1: Add end-to-end tests**

Append to `integration_test.clj`:

```clojure
(deftest e2e-eval-sandbox-refusal
  (testing "POST /agent/eval refuses System/exit; response is well-formed"
    (let [r (post-eval "(System/exit 0)")]
      (is (false? (:ok? r)))
      (is (string? (:error/message r))))))

(deftest e2e-eval-timeout
  (testing "POST /agent/eval times out an infinite loop"
    (let [r (post-eval "(loop [] (recur))")]
      (is (false? (:ok? r)))
      (is (= "timeout" (name (keyword (:error/kind r))))))))

(deftest e2e-drift-derivation
  (testing "agent can derive drift from L0/L1 and from L2; same answer"
    (let [via-l1 (post-eval "(count (:rows (relations :kind :projects :validity :absent)))")
          via-l2 (post-eval "(count (drift))")]
      (is (= (:result via-l1) (:result via-l2))))))

(deftest e2e-help
  (testing "help surfaces L1 primitives entry"
    (let [r (post-eval "(get-in (help) ['fukan.agent.api :L1])")]
      (is (true? (:ok? r)))
      (is (some #(= "primitives" (name (:name %))) (:result r))))))
```

- [ ] **Step 2: Run all agent tests**

Run: `clojure -M:test -d test/fukan/agent`
Expected: all tests pass (~30+ assertions across the agent surface).

- [ ] **Step 3: Commit**

```bash
git add test/fukan/agent/integration_test.clj
git commit -m "test(agent): end-to-end smoke — sandbox refusal, timeout, drift derivation, help"
```

---

## Self-review

### Spec coverage

| Spec section | Task |
|---|---|
| §3 Architecture — `/agent/eval`, `/agent/status` endpoints | 19 |
| §3 — SCI sandbox, frozen ns map | 16, 17, 18 |
| §3 — CLI as thin client over loopback | 22, 23 |
| §4 L0 — `q` Datalog | 6 (built on 4, 5) |
| §4 L1 — `vocabulary`, `schema` | 10 |
| §4 L1 — `primitives`, `get-primitive` | 7, 8 |
| §4 L1 — `relations` | 9 |
| §4 L1 — `idioms`, `constraints`, `violations` | 11 |
| §4 L2 — `drift` (with L0/L1 equivalence test) | 12 |
| §4 L2 — `neighborhood` | 13 |
| §5 `agent.system` — `status`, `refresh` | 14 |
| §5 `agent.system` — `help`, `source` | 15 |
| §6 `.fukan/agent-views.clj` loader, partial-load, auto-load on refresh | 20, 21 |
| §7 `AGENTS.md` primer | 24 |
| §7 `fukan init` — additive on existing AGENTS.md | 25 |
| §7 `fukan primer` CLI | 24 |
| §8 Error envelope — `:syntax/:unbound/:runtime/:timeout/:forbidden` | 16, 17, 18 (verified e2e in 26) |
| §8 Safeguards — timeout, sandbox | 17, 18 |
| §8 Testing — L1 unit, L0/L1/L2 equivalence, sandbox refusals, agent-views loading, e2e | 7-13 (unit), 12 (equivalence), 18 (refusal), 20 (views), 26 (e2e) |

### Placeholder scan

- All code blocks contain runnable code, not pseudocode.
- All file paths are concrete.
- No "TODO", "TBD", "implement later" markers.
- Two intentional design notes (Task 5 evaluator scope, Task 15 `source-fn` fallback) are explanation, not gaps.

### Type consistency

- `:endpoint/primitive` used consistently in fixture, edb projection, L1 `relations` filtering, L2 `drift` and `neighborhood`.
- `:primitive/kind`, `:relation/kind`, `:relation/validity` predicates consistent across `edb.clj`, fixture EDN, and L0 `q` tests.
- Error envelope keys (`:ok?`, `:error/kind`, `:error/message`, `:error/elapsed-ms`) consistent across `sci.clj` and integration tests.
- `(help)` shape — nested `{ns {:L0 […] :L1 […] :L2 […]}}` — consistent between Task 15 implementation and Task 26 e2e tests.

### Scope check

This plan implements the full MVP spec in one pass. The phases form a clean dependency chain (deps → edb → query → api → system → sci → http → views → cli → primer → e2e), so subagent-driven execution can verify each phase independently. Task 20 has a non-trivial cross-task dependency (updates `sci.clj` to use a shared view ctx) — flagged inline.

---

## Notes for the executor

- **Run all agent tests with:** `clojure -M:test -d test/fukan/agent` after each phase.
- **Daemon must be running for Phase 8+ manual smoke.** `clj -M:run` in another terminal.
- **Babashka required for CLI.** Install via `brew install borkdude/brew/babashka` if absent.
- **SCI version pin** (`0.8.41`) is a reasonable known-stable target. If the latest stable differs significantly when executing, hold the pin or update both `deps.edn` and any breaking-API call sites together.
- **The constraint-evaluator integration in Phase 2 is deliberately tighter than the full `fukan.constraint.evaluator/query` form.** It uses the same EDB shape, so the upgrade path (when aggregations or stratified negation become needed at L0) is a swap, not a rewrite. Out of MVP scope.
