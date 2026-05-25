(ns fukan.canvas.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.check :as check]
            [fukan.canvas.helpers :as h]))

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
