(ns canvas.agent.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.agent.query :as port]
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
      (is (contains? names "agent.query")))
    (testing "value types present"
      (is (contains? names "QueryForm"))
      (is (contains? names "ParsedQuery"))
      (is (contains? names "QueryAtom"))
      (is (contains? names "QueryRow")))
    (testing "invariants present"
      (is (contains? names "DSLShape"))
      (is (contains? names "UnificationSemantics"))
      (is (contains? names "ParseFailureModes")))
    (testing "functions present"
      (is (contains? names "parse"))
      (is (contains? names "evaluate")))))
