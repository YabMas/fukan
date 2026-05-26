(ns canvas.project_layer.defaults-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.project_layer.defaults :as port]
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
    (is (contains? names "project_layer.defaults") "module name present")
    (is (contains? names "SelfReferentialIdentity") "SelfReferentialIdentity invariant present")
    (is (contains? names "fukan_on_fukan") "fukan_on_fukan function present")))
