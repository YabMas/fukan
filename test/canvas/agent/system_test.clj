(ns canvas.agent.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.agent.system :as port]
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
      (is (contains? names "agent.system")))
    (testing "value types present"
      (is (contains? names "AgentStatus"))
      (is (contains? names "HelpEntry"))
      (is (contains? names "SourceEntry")))
    (testing "invariants present"
      (is (contains? names "EditRefreshQueryLoop"))
      (is (contains? names "RefreshRebuildsViews"))
      (is (contains? names "RefreshIdempotence"))
      (is (contains? names "SelfDocumenting"))
      (is (contains? names "StatusReflectsLoadedModel")))
    (testing "functions present"
      (is (contains? names "status"))
      (is (contains? names "refresh"))
      (is (contains? names "help"))
      (is (contains? names "source")))))
