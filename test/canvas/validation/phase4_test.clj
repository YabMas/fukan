(ns canvas.validation.phase4-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.validation.phase4 :as port]
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
    (is (contains? names "validation.phase4") "module name present")
    (is (contains? names "Phase4Result") "Phase4Result record present")
    (is (contains? names "SubPhaseOrdering") "invariant present")
    (is (contains? names "run") "run function present")
    (is (contains? names "rules_4a") "rules_4a checker present")
    (is (contains? names "rules_4g") "rules_4g checker present")))
