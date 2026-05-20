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
