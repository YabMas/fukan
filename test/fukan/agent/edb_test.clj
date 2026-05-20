(ns fukan.agent.edb-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(deftest fixture-loads
  (testing "fixture model parses and has expected shape"
    (let [m (load-fixture)]
      (is (= 4 (count (:primitives m))))
      (is (= 5 (count (:edges m))))
      (is (= 1 (count (:artifacts m)))))))
