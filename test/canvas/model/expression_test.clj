(ns canvas.model.expression-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.expression :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (is (contains? names "LabelIsAddressabilityOnly"))
    (is (contains? names "AggregateKindIsClosed"))
    (is (contains? names "EnvironmentIsCallsiteTyped"))
    (is (contains? names "make_var"))
    (is (contains? names "make_apply"))
    (is (contains? names "make_aggregate"))
    (is (contains? names "expression_identity"))
    (is (contains? names "make_environment_onestate"))
    (is (contains? names "make_environment_twostate"))
    (is (contains? names "make_environment_model_introspection"))))
