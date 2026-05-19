(ns fukan.target.clojure.analyzer-unprojected-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.analyzer :as analyzer]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest unprojected-defns-become-artifacts
  ;; The m-with-submit fixture has (defn submit []) AND (defn helper-fn []).
  ;; With no spec primitives, neither is canonical-projected. Plan 6 Task 13
  ;; surfaces both as unprojected Code.Function artifacts.
  (let [m (analyzer/run (build/empty-model) (registry/make-registry)
                        "test/fixtures/clojure-projects/m-with-submit")
        fn-artifacts (filter (fn [[_ a]]
                               (= :code/function (get-in a [:sub :case])))
                             (:artifacts m))]
    (is (= 2 (count fn-artifacts)) "submit + helper-fn both materialised")))

(deftest unprojected-artifacts-have-no-projects-edge
  (let [m (analyzer/run (build/empty-model) (registry/make-registry)
                        "test/fixtures/clojure-projects/m-with-submit")
        projects-edges (filter #(= :relation/projects (:kind %)) (:edges m))]
    (is (zero? (count projects-edges))
        "no primitives → no projects edges, even though artifacts materialised")))
