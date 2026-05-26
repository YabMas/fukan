(ns canvas.target.clojure.projector-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.target.clojure.projector :as port]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db    (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (testing "module present"
      (is (contains? names "target.clojure.projector")))
    (testing "invariants present"
      (is (contains? names "OnDemand"))
      (is (contains? names "SixComponentAssembly"))
      (is (contains? names "ReadOnly"))
      (is (contains? names "AddressMatchesAnalyzer"))
      (is (contains? names "IdiomSelectionRoute")))
    (testing "function present"
      (is (contains? names "project")))))
