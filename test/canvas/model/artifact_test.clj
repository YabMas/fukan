(ns canvas.model.artifact-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.artifact :as port]
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
    (is (contains? names "ArtifactIdentityIsTriple"))
    (is (contains? names "make_code_function"))
    (is (contains? names "make_code_data_structure"))
    (is (contains? names "artifact_identity"))))
