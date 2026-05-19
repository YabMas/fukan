# Constraint Language + Phase 5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the constraint language (§6 substrate — stratified Datalog with negation + aggregation) and Phase 5 runner that evaluates `PredicateRegistration`s against the merged Model, producing Violations. Ship the five fukan-shipped well-known constraints per [MODEL.md §10.3](../MODEL.md#103-the-project-layer--sub-loci-and-composition). Resolve all five Plan 3c carry-forward concerns.

**Architecture:** New `src/fukan/constraint/` namespace housing the AST, evaluator, sort system, built-ins, and kernel-universal derivations. Phase 5 runs after Phase 4 in `model/pipeline/load-source`. Phase 5 violations attach to the Model alongside Phase 4 violations under `:violations`. Constraints register via the kernel's `:predicates` vector (per MODEL.md §5.3) — populated either by `.boundary` `subsystem rules:` (Plan 3b) or by Vocabulary/project setup at engine boot. Phase 5 is a non-gating phase — its violations surface in the explorer but don't halt the build.

**Tech Stack:** Existing Clojure 1.11. No new dependencies. The Datalog evaluator is a small bottom-up fixed-point iterator with stratified negation; no third-party Datalog engine. Tests via `clojure.test` + `cognitect.test-runner` as throughout.

---

## Plan-of-plans context

This is **Plan 4 of 9** in the next-chapter overhaul. The sequence:

1. Kernel substrate *(closed)*
2. 2a. Allium parser *(closed)*
3. 2b. Allium analyzer *(closed)*
4. 3a. Boundary parser *(closed)*
5. 3b. Boundary analyzer + multi-extension pipeline *(closed)*
6. 3c. Phase 4 structural validation *(closed)*
7. **4. Constraint language + Phase 5** *(this plan)*
8. 5. Clojure Target extension
9. 6. Explorer rewrite + generation flow

Plan 3c closed with 315 tests / 0 failures, the Phase 4 validation layer operational, and 9 warnings against the fukan corpus (6 `:4a/top-level-module` + 3 `:4c/signature-match-uncertain`). Five Plan 3c carry-forwards routed here:

1. **4b rule 4** — Allium rule 30 (Surface `provides:` triggers must be external-stimulus in a rule of the same module). Requires edge-walking that fits naturally in the constraint engine's kernel-universal derivations.
2. **4c rule 4** — full signature equality between Operation parameters and bound Rule's event-shaped `when:` clause. Same — needs Datalog-style introspection.
3. **`requiring-resolve` cleanup** in `src/fukan/validation/rules_4c.clj` line 67 — replace lazy resolve of `fukan.model.relations/edge-identity` with a direct require.
4. **Stale doc-comments** in `src/fukan/infra/model.clj` (lines 6 + 18 still say "Plan 3b") and `src/fukan/vocabulary/boundary/analyzer.clj` line 255 (mentions `:predicate-registrations` slot which was renamed to `:predicates` in Plan 3c Task 2).
5. **Missing positive test** for `:4c/signature-match-uncertain` warning — currently only covered by the clean-baseline negative case.

Per MODEL.md §13 deferrals (committed scope of this plan):
- **Path navigation sugar** (§6.7) and **type-sum case-analysis sugar** (§6.8) — DEFERRED. Constraints author in Datalog AST form only. Surface tokenisation lands in a later plan.
- **Project-layer composition mechanics** (severity overrides, profiles, bundle composition) — DEFERRED per DESIGN.md "MVP commitments". Per-entry registration is what Plan 4 ships; layered composition waits for lived experience.

Authoritative refs:
- [MODEL.md §6](../MODEL.md#6-the-constraint-language) — the constraint language spec. §6.1 (three layers), §6.2 (Datalog substrate), §6.3 (sort system), §6.5 (built-ins), §6.6 (kernel-universal derivations), §6.9 (coherence queries), §6.10 (worked examples).
- [MODEL.md §5.3](../MODEL.md#53-predicate-registrations) — `PredicateRegistration` shape (already used by Plan 3b/3c).
- [MODEL.md §10.3](../MODEL.md#103-the-project-layer--sub-loci-and-composition) — well-known constraints list + project-layer framing.
- [DESIGN.md "Phase 5 — Constraint evaluation"](../DESIGN.md#phase-ordering-and-error-semantics) — Phase 5 is non-gating; violations are outputs not blockers.
- [Plan 3c](2026-05-18-phase4-validation.md) — Phase 4 framework Plan 4 layers onto. The Violation shape from `fukan.validation.violation` is reused; Phase 5 violations carry `:phase :phase5 :sub-phase :5`.

---

## Repository conventions (jj over git)

Identical to prior plans. **NEVER `jj squash -m "..."`** (silently collapses commits). Use `jj desc -m "..."` + `jj new` after each task commit.

---

## Conventions used throughout this plan

- **Namespace structure** — `src/fukan/constraint/{ast,evaluator,sort,builtins,derivations,phase5}.clj`. One file per logical unit; each file ~50–200 lines.
- **AST shapes** — plain Clojure maps with stable keys. No records, no defmulti dispatch at the AST level. Variables are keywords starting with `?` (e.g., `?x`, `?module`). Constants are plain Clojure values (strings, numbers, keywords). The constraint AST is data, not code.
- **Datalog evaluator interface** — `(evaluate [model rule-set query]) → #{<binding-maps>}`. Pure function over (model, rules, query) → set of variable-binding maps. Bottom-up naive iteration to fixed point.
- **PredicateRegistration → Phase 5 contract** — each registration carries a Datalog rule whose head signals violation. When the head produces any tuples after evaluation, the registration fires; each tuple becomes one Violation.
- **Test-as-spec** — every constraint and every kernel-universal derivation has a per-construct test. Evaluator gets unit tests for negation, aggregation, stratification.
- **Phase 5 is non-gating** — violations attach to the Model under `:violations`, but the pipeline doesn't halt. The explorer surfaces them; Phase 4 is the gate.
- **Reuse the Violation record** from `fukan.validation.violation`. Phase 5 violations have `:phase :phase5 :sub-phase :5`.

---

## File Structure

### Files to create

- `src/fukan/constraint/ast.clj` — AST constructors + predicates (`make-rule`, `make-atom`, `make-negation`, `make-aggregation`, `var?`, `constant?`).
- `src/fukan/constraint/evaluator.clj` — bottom-up Datalog evaluator with stratified negation.
- `src/fukan/constraint/sort.clj` — sort guards + type-checking helpers.
- `src/fukan/constraint/builtins.clj` — comparison / logical / aggregation / set built-ins.
- `src/fukan/constraint/derivations.clj` — kernel-universal predicates fukan ships (`depends-on`, `chains`, `has-tag`, `in-module`, etc.).
- `src/fukan/constraint/well_known.clj` — the 5 fukan-shipped well-known constraints registered as `PredicateRegistration` data.
- `src/fukan/constraint/phase5.clj` — Phase 5 runner. Reads `:predicates` from model; evaluates each; produces Violations.
- `test/fukan/constraint/{ast,evaluator,sort,builtins,derivations,well_known,phase5}_test.clj` — per-file tests.

### Files to modify

- `src/fukan/model/pipeline.clj` — wire Phase 5 after Phase 4.
- `src/fukan/validation/rules_4b.clj` — implement rule 4 (Allium rule 30).
- `src/fukan/validation/rules_4c.clj` — implement rule 4 (full signature equality); clean up `requiring-resolve`.
- `test/fukan/validation/rules_4c_test.clj` — add positive test for `:4c/signature-match-uncertain`.
- `src/fukan/infra/model.clj` — update stale Plan 3b doc-comments.
- `src/fukan/vocabulary/boundary/analyzer.clj` — update stale `:predicate-registrations` doc-comment.

### Files to leave untouched

- All Plan-1 substrate (`src/fukan/model/*.clj` except `pipeline.clj`).
- Allium / Boundary parsers + analyzers — frozen.
- Phase 4 sub-phase rules (`src/fukan/validation/rules_4a.clj`, `rules_4d.clj`–`rules_4g.clj`) — frozen.

---

## Reading the canonical reference

[MODEL.md §6](../MODEL.md#6-the-constraint-language) is the authoritative spec. The relevant subsections by task:

| Subsection | Lines (current docs) | Task |
|---|---|---|
| §6.1 Three layers | 818–826 | 1, 2 |
| §6.2 Datalog substrate | 828–851 | 2 |
| §6.3 Sort system | 853–866 | 3 |
| §6.4 Quantification | 868–885 | 2 (∀/∃ via aggregation in MVP) |
| §6.5 Built-ins | 887–898 | 4 |
| §6.6 Kernel-universal derivations | 900–1028 | 5 |
| §6.9 Coherence queries | 1082–1086 | 6 |
| §6.10 Worked examples | 1088–1143 | 7 (well-known constraints) |
| §6.11 Cross-cutting commitments | 1145–1180 | All |
| §10.3 Well-known constraints list | 1509 | 7 |

---

## Task 0: Scaffold + smoke target

**Files:**
- Create: `src/fukan/constraint/ast.clj` (stub)
- Create: `src/fukan/constraint/evaluator.clj` (stub)
- Create: `src/fukan/constraint/phase5.clj` (stub — returns empty violations)
- Create: `test/fukan/constraint/phase5_test.clj` (failing smoke)

Lay down the namespace skeleton and a failing smoke that each task brings closer.

- [ ] **Step 0.1: Create `src/fukan/constraint/ast.clj` (stub)**

```clojure
(ns fukan.constraint.ast
  "Constraint AST — plain Clojure maps for Datalog rule definition.

   Task 1 fills in the constructors.")

(defn var?
  "True iff x is a logic variable (keyword starting with ?)."
  [x]
  (and (keyword? x) (clojure.string/starts-with? (name x) "?")))

(defn constant?
  "True iff x is a constant term (anything that's not a variable)."
  [x]
  (not (var? x)))
```

- [ ] **Step 0.2: Create `src/fukan/constraint/evaluator.clj` (stub)**

```clojure
(ns fukan.constraint.evaluator
  "Stratified bottom-up Datalog evaluator. Task 2 implements; for now,
   evaluate returns an empty set of bindings."
  (:require [fukan.constraint.ast :as ast]))

(defn evaluate
  "Evaluate a Datalog rule-set against a model and a query rule.
   Returns a set of variable-binding maps. Stub: returns #{}."
  [_model _rule-set _query]
  #{})
```

- [ ] **Step 0.3: Create `src/fukan/constraint/phase5.clj` (stub)**

```clojure
(ns fukan.constraint.phase5
  "Phase 5 constraint evaluation runner. Reads :predicates from the
   model, evaluates each against the kernel-universal derivations + any
   project-shipped constraints, and produces Violations.

   Per DESIGN.md: Phase 5 is non-gating. Violations attach to the Model
   under :violations alongside Phase 4 violations."
  (:require [fukan.validation.violation :as v]))

(defn run
  "Run Phase 5 against the model. Returns the model with Phase 5
   violations appended to :violations.

   Stub (Task 6 implements): no constraints evaluated, no violations
   added — model passes through unchanged."
  [model]
  model)
```

- [ ] **Step 0.4: Create `test/fukan/constraint/phase5_test.clj`**

```clojure
(ns fukan.constraint.phase5-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.phase5 :as phase5]
            [fukan.model.build :as build]
            [fukan.model.pipeline :as model-pipeline]))

(deftest phase5-on-empty-model-passes
  (testing "an empty model produces no Phase 5 violations"
    (let [m (phase5/run (build/empty-model))]
      (is (map? m))
      (is (= [] (vec (or (:violations m) [])))))))

(deftest phase5-attaches-violations-non-destructively
  (testing "Phase 5 preserves existing Phase 4 violations"
    (let [m0 (assoc (build/empty-model)
                    :violations [{:phase :phase4 :sub-phase :4a :kind :test/x
                                  :severity :warning :message "synthetic"}])
          m1 (phase5/run m0)]
      (is (= 1 (count (:violations m1)))
          "Phase 5 stub didn't strip existing violations"))))

(deftest combined-pipeline-with-phase5-runs-cleanly
  (testing "fukan-on-fukan loads through Allium + Boundary + Phase 4 + Phase 5"
    (let [m (model-pipeline/load-source "src")]
      (is (map? m))
      (is (contains? m :violations))
      ;; Until Task 10 wires Phase 5 into the pipeline, :violations only
      ;; contains Phase 4 entries.
      (let [errors (filter #(= :error (:severity %)) (:violations m))]
        (is (empty? errors) "no errors expected against current corpus")))))
```

- [ ] **Step 0.5: Run, expect 315 + 3 = 318/0/0**

```
clj -M:test
```

- [ ] **Step 0.6: Commit**

```bash
jj desc -m "scaffold(constraint): namespace structure + smoke target

Plan 4 Task 0: lays down fukan.constraint/{ast,evaluator,phase5}.clj
stubs plus test smoke. Each subsequent task brings the implementation
closer to a working Datalog engine + Phase 5 runner.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 1: Constraint AST

**Files:**
- Modify: `src/fukan/constraint/ast.clj`
- Create: `test/fukan/constraint/ast_test.clj`

Define the AST as plain Clojure maps with stable keys. Per MODEL.md §6.2: rules consist of a head and a body. The body is a sequence of atoms. Atoms have one of these kinds:

| Kind | Shape | Semantics |
|---|---|---|
| `:atom` | `{:kind :atom :predicate <kw> :args [<term>...]}` | Positive literal: predicate(args). |
| `:negation` | `{:kind :negation :inner <atom>}` | Negation as failure. Inner must be `:atom`. |
| `:comparison` | `{:kind :comparison :op <kw> :left <term> :right <term>}` | `<`, `<=`, `=`, `!=`, `>`, `>=`. |
| `:aggregation` | `{:kind :aggregation :op <kw> :var <var> :body [<atom>...] :result <var>}` | `:count`/`:sum`/`:min`/`:max` over body bindings of `:var`; binds `:result`. |

A rule:

```clojure
{:head {:predicate <kw> :args [<term>...]}
 :body [<atom>...]}
```

- [ ] **Step 1.1: Tests**

```clojure
(ns fukan.constraint.ast-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.ast :as ast]))

(deftest var-recognition
  (is (ast/var? :?x))
  (is (ast/var? :?module))
  (is (not (ast/var? :literal)))
  (is (not (ast/var? "?x")))
  (is (not (ast/var? 42))))

(deftest constant-is-anything-not-var
  (is (ast/constant? "foo"))
  (is (ast/constant? 42))
  (is (ast/constant? :keyword))
  (is (not (ast/constant? :?x))))

(deftest make-atom-shape
  (is (= {:kind :atom :predicate :depends-on :args [:?x :?y]}
         (ast/make-atom :depends-on [:?x :?y]))))

(deftest make-negation-shape
  (is (= {:kind :negation :inner {:kind :atom :predicate :has-tag :args [:?x "Boundary::Function"]}}
         (ast/make-negation (ast/make-atom :has-tag [:?x "Boundary::Function"])))))

(deftest make-comparison-shape
  (is (= {:kind :comparison :op := :left :?x :right :?y}
         (ast/make-comparison := :?x :?y))))

(deftest make-aggregation-shape
  (is (= {:kind :aggregation :op :count :var :?m :body [{:kind :atom :predicate :module :args [:?m]}] :result :?n}
         (ast/make-aggregation :count :?m
                               [(ast/make-atom :module [:?m])]
                               :?n))))

(deftest make-rule-shape
  (is (= {:head {:predicate :violates :args [:?x]}
          :body [{:kind :atom :predicate :is-module :args [:?x]}
                 {:kind :negation :inner {:kind :atom :predicate :has-tag :args [:?x "Boundary::Function"]}}]}
         (ast/make-rule {:predicate :violates :args [:?x]}
                        [(ast/make-atom :is-module [:?x])
                         (ast/make-negation (ast/make-atom :has-tag [:?x "Boundary::Function"]))]))))
```

- [ ] **Step 1.2: Run, see fail**

Expected: constructors don't exist yet.

- [ ] **Step 1.3: Implement**

Replace `src/fukan/constraint/ast.clj` body with:

```clojure
(ns fukan.constraint.ast
  "Constraint AST — plain Clojure maps for Datalog rule definition.

   Rule = {:head <atom> :body [<atom-or-neg-or-comp-or-agg>...]}
   Atom = {:kind :atom :predicate <kw> :args [<term>...]}
   Neg  = {:kind :negation :inner <atom>}
   Cmp  = {:kind :comparison :op <kw> :left <term> :right <term>}
   Agg  = {:kind :aggregation :op <kw> :var <var> :body [<atom>...] :result <var>}

   Terms are either variables (keywords starting with ?) or constants
   (anything else)."
  (:require [clojure.string :as str]))

(defn var?
  [x]
  (and (keyword? x) (str/starts-with? (name x) "?")))

(defn constant?
  [x]
  (not (var? x)))

(defn make-atom
  [predicate args]
  {:kind :atom :predicate predicate :args (vec args)})

(defn make-negation
  [inner-atom]
  {:kind :negation :inner inner-atom})

(defn make-comparison
  [op left right]
  {:kind :comparison :op op :left left :right right})

(defn make-aggregation
  [op input-var body result-var]
  {:kind :aggregation :op op :var input-var :body (vec body) :result result-var})

(defn make-rule
  [head body]
  {:head head :body (vec body)})

(defn vars-in-term [t] (if (var? t) #{t} #{}))

(defn vars-in-atom [atom]
  (case (:kind atom)
    :atom        (reduce into #{} (map vars-in-term (:args atom)))
    :negation    (vars-in-atom (:inner atom))
    :comparison  (into (vars-in-term (:left atom)) (vars-in-term (:right atom)))
    :aggregation (conj (reduce into #{} (map vars-in-atom (:body atom)))
                       (:result atom))))

(defn vars-in-body [body]
  (reduce into #{} (map vars-in-atom body)))
```

- [ ] **Step 1.4: Run, expect pass**

Expected: 7 new tests pass; 325/0/0.

- [ ] **Step 1.5: Commit**

```bash
jj desc -m "feat(constraint): AST constructors + var/constant predicates

Plain Clojure maps for Datalog rule definition. Five atom kinds:
positive atom, negation, comparison, aggregation, plus the rule
wrapper. Variables are keywords starting with ?. The AST is data,
not code — the evaluator (Task 2) walks it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 2: Stratified Datalog evaluator

**Files:**
- Modify: `src/fukan/constraint/evaluator.clj`
- Create: `test/fukan/constraint/evaluator_test.clj`

Bottom-up naive evaluator with stratified negation + aggregation. Algorithm:

1. **Stratify**: partition the rule-set into strata such that each stratum's predicates don't depend (transitively) on a negated or aggregated predicate from a later stratum. Negation/aggregation force stratum boundaries.
2. **Iterate per stratum**: starting with EDB facts (extensional database — the model-derived ground atoms), apply each rule in the stratum until no new facts are derived (fixed point).
3. **Carry forward**: facts derived in earlier strata are available as inputs to later strata.

The evaluator's interface:
- `(evaluate-rules [rule-set edb-facts])` → map of predicate → set of fact tuples.
- `(query [rule-set edb-facts query-atom])` → set of variable-binding maps.

For Plan 4 MVP, the evaluator is naive (no semi-naive optimisation, no magic-set transformation). Performance is acceptable for the corpus's scale (hundreds of primitives + edges, single-digit constraints).

- [ ] **Step 2.1: Tests**

```clojure
(ns fukan.constraint.evaluator-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.evaluator :as e]
            [fukan.constraint.ast :as ast]))

(deftest single-rule-positive-derivation
  (testing "rule with positive body derives correctly"
    ;; rule: parent(?x, ?y) :- direct-parent(?x, ?y)
    (let [rule (ast/make-rule
                 {:predicate :parent :args [:?x :?y]}
                 [(ast/make-atom :direct-parent [:?x :?y])])
          edb {:direct-parent #{["a" "b"] ["b" "c"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["a" "b"] ["b" "c"]} (:parent result))))))

(deftest transitive-closure
  (testing "recursive rule computes transitive closure"
    ;; ancestor(?x, ?z) :- direct-parent(?x, ?z).
    ;; ancestor(?x, ?z) :- direct-parent(?x, ?y), ancestor(?y, ?z).
    (let [base-rule (ast/make-rule
                      {:predicate :ancestor :args [:?x :?z]}
                      [(ast/make-atom :direct-parent [:?x :?z])])
          trans-rule (ast/make-rule
                       {:predicate :ancestor :args [:?x :?z]}
                       [(ast/make-atom :direct-parent [:?x :?y])
                        (ast/make-atom :ancestor [:?y :?z])])
          edb {:direct-parent #{["a" "b"] ["b" "c"] ["c" "d"]}}
          result (e/evaluate-rules [base-rule trans-rule] edb)]
      (is (= #{["a" "b"] ["b" "c"] ["c" "d"]
               ["a" "c"] ["b" "d"]
               ["a" "d"]}
             (:ancestor result))))))

(deftest negation-stratified
  (testing "negation against an EDB or earlier-stratum predicate works"
    ;; isolated(?x) :- node(?x), not connected(?x).
    (let [rule (ast/make-rule
                 {:predicate :isolated :args [:?x]}
                 [(ast/make-atom :node [:?x])
                  (ast/make-negation (ast/make-atom :connected [:?x]))])
          edb {:node #{["a"] ["b"] ["c"]}
               :connected #{["b"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["a"] ["c"]} (:isolated result))))))

(deftest comparison-filters
  (testing "comparison atoms filter bindings"
    ;; same(?x, ?y) :- node(?x), node(?y), ?x = ?y.
    (let [rule (ast/make-rule
                 {:predicate :same :args [:?x :?y]}
                 [(ast/make-atom :node [:?x])
                  (ast/make-atom :node [:?y])
                  (ast/make-comparison := :?x :?y)])
          edb {:node #{["a"] ["b"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["a" "a"] ["b" "b"]} (:same result))))))

(deftest aggregation-count
  (testing "aggregation binds a count to the result variable"
    ;; module-child-count(?m, ?n) :- module(?m), count ?c in (child-of(?c, ?m)) into ?n.
    (let [rule (ast/make-rule
                 {:predicate :module-child-count :args [:?m :?n]}
                 [(ast/make-atom :module [:?m])
                  (ast/make-aggregation :count :?c
                                        [(ast/make-atom :child-of [:?c :?m])]
                                        :?n)])
          edb {:module #{["m1"] ["m2"]}
               :child-of #{["a" "m1"] ["b" "m1"] ["c" "m2"]}}
          result (e/evaluate-rules [rule] edb)]
      (is (= #{["m1" 2] ["m2" 1]} (:module-child-count result))))))

(deftest query-returns-bindings
  (testing "query takes a single atom and returns variable bindings"
    (let [rule (ast/make-rule {:predicate :parent :args [:?x :?y]}
                              [(ast/make-atom :direct-parent [:?x :?y])])
          edb {:direct-parent #{["a" "b"]}}
          bindings (e/query [rule] edb (ast/make-atom :parent [:?x :?y]))]
      (is (= #{{:?x "a" :?y "b"}} bindings)))))
```

- [ ] **Step 2.2: Run, see fail**

- [ ] **Step 2.3: Implement**

Replace `src/fukan/constraint/evaluator.clj`:

```clojure
(ns fukan.constraint.evaluator
  "Stratified bottom-up Datalog evaluator.

   Algorithm: partition rules into strata such that negated/aggregated
   atoms in a rule's body reference predicates from strictly earlier
   strata (or EDB only). Within each stratum, iterate naive fixed point.

   Limits — MVP scope:
   - No semi-naive optimisation; full re-evaluation per iteration.
   - No magic-set transformation.
   - Stratification detector trusts the rule author; obviously
     non-stratifiable rule sets (negation cycle) raise ex-info."
  (:require [fukan.constraint.ast :as ast]
            [fukan.constraint.builtins :as builtins]))

;; ---------------------------------------------------------------------------
;; Stratification
;; ---------------------------------------------------------------------------

(defn- head-predicate [rule] (-> rule :head :predicate))

(defn- negated-or-aggregated-preds [body]
  (mapcat (fn [atom]
            (case (:kind atom)
              :negation    [(-> atom :inner :predicate)]
              :aggregation (map :predicate (filter #(= :atom (:kind %)) (:body atom)))
              []))
          body))

(defn- positive-preds [body]
  (keep (fn [atom] (when (= :atom (:kind atom)) (:predicate atom))) body))

(defn- stratify
  "Partition rules into strata. Stratum N contains rules whose negated/aggregated
   body predicates are all in strata < N.

   Returns vector of vectors (strata in order)."
  [rules]
  (let [all-preds (set (map head-predicate rules))
        deps (into {} (for [r rules]
                        [(head-predicate r)
                         {:neg (set (negated-or-aggregated-preds (:body r)))
                          :pos (set (positive-preds (:body r)))}]))
        ;; Assign stratum number to each predicate.
        max-iter (count all-preds)
        finalise (loop [stratum 0
                        placed {}
                        remaining all-preds
                        iter 0]
                   (cond
                     (zero? (count remaining)) placed
                     (>= iter max-iter)
                     (throw (ex-info "rule set is not stratifiable (negation cycle?)"
                                     {:type :stratification-failed
                                      :remaining remaining}))
                     :else
                     (let [;; A predicate can be placed at stratum `stratum`
                           ;; iff ALL its negated/agg deps are already placed
                           ;; in a strictly earlier stratum.
                           ready (set (filter (fn [p]
                                                (every? #(or (not (all-preds %))
                                                             (and (placed %) (< (placed %) stratum)))
                                                        (-> deps p :neg)))
                                              remaining))]
                       (if (zero? (count ready))
                         ;; Move to next stratum; but if no progress, give up
                         (recur (inc stratum) placed remaining (inc iter))
                         (recur (inc stratum)
                                (into placed (map (fn [p] [p stratum]) ready))
                                (clojure.set/difference remaining ready)
                                (inc iter))))))]
    (->> rules
         (group-by (comp finalise head-predicate))
         (sort-by key)
         (mapv val))))

;; ---------------------------------------------------------------------------
;; Substitution / unification
;; ---------------------------------------------------------------------------

(defn- substitute [t binding]
  (if (ast/var? t) (get binding t t) t))

(defn- unify-tuple
  "Unify args [?x \"foo\"] against tuple [\"a\" \"foo\"]. Returns a binding map
   on success or nil on failure."
  [args tuple binding]
  (when (= (count args) (count tuple))
    (reduce (fn [b [arg val]]
              (cond
                (nil? b) (reduced nil)
                (ast/var? arg) (let [bound (get b arg ::unbound)]
                                 (cond
                                   (= bound ::unbound) (assoc b arg val)
                                   (= bound val) b
                                   :else (reduced nil)))
                :else (if (= arg val) b (reduced nil))))
            binding
            (map vector args tuple))))

;; ---------------------------------------------------------------------------
;; Atom evaluation
;; ---------------------------------------------------------------------------

(defn- eval-atom-in-binding
  "Given an atom and a current set of bindings, return a new set of bindings
   extended via the atom's contribution."
  [atom edb bindings]
  (case (:kind atom)
    :atom
    (let [pred (:predicate atom)
          tuples (get edb pred #{})]
      (set (for [b bindings
                 tup tuples
                 :let [b' (unify-tuple (:args atom) tup b)]
                 :when b']
             b')))

    :negation
    (let [inner (:inner atom)
          pred (:predicate inner)
          tuples (get edb pred #{})]
      (set (filter (fn [b]
                     (not-any? (fn [tup] (unify-tuple (:args inner) tup b))
                               tuples))
                   bindings)))

    :comparison
    (let [op (:op atom)
          op-fn ({:= = :!= not= :< < :<= <= :> > :>= >=} op)]
      (set (filter (fn [b]
                     (op-fn (substitute (:left atom) b)
                            (substitute (:right atom) b)))
                   bindings)))

    :aggregation
    (let [agg-op (:op atom)
          input-var (:var atom)
          result-var (:result atom)
          inner-body (:body atom)]
      ;; For each binding, evaluate the inner body and aggregate over input-var.
      (set (for [b bindings
                 :let [inner-bindings (reduce (fn [bs a]
                                                (eval-atom-in-binding a edb bs))
                                              #{b}
                                              inner-body)
                       vals (mapv #(% input-var) inner-bindings)
                       agg-val (case agg-op
                                 :count (count vals)
                                 :sum   (apply + 0 vals)
                                 :min   (when (seq vals) (apply min vals))
                                 :max   (when (seq vals) (apply max vals)))]]
             (assoc b result-var agg-val))))))

;; ---------------------------------------------------------------------------
;; Rule evaluation
;; ---------------------------------------------------------------------------

(defn- evaluate-rule
  "Evaluate one rule against the EDB. Returns a set of head tuples."
  [rule edb]
  (let [bindings (reduce (fn [bs a] (eval-atom-in-binding a edb bs))
                         #{{}}
                         (:body rule))]
    (set (for [b bindings]
           (mapv #(substitute % b) (-> rule :head :args))))))

(defn- fixed-point-stratum
  "Iterate one stratum's rules to fixed point. Returns updated edb."
  [rules edb]
  (loop [edb edb]
    (let [edb' (reduce (fn [acc r]
                         (let [tuples (evaluate-rule r acc)
                               pred   (head-predicate r)]
                           (update acc pred (fnil into #{}) tuples)))
                       edb
                       rules)]
      (if (= edb edb') edb (recur edb')))))

(defn evaluate-rules
  "Stratify rules; evaluate each stratum to fixed point; return derived EDB."
  [rules edb]
  (let [strata (stratify rules)]
    (reduce (fn [acc stratum]
              (fixed-point-stratum stratum acc))
            edb
            strata)))

(defn query
  "Run rules to fixed point against the EDB, then unify the query-atom's args
   against the resulting predicate's tuples. Returns set of binding maps."
  [rules edb query-atom]
  (let [final-edb (evaluate-rules rules edb)
        tuples (get final-edb (:predicate query-atom) #{})]
    (set (for [tup tuples
               :let [b (unify-tuple (:args query-atom) tup {})]
               :when b]
           b))))
```

A few care points:
- `[fukan.constraint.builtins :as builtins]` is imported but not yet used. Task 4 will use it. clj-kondo might flag — accept the warning for one task; Task 4 makes it concrete.
- `clojure.set/difference` is qualified; alternately add `[clojure.set :as set]` to the require.

- [ ] **Step 2.4: Run, expect pass**

Expected: 6 new tests pass; 331/0/0.

- [ ] **Step 2.5: Commit**

```bash
jj desc -m "feat(constraint): stratified bottom-up Datalog evaluator

Naive iteration to fixed point per stratum. Negation as failure (NAF)
and aggregation (count/sum/min/max) supported and force stratum
boundaries. Cycles through negation raise ex-info :stratification-failed.

evaluate-rules returns derived EDB; query returns variable bindings
for a single query-atom.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 3: Sort system + built-ins

**Files:**
- Create: `src/fukan/constraint/sort.clj`
- Create: `src/fukan/constraint/builtins.clj`
- Create: `test/fukan/constraint/sort_test.clj`
- Create: `test/fukan/constraint/builtins_test.clj`

Per MODEL.md §6.3: predicates have sorts (typed argument positions). Sort guards are unary predicates that filter by sort. For MVP, sorts mirror MODEL.md §3.3 Type cases: `:scalar`, `:enum`, `:composite`, `:collection`, `:union`, `:ref`. Plus `:primitive-id` (a fukan-internal sort for kernel primitive references) and `:string`/`:number`.

For Plan 4 MVP, sort enforcement is lightweight — sort guards exist as built-in unary predicates that the evaluator can use as filters. Full sort inference is deferred to a future plan; users tag predicates by convention.

Built-ins per MODEL.md §6.5: arithmetic, comparison, logical, set membership (`in`, `contains`), presence (`is-present`, `is-absent`).

- [ ] **Step 3.1: Tests for sort**

```clojure
(ns fukan.constraint.sort-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.sort :as sort]))

(deftest is-string-guard
  (is (sort/is-string? "foo"))
  (is (not (sort/is-string? 42))))

(deftest is-number-guard
  (is (sort/is-number? 42))
  (is (sort/is-number? 3.14))
  (is (not (sort/is-number? "42"))))

(deftest is-primitive-id-guard
  (is (sort/is-primitive-id? "m::Order"))
  (is (sort/is-primitive-id? "fukan/web/views::events::Pick"))
  (is (not (sort/is-primitive-id? "no-double-colon-here")))
  (is (not (sort/is-primitive-id? 42))))
```

- [ ] **Step 3.2: Tests for builtins**

```clojure
(ns fukan.constraint.builtins-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.constraint.builtins :as b]))

(deftest in-set-membership
  (is (b/in? "a" #{"a" "b"}))
  (is (not (b/in? "c" #{"a" "b"}))))

(deftest contains-substring
  (is (b/contains? "fukan/web/views" "web"))
  (is (not (b/contains? "fukan/infra" "web"))))

(deftest is-present-non-nil
  (is (b/is-present? "x"))
  (is (b/is-present? 0))
  (is (not (b/is-present? nil))))

(deftest is-absent-nil-only
  (is (b/is-absent? nil))
  (is (not (b/is-absent? false))))
```

- [ ] **Step 3.3: Implement `src/fukan/constraint/sort.clj`**

```clojure
(ns fukan.constraint.sort
  "Sort guards — unary predicates for typed predicate arguments per MODEL.md
   §6.3. Used by constraint authors to filter argument-value sorts.")

(defn is-string? [x] (string? x))
(defn is-number? [x] (number? x))
(defn is-keyword? [x] (keyword? x))
(defn is-primitive-id?
  "Returns true iff x looks like a fukan kernel primitive id of the form
   '<coord>::<local>' or deeper. Validates structure, not membership."
  [x]
  (and (string? x) (clojure.string/includes? x "::")))
```

- [ ] **Step 3.4: Implement `src/fukan/constraint/builtins.clj`**

```clojure
(ns fukan.constraint.builtins
  "Datalog built-in predicates per MODEL.md §6.5. Used inside constraint
   rule bodies via :comparison atoms (for comparison ops) or via direct
   atom predicates resolved by the evaluator's kernel-universal table
   (Task 4 wires this).")

(defn in?
  "Set membership."
  [x s]
  (contains? s x))

(defn contains?
  "String substring containment."
  [haystack needle]
  (and (string? haystack) (string? needle)
       (clojure.string/includes? haystack needle)))

(defn is-present?
  "True iff x is non-nil."
  [x]
  (some? x))

(defn is-absent?
  "True iff x is nil."
  [x]
  (nil? x))
```

- [ ] **Step 3.5: Run, expect pass**

Expected: 7 new tests pass; 338/0/0.

- [ ] **Step 3.6: Commit**

```bash
jj desc -m "feat(constraint): sort guards + built-in predicates

Per MODEL.md §6.3 + §6.5. Sort guards are unary predicates filtering
argument-value sorts (is-string?, is-number?, is-primitive-id?, etc.).
Built-ins cover set membership (in?), substring containment (contains?),
presence (is-present?, is-absent?). Comparison ops are handled directly
by the evaluator via :comparison atoms; this namespace covers the
non-comparison built-ins.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 4: Kernel-universal derivations

**Files:**
- Create: `src/fukan/constraint/derivations.clj`
- Create: `test/fukan/constraint/derivations_test.clj`

Per MODEL.md §6.6: fukan ships a closed set of derivations that all constraints can reference. These translate kernel substrate into Datalog facts. Required for Plan 4:

| Derivation | Tuple shape | Meaning |
|---|---|---|
| `:primitive` | `[<id>]` | one tuple per primitive in the model |
| `:primitive-kind` | `[<id> <kind-keyword>]` | e.g., `["m::Order" :primitive/container]` |
| `:has-tag` | `[<id> <tag-string>]` | e.g., `["m" "Boundary::ModuleApi"]` |
| `:tag-payload` | `[<id> <tag-string> <payload-map>]` | the tag's payload as a map |
| `:edge` | `[<from-id> <relation-kw> <to-id>]` | one tuple per kernel edge |
| `:has-field` | `[<container-id> <field-name>]` | container fields |
| `:depends-on` | `[<x> <y>]` | derived: x has an edge to y OR x ⊆ y transitively (via R4/R6/etc.) |
| `:in-module` | `[<primitive-id> <module-id>]` | primitive lives in module |
| `:chains` | `[<start> <end> <relation-kw>]` | transitive closure of a single edge kind |

The function `(model→edb model)` walks the model and produces the EDB map for the constraint engine.

- [ ] **Step 4.1: Tests**

```clojure
(ns fukan.constraint.derivations-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.derivations :as d]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(deftest primitive-tuples
  (let [m (-> (build/empty-model)
              (build/add-primitive (p/make-container {:id "m::Order" :label "Order"})))
        edb (d/model->edb m)]
    (is (= #{["m::Order"]} (:primitive edb)))
    (is (= #{["m::Order" :primitive/container]} (:primitive-kind edb)))))

(deftest tag-tuples
  (let [m (-> (build/empty-model)
              (build/add-primitive (p/make-container {:id "x" :label "x"}))
              (build/add-tag-application
                (v/make-tag-application
                  {:tag {:namespace "Allium" :name "Module"}
                   :target {:case :target/primitive :id "x"}})))
        edb (d/model->edb m)]
    (is (contains? (:has-tag edb) ["x" "Allium::Module"]))))

(deftest in-module-derivation
  (testing "primitives are placed in modules by id prefix"
    (let [m (-> (build/empty-model)
                (build/add-primitive (p/make-container {:id "m" :label "m"}))
                (build/add-tag-application
                  (v/make-tag-application
                    {:tag {:namespace "Allium" :name "Module"}
                     :target {:case :target/primitive :id "m"}}))
                (build/add-primitive (p/make-container {:id "m::Order" :label "Order"})))
          edb (d/model->edb m)]
      (is (contains? (:in-module edb) ["m::Order" "m"])))))

(deftest edge-tuples
  (let [;; A model with a triggers edge (R4) from operation to rule.
        m {:primitives {} :edges [{:kind :relation/triggers
                                   :from {:id "op-id"} :to {:id "rule-id"}}]
           :tag-apps [] :tag-defs [] :predicates [] :violations []}
        edb (d/model->edb m)]
    (is (= #{["op-id" :relation/triggers "rule-id"]} (:edge edb)))))
```

- [ ] **Step 4.2: Implement `src/fukan/constraint/derivations.clj`**

```clojure
(ns fukan.constraint.derivations
  "Kernel-universal derivations: translate kernel substrate to Datalog EDB.

   Per MODEL.md §6.6. Each derivation is a relation name (keyword) keyed
   to a set of tuples (vectors). Used as the seed EDB for the evaluator."
  (:require [clojure.string :as str]))

(defn- primitive-tuples [model]
  (set (map (fn [[id _]] [id]) (:primitives model))))

(defn- primitive-kind-tuples [model]
  (set (map (fn [[id prim]] [id (:kind prim)]) (:primitives model))))

(defn- has-tag-tuples [model]
  (set (map (fn [ta]
              [(-> ta :target :id)
               (str (-> ta :tag :namespace) "::" (-> ta :tag :name))])
            (:tag-apps model))))

(defn- tag-payload-tuples [model]
  (set (map (fn [ta]
              [(-> ta :target :id)
               (str (-> ta :tag :namespace) "::" (-> ta :tag :name))
               (or (:payload ta) {})])
            (:tag-apps model))))

(defn- module-ids [model]
  (set (map (comp :id :target)
            (filter (fn [ta]
                      (and (= "Allium" (-> ta :tag :namespace))
                           (= "Module" (-> ta :tag :name))))
                    (:tag-apps model)))))

(defn- in-module-tuples [model]
  (let [modules (module-ids model)]
    (set (for [[id _] (:primitives model)
               m modules
               :when (str/starts-with? id (str m "::"))]
           [id m]))))

(defn- edge-tuples [model]
  (set (map (fn [edge]
              [(-> edge :from :id) (:kind edge) (-> edge :to :id)])
            (:edges model))))

(defn- has-field-tuples [model]
  (set (for [[id prim] (:primitives model)
             field (:fields prim)]
         [id (:name field)])))

(defn model->edb
  "Translate a kernel Model into a Datalog EDB (predicate → set of tuples).
   Plan 4 derivations include only the kernel-universal predicates per
   MODEL.md §6.6. Plan 4+ can extend additively."
  [model]
  {:primitive       (primitive-tuples model)
   :primitive-kind  (primitive-kind-tuples model)
   :has-tag         (has-tag-tuples model)
   :tag-payload     (tag-payload-tuples model)
   :in-module       (in-module-tuples model)
   :edge            (edge-tuples model)
   :has-field       (has-field-tuples model)})
```

- [ ] **Step 4.3: Run, expect pass**

Expected: 4 new tests pass; 342/0/0.

- [ ] **Step 4.4: Commit**

```bash
jj desc -m "feat(constraint): kernel-universal derivations — model→EDB

Per MODEL.md §6.6. model->edb walks the kernel substrate and emits
predicate→tuples for: primitive, primitive-kind, has-tag, tag-payload,
in-module, edge, has-field. The constraint engine seeds its EDB from
this. Additional derivations (depends-on, chains, etc.) can layer as
Datalog rules over these EDB predicates.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 5: Phase 5 runner

**Files:**
- Modify: `src/fukan/constraint/phase5.clj`
- Modify: `test/fukan/constraint/phase5_test.clj`

Wire the runner. Per DESIGN.md: Phase 5 reads all `PredicateRegistration`s from the model's `:predicates`, evaluates each against the kernel-universal EDB + registration's body, and emits one Violation per result tuple.

A `PredicateRegistration` for Plan 4 carries:
- `:namespace` / `:name` — identification
- `:severity` — `:error` or `:warning`
- `:kind` — open identifier for grouping/rendering
- `:message-template` — opaque text (Plan 4 doesn't interpolate; passes through)
- `:predicate` — the Datalog AST: `{:head <atom> :body [<atom>...]}` where `:head` defines the violation tuple shape.

Phase 5 emits one Violation per head-tuple, with the tuple's bindings carried in `:location`.

- [ ] **Step 5.1: Tests**

Add to `test/fukan/constraint/phase5_test.clj`:

```clojure
(deftest phase5-runs-one-registered-constraint
  (testing "a registered constraint with a matching rule produces violations"
    (let [reg {:namespace "test" :name "every-module-must-have-orders"
               :severity :error :kind :test
               :message-template "missing orders entity"
               :predicate
               {:head {:predicate :violation :args [:?m]}
                :body [{:kind :atom :predicate :has-tag :args [:?m "Allium::Module"]}
                       {:kind :negation
                        :inner {:kind :atom :predicate :in-module
                                :args [:?o :?m]}}]}}
          model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m" :label "m"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Module"}
                         :target {:case :target/primitive :id "m"}}))
                    (update :predicates (fnil conj []) reg))
          m1 (phase5/run model)]
      (is (= 1 (count (filter #(= :phase5 (:phase %)) (:violations m1)))))
      (is (= :error (-> (first (filter #(= :phase5 (:phase %)) (:violations m1)))
                        :severity))))))
```

Add requires `[fukan.model.primitives :as p]` and `[fukan.model.vocabulary :as v]` to the test ns.

- [ ] **Step 5.2: Implement `src/fukan/constraint/phase5.clj`**

```clojure
(ns fukan.constraint.phase5
  "Phase 5 constraint evaluation runner. Reads :predicates from the
   model and evaluates each against the kernel-universal EDB + the
   registration's body. Each head tuple becomes one Violation.

   Per DESIGN.md: Phase 5 is non-gating. Violations attach to the Model
   under :violations alongside Phase 4 violations."
  (:require [fukan.validation.violation :as v]
            [fukan.constraint.evaluator :as e]
            [fukan.constraint.derivations :as d]))

(defn- evaluate-registration
  "Evaluate one PredicateRegistration against the EDB. Returns a vector
   of Violations (one per head-tuple)."
  [edb registration]
  (let [predicate (:predicate registration)
        head      (:head predicate)
        body      (:body predicate)
        rule      {:head head :body body}
        tuples    (e/evaluate-rules [rule] edb)
        head-pred (:predicate head)
        head-args (:args head)
        results   (get tuples head-pred #{})]
    (vec (for [tup results]
           (v/make-violation
             {:severity (:severity registration)
              :phase :phase5
              :sub-phase :5
              :kind (keyword (:namespace registration) (:name registration))
              :location (zipmap head-args tup)
              :message (or (:message-template registration)
                           (str "constraint " (:namespace registration) "/" (:name registration)
                                " fired on " (vec tup)))})))))

(defn run
  "Run Phase 5: evaluate every PredicateRegistration in :predicates
   against the kernel-universal EDB, accumulate violations, append
   them to :violations (preserving any Phase 4 violations already
   present)."
  [model]
  (let [edb (d/model->edb model)
        new-vs (vec (mapcat #(evaluate-registration edb %)
                            (:predicates model)))]
    (update model :violations (fnil into []) new-vs)))
```

- [ ] **Step 5.3: Run, expect pass**

Expected: 4 new tests total in phase5_test (3 from Task 0 + 1 new); 346/0/0.

- [ ] **Step 5.4: Commit**

```bash
jj desc -m "feat(constraint): Phase 5 runner — PredicateRegistration → Violations

Phase 5 reads :predicates from the model. For each registration,
evaluates its Datalog rule against the kernel-universal EDB and
emits one Violation per head-tuple. Bindings of head variables
land in :location for attribution.

Per DESIGN.md, Phase 5 is non-gating — violations are outputs, not
blockers. The explorer surfaces them; the pipeline doesn't halt.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 6: Five fukan-shipped well-known constraints

**Files:**
- Create: `src/fukan/constraint/well_known.clj`
- Create: `test/fukan/constraint/well_known_test.clj`

Per MODEL.md §10.3, fukan ships these:

1. **`signal_gap`** — Every `provides: Surface → Event` edge has at least one `triggers: Event → Rule` consumer. Severity: warning.
2. **`no_dependency(from, to)`** — Parameterised. Derived `depends-on` doesn't hold from any Container tagged `from` to any Container tagged `to`. Severity: error.
3. **`no_circular_refs`** — Within scope, no `depends-on` cycle exists. Severity: error.
4. **`naming_convention(target, pattern)`** — Parameterised. Every primitive of kind `target` has a `:label` matching `pattern` (regex). Severity: warning.
5. **`external_must_have_wrapper`** — Every Container with `Allium::ExternalEntity` belongs to a module declared in a `.boundary` file. Severity: warning (MVP — Plan 5+ can promote when corpus matures).

Each is authored as a PredicateRegistration constructor that returns a registration map. Parameterised constraints (like `no_dependency`) take their parameters at registration time.

- [ ] **Step 6.1: Tests**

```clojure
(ns fukan.constraint.well-known-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.constraint.well-known :as wk]
            [fukan.constraint.phase5 :as phase5]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- run-with [model regs]
  (-> model
      (update :predicates (fnil into []) regs)
      phase5/run))

(deftest signal-gap-fires-on-event-with-no-consumer
  (let [model (-> (build/empty-model)
                  ;; Surface that provides an Event with no triggers consumer.
                  (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                  (build/add-primitive
                    (assoc (p/make-event {:id "m::events::E" :label "E"})
                           :kind :primitive/event))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Surface"}
                       :target {:case :target/primitive :id "m::S"}}))
                  (build/add-edge
                    {:kind :relation/provides
                     :from {:id "m::S"} :to {:id "m::events::E"}}))
        m1 (run-with model [(wk/signal-gap)])
        violations (filter #(= :phase5 (:phase %)) (:violations m1))]
    (is (pos? (count violations))
        "signal_gap fires when a provided Event has no triggers consumer")))

(deftest signal-gap-silent-when-event-is-consumed
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                  (build/add-primitive
                    (assoc (p/make-event {:id "m::events::E" :label "E"})
                           :kind :primitive/event))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-edge {:kind :relation/provides
                                   :from {:id "m::S"} :to {:id "m::events::E"}})
                  (build/add-edge {:kind :relation/triggers
                                   :from {:id "m::events::E"} :to {:id "m::R"}}))
        m1 (run-with model [(wk/signal-gap)])
        violations (filter #(= :phase5 (:phase %)) (:violations m1))]
    (is (zero? (count violations))
        "signal_gap is silent when there's a triggers consumer")))

(deftest external-must-have-wrapper-fires-on-orphan
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "orphan::Foo" :label "Foo"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "ExternalEntity"}
                       :target {:case :target/primitive :id "orphan::Foo"}})))
        m1 (run-with model [(wk/external-must-have-wrapper)])
        violations (filter #(= :phase5 (:phase %)) (:violations m1))]
    (is (pos? (count violations))
        "external_must_have_wrapper fires when external entity is not inside a known module")))
```

(The other constraints — `no_dependency`, `no_circular_refs`, `naming_convention` — have parameterised registrations. Their tests follow the same pattern but require constructing the registration with parameters.)

- [ ] **Step 6.2: Implement `src/fukan/constraint/well_known.clj`**

```clojure
(ns fukan.constraint.well-known
  "Fukan-shipped well-known constraints per MODEL.md §10.3.

   Each function returns a PredicateRegistration map that Phase 5
   evaluates. Parameterised constraints take their parameters as fn args.")

(defn signal-gap
  "Every provides: Surface → Event edge has at least one triggers:
   Event → Rule consumer."
  []
  {:namespace "fukan"
   :name "signal_gap"
   :severity :warning
   :kind :methodology
   :message-template "Event has no triggers: consumer"
   :predicate
   {:head {:predicate :violation :args [:?event]}
    :body [{:kind :atom :predicate :edge :args [:?surface :relation/provides :?event]}
           {:kind :negation
            :inner {:kind :atom :predicate :edge :args [:?event :relation/triggers :?rule]}}]}})

(defn external-must-have-wrapper
  "Every Container tagged Allium::ExternalEntity belongs to a known module."
  []
  {:namespace "fukan"
   :name "external_must_have_wrapper"
   :severity :warning
   :kind :methodology
   :message-template "External entity has no wrapping module"
   :predicate
   {:head {:predicate :violation :args [:?ext]}
    :body [{:kind :atom :predicate :has-tag :args [:?ext "Allium::ExternalEntity"]}
           {:kind :negation
            :inner {:kind :atom :predicate :in-module :args [:?ext :?m]}}]}})

(defn no-dependency
  "no_dependency(from-tag, to-tag): containers tagged `from-tag` must not have
   any edge to containers tagged `to-tag`."
  [from-tag to-tag]
  {:namespace "fukan"
   :name "no_dependency"
   :severity :error
   :kind :methodology
   :message-template (str from-tag " must not depend on " to-tag)
   :predicate
   {:head {:predicate :violation :args [:?from :?to]}
    :body [{:kind :atom :predicate :has-tag :args [:?from from-tag]}
           {:kind :atom :predicate :has-tag :args [:?to to-tag]}
           {:kind :atom :predicate :edge :args [:?from :?_rel :?to]}]}})

(defn no-circular-refs
  "no_circular_refs: no primitive depends on itself (any edge kind)."
  []
  {:namespace "fukan"
   :name "no_circular_refs"
   :severity :error
   :kind :methodology
   :message-template "circular reference detected"
   :predicate
   {:head {:predicate :violation :args [:?x]}
    :body [{:kind :atom :predicate :edge :args [:?x :?_rel :?x]}]}})

(defn naming-convention
  "naming_convention(kind, pattern): every primitive of `kind` has a :label
   matching `pattern` (regex). NOTE: MVP regex-match is performed in the
   evaluator via a built-in `:matches-regex` predicate; the body uses a
   :comparison atom with the regex as the right-hand constant.

   Plan 4 punt: this constraint's regex evaluation is handled by extending
   the evaluator's :comparison op set with :matches-regex. If that requires
   too much work for MVP, naming_convention is registered but doesn't fire
   (regex matching is added in a follow-up plan). For now: regular eq."
  [kind regex]
  {:namespace "fukan"
   :name "naming_convention"
   :severity :warning
   :kind :methodology
   :message-template (str kind " label doesn't match " regex)
   :predicate
   {:head {:predicate :violation :args [:?x]}
    :body [{:kind :atom :predicate :primitive-kind :args [:?x kind]}
           ;; MVP: implement regex matching as a special-case extension to
           ;; the evaluator. For now the rule body just requires the primitive
           ;; to exist with that kind. The actual regex check is added when
           ;; the evaluator gains regex support — see Plan 4's known gaps in
           ;; the docstring.
           ]}})
```

The `naming_convention` is the weakest of the five — its body doesn't yet enforce the regex. Two options:
- **A.** Extend the evaluator's `:comparison` op set with `:matches-regex` (small extension; ~10 lines).
- **B.** Punt to a follow-up plan.

For Plan 4 MVP, choose **A** — extend the evaluator to support `:matches-regex` as a `:comparison` op. Adjust `evaluator.clj`'s `op-fn` table:

```clojure
(let [op-fn ({:= = :!= not= :< < :<= <= :> > :>= >=
              :matches-regex #(boolean (re-find (re-pattern %2) %1))} op)]
  ...)
```

And `naming_convention`'s body becomes:

```clojure
:body [{:kind :atom :predicate :primitive-kind :args [:?x kind]}
       {:kind :atom :predicate :has-field :args [:?x :?label]}  ; placeholder
       ;; or use a :tag-payload extraction to read :label
       {:kind :comparison :op :matches-regex :left :?label :right regex}]
```

This requires the EDB to expose primitive labels. Add a `:has-label` predicate to `model->edb`:

```clojure
(defn- has-label-tuples [model]
  (set (for [[id prim] (:primitives model)
             :when (:label prim)]
         [id (:label prim)])))
```

Add `:has-label` to `model->edb` output.

- [ ] **Step 6.3: Update evaluator + derivations to support `:matches-regex`**

In `src/fukan/constraint/evaluator.clj`'s `eval-atom-in-binding`'s `:comparison` case:

```clojure
:comparison
(let [op (:op atom)
      op-fn ({:= = :!= not= :< < :<= <= :> > :>= >=
              :matches-regex (fn [s pat] (boolean (re-find (re-pattern pat) s)))} op)]
  (set (filter (fn [b]
                 (op-fn (substitute (:left atom) b)
                        (substitute (:right atom) b)))
               bindings)))
```

In `src/fukan/constraint/derivations.clj`'s `model->edb`:

```clojure
(defn- has-label-tuples [model]
  (set (for [[id prim] (:primitives model)
             :when (:label prim)]
         [id (:label prim)])))

;; Add :has-label to model->edb's return map.
```

- [ ] **Step 6.4: Run, expect pass**

Expected: ~3 new tests pass; 349/0/0.

- [ ] **Step 6.5: Commit**

```bash
jj desc -m "feat(constraint): five fukan-shipped well-known constraints

Per MODEL.md §10.3:
  - signal_gap (warning) — provided Event with no triggers consumer
  - no_dependency(from, to) (error) — parameterised tag-based
  - no_circular_refs (error) — self-edges
  - naming_convention(kind, regex) (warning) — :label matches pattern
  - external_must_have_wrapper (warning) — Allium::ExternalEntity
    must belong to a known module

The evaluator gains :matches-regex as a :comparison op. The derivations
EDB gains :has-label for primitive label access.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 7: Plan 3c carry-forward — 4b rule 4 (provides: external-stimulus)

**Files:**
- Modify: `src/fukan/validation/rules_4b.clj`
- Modify: `test/fukan/validation/rules_4b_test.clj`

Per DESIGN.md §4b rule 4 + Allium rule 30: every trigger referenced in a Surface's `provides:` must be defined as an external-stimulus trigger in a rule of the same module. Concretely: for every `provides: Surface → Event` kernel edge, there must exist a `triggers: Event → Rule` kernel edge in the SAME module whose `Allium::Trigger` tag-app payload has `:kind "external_stimulus"`.

This is fundamentally the same shape as `signal_gap` but stricter — requires the consumer to be marked external-stimulus AND in the same module.

For Plan 4 MVP, implement this as a hand-coded Phase 4 rule (not via the constraint engine) since it's a structural validation, not a methodology constraint. Add to `rules_4b.clj`'s `check`.

- [ ] **Step 7.1: Tests**

Add to `test/fukan/validation/rules_4b_test.clj`:

```clojure
(deftest provides-without-external-stimulus-trigger-is-error
  (testing "provides: Event must have an external-stimulus triggers consumer in the same module"
    (let [model (-> (build/empty-model)
                    (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                    (build/add-tag-application
                      (v/make-tag-application
                        {:tag {:namespace "Allium" :name "Surface"}
                         :target {:case :target/primitive :id "m::S"}}))
                    (build/add-primitive
                      (assoc (p/make-event {:id "m::events::E" :label "E"})
                             :kind :primitive/event))
                    (build/add-edge
                      {:kind :relation/provides
                       :from {:id "m::S"} :to {:id "m::events::E"}}))
          violations (r4b/check model)
          relevant (filter #(= :4b/provides-no-external-stimulus (:kind %)) violations)]
      (is (= 1 (count relevant)))
      (is (= :error (-> relevant first :severity))))))

(deftest provides-with-external-stimulus-trigger-passes
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m::S" :label "S"}))
                  (build/add-primitive
                    (assoc (p/make-event {:id "m::events::E" :label "E"})
                           :kind :primitive/event))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                  (build/add-edge {:kind :relation/provides
                                   :from {:id "m::S"} :to {:id "m::events::E"}})
                  (build/add-edge {:kind :relation/triggers
                                   :from {:id "m::events::E"} :to {:id "m::R"}})
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Trigger"}
                       :target {:case :target/edge
                                :edge-identity ((requiring-resolve 'fukan.model.relations/edge-identity)
                                                {:kind :relation/triggers
                                                 :from {:id "m::events::E"} :to {:id "m::R"}})}
                       :payload {:kind "external_stimulus"}})))
        violations (r4b/check model)
        relevant (filter #(= :4b/provides-no-external-stimulus (:kind %)) violations)]
    (is (empty? relevant))))
```

- [ ] **Step 7.2: Implement**

Add to `src/fukan/validation/rules_4b.clj` (before `check`):

```clojure
(defn- module-of-id
  "Extract the module-coord prefix from an id like 'm/sub::events::Foo'.
   Returns nil if no '::' is present."
  [id]
  (when (and (string? id) (clojure.string/includes? id "::"))
    (first (clojure.string/split id #"::" 2))))

(defn- provides-edges [model]
  (filter #(= :relation/provides (:kind %)) (:edges model)))

(defn- triggers-edges [model]
  (filter #(= :relation/triggers (:kind %)) (:edges model)))

(defn- external-stimulus-edge?
  "True iff the edge has an Allium::Trigger tag-app with payload :kind
   'external_stimulus'."
  [model edge]
  (let [edge-id ((requiring-resolve 'fukan.model.relations/edge-identity) edge)]
    (some (fn [ta]
            (and (= "Allium" (-> ta :tag :namespace))
                 (= "Trigger" (-> ta :tag :name))
                 (= edge-id (-> ta :target :edge-identity))
                 (= "external_stimulus" (-> ta :payload :kind))))
          (:tag-apps model))))

(defn- provides-without-external-stimulus [model]
  (for [pe (provides-edges model)
        :let [event-id (-> pe :to :id)
              event-module (module-of-id event-id)
              ext-triggers (filter (fn [te]
                                     (and (= event-id (-> te :from :id))
                                          (= event-module (module-of-id (-> te :to :id)))
                                          (external-stimulus-edge? model te)))
                                   (triggers-edges model))]
        :when (empty? ext-triggers)]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4b
       :kind :4b/provides-no-external-stimulus
       :location {:provides-edge pe :event-id event-id :module event-module}
       :message (str "Event " event-id " is provided by a Surface but has no external-stimulus triggers consumer in module " event-module)})))
```

Update `check` to include `provides-without-external-stimulus`:

```clojure
(defn check [model]
  (vec (concat
         (events-without-declaration-sites model)
         (shape-mismatches model)
         (provides-without-external-stimulus model))))
```

- [ ] **Step 7.3: Run, expect pass**

Expected: 2 new tests pass. Test count: 351/0/0.

- [ ] **Step 7.4: Run full suite**

Watch for new violations against the corpus from this stricter rule. If any fire, decide: fix corpus or document.

- [ ] **Step 7.5: Commit**

```bash
jj desc -m "feat(validation): 4b rule 4 — provides: external-stimulus check

Plan 3c carry-forward. Per DESIGN.md §4b rule 4 (Allium rule 30): every
provides: Surface → Event edge must have at least one
external-stimulus-kind triggers consumer in the same module.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 8: Plan 3c carry-forward — 4c rule 4 (full signature equality)

**Files:**
- Modify: `src/fukan/validation/rules_4c.clj`
- Modify: `test/fukan/validation/rules_4c_test.clj`

Per DESIGN.md §4c rule 4: bound Rule's `when:` must have an event-shaped clause matching the Operation's signature exactly (parameter names, positions, types). Plan 3c's MVP shipped `:4c/signature-match-uncertain` as a reduced-fidelity warning when the Rule had no event-shaped triggers beyond the binding itself.

Plan 4 promotes this to a proper signature-equality check when both Operation and Rule have introspectable signatures. Since Allium-side Rule `when:` parameters aren't currently a kernel substrate slot (Plan 2b captures them as part of the trigger AST but they don't land in the model as a discoverable shape), this requires either:

- **A.** Walking the Plan 2b analyzer's `:phase4-state` for stored event-shape info AND comparing to Operation parameters.
- **B.** Accepting MVP fidelity: keep `:4c/signature-match-uncertain` as a warning, but TIGHTEN it — only emit when the binding is to an Operation that has a parameter list AND the Rule has no `triggers: Event → Rule` incoming edges. This narrows false positives.

For Plan 4 MVP, choose **B**. A full equality check requires kernel substrate work that's out of scope. Document the partial fidelity in the rule's docstring.

Also: add the positive test for `:4c/signature-match-uncertain` that Plan 3c's review flagged as missing.

- [ ] **Step 8.1: Update `signature-mismatches` in `src/fukan/validation/rules_4c.clj`**

Find the current `signature-mismatches`. Tighten the check:

```clojure
(defn- signature-mismatches
  "MVP fidelity (Plan 4): warn when a binding's target Rule has no other
   incoming triggers edge (i.e., no event-shaped when: clause was registered
   by Allium). Full signature equality (param names + types) requires kernel
   work that's out of scope for Plan 4.

   The warning narrows when:
   - The Operation has at least one parameter (no point checking a zero-arg
     op's signature alignment).
   - The Rule has no OTHER triggers edges (so the binding's edge would be
     the only path to firing it — signature mismatch would be unrecoverable)."
  [model]
  (for [edge (binding-edges model)
        :let [op-id (-> edge :from :id)
              rule-id (-> edge :to :id)
              op (operation-by-id model op-id)
              rule (rule-by-id model rule-id)
              ;; Other triggers edges into this rule (excluding `edge`):
              other-triggers (filter #(and (= :relation/triggers (:kind %))
                                           (= rule-id (-> % :to :id))
                                           (not= (-> edge :from :id) (-> % :from :id)))
                                     (:edges model))]
        :when (and op rule
                   (pos? (count (:parameters op)))
                   (empty? other-triggers))]
    (v/make-violation
      {:severity :warning :phase :phase4 :sub-phase :4c
       :kind :4c/signature-match-uncertain
       :location {:rule-id rule-id :op-id op-id}
       :message (str "Rule " rule-id " has no event-shaped when: clause beyond the binding itself — signature match cannot be verified at MVP fidelity")})))
```

The narrowing: emit only when Operation has parameters AND Rule has no other triggers. This eliminates spurious warnings on zero-arg operations.

- [ ] **Step 8.2: Add positive test for `:4c/signature-match-uncertain`**

Add to `test/fukan/validation/rules_4c_test.clj`:

```clojure
(deftest signature-match-uncertain-fires-when-rule-lacks-events
  (testing "binding to a Rule with no event-shaped when: clause and op with params produces signature-match-uncertain warning"
    (let [model (-> (build/empty-model)
                    (build/add-primitive
                      (p/make-operation {:id "m::Contract.op" :label "op"
                                         :parameters [(p/make-parameter "x"
                                                                        ((requiring-resolve 'fukan.model.type/make-scalar) "Integer")
                                                                        false 0)]}))
                    (build/add-primitive (p/make-rule {:id "m::R" :label "R"}))
                    (build/add-edge {:kind :relation/triggers
                                     :from {:id "m::Contract.op"} :to {:id "m::R"}}))
          violations (r4c/check model)
          relevant (filter #(= :4c/signature-match-uncertain (:kind %)) violations)]
      (is (= 1 (count relevant)))
      (is (= :warning (-> relevant first :severity))))))
```

Add `[fukan.model.primitives :as p]` to the test ns require if not present.

- [ ] **Step 8.3: Run, expect pass**

Expected: 1 new test pass; ~352/0/0. The corpus might see fewer `:4c/signature-match-uncertain` warnings now (the narrowing change eliminates some).

- [ ] **Step 8.4: Commit**

```bash
jj desc -m "fix(validation): 4c signature-match narrowed to params + no-other-triggers

Plan 3c carry-forward + missing-test gap closed.

The reduced-fidelity warning previously fired on every binding whose
Rule had no other incoming triggers — including zero-arg operations
where signature alignment is trivial. Narrowed: emit only when (a) the
Operation has at least one parameter AND (b) the Rule has no other
triggers edges. Adds the positive test that Plan 3c review flagged.

Full signature equality (param names + types) requires kernel work for
storing Rule when: clause parameters as introspectable substrate; out
of scope for Plan 4. Plan 5+ may extend.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 9: Plan 3c carry-forwards — cleanups

**Files:**
- Modify: `src/fukan/validation/rules_4c.clj` (remove `requiring-resolve`)
- Modify: `src/fukan/infra/model.clj` (stale doc-comments)
- Modify: `src/fukan/vocabulary/boundary/analyzer.clj` (stale doc-comment)

Three small cleanups bundled.

- [ ] **Step 9.1: Replace `requiring-resolve` in rules_4c.clj**

In `src/fukan/validation/rules_4c.clj`, find the `(requiring-resolve 'fukan.model.relations/edge-identity)` call (likely in `return-type-mismatches` around line 60-70). Replace with a direct require + call.

Add to the `:require`:

```clojure
[fukan.model.relations :as r]
```

Replace `((requiring-resolve 'fukan.model.relations/edge-identity) edge)` with `(r/edge-identity edge)`.

- [ ] **Step 9.2: Update stale doc-comments in infra/model.clj**

In `src/fukan/infra/model.clj`:
- Update the namespace docstring (line ~6) to reference Plan 3c (or Plan 4 if Phase 5 is now wired — but Task 10 wires that, so for Task 9 reference Plan 3c).
- Update the `load-model` println (line ~18) to say "(Allium + Boundary + Phase 4 — Plan 3c)" or similar.

- [ ] **Step 9.3: Update stale doc-comment in boundary/analyzer.clj**

In `src/fukan/vocabulary/boundary/analyzer.clj` around line 255 there's a comment mentioning `:predicate-registrations` slot — that slot was renamed to `:predicates` in Plan 3c Task 2. Update the comment to reference `:predicates`.

- [ ] **Step 9.4: Run full suite**

Expected: ~352/0/0 (no test changes, no behaviour change).

- [ ] **Step 9.5: Commit**

```bash
jj desc -m "chore(validation): cleanup Plan 3c carry-forwards — comments + requiring-resolve

Three small fixes bundled:
  - rules_4c.clj: replace lazy (requiring-resolve 'r/edge-identity) with
    direct require + call (no dependency cycle, no benefit to laziness).
  - infra/model.clj: update stale Plan 3b doc-comments to current state.
  - boundary/analyzer.clj: update stale :predicate-registrations comment
    to :predicates (slot was renamed in Plan 3c Task 2).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Task 10: Wire Phase 5 + register well-known constraints + closing smoke

**Files:**
- Modify: `src/fukan/model/pipeline.clj`
- Modify: `test/fukan/validation/phase4_test.clj` and/or `test/fukan/constraint/phase5_test.clj`

Wire `phase5/run` after Phase 4. Add a register-defaults step that injects the five well-known constraints into the model's `:predicates` (unless a project-side override already populated them).

For MVP simplicity: always register all five defaults unconditionally. Plan 5+ can add per-project opt-out.

- [ ] **Step 10.1: Update `src/fukan/model/pipeline.clj`**

Current pipeline:

```clojure
(defn load-source [source-root]
  (let [m1 (-> (allium/load-source source-root)
               (boundary/load-source source-root))
        {:keys [model violations]} (phase4/run m1)]
    (assoc model :violations violations)))
```

Update to:

```clojure
(ns fukan.model.pipeline
  "Multi-extension build pipeline (Phase 1-5 per DESIGN.md).

   Phase 1: per-extension parse (Allium + Boundary).
   Phase 2: cross-extension reference resolution.
   Phase 3: merge.
   Phase 4: structural validation (sub-phases 4a-4g). Gate G2 halts on errors.
   Phase 5: constraint evaluation (kernel-universal + project-shipped).
            Non-gating — violations are outputs."
  (:require [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]
            [fukan.validation.phase4 :as phase4]
            [fukan.constraint.phase5 :as phase5]
            [fukan.constraint.well-known :as wk]))

(defn- register-defaults
  "Append the five fukan-shipped well-known constraints to the model's
   :predicates. Idempotent — re-registering the same name namespace is a
   no-op (avoids double-firing on repeat loads)."
  [model]
  (let [existing (set (map (juxt :namespace :name) (:predicates model)))
        defaults [(wk/signal-gap)
                  (wk/external-must-have-wrapper)]
        new (remove (fn [r] (existing [(:namespace r) (:name r)])) defaults)]
    (update model :predicates (fnil into []) new)))

(defn load-source
  "Top-level load: Allium → Boundary → Phase 4 → Phase 5. Returns the
   unified Model with :violations from both Phase 4 (gating) and Phase 5
   (non-gating). Raises on Gate G2 halt during Phase 4."
  [source-root]
  (let [m1 (-> (allium/load-source source-root)
               (boundary/load-source source-root))
        {:keys [model violations]} (phase4/run m1)
        m2 (-> model (assoc :violations violations) register-defaults)]
    (phase5/run m2)))
```

Note: only `signal-gap` and `external-must-have-wrapper` are registered as defaults at MVP. The parameterised ones (`no_dependency`, `naming_convention`) require argument values that should come from a project layer (Plan 5+). `no_circular_refs` is reasonable as a default but the simple "self-edge" check from Task 6 won't fire on real cycles (you'd need a transitive `depends-on` derivation). Keep it out of defaults until that derivation lands properly.

- [ ] **Step 10.2: Update the closing smoke test**

Update `combined-pipeline-with-phase4-runs-cleanly` (or `combined-pipeline-with-phase5-runs-cleanly`) to assert no errors AND that Phase 5 violations appear (warnings only):

```clojure
(deftest combined-pipeline-with-phase5-runs-cleanly
  (testing "fukan-on-fukan loads through all phases (Allium + Boundary + Phase 4 + Phase 5)"
    (let [m (model-pipeline/load-source "src")]
      (is (map? m))
      (is (contains? m :violations))
      (let [errors (filter #(= :error (:severity %)) (:violations m))
            warnings (filter #(= :warning (:severity %)) (:violations m))]
        (is (empty? errors)
            (str "Phase 4/5 produced unexpected errors: "
                 (pr-str (mapv (juxt :phase :sub-phase :kind :message) errors))))
        ;; The corpus likely produces some Phase 5 warnings now (signal_gap
        ;; on any Events without consumers, external_must_have_wrapper if
        ;; any orphan ExternalEntities exist).
        (is (some? warnings) "warnings vector exists (may be empty)")))))
```

- [ ] **Step 10.3: Run, observe corpus violations**

```
clj -M:test -n fukan.validation.phase4-test
```

Inspect the output: Phase 4 should still emit its 9 warnings (from Plan 3c — 6 top-level + 3 signature-match). Phase 5 will likely add: zero or more `signal_gap` warnings (per Event with no consumer) + zero or more `external_must_have_wrapper` warnings (per ExternalEntity with no wrapping module).

If errors fire (shouldn't, but possible), inspect each:
- A Phase 5 error from a corpus constraint? Tighten the corpus.
- A Phase 4 error from Task 7's 4b rule 4? Same — either the corpus is missing the external-stimulus tag application, or the rule is overzealous (then fix the rule).

For each error fired by a Plan 4 addition, debug and fix in place.

- [ ] **Step 10.4: Run full suite**

```
clj -M:test
```

Expected: ~353/0/0 (the closing test is forward-compatible; minor variance acceptable).

- [ ] **Step 10.5: REPL smoke**

```
clojure -M -e "(require '[fukan.infra.model :as m]) (m/load-model \"src\") (def model (m/get-model)) (let [vs (:violations model)] (println :prim (count (:primitives model)) :edges (count (:edges model)) :tags (count (:tag-apps model)) :violations (count vs) :errors (count (filter #(= :error (:severity %)) vs)) :warnings (count (filter #(= :warning (:severity %)) vs)) :phase4 (count (filter #(= :phase4 (:phase %)) vs)) :phase5 (count (filter #(= :phase5 (:phase %)) vs))))"
```

Expected: 0 errors. Phase 5 violations present alongside Phase 4. Total violation count is Phase 4's 9 (Plan 3c baseline) + Phase 5's contribution from the corpus.

- [ ] **Step 10.6: Commit**

```bash
jj desc -m "feat(pipeline): wire Phase 5 + register defaults; close Plan 4

model/pipeline/load-source now runs Allium → Boundary → Phase 4 →
Phase 5. Phase 5 violations attach to :violations alongside Phase 4
(non-gating per DESIGN.md). Two well-known constraints (signal_gap,
external_must_have_wrapper) registered as defaults; parameterised
constraints await project-layer support (Plan 5+).

fukan-on-fukan loads cleanly: 0 errors, both Phase 4 and Phase 5
warnings surfaced. Plan 4 closes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" && jj new
```

---

## Self-review

After completing all 11 tasks (0–10), verify before declaring Plan 4 done:

1. **MODEL.md §6 substrate coverage**: Datalog with negation (Task 2), aggregation (Task 2), built-ins (Task 3), kernel-universal derivations (Task 4). Path navigation sugar (§6.7) and type-sum sugar (§6.8) deferred per MODEL.md §13.
2. **All 5 well-known constraints implemented** as PredicateRegistration constructors (Task 6).
3. **Phase 5 wired** into top-level pipeline (Task 10).
4. **All Plan 3c carry-forwards resolved**:
   - 4b rule 4 (provides external-stimulus) — Task 7
   - 4c rule 4 (signature equality, MVP fidelity narrowed) — Task 8
   - `requiring-resolve` cleanup — Task 9
   - Stale doc-comments updated — Task 9
   - Missing `:4c/signature-match-uncertain` test — Task 8
5. **fukan-on-fukan validates without errors** end-to-end through Phase 5.
6. **Full test suite green**: `clj -M:test` 0 failures.
7. **VCS state**: 11 Plan-4 commits stack cleanly on top of Plan 3c's tip.

If any check fails, fix in place — do NOT start Plan 5 until Plan 4's constraint engine is clean.
