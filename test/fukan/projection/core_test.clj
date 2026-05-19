(ns fukan.projection.core-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.projection.core :as proj]
            [fukan.model.build :as build]))

(deftest project-model-on-empty-returns-empty-graph
  (let [g (proj/project-model (build/empty-model))]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))
