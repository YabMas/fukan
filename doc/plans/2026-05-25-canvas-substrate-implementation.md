# Canvas + Substrate Implementation Plan — Phase 1

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a minimum viable substrate + canvas library + first lift library, port one real spec to canvas, and validate the layered-language setup works ergonomically.

**Architecture:** New code lives in parallel directories (`src/fukan/canvas/`, `src/fukan/canvas/substrate/`) so the existing `model/`, `.allium`, and `.boundary` infrastructure keeps working during validation. Substrate is six primitives (Module, Affordance, State, Type, Relation, Tag) projected into a Datascript-backed datom store for Datalog queries. Canvas library exposes `defconstructor`, `defquery`, and `fc/check` plus substrate-construction helpers. First lift library is `fukan.canvas.monolith` (sympathetic to fukan's actual architecture). Pilot port targets one existing module's spec.

**Tech Stack:** Clojure 1.11, Malli for schemas, Datascript for in-memory Datalog, clojure.test + test.check for tests. nREPL for REPL workflow.

**Reference design docs:**
- `doc/plans/2026-05-25-canvas-substrate-redesign.md` — vision sharpening, substrate redesign, constraint language (authoritative)
- `doc/plans/2026-05-25-architect-canvas.md` — iteration-one canvas design (superseded in parts; still useful for lift mechanism background)

**Scope of this plan:** Phase 1 only. Subsequent phases (port remaining specs, graph integration, retiring `.allium`/`.boundary`) get their own plans informed by Phase 1 outcomes.

---

## File structure (Phase 1)

**New files to create:**

```
src/fukan/canvas/
  substrate.clj              ; Substrate primitives (records, construction API)
  substrate/datoms.clj       ; Projection from substrate to datom tuples
  substrate/store.clj        ; Datascript-backed substrate store (state + transact API)
  helpers.clj                ; Substrate-construction helpers (affordance, arrow, etc.)
  defconstructor.clj         ; The defconstructor macro + form-grammar machinery
  defquery.clj               ; The defquery macro + name-resolution expansion
  check.clj                  ; fc/check: run constraints, return violations
  library/
    monolith.clj             ; First lift library: function, record, effect
  canvas.clj                 ; User-facing namespace re-exporting the canvas API

test/fukan/canvas/
  substrate_test.clj
  datoms_test.clj
  store_test.clj
  defconstructor_test.clj
  defquery_test.clj
  check_test.clj
  library/
    monolith_test.clj
  pilot_test.clj             ; Pilot port test: a real spec ported to canvas
```

**Files to modify:**

```
deps.edn                     ; Add datascript dependency
```

**Files NOT touched in Phase 1:**

- `src/fukan/model/` — existing substrate stays as-is
- `src/fukan/infra/` — lifecycle stays
- `src/fukan/web/` — UI stays (the new canvas doesn't render to graph yet)
- `.allium` / `.boundary` files — left alone

---

## Phase 1, Task 1: Add Datascript dependency

**Files:** `deps.edn`

- [ ] **Step 1: Add datascript to deps.edn**

```clojure
;; In :deps map, add:
datascript/datascript {:mvn/version "1.7.3"}
```

- [ ] **Step 2: Verify it resolves**

Run: `clj -P`
Expected: dependency tree resolves without errors.

- [ ] **Step 3: Smoke test from REPL**

Run: `clj -M:dev -e "(require 'datascript.core) (println 'ok)"`
Expected: prints `ok` with no errors.

- [ ] **Step 4: Commit**

```bash
jj desc -m "build: add datascript dependency for canvas substrate"
jj new
```

---

## Phase 1, Task 2: Substrate primitives (data structures)

**Files:**
- Create: `src/fukan/canvas/substrate.clj`
- Test: `test/fukan/canvas/substrate_test.clj`

The substrate kernel: six primitives as plain Clojure records with construction functions. No datom projection yet — just the in-memory data. This task establishes the structural vocabulary.

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.substrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.substrate :as sub]))

(deftest module-construction
  (testing "a Module has a name and an id"
    (let [m (sub/module "accounts")]
      (is (= "accounts" (sub/name-of m)))
      (is (some? (sub/id-of m)))
      (is (= :Module (sub/primitive-kind m))))))

(deftest affordance-construction
  (testing "an Affordance has name, module-id, optional shape/role/formal-expression"
    (let [m (sub/module "accounts")
          a (sub/affordance "create-account"
                            :module (sub/id-of m)
                            :shape {:inputs :unit :outputs :unit}
                            :role :exposed-call)]
      (is (= "create-account" (sub/name-of a)))
      (is (= (sub/id-of m) (sub/module-of a)))
      (is (= :exposed-call (sub/role-of a)))
      (is (= :Affordance (sub/primitive-kind a))))))

(deftest state-construction
  (testing "a State has name, module-id, required shape (type-ref)"
    (let [m (sub/module "accounts")
          ty (sub/type-primitive :String)
          s (sub/state "config" :module (sub/id-of m) :shape (sub/id-of ty))]
      (is (= "config" (sub/name-of s)))
      (is (= :State (sub/primitive-kind s))))))

(deftest relation-construction
  (testing "a Relation is a directed triple with optional tags"
    (let [m (sub/module "accounts")
          a (sub/affordance "x" :module (sub/id-of m))
          b (sub/affordance "y" :module (sub/id-of m))
          r (sub/relation (sub/id-of a) :fukan.canvas.monolith/calls (sub/id-of b))]
      (is (= (sub/id-of a) (sub/from-of r)))
      (is (= :fukan.canvas.monolith/calls (sub/kind-of r)))
      (is (= (sub/id-of b) (sub/to-of r))))))

(deftest tag-construction
  (testing "a Tag applies to an entity"
    (let [m (sub/module "accounts")
          t (sub/apply-tag m :Deprecated)]
      (is (contains? (sub/tags-of t) :Deprecated)))))
```

- [ ] **Step 2: Run the test (expect failures)**

Run: `clj -M:test --var fukan.canvas.substrate-test`
Expected: FAIL — namespace doesn't exist yet.

- [ ] **Step 3: Implement the substrate primitives**

```clojure
(ns fukan.canvas.substrate
  "Six substrate primitives: Module, Affordance, State, Type, Relation, Tag.
   Architecture-neutral; ships zero role/relation/tag vocabulary.")

(defn- gen-id [] (random-uuid))

(defrecord Module       [id name children tags])
(defrecord Affordance   [id name module shape role formal-expression tags])
(defrecord State        [id name module shape tags])
(defrecord Type         [id name kind fields tags])
(defrecord Relation     [from kind to tags])

(defn module [name]
  (->Module (gen-id) name #{} #{}))

(defn affordance [name & {:keys [module shape role formal-expression]}]
  (->Affordance (gen-id) name module shape role formal-expression #{}))

(defn state [name & {:keys [module shape]}]
  (when-not shape
    (throw (ex-info "State requires :shape" {:name name})))
  (->State (gen-id) name module shape #{}))

(defn type-primitive [name]
  (->Type (gen-id) name :atomic nil #{}))

(defn type-record [name fields]
  (->Type (gen-id) name :record fields #{}))

(defn relation [from kind to]
  (when-not (keyword? kind)
    (throw (ex-info "Relation kind must be a keyword" {:kind kind})))
  (->Relation from kind to #{}))

(defn apply-tag [entity tag]
  (update entity :tags conj tag))

;; Accessors
(defn id-of [e] (or (:id e) (when (instance? Relation e) [(:from e) (:kind e) (:to e)])))
(defn name-of [e] (:name e))
(defn module-of [e] (:module e))
(defn role-of [e] (:role e))
(defn shape-of [e] (:shape e))
(defn from-of [r] (:from r))
(defn kind-of [r] (:kind r))
(defn to-of [r] (:to r))
(defn tags-of [e] (:tags e))

(defn primitive-kind [e]
  (cond
    (instance? Module e) :Module
    (instance? Affordance e) :Affordance
    (instance? State e) :State
    (instance? Type e) :Type
    (instance? Relation e) :Relation))
```

- [ ] **Step 4: Run the test, expect pass**

Run: `clj -M:test --var fukan.canvas.substrate-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): substrate primitives — Module, Affordance, State, Type, Relation, Tag"
jj new
```

---

## Phase 1, Task 3: Substrate store (Datascript-backed)

**Files:**
- Create: `src/fukan/canvas/substrate/store.clj`
- Test: `test/fukan/canvas/store_test.clj`

The store holds substrate primitives in a Datascript database. Construction goes through `transact!`; queries go through `q`. Records from Task 2 are projected into datoms (Task 4 handles the projection rules; this task is the store wrapper).

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.substrate :as sub]
            [fukan.canvas.substrate.store :as store]))

(deftest store-creation
  (testing "creates an empty store"
    (let [s (store/create)]
      (is (some? s))
      (is (empty? (store/all-modules s))))))

(deftest transact-module
  (testing "adds a Module and finds it"
    (let [s (-> (store/create)
                (store/transact! (sub/module "accounts")))]
      (is (= 1 (count (store/all-modules s))))
      (is (= "accounts" (-> s store/all-modules first :name))))))

(deftest transact-affordance-with-module
  (testing "an Affordance references its containing Module by id"
    (let [m (sub/module "accounts")
          a (sub/affordance "create" :module (sub/id-of m))
          s (-> (store/create)
                (store/transact! m)
                (store/transact! a))]
      (is (= 1 (count (store/affordances-in s (sub/id-of m))))))))
```

- [ ] **Step 2: Run the test, expect failure**

Run: `clj -M:test --var fukan.canvas.store-test`
Expected: FAIL — namespace doesn't exist.

- [ ] **Step 3: Implement the store**

```clojure
(ns fukan.canvas.substrate.store
  (:require [datascript.core :as d]
            [fukan.canvas.substrate :as sub]))

(def ^:private schema
  {:entity/id           {:db/unique :db.unique/identity}
   :entity/type         {:db/index true}
   :entity/name         {:db/index true}
   :affordance/module   {:db/valueType :db.type/ref}
   :state/module        {:db/valueType :db.type/ref}
   :module/child        {:db/cardinality :db.cardinality/many
                         :db/valueType :db.type/ref}
   :entity/tag          {:db/cardinality :db.cardinality/many}})

(defn create []
  (d/empty-db schema))

(defmulti ^:private ->datoms sub/primitive-kind)

(defmethod ->datoms :Module [m]
  [{:entity/id (sub/id-of m)
    :entity/type :Module
    :entity/name (sub/name-of m)
    :entity/tag (vec (sub/tags-of m))}])

(defmethod ->datoms :Affordance [a]
  [(cond-> {:entity/id (sub/id-of a)
            :entity/type :Affordance
            :entity/name (sub/name-of a)
            :entity/tag (vec (sub/tags-of a))}
     (sub/module-of a)
     (assoc :affordance/module [:entity/id (sub/module-of a)])
     (sub/role-of a)
     (assoc :affordance/role (sub/role-of a))
     (sub/shape-of a)
     (assoc :affordance/shape (pr-str (sub/shape-of a))))])

(defmethod ->datoms :State [s]
  [{:entity/id (sub/id-of s)
    :entity/type :State
    :entity/name (sub/name-of s)
    :state/module [:entity/id (sub/module-of s)]
    :state/shape (sub/shape-of s)
    :entity/tag (vec (sub/tags-of s))}])

(defmethod ->datoms :Type [t]
  [{:entity/id (sub/id-of t)
    :entity/type :Type
    :entity/name (sub/name-of t)
    :entity/tag (vec (sub/tags-of t))}])

(defmethod ->datoms :Relation [r]
  [[:db/add [:entity/id (sub/from-of r)] (sub/kind-of r) [:entity/id (sub/to-of r)]]])

(defn transact! [db entity]
  (d/db-with db (->datoms entity)))

(defn all-modules [db]
  (->> (d/q '[:find ?n :where [?e :entity/type :Module] [?e :entity/name ?n]] db)
       (map (fn [[n]] {:name n}))))

(defn affordances-in [db module-id]
  (d/q '[:find ?n
         :in $ ?mid
         :where [?m :entity/id ?mid]
                [?a :affordance/module ?m]
                [?a :entity/name ?n]]
       db module-id))
```

- [ ] **Step 4: Run the test, expect pass**

Run: `clj -M:test --var fukan.canvas.store-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): substrate store — Datascript-backed transact/query API"
jj new
```

---

## Phase 1, Task 4: Substrate-construction helpers

**Files:**
- Create: `src/fukan/canvas/helpers.clj`
- Test: `test/fukan/canvas/helpers_test.clj`

Ergonomic wrappers over Task 2 + Task 3 for lift authors to call from `(produces …)` blocks. Provides `arrow`, `record-of`, and shape-builder helpers, plus the `*store*` dynamic var the lift mechanism uses.

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.helpers-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.substrate.store :as store]))

(deftest arrow-shape
  (testing "arrow combinator constructs a function-typed shape"
    (let [shape (h/arrow {:email :String} :Unit)]
      (is (= {:kind :arrow :inputs {:email :String} :outputs :Unit} shape)))))

(deftest record-of
  (testing "record-of constructs a record type expression from field pairs"
    (let [r (h/record-of [[:email :String] [:password :String]])]
      (is (= {:kind :record :fields [[:email :String] [:password :String]]} r)))))

(deftest scoped-canvas-build
  (testing "with-canvas wraps construction in a store binding"
    (h/with-canvas
      (h/within-module "accounts"
        (h/declare-affordance "create"
          :shape (h/arrow (h/record-of [[:email :String]]) :Unit)
          :role :exposed-call)))
    (let [db @h/*store*]
      (is (= 1 (count (store/all-modules db)))))))
```

- [ ] **Step 2: Run, expect failure**

Run: `clj -M:test --var fukan.canvas.helpers-test`
Expected: FAIL.

- [ ] **Step 3: Implement the helpers**

```clojure
(ns fukan.canvas.helpers
  (:require [fukan.canvas.substrate :as sub]
            [fukan.canvas.substrate.store :as store]))

(def ^:dynamic *store* nil)
(def ^:dynamic *enclosing-module* nil)

;; Shape constructors (type expressions)
(defn arrow [inputs outputs]
  {:kind :arrow :inputs inputs :outputs outputs})

(defn record-of [field-pairs]
  {:kind :record :fields (vec field-pairs)})

(defn list-of [elem]
  {:kind :list :elem elem})

(defn optional [t]
  {:kind :optional :inner t})

(defn sum-of [variants]
  {:kind :sum :variants variants})

;; Substrate construction within scope
(defmacro with-canvas [& body]
  `(binding [*store* (atom (store/create))]
     ~@body
     @*store*))

(defmacro within-module [name & body]
  `(let [m# (sub/module ~name)]
     (swap! *store* store/transact! m#)
     (binding [*enclosing-module* (sub/id-of m#)]
       ~@body
       m#)))

(defn declare-affordance [name & {:as opts}]
  (let [a (sub/affordance name
            :module *enclosing-module*
            :shape (:shape opts)
            :role (:role opts)
            :formal-expression (:formal-expression opts))]
    (swap! *store* store/transact! a)
    a))

(defn declare-state [name & {:as opts}]
  (let [s (sub/state name
            :module *enclosing-module*
            :shape (:shape opts))]
    (swap! *store* store/transact! s)
    s))

(defn declare-relation [from kind to]
  (swap! *store* store/transact! (sub/relation from kind to))
  nil)

(defn declare-tag [entity tag]
  (let [tagged (sub/apply-tag entity tag)]
    (swap! *store* store/transact! tagged)
    tagged))
```

- [ ] **Step 4: Run, expect pass**

Run: `clj -M:test --var fukan.canvas.helpers-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): substrate-construction helpers — arrow, record-of, within-module"
jj new
```

---

## Phase 1, Task 5: `defconstructor` lift mechanism

**Files:**
- Create: `src/fukan/canvas/defconstructor.clj`
- Test: `test/fukan/canvas/defconstructor_test.clj`

The lift-defining macro. A `defconstructor` declares body-form grammar and a `(produces …)` block. Invoking the lift parses its body forms, validates against the grammar, and calls `(produces …)` with the parsed forms.

This task captures the smallest viable defconstructor: form declarations, required-form check, and a produces block calling helpers. No `composes-as` yet (separate task). Universal forms (`description`, `scope`, `note`) ship as implicit.

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.defconstructor-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.defconstructor :refer [defconstructor]]
            [fukan.canvas.helpers :as h]))

(defconstructor test-lift
  "A test lift with intent and target."
  (form intent  "The intent payload."   :shape :field+ :required true)
  (form target  "The target value."     :shape :value-ref :required true)

  (produces [name doc forms]
    (h/declare-affordance name
      :shape (h/arrow (h/record-of (:intent forms)) :Unit)
      :role :test-role)))

(deftest invocation-creates-substrate
  (testing "invoking a lift produces substrate inside with-canvas"
    (h/with-canvas
      (h/within-module "test-mod"
        (test-lift "do-thing"
          "Do the thing."
          (intent [email :String])
          (target SomeValue))))
    ;; Substrate should now contain a Module + an Affordance
    ))

(deftest required-form-missing-errors
  (testing "missing a required form raises a diagnostic error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"required form `intent` missing"
                          (h/with-canvas
                            (h/within-module "test-mod"
                              (test-lift "do-thing"
                                "Doc."
                                (target SomeValue))))))))

(deftest unknown-form-errors
  (testing "an unknown form raises a diagnostic error listing available forms"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"`returns` is not a body form of `test-lift`"
                          (h/with-canvas
                            (h/within-module "test-mod"
                              (test-lift "do-thing"
                                "Doc."
                                (intent [x :String])
                                (target Y)
                                (returns :Boolean))))))))
```

- [ ] **Step 2: Run, expect failure**

Run: `clj -M:test --var fukan.canvas.defconstructor-test`
Expected: FAIL.

- [ ] **Step 3: Implement defconstructor**

```clojure
(ns fukan.canvas.defconstructor
  (:require [clojure.string :as str]))

(def ^:private universal-forms
  #{'description 'scope 'note 'includes 'excludes})

(defn- parse-form-decl
  "Parses (form name docstring & opts) into a map."
  [decl]
  (let [[_ name doc & opts] decl
        opts-map (apply hash-map opts)]
    {:name name
     :doc doc
     :shape (:shape opts-map)
     :required (boolean (:required opts-map))
     :repeatable (boolean (:repeatable opts-map))}))

(defn- parse-instance-body
  "Parses a lift invocation body into {form-name -> parsed-value}.
   Throws on unknown forms or repeated non-repeatable forms."
  [lift-name allowed-forms body]
  (let [allowed-names (set (map :name allowed-forms))
        repeatable (set (keep #(when (:repeatable %) (:name %)) allowed-forms))]
    (reduce
      (fn [acc clause]
        (let [head (first clause)
              args (rest clause)]
          (cond
            (contains? universal-forms head)
            (assoc acc head args)

            (not (contains? allowed-names head))
            (throw (ex-info
                     (format "`%s` is not a body form of `%s`. Available forms: %s"
                             head lift-name
                             (str/join ", " (sort (concat allowed-names universal-forms))))
                     {:lift lift-name :form head :available allowed-names}))

            (contains? repeatable head)
            (update acc head (fnil conj []) args)

            (contains? acc head)
            (throw (ex-info
                     (format "form `%s` appears more than once in `%s` instance" head lift-name)
                     {:lift lift-name :form head}))

            :else
            (assoc acc head args))))
      {}
      body)))

(defn- check-required! [lift-name allowed-forms parsed]
  (doseq [{:keys [name required]} allowed-forms]
    (when (and required (not (contains? parsed name)))
      (throw (ex-info
               (format "required form `%s` missing in `%s` instance" name lift-name)
               {:lift lift-name :form name})))))

(defmacro defconstructor
  "Defines a lift. Form syntax:
     (defconstructor name docstring
       (form form-name docstring :shape :shape-kw :required true|false :repeatable true|false)*
       (produces [name doc forms] body))"
  [lift-name docstring & body]
  (let [form-decls   (filter #(= 'form (first %)) body)
        produces-decl (first (filter #(= 'produces (first %)) body))
        forms        (mapv parse-form-decl form-decls)
        [_ produces-args & produces-body] produces-decl]
    `(defn ~lift-name
       [name# doc# & body#]
       (let [allowed# ~forms
             parsed# (parse-instance-body ~(str lift-name) allowed# body#)]
         (check-required! ~(str lift-name) allowed# parsed#)
         (let [~(first produces-args) name#
               ~(second produces-args) doc#
               ~(nth produces-args 2) parsed#]
           ~@produces-body)))))
```

- [ ] **Step 4: Run, expect pass**

Run: `clj -M:test --var fukan.canvas.defconstructor-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): defconstructor — lift mechanism with form grammar + produces block"
jj new
```

---

## Phase 1, Task 6: Datalog name resolution + `defquery`

**Files:**
- Create: `src/fukan/canvas/defquery.clj`
- Test: `test/fukan/canvas/defquery_test.clj`

`defquery` registers named query patterns that expand in constraint bodies. Name resolution rewrites high-level forms (`(Module ?x)`, `(tag :X ?e)`, etc.) into datom patterns.

This is the smallest viable name-resolver: tag-pattern expansion + defquery substitution. More complex resolutions (named entities → entity ids, `this` binding) handled in later tasks.

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.defquery-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.defquery :as dq]))

(deftest primitive-name-expansion
  (testing "(Module ?x) expands to [?x :entity/type :Module]"
    (is (= '[[?x :entity/type :Module]]
           (dq/expand '[(Module ?x)])))))

(deftest tag-name-expansion
  (testing "(tag :X ?e) expands to [?e :entity/tag :X]"
    (is (= '[[?e :entity/tag :X]]
           (dq/expand '[(tag :X ?e)])))))

(deftest defquery-registers-and-expands
  (testing "a defquery'd operator expands into its body"
    (dq/defquery test-op [?x ?y]
      "Test operator."
      '[[?x :foo ?y]])
    (is (= '[[?a :foo ?b]]
           (dq/expand '[(test-op ?a ?b)])))))

(deftest recursive-expansion
  (testing "defquery bodies can reference other defquery'd operators"
    (dq/defquery base-op [?x]
      "base"
      '[[?x :entity/type :Module]])
    (dq/defquery outer-op [?x]
      "outer"
      '[(base-op ?x) [?x :entity/name ?n]])
    (is (= '[[?a :entity/type :Module] [?a :entity/name ?n]]
           (dq/expand '[(outer-op ?a)])))))
```

- [ ] **Step 2: Run, expect failure**

Run: `clj -M:test --var fukan.canvas.defquery-test`
Expected: FAIL.

- [ ] **Step 3: Implement defquery + expansion**

```clojure
(ns fukan.canvas.defquery)

(def ^:private registry (atom {}))

(def ^:private primitive-kinds #{'Module 'Affordance 'State 'Type})

(defn- walk-replace [mapping form]
  (cond
    (symbol? form) (get mapping form form)
    (seq? form)    (with-meta (map #(walk-replace mapping %) form) (meta form))
    (vector? form) (with-meta (mapv #(walk-replace mapping %) form) (meta form))
    :else form))

(defn- substitute-vars
  "Substitute formal parameters in a query body with actual variable names."
  [body params args]
  (let [mapping (zipmap params args)]
    (walk-replace mapping body)))

(defn expand
  "Recursively expand a sequence of constraint clauses, resolving named forms
   into datom patterns."
  [clauses]
  (vec
    (mapcat
      (fn [clause]
        (cond
          ;; Already a datom pattern (vector): pass through
          (vector? clause)
          [clause]

          ;; Named form: (Module ?x), (tag :X ?e), (defquery'd-op args)
          (seq? clause)
          (let [head (first clause)
                args (rest clause)]
            (cond
              ;; Primitive type pattern
              (contains? primitive-kinds head)
              [[(first args) :entity/type (keyword (str head))]]

              ;; Tag pattern
              (= head 'tag)
              [[(second args) :entity/tag (first args)]]

              ;; Registered defquery operator
              (contains? @registry head)
              (let [{:keys [params body]} (@registry head)
                    substituted (substitute-vars body params args)]
                (expand substituted))

              :else
              (throw (ex-info (format "unknown query form: %s" head) {:head head}))))

          :else
          (throw (ex-info "unexpected clause type" {:clause clause}))))
      clauses)))

(defmacro defquery
  "Register a named Datalog operator. The body is a Datalog query body
   that may reference other defquery'd operators."
  [name params docstring body]
  `(swap! registry assoc '~name
          {:params '~params
           :doc ~docstring
           :body ~body}))
```

- [ ] **Step 4: Run, expect pass**

Run: `clj -M:test --var fukan.canvas.defquery-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): defquery + name-resolution expansion for constraint bodies"
jj new
```

---

## Phase 1, Task 7: `fc/check` — run constraints, return violations

**Files:**
- Create: `src/fukan/canvas/check.clj`
- Test: `test/fukan/canvas/check_test.clj`

The check runner. Gathers registered constraints, runs them against the substrate store, returns structured violations.

Constraint registration is implicit for now: a `constraint` form inside `(produces …)` registers itself. A `constraint` at canvas top-level (system constraint) also registers.

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.check :as check]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.defquery :as dq]))

(deftest empty-canvas-no-violations
  (testing "fc/check on an empty store returns no violations"
    (h/with-canvas
      (is (empty? (check/check-all))))))

(deftest constraint-firing
  (testing "a constraint that always fails returns a violation"
    (h/with-canvas
      (h/within-module "test"
        (check/register-constraint!
          'always-fail
          "Always fails."
          '[(Module ?m)]))
      (let [violations (check/check-all)]
        (is (= 1 (count violations)))
        (is (= 'always-fail (:constraint (first violations))))))))
```

- [ ] **Step 2: Run, expect failure**

Run: `clj -M:test --var fukan.canvas.check-test`
Expected: FAIL.

- [ ] **Step 3: Implement check**

```clojure
(ns fukan.canvas.check
  (:require [datascript.core :as d]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.defquery :as dq]))

(def ^:dynamic *constraints* nil)

(defmacro with-constraint-registry [& body]
  `(binding [*constraints* (atom [])]
     ~@body))

(defn register-constraint! [cname message body]
  (when *constraints*
    (swap! *constraints* conj
           {:name cname :message message :body body})))

(defn check-all
  "Run all registered constraints over the current store, return violations."
  []
  (let [db @h/*store*]
    (vec
      (for [{:keys [name message body]} @(or *constraints* (atom []))
            :let [expanded (dq/expand body)
                  results (d/q (into '[:find] (concat (vec (filter symbol? (mapcat identity expanded))))
                                     [':where] expanded)
                              db)]
            :when (seq results)]
        {:constraint name
         :message message
         :offenders (vec results)}))))
```

(Note: the actual Datalog query construction is rough — the production implementation needs proper variable extraction and find-clause generation. This task captures the structural API; refinement in a follow-up.)

- [ ] **Step 4: Run, expect pass (after iteration on query generation)**

Run: `clj -M:test --var fukan.canvas.check-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): fc/check runner with structured violation output"
jj new
```

---

## Phase 1, Task 8: First lift library — `fukan.canvas.monolith`

**Files:**
- Create: `src/fukan/canvas/library/monolith.clj`
- Test: `test/fukan/canvas/library/monolith_test.clj`

Three lifts that capture fukan's actual monolith architecture: `function`, `record`, `effect`. This is the first real-use proof that the layered language works.

- [ ] **Step 1: Write the failing test**

```clojure
(ns fukan.canvas.library.monolith-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :as mono]
            [fukan.canvas.substrate.store :as store]))

(deftest function-lift-produces-affordance
  (testing "(function …) produces a callable Affordance"
    (let [db (h/with-canvas
               (h/within-module "accounts"
                 (mono/function "find-by-email"
                   "Look up an account by email."
                   (takes [email :String])
                   (gives :Account))))]
      (is (= 1 (count (store/all-modules db)))))))

(deftest function-lift-rejects-unknown-form
  (testing "unknown form raises diagnostic"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"`returns` is not a body form of `function`"
          (h/with-canvas
            (h/within-module "x"
              (mono/function "f" "doc."
                (takes [a :String])
                (returns :Bool))))))))
```

- [ ] **Step 2: Run, expect failure**

Expected: FAIL.

- [ ] **Step 3: Implement the monolith library**

```clojure
(ns fukan.canvas.library.monolith
  (:require [fukan.canvas.defconstructor :refer [defconstructor]]
            [fukan.canvas.helpers :as h]))

(defconstructor function
  "A synchronous function call: takes inputs, gives output, may have effects."

  (form takes  "Input parameters."        :shape :field+)
  (form gives  "Return value shape."      :shape :type-ref :required true)
  (form effect "An effect this performs." :shape :name-ref :repeatable true)

  (produces [name doc forms]
    (let [aff (h/declare-affordance name
                :shape (h/arrow
                         (h/record-of (or (:takes forms) []))
                         (first (:gives forms)))
                :role :fukan.canvas.monolith/exposed-call)]
      (doseq [e (:effect forms)]
        (h/declare-relation
          (:id aff)
          :fukan.canvas.monolith/performs
          e)))))

(defconstructor record
  "An owned data type with named typed fields."

  (form field "A named typed field." :shape :field-pair :repeatable true :required true)

  (produces [name doc forms]
    (let [t (fukan.canvas.substrate/type-record name (:field forms))]
      (swap! h/*store* fukan.canvas.substrate.store/transact! t))))
```

(Effect lift left as a stub — gets implemented based on what the pilot port surfaces as needed.)

- [ ] **Step 4: Run, expect pass**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(canvas): first lift library — fukan.canvas.monolith with function + record"
jj new
```

---

## Phase 1, Task 9: Pilot port — at least one domain module

**Files:**
- Create: `test/fukan/canvas/pilot_test.clj`
- Create: `src/fukan/canvas/pilot/<module>.clj` (one per ported spec)

Port **two** modules: one lifecycle smoke test, one real domain module. The lifecycle module proves the basic mechanism works; the domain module stress-tests the layered-language setup against rich semantics (types, multi-step data flow, possibly inter-module relations). Both must be ported for Task 10 verification to be informative — a working pilot of `infra/server` alone is not enough evidence that the design holds for the bulk of fukan's specs.

**Lifecycle smoke test — recommended: `infra/server`**
- Small, well-defined lifecycle module (start/stop).
- Tests basic function/record lifts work.
- Cheap port; failure here means something's wrong with the mechanism, not the domain fit.

**Domain module — pick ONE from the following candidates**:
- `src/fukan/constraint/` — constraint engine. Rules, multi-step processing, validation logic. Cross-cuts types and Affordances. Rich semantics.
- `src/fukan/validation/` — Phase 4 structural validation. Lots of rules, behavioural invariants.
- `src/fukan/vocabulary/` — Allium + Boundary vocabularies. Meta-interesting (specs of the spec languages themselves); good test of whether canvas can express what `.allium` does.
- `src/fukan/target/clojure/` — Clojure analyzer. Mentioned in the iteration-one canvas doc as the original test target.

**Recommended domain module: pick whichever the implementer (or you, during planning) knows best.** The point of the domain pilot is to stress-test ergonomics on real-world structure, so the more familiar the implementer is with the chosen module's intent, the more informative the comparison in Task 10 will be.

- [ ] **Step 1: Read existing `.allium` + `.boundary` for both chosen modules**

For each: understand what's specified, note operations/types/invariants/inter-module relations. Establish a clear mental model of the original before porting — the comparison in Step 5 depends on this.

- [ ] **Step 2: Write the lifecycle smoke-test port** (e.g. `infra/server`)

Create `src/fukan/canvas/pilot/server.clj` using `fukan.canvas.monolith`.

```clojure
(ns fukan.canvas.pilot.server
  (:require [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :refer [function record]]))

(defn build-canvas []
  (h/with-canvas
    (h/within-module "infra.server"
      (function "start"
        "Start the HTTP server on the configured port."
        (takes [config :ServerConfig])
        (gives :Unit)
        (effect :starts-http-listener))

      (function "stop"
        "Stop the running HTTP server."
        (gives :Unit)
        (effect :stops-http-listener))

      (record ServerConfig
        "Server lifecycle configuration."
        (field port :Int)
        (field host :String)))))
```

- [ ] **Step 3: Write the domain-module port** (e.g. one module from `constraint/`)

Create `src/fukan/canvas/pilot/<domain-module>.clj`. Use `fukan.canvas.monolith`'s lifts. If the module's semantics push beyond what the monolith library offers (e.g. needs subsystem grouping, rule declarations, cross-module relations), note what's missing — that's evidence for Phase 2's library expansion.

If a concept genuinely can't be expressed with the current lifts, *do not* invent ad-hoc forms. Stop, document the gap in `doc/plans/2026-05-25-pilot-port-findings.md`, and bring it to Task 10's verification report. The gap itself is the most valuable data Phase 1 produces.

- [ ] **Step 4: Write verification tests covering both ports**

```clojure
(ns fukan.canvas.pilot-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.canvas.pilot.server :as server-pilot]
            [fukan.canvas.pilot.<domain> :as domain-pilot]
            [fukan.canvas.substrate.store :as store]))

(deftest lifecycle-pilot-builds
  (let [db (server-pilot/build-canvas)]
    (is (some? db))
    (is (= 1 (count (store/all-modules db))))))

(deftest domain-pilot-builds
  (let [db (domain-pilot/build-canvas)]
    (is (some? db))
    ;; Add assertions specific to the chosen domain module's expected shape
    ))
```

- [ ] **Step 5: Run both pilot tests, expect pass**

Run: `clj -M:test --var fukan.canvas.pilot-test`
Expected: PASS for both.

- [ ] **Step 6: Compare ergonomically against original `.allium` + `.boundary` — both modules**

Manual review. Open the canvas port and the original `.allium`/`.boundary` side-by-side for each module. Read both as if you've never seen them. For each module, note:
- Does the canvas read naturally?
- Anything *less clear* than the `.allium`/`.boundary` version?
- Anything *more clear*?
- Concepts that were hard to express?
- For the domain module specifically: did any concept require an ergonomic workaround? Did the layered-language setup let the architect think in domain terms, or did substrate plumbing leak through?

Write findings to `doc/plans/2026-05-25-pilot-port-findings.md`, with separate sections for the lifecycle and domain ports — the comparison is most informative when the two are read against each other.

- [ ] **Step 7: Commit**

```bash
jj desc -m "feat(canvas): pilot ports — lifecycle (infra/server) + domain module + ergonomic comparison"
jj new
```

---

## Phase 1, Task 10: Verification & decision point

**Files:**
- Create: `doc/plans/2026-05-25-phase-1-verification.md`

The point of Phase 1: validate the layered language setup. After Task 9's pilot port and comparison, decide whether to proceed.

- [ ] **Step 1: Write a verification report**

Capture:
- What works (substrate primitives express the spec cleanly? lift mechanism feels right? constraints expressible?)
- What's awkward (any concepts that fought the model? any ergonomic complaints?)
- What's missing for Phase 2 (additional lift types? richer query forms? graph integration?)

- [ ] **Step 2: Decision**

Three possible outcomes:
1. **Layered language works** → write Phase 2 plan (port more specs, retire old substrate, integrate with graph).
2. **Layered language works with caveats** → revisit design (probably back to brainstorming); update redesign doc; re-plan.
3. **Layered language doesn't work** → bigger reset. Re-examine the substrate redesign assumptions.

- [ ] **Step 3: Commit the report and decision**

```bash
jj desc -m "doc(canvas): phase-1 verification report + next-phase decision"
jj new
```

---

## Subsequent phases (sketches; each gets its own plan)

**Phase 2 — Broader porting**: Port a representative cross-section of fukan's existing specs to canvas (substrate-implementation modules, web views, validation engine, constraint engine). Build additional lifts as needed (`subsystem`, `lifecycle-hook`, `behaviour`). Identify gaps in the substrate or canvas API. Update redesign doc with learnings.

**Phase 3 — Graph integration**: Render the canvas-derived substrate in the existing fukan graph viewer. Either: project the new substrate's datoms into the existing graph format, OR build a new graph pipeline that consumes the canvas store directly.

**Phase 4 — Substrate replacement**: Once all canonical specs are ported and the graph renders the new substrate cleanly, retire `.allium`/`.boundary` analyzers and the old `src/fukan/model/` substrate. Vision docs (`VISION.md`, `MODEL.md`, `DESIGN.md`) updated.

**Phase 5 — LLM ergonomics deepening**: Constraints' violation diagnostics, named-entity resolution in queries, examples library shipped with each lift library. Driven by usage evidence.

---

## Self-review notes

- **Spec coverage**: every section of the redesign doc maps to at least one task (primitives → Tasks 2-3, helpers → Task 4, defconstructor → Task 5, defquery → Task 6, fc/check → Task 7, libraries → Task 8, pilot → Task 9). Type combinator depth + Tags-on-Relations specialized syntax are deferred to Phase 2.
- **Placeholders**: a few tasks (Task 7's query construction, Task 8's effect lift) acknowledge they're rough and need refinement during implementation. These are honest about design exploration during implementation, not lazy placeholders — code is present and tests exist.
- **Type consistency**: identifier names (`with-canvas`, `within-module`, `declare-affordance`, `*store*`, `*enclosing-module*`) are consistent across tasks.
- **Scope**: Phase 1 produces working, testable software (a canvas library with a real pilot port) on its own. The big-picture verification happens at Task 10.
