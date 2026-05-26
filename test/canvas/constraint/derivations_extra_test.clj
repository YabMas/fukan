(ns canvas.constraint.derivations-extra-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.constraint.derivations-extra :as port]
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
    (is (contains? names "constraint.derivations-extra") "module name present")
    (is (contains? names "DependsOnSemantics") "DependsOnSemantics invariant present")
    (is (contains? names "AppendsToBaseEDB") "AppendsToBaseEDB invariant present")
    (is (contains? names "PureDerivationLayer") "PureDerivationLayer invariant present")
    (is (contains? names "depends_on_rules") "depends_on_rules function present")))
