(ns fukan.agent.api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.api :as api]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest q-finds-primitives-by-kind
  (testing "(q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]]) returns 2 rows"
    (let [rows (api/q '[:find ?p :where [?p :primitive/kind :primitive/behaviour]])]
      (is (= 2 (count rows))))))

(deftest primitives-all
  (testing "(primitives) returns the standard listing envelope"
    (let [r (api/primitives)]
      (is (= 4 (count (:rows r))))
      (is (= 4 (:total r)))
      (is (false? (:truncated? r)))
      (is (every? #(contains? % :id) (:rows r)))
      (is (every? #(contains? % :kind) (:rows r))))))

(deftest primitives-by-kind
  (testing "(primitives :kind :primitive/behaviour) filters"
    (let [r (api/primitives :kind :primitive/behaviour)]
      (is (= 2 (count (:rows r))))
      (is (every? #(= :primitive/behaviour (:kind %)) (:rows r))))))

(deftest primitives-truncation
  (testing ":truncated? true and :total N when limit exceeded"
    (let [r (api/primitives :limit 2)]
      (is (= 2 (count (:rows r))))
      (is (true? (:truncated? r)))
      (is (= 4 (:total r))))))
