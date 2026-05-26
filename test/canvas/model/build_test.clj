(ns canvas.model.build-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.build :as port]
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
    (is (contains? names "PureConstruction"))
    (is (contains? names "UniquePrimitiveIds"))
    (is (contains? names "EndpointResolution"))
    (is (contains? names "MultiEdgeIdentity"))
    (is (contains? names "UniqueArtifactIdentity"))
    (is (contains? names "empty_model"))
    (is (contains? names "add_primitive"))
    (is (contains? names "add_edge"))
    (is (contains? names "add_artifact"))))
