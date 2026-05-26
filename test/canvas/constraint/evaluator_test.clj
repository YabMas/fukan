(ns canvas.constraint.evaluator-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.constraint.evaluator :as port]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-key-entities
  (let [db (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "constraint.evaluator") "module name present")
    (is (contains? names "Stratum") "Stratum value type present")
    (is (contains? names "Binding") "Binding value type present")
    (is (contains? names "StratifiedFixedPoint") "invariant present")
    (is (contains? names "evaluate_rules") "evaluate_rules function present")
    (is (contains? names "query") "query function present")))
