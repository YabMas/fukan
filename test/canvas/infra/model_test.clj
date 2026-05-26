(ns canvas.infra.model-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.infra.model :as port]
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
    (is (contains? names "infra.model") "module name present")
    (is (contains? names "SnapshotIsolation") "SnapshotIsolation invariant present")
    (is (contains? names "SingleModelSource") "SingleModelSource invariant present")
    (is (contains? names "ModelServerDecoupled") "ModelServerDecoupled invariant present")
    (is (contains? names "load_model") "load_model function present")
    (is (contains? names "get_model") "get_model getter present")
    (is (contains? names "refresh_model") "refresh_model function present")
    (is (contains? names "get_src") "get_src getter present")))
