(ns fukan.agent.views-loader-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.sci :as agent-sci]
            [fukan.agent.views-loader :as loader]
            [fukan.infra.model :as infra-model]))

(defn load-fixture-model []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture-model))
  (loader/reset!)
  (f))

(use-fixtures :each with-fixture-model)

(deftest load-good-file
  (testing "good views file loads; defs callable via eval"
    (let [path (.getPath (io/resource "fukan/fixtures/agent/agent-views-good.clj"))
          report (loader/load-file! path)]
      (is (empty? (:errors report)))
      (let [r (agent-sci/eval-string "(unrealised-by-altitude)")]
        (is (true? (:ok? r)))))))

(deftest partial-load-on-syntax-error
  (testing "syntax error reports; daemon still healthy"
    (let [path (.getPath (io/resource "fukan/fixtures/agent/agent-views-syntax-error.clj"))
          report (loader/load-file! path)]
      (is (seq (:errors report)))
      (is (= :syntax (-> report :errors first :error/kind))))))

(deftest unbound-var-reported
  (testing "form referring to an unbound var loads as a def but errors when called"
    (let [path (.getPath (io/resource "fukan/fixtures/agent/agent-views-unbound.clj"))
          _ (loader/load-file! path)
          r (agent-sci/eval-string "(refers-to-unknown)")]
      (is (false? (:ok? r))))))
