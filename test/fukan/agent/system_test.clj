(ns fukan.agent.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.agent.system :as system]
            [fukan.agent.views-loader :as views-loader]
            [fukan.infra.model :as infra-model]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (views-loader/reset!)
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

(deftest help-lists-surface
  (testing "(help) groups fns by namespace and layer"
    (let [h (system/help)]
      (is (contains? h 'fukan.agent.api))
      (is (contains? h 'fukan.agent.system))
      (let [api (get h 'fukan.agent.api)]
        (is (contains? api :L0))
        (is (contains? api :L1))
        (is (contains? api :L2))
        (is (some #(= 'primitives (:name %)) (:L1 api)))
        (is (some #(= 'q (:name %)) (:L0 api)))
        (is (some #(= 'drift (:name %)) (:L2 api)))))))

(deftest help-for-single-fn
  (testing "(help 'primitives) returns docstring + signatures + examples"
    (let [h (system/help 'primitives)]
      (is (= 'primitives (:name h)))
      (is (= :L1 (:layer h)))
      (is (string? (:doc h)))
      (is (string? (:example h))))))

(deftest source-returns-implementation
  (testing "(source 'drift) returns the L2 implementation as a string"
    (let [s (system/source 'drift)]
      (is (= 'drift (:name s)))
      (is (string? (:source s)))
      (is (re-find #"defn drift" (:source s))))))

(deftest status-includes-views-report
  (let [s (system/status)]
    (is (contains? s :views))
    (is (contains? (:views s) :loaded))
    (is (contains? (:views s) :errors))))
