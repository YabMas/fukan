(ns fukan.target.clojure.projector-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]))

(deftest projector-on-empty-model-returns-empty-blueprint
  (let [bp (projector/project (build/empty-model) (registry/make-registry)
                              "unknown::id" :projection-kind/rule)]
    (is (map? bp))
    (is (= :blueprint/v1 (:case bp)))))
