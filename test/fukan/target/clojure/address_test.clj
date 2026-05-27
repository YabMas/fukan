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

(deftest canonical-rule-pascal-name-via-canvas
  ;; Phase 6 Sprint 2 Task 4 sub-task C: canvas rules carry their own
  ;; :entity/name as the primitive label (e.g. "AtMostOneCompositeParent").
  ;; Address derivation must kebab-case it to the expected code-side
  ;; predicate fn name.
  (let [reg (r/with-root-prefix (r/make-registry) "fukan")]
    (is (= {:ns "fukan.validation.rules-4a" :name "at-most-one-composite-parent"}
           (addr/canonical reg :primitive/rule :projection-kind/rule
                           "validation.rules-4a" "AtMostOneCompositeParent")))))
