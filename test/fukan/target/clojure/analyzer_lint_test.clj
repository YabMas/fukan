(ns fukan.target.clojure.analyzer-lint-test
  "Plan 5 Task 8 — validity discipline + lint scope.

   Plan 6 Task 13 landed unprojected Code.* artifact materialisation;
   the earlier deferral assertions have been removed."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest empty-model-produces-no-edges
  (testing "no primitives → no projects edges emitted"
    (let [m (build/empty-model)
          m1 (analyzer/run m (registry/make-registry)
                           "test/fixtures/clojure-projects/m-with-submit")]
      (is (zero? (count (:edges m1)))
          "no primitives → no projects edges"))))
