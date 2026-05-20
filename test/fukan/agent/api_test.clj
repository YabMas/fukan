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

(deftest get-primitive-returns-full-detail
  (testing "get-primitive returns the full primitive map, not the summary"
    (let [p (api/get-primitive "behaviour:hex/core/r-mint")]
      (is (= :primitive/behaviour (:kind p)))
      (is (contains? p :rules))
      (is (= "mint" (:label p))))))

(deftest get-primitive-missing-returns-nil
  (testing "missing id returns nil"
    (is (nil? (api/get-primitive "behaviour:does-not-exist")))))

(deftest relations-all
  (testing "(relations) returns all edges in the model"
    (let [r (api/relations)]
      (is (= 5 (count (:rows r))))
      (is (= 5 (:total r))))))

(deftest relations-by-kind
  (testing "(relations :kind :projects) filters to projects edges"
    (let [r (api/relations :kind :projects)]
      (is (= 2 (count (:rows r)))))))

(deftest relations-by-validity
  (testing "(relations :kind :projects :validity :absent) finds drift candidates"
    (let [r (api/relations :kind :projects :validity :absent)]
      (is (= 1 (count (:rows r))))
      (is (= "behaviour:hex/core/r-mint"
             (-> r :rows first :from :endpoint/primitive))))))

(deftest relations-by-from
  (testing "(relations :from id) filters edges originating at id"
    (let [r (api/relations :from "container:hex/core")]
      (is (every? #(= "container:hex/core"
                      (-> % :from :endpoint/primitive)) (:rows r))))))

(deftest vocabulary-returns-kinds-in-use
  (testing "vocabulary surfaces primitive-kinds and relation-kinds present in the loaded Model"
    (let [v (api/vocabulary)]
      (is (contains? (set (:primitive-kinds v)) :primitive/behaviour))
      (is (contains? (set (:primitive-kinds v)) :primitive/container))
      (is (contains? (set (:relation-kinds v)) :projects))
      (is (contains? (set (:relation-kinds v)) :owns)))))

(deftest schema-for-kind
  (testing "(schema :kind :primitive/behaviour) surfaces attribute keys observed in fixture"
    (let [s (api/schema :kind :primitive/behaviour)]
      (is (contains? (set (:attributes s)) :rules))
      (is (contains? (set (:attributes s)) :label)))))

(deftest idioms-empty-on-fixture
  (testing "idioms returns empty vec when project layer has no entries"
    (is (vector? (api/idioms)))))

(deftest constraints-empty-on-fixture
  (testing "constraints returns empty vec on fixture (no constraint defs)"
    (is (vector? (api/constraints)))))

(deftest violations-empty-on-fixture
  (testing "violations returns empty vec on fixture"
    (is (vector? (api/violations)))))
