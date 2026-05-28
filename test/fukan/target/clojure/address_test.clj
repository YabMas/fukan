(ns fukan.target.clojure.address-test
  (:require [clojure.test :refer [deftest is]]
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

(deftest canonical-event-schema-is-symmetric-with-container
  ;; Phase 7 Task 4 gap 3 — verify `:primitive/event` produces a canonical
  ;; address consistent with `:primitive/container` under
  ;; `:projection-kind/schema`. Layer A's `event-to-schema` projection
  ;; reuses the same machinery as `type-to-malli`, so both kinds must
  ;; resolve to identically-shaped {:ns :name} maps with PascalCase
  ;; preservation. This locks the behavior down so future address-machinery
  ;; refactors don't silently diverge.
  (let [reg     (r/with-root-prefix (r/make-registry) "fukan")
        ev-addr (addr/canonical reg :primitive/event :projection-kind/schema
                                "distributed.cluster" "OrderPlaced")
        ty-addr (addr/canonical reg :primitive/container :projection-kind/schema
                                "distributed.cluster" "OrderPlaced")]
    (is (= ev-addr ty-addr)
        "an event and a container of the same name + module produce identical addresses")
    (is (= {:ns "fukan.distributed.cluster" :name "OrderPlaced"} ev-addr)
        "event address preserves PascalCase symbol and dots/kebabs the module coord")))

(deftest canonical-rule
  (is (= {:ns "fukan.web.views.spec" :name "select-node"}
         (addr/canonical (r/make-registry) :primitive/rule :projection-kind/rule
                         "fukan/web/views/spec" "SelectNode"))))

(deftest canonical-invariant-via-entity-name-label
  ;; Phase 7.5 Sprint 2: invariants carry their :entity/name as the
  ;; primitive label (uniform with all other affordance kinds). The
  ;; address machinery kebab-cases PascalCase entity names into legal
  ;; Clojure symbols on both the analyzer (drift) and Layer A (instruct)
  ;; sides.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.validation.rules-4a" :name "at-most-one-composite-parent"}
           (addr/canonical reg :primitive/rule :projection-kind/invariant
                           "validation.rules-4a"
                           "AtMostOneCompositeParent")))))

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

(deftest invariant-entity-name-kebabs-to-predicate-name
  ;; Phase 7.5 Sprint 2 — invariant primitives carry their PascalCase
  ;; entity-name as the label. Address derivation kebab-cases it the
  ;; same way as any other function-shaped projection, producing a
  ;; legal Clojure symbol on both the analyzer and Layer A sides.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.distributed.cluster"
            :name "term-monotonicity"}
           (addr/canonical reg :primitive/rule :projection-kind/invariant
                           "distributed.cluster" "TermMonotonicity")))
    (is (= {:ns "fukan.distributed.cluster"
            :name "majority-required-for-leadership"}
           (addr/canonical reg :primitive/rule :projection-kind/invariant
                           "distributed.cluster" "MajorityRequiredForLeadership")))))

(deftest canonical-rule-pascal-name-via-canvas
  ;; Phase 6 Sprint 2 Task 4 sub-task C: canvas rules carry their own
  ;; :entity/name as the primitive label (e.g. "AtMostOneCompositeParent").
  ;; Address derivation must kebab-case it to the expected code-side
  ;; predicate fn name.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.validation.rules-4a" :name "at-most-one-composite-parent"}
           (addr/canonical reg :primitive/rule :projection-kind/rule
                           "validation.rules-4a" "AtMostOneCompositeParent")))))
