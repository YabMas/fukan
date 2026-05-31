(ns fukan.canvas.instruct.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.instruct.core :as core]
            [fukan.canvas.instruct.registry :as registry]))

(deftest all-scenarios-returns-collection
  (let [all (registry/all-scenarios)]
    (is (or (seq? all) (nil? all) (sequential? all)))
    (testing "every registered scenario satisfies the contract"
      (doseq [s all]
        (is (core/valid-scenario? s))))))

(deftest scenario-by-id-unknown-returns-nil
  (is (nil? (registry/scenario-by-id :code-side/no-such-scenario))))

(deftest scenario-by-id-roundtrips-registered
  (testing "every known-scenarios entry is findable by its id"
    (doseq [s (registry/all-scenarios)]
      (is (= s (registry/scenario-by-id (:scenario-id s)))))))

(deftest invalid-scenario-skipped-with-warning
  (testing "an invalid scenario var is filtered, not thrown"
    (with-redefs [registry/known-scenarios [#'registry/known-scenarios]]
      ;; #'known-scenarios itself is a var, dereffed it's not a scenario
      ;; map at all — validate-scenario rejects it.
      (let [stderr (java.io.StringWriter.)]
        (binding [*err* stderr]
          (is (= [] (vec (registry/all-scenarios)))))
        (is (re-find #"invalid scenario" (str stderr)))))))
