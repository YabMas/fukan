(ns canvas.web.views.projection-test
  (:require [clojure.test :refer [deftest is]]
            [canvas.web.views.projection :as port]
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
    (is (contains? names "web.views.projection") "module present")
    (is (contains? names "Projection") "Projection record present")
    (is (contains? names "Node") "Node record present")
    (is (contains? names "Edge") "Edge record present")
    (is (contains? names "NodeId") "NodeId stub present")))
