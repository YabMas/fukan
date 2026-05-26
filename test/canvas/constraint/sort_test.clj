(ns canvas.constraint.sort-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.constraint.sort :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "constraint.sort") "module name present")
    (is (contains? names "SortGuardCatalogue") "SortGuardCatalogue invariant present")
    (is (contains? names "is_string") "is_string function present")
    (is (contains? names "is_number") "is_number function present")
    (is (contains? names "is_keyword") "is_keyword function present")
    (is (contains? names "is_primitive_id") "is_primitive_id function present")))
