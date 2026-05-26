(ns canvas.model.relations-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.model.relations :as port]
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
    (is (contains? names "MakeEdgeValidatesRelationKind"))
    (is (contains? names "EdgeIdentityIsPerRelation"))
    (is (contains? names "primitive_ref"))
    (is (contains? names "substrate_address"))
    (is (contains? names "artifact_ref"))
    (is (contains? names "make_edge"))
    (is (contains? names "identifying_slots"))
    (is (contains? names "edge_identity"))))
