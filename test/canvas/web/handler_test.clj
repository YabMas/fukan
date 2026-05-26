(ns canvas.web.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.web.handler :as port]
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
    (is (contains? names "web.handler") "module present")
    (is (contains? names "PureDelegation") "PureDelegation invariant present")
    (is (contains? names "PerRequestModel") "PerRequestModel invariant present")
    (is (contains? names "ViewTransport") "ViewTransport invariant present")
    (is (contains? names "SignalDelivery") "SignalDelivery invariant present")
    (is (contains? names "FailureModes") "FailureModes invariant present")
    (is (contains? names "create_handler") "create_handler function present")))
