(ns fukan.agent.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [fukan.infra.model :as infra-model]
            [fukan.web.handler :as handler]))

(defn load-fixture []
  (edn/read-string (slurp (io/resource "fukan/fixtures/agent/small_model.edn"))))

(defn with-fixture-model [f]
  (infra-model/set-model-for-test! (load-fixture))
  (f))

(use-fixtures :each with-fixture-model)

(defn- post-eval [expr]
  (let [h (handler/create-handler)
        req {:request-method :post
             :uri "/agent/eval"
             :body (java.io.ByteArrayInputStream.
                     (.getBytes (json/generate-string {:expr expr}) "UTF-8"))
             :headers {"content-type" "application/json"}}
        resp (h req)]
    (json/parse-string (:body resp) true)))

(defn- get-status []
  (let [h (handler/create-handler)
        resp (h {:request-method :get :uri "/agent/status"})]
    (json/parse-string (:body resp) true)))

(deftest http-eval-roundtrip
  (testing "POST /agent/eval evaluates and returns JSON"
    (let [r (post-eval "(+ 1 2)")]
      (is (true? (:ok? r)))
      (is (= 3 (:result r))))))

(deftest http-eval-primitives
  (testing "POST /agent/eval can list primitives"
    (let [r (post-eval "(count (:rows (primitives)))")]
      (is (= 4 (:result r))))))

(deftest http-status-snapshot
  (testing "GET /agent/status returns the current snapshot"
    (let [r (get-status)]
      (is (true? (-> r :result :model-loaded?)))
      (is (= 4 (-> r :result :primitive-count))))))
