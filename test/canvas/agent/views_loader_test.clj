(ns canvas.agent.views-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.agent.views-loader :as port]
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
      (is (contains? names "agent.views_loader")))
    (testing "value types present"
      (is (contains? names "LoadReport"))
      (is (contains? names "LoadError")))
    (testing "invariants present"
      (is (contains? names "LoadFileReplacesViews"))
      (is (contains? names "ReportShape"))
      (is (contains? names "PerFormIsolation"))
      (is (contains? names "DiscoveryConvention"))
      (is (contains? names "ResetClearsBoth")))
    (testing "functions present"
      (is (contains? names "load_file"))
      (is (contains? names "auto_load"))
      (is (contains? names "discover"))
      (is (contains? names "last_report"))
      (is (contains? names "reset")))))
