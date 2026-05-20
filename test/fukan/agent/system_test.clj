(ns fukan.agent.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.system :as system]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest status-returns-snapshot
  (testing "status surfaces counts and target"
    (let [s (system/status)]
      (is (= 4 (:primitive-count s)))
      (is (= 5 (:relation-count s)))
      (is (true? (:model-loaded? s)))
      (is (contains? s :target)))))

(deftest status-no-model
  (testing "status reflects unloaded state"
    (infra-model/set-model-for-test! nil)
    (let [s (system/status)]
      (is (false? (:model-loaded? s)))
      (is (zero? (:primitive-count s))))))
