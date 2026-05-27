(ns fukan.target.clojure.address-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.address :as addr]
            [fukan.project-layer.registry :as r]))

(deftest module-ns-empty-prefix
  (is (= "fukan.web.views.spec"
         (addr/module-ns (r/make-registry) "fukan/web/views/spec"))))

(deftest module-ns-with-prefix
  (is (= "myapp.fukan.web.views.spec"
         (addr/module-ns (r/with-root-prefix (r/make-registry) "myapp")
                         "fukan/web/views/spec"))))

(deftest entity-name-preserves-case
  (is (= "Order" (addr/local-name :primitive/container :projection-kind/schema "Order")))
  (is (= "OrderConfirmed" (addr/local-name :primitive/event :projection-kind/schema "OrderConfirmed"))))

(deftest rule-name-kebab-lower-from-pascal
  (is (= "process-submission"
         (addr/local-name :primitive/rule :projection-kind/rule "ProcessSubmission"))))

(deftest operation-name-kebab-lower-from-snake
  (is (= "submit-order"
         (addr/local-name :primitive/operation :projection-kind/operation "submit_order"))))

(deftest invariant-name-kebab-lower
  (is (= "no-negative-balance"
         (addr/local-name :primitive/expression :projection-kind/invariant "NoNegativeBalance"))))

(deftest test-projection-suffixes-ns-and-name
  (let [reg (r/make-registry)]
    (is (= {:ns "fukan.web.views.spec-test" :name "process-submission-test"}
           (addr/canonical reg :primitive/rule :projection-kind/test
                           "fukan/web/views/spec" "ProcessSubmission")))))

(deftest canonical-entity-schema
  (is (= {:ns "fukan.model.spec" :name "Order"}
         (addr/canonical (r/make-registry) :primitive/container :projection-kind/schema
                         "fukan/model/spec" "Order"))))

(deftest canonical-rule
  (is (= {:ns "fukan.web.views.spec" :name "select-node"}
         (addr/canonical (r/make-registry) :primitive/rule :projection-kind/rule
                         "fukan/web/views/spec" "SelectNode"))))

(deftest canonical-invariant-via-holds-that-label
  ;; Phase 6 Sprint 2 Task 4 sub-task C: invariants project as :primitive/rule
  ;; with the holds-that string as label. The address machinery must accept
  ;; the label verbatim when it's already kebab-case (the convention for
  ;; holds-that strings) and pass through unchanged.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.validation.rules-4a" :name "module-has-at-most-one-composite-parent"}
           (addr/canonical reg :primitive/rule :projection-kind/rule
                           "validation.rules-4a"
                           "module-has-at-most-one-composite-parent")))))

(deftest module-ns-converts-underscores-to-hyphens
  ;; Phase 6 Sprint 2 Task 4 sub-task E: canvas module names use underscores
  ;; in some places (e.g. agent.views_loader, project_layer.registry) while
  ;; real Clojure namespaces use hyphens (agent.views-loader,
  ;; project-layer.registry). module-ns must convert underscores to hyphens
  ;; for each dot-separated segment, otherwise drift derives addresses that
  ;; can never match the actual ns and emits false :absent findings.
  (is (= "fukan.agent.views-loader"
         (addr/module-ns (r/with-root-prefix (r/make-registry) "fukan")
                         "agent.views_loader")))
  (is (= "fukan.project-layer.registry"
         (addr/module-ns (r/with-root-prefix (r/make-registry) "fukan")
                         "project_layer.registry"))))

(deftest invariant-holds-that-text-kebabs-to-predicate-name
  ;; Phase 7 Task 4 gap 2 — invariant primitives carry the `holds-that`
  ;; clause as their primitive label. When the clause is present, the
  ;; address derivation kebab-cases it the same way as any other
  ;; function-shaped projection.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.distributed.cluster"
            :name "current-term-is-monotonically-non-decreasing-per-node"}
           (addr/canonical reg :primitive/rule :projection-kind/invariant
                           "distributed.cluster"
                           "current-term-is-monotonically-non-decreasing-per-node")))))

(deftest invariant-holds-that-absent-falls-back-to-kebab-invariant-name
  ;; Phase 7 Task 4 gap 2 — when a canvas invariant declaration omits
  ;; `holds-that` (or the clause is blank/nil), `addr/canonical` must
  ;; fall back to `kebab(invariant-name)` rather than emitting an empty
  ;; or junk predicate name. The caller supplies the invariant name as
  ;; a fallback via the opts arity.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (testing "nil label falls back to kebab(invariant-name)"
      (is (= {:ns "fukan.distributed.cluster"
              :name "term-monotonicity"}
             (addr/canonical reg :primitive/rule :projection-kind/invariant
                             "distributed.cluster" nil
                             {:invariant-name "TermMonotonicity"}))))
    (testing "blank-string label falls back to kebab(invariant-name)"
      (is (= {:ns "fukan.distributed.cluster"
              :name "at-most-one-leader-per-term"}
             (addr/canonical reg :primitive/rule :projection-kind/invariant
                             "distributed.cluster" "   "
                             {:invariant-name "AtMostOneLeaderPerTerm"}))))
    (testing "present label wins; fallback unused"
      (is (= {:ns "fukan.distributed.cluster"
              :name "majority-required-for-leadership"}
             (addr/canonical reg :primitive/rule :projection-kind/invariant
                             "distributed.cluster"
                             "majority-required-for-leadership"
                             {:invariant-name "Ignored"}))))))

(deftest canonical-rule-pascal-name-via-canvas
  ;; Phase 6 Sprint 2 Task 4 sub-task C: canvas rules carry their own
  ;; :entity/name as the primitive label (e.g. "AtMostOneCompositeParent").
  ;; Address derivation must kebab-case it to the expected code-side
  ;; predicate fn name.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.validation.rules-4a" :name "at-most-one-composite-parent"}
           (addr/canonical reg :primitive/rule :projection-kind/rule
                           "validation.rules-4a" "AtMostOneCompositeParent")))))
