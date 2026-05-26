(ns canvas.infra.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.infra.server :as port]
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
    (is (contains? names "infra.server") "module name present")
    (is (contains? names "ServerOpts") "ServerOpts record present")
    (is (contains? names "ServerInfo") "ServerInfo record present")
    (is (contains? names "SingleServerInstance") "invariant present")
    (is (contains? names "start_server") "start_server function present")
    (is (contains? names "get_port") "get_port getter present")))
