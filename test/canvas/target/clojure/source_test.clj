(ns canvas.target.clojure.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.target.clojure.source :as port]
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
      (is (contains? names "target.clojure.source")))
    (testing "value type present"
      (is (contains? names "SourceSymbol")))
    (testing "invariants present"
      (is (contains? names "ReadOnly"))
      (is (contains? names "DeterministicWalk"))
      (is (contains? names "TopLevelOnly"))
      (is (contains? names "NoEval")))
    (testing "functions present"
      (is (contains? names "find_clj_files"))
      (is (contains? names "read_forms"))
      (is (contains? names "extract_symbols")))))
