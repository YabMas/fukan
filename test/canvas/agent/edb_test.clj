(ns canvas.agent.edb-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.agent.edb :as port]
            [fukan.canvas.core.helpers :as h]
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
    (testing "module present"
      (is (contains? names "agent.edb")))
    (testing "opaque value type present"
      (is (contains? names "EDB")))
    (testing "invariants present"
      (is (contains? names "PredicateCatalogue"))
      (is (contains? names "EndpointEncoding"))
      (is (contains? names "EdgeIdSynthesis")))
    (testing "function present"
      (is (contains? names "model_to_edb")))))
