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

(deftest eval-timeout
  (testing "infinite loop returns :timeout, daemon still healthy"
    (let [r (agent-sci/eval-string "(loop [] (recur))" {:timeout-ms 100})]
      (is (false? (:ok? r)))
      (is (= :timeout (:error/kind r)))
      (is (number? (:error/elapsed-ms r))))))

(deftest sandbox-refuses-system-exit
  (testing "(System/exit 0) is refused; daemon stays up"
    (let [r (agent-sci/eval-string "(System/exit 0)")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-java-io
  (testing "(slurp \"/etc/passwd\") is refused"
    (let [r (agent-sci/eval-string "(slurp \"/etc/passwd\")")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-shell
  (testing "no access to clojure.java.shell"
    (let [r (agent-sci/eval-string "(require '[clojure.java.shell]) (clojure.java.shell/sh \"echo\" \"hi\")")]
      (is (false? (:ok? r))))))

(deftest sandbox-refuses-internal-ns
  (testing "no access to fukan.* internal namespaces"
    (let [r (agent-sci/eval-string "(fukan.model.build/empty-model)")]
      (is (false? (:ok? r))))))
