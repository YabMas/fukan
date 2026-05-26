(ns canvas.validation.violation-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.validation.violation :as port]
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
    (is (contains? names "validation.violation") "module name present")
    (is (contains? names "make_violation") "make_violation function present")
    (is (contains? names "error") "error predicate present")
    (is (contains? names "errors") "errors filter present")
    (is (contains? names "warnings") "warnings filter present")
    (is (contains? names "ViolationShape") "shape invariant present")
    (is (contains? names "SeverityPartition") "partition invariant present")))
