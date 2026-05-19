(ns fukan.target.clojure.analyzer-lint-test
  "Plan 5 Task 8 — validity discipline + lint scope.

   The heavier lint work (duplicate canonical-address detection;
   unprojected Code.* artifact discovery for the explorer's
   'unbound code' view) is deferred to Plan 6, where it pairs
   naturally with interactive surfacing. Task 8 verifies that the
   analyzer doesn't emit any of that yet — :validity :valid/:absent
   emission stays clean, with no extra artifacts beyond what the
   spec primitives demand."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest empty-model-produces-no-artifacts
  (testing "no primitives → no artifacts materialised, even with source root"
    (let [m (build/empty-model)
          m1 (analyzer/run m (registry/make-registry)
                           "test/fixtures/clojure-projects/m-with-submit")]
      (is (zero? (count (:artifacts m1)))
          "no primitives to project from → no artifacts emitted")
      (is (zero? (count (:edges m1)))
          "no primitives → no projects edges"))))

(deftest analyzer-emits-only-canonical-projections-not-unprojected
  (testing "the analyzer does NOT walk source to surface unprojected defns"
    ;; The fixture m.clj has (defn submit []) AND (defn helper-fn []).
    ;; Without a spec primitive named submit, neither appears as an artifact.
    ;; Plan 6 (Explorer) will add unprojected-code discovery via direct source
    ;; walking; Plan 5 MVP keeps analyzer scope tight to canonical projections.
    (let [m (build/empty-model)
          m1 (analyzer/run m (registry/make-registry)
                           "test/fixtures/clojure-projects/m-with-submit")]
      (is (zero? (count (:artifacts m1)))))))
