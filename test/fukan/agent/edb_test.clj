(ns fukan.agent.edb-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.edb :as edb]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(deftest fixture-loads
  (testing "fixture model parses and has expected shape"
    (let [m (load-fixture)]
      (is (= 4 (count (:primitives m))))
      (is (= 5 (count (:edges m))))
      (is (= 1 (count (:artifacts m)))))))

(deftest model->edb-projects-primitives
  (testing "every primitive becomes a :primitive/kind tuple"
    (let [m   (load-fixture)
          out (edb/model->edb m)]
      (is (contains? out :primitive/kind))
      (is (contains? (get out :primitive/kind)
                     ["behaviour:hex/core/r-mint" :primitive/behaviour]))
      (is (= 4 (count (get out :primitive/kind)))))))

(deftest model->edb-projects-edges
  (testing "every edge becomes a :relation/kind tuple"
    (let [m   (load-fixture)
          out (edb/model->edb m)]
      (is (contains? out :relation/kind))
      (is (= 5 (count (get out :relation/kind)))))))

(deftest model->edb-projects-validity
  (testing "projects edges carry :validity tuples"
    (let [m   (load-fixture)
          out (edb/model->edb m)]
      (is (contains? out :relation/validity))
      (is (= 2 (count (get out :relation/validity)))))))
