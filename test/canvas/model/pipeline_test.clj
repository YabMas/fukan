(ns canvas.model.pipeline-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.pipeline :as port]
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
    (is (contains? names "PhaseOrdering"))
    (is (contains? names "GateG2Halts"))
    (is (contains? names "NonGatingPhases"))
    (is (contains? names "DefaultsRegistrationIsIdempotent"))
    (is (contains? names "build_model"))))
