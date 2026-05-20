(ns fukan.agent.sci-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.sci :as agent-sci]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(deftest eval-arithmetic
  (testing "trivial expression"
    (let [r (agent-sci/eval-string "(+ 1 2)")]
      (is (true? (:ok? r)))
      (is (= 3 (:result r))))))

(deftest eval-primitives
  (testing "agent can call (primitives) without namespace prefix"
    (let [r (agent-sci/eval-string "(primitives :kind :primitive/behaviour)")]
      (is (true? (:ok? r)))
      (is (= 2 (count (:rows (:result r))))))))

(deftest eval-status
  (testing "agent can call (status) without namespace prefix"
    (let [r (agent-sci/eval-string "(status)")]
      (is (true? (:ok? r)))
      (is (true? (-> r :result :model-loaded?))))))
