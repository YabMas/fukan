(ns fukan.canvas.core.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.core.check :as check]
            [fukan.canvas.core.helpers :as h]))

(deftest empty-canvas-no-violations
  (testing "fc/check on an empty store returns no violations"
    (check/with-constraint-registry
      (h/with-canvas
        (is (empty? (check/check-all)))))))

(deftest constraint-firing
  (testing "a constraint that always fails returns a violation"
    (check/with-constraint-registry
      (h/with-canvas
        (h/within-module "test"
          (check/register-constraint!
            'always-fail
            "Always fails."
            '[(Module ?m)]))
        (let [violations (check/check-all)]
          (is (= 1 (count violations)))
          (is (= 'always-fail (:constraint (first violations)))))))))

(deftest violations-carry-source-location
  (testing "registered constraints capture their registration source"
    (check/with-constraint-registry
      (h/with-canvas
        (h/within-module "test.loc"
          (check/register-constraint!
            'loc-test
            "Location test."
            '[(Module ?m)]))
        (let [violations (check/check-all)
              v          (first violations)
              loc        (:source-location v)]
          (is (= 1 (count violations)))
          (is (map? loc) "source-location should be a map")
          (is (string? (:ns loc)) ":ns should be a string")
          (is (number? (:line loc)) ":line should be a number"))))))

(deftest violations-carry-stable-ids
  (testing "offenders are reported with their stable ids"
    (check/with-constraint-registry
      (h/with-canvas
        (h/within-module "test.sid"
          (check/register-constraint!
            'module-check
            "All modules violate."
            '[(Module ?m)]))
        (let [violations (check/check-all)
              v          (first violations)
              offenders  (:offenders v)]
          (is (= 1 (count violations)))
          (is (seq offenders) "offenders should be non-empty")
          (is (map? (first offenders)) "each offender should be a map")
          (is (contains? (first offenders) :eid) "offender should have :eid")
          (is (contains? (first offenders) :stable-id) "offender should have :stable-id")
          (is (= "test.sid" (:stable-id (first offenders)))
              "module stable-id should be the module name"))))))
