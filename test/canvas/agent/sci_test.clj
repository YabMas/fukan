(ns canvas.agent.sci-test
  (:require [clojure.test :refer [deftest is testing]]
            [canvas.agent.sci :as port]
            [fukan.canvas.core.helpers :as h]
            [fukan.canvas.core.substrate.store :as store]
            [datascript.core :as d]))

(deftest canvas-build-produces-non-empty-store
  (let [db (port/build-canvas)]
    (is (some? db))
    (is (>= (count (store/all-modules db)) 1))))

(deftest canvas-build-has-expected-entities
  (let [db   (port/build-canvas)
        names (->> (d/q '[:find ?n :where [?e :entity/name ?n]] db)
                   (map first)
                   set)]
    (testing "module present"
      (is (contains? names "agent.sci")))
    (testing "value types present"
      (is (contains? names "EvalResult"))
      (is (contains? names "EvalOpts")))
    (testing "invariants present"
      (is (contains? names "SandboxSafety"))
      (is (contains? names "SharedContextLifetime"))
      (is (contains? names "EvalTimeout"))
      (is (contains? names "SandboxFailureModes")))
    (testing "functions present"
      (is (contains? names "eval_string"))
      (is (contains? names "eval_string_as_view"))
      (is (contains? names "reset_ctx")))))
