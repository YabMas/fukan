(ns canvas.target.clojure.blueprint-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.target.clojure.blueprint :as port]
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
      (is (contains? names "target.clojure.blueprint")))
    (testing "value type present"
      (is (contains? names "Blueprint")))
    (testing "invariants present"
      (is (contains? names "Ephemeral"))
      (is (contains? names "IdentityIsKey"))
      (is (contains? names "EdnRoundtripCanonical"))
      (is (contains? names "DualSerialisation")))
    (testing "functions present"
      (is (contains? names "make"))
      (is (contains? names "identity"))
      (is (contains? names "to_edn"))
      (is (contains? names "to_markdown")))))
