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

(deftest e2e-eval-sandbox-refusal
  (testing "POST /agent/eval refuses System/exit; response is well-formed"
    (let [r (post-eval "(System/exit 0)")]
      (is (false? (:ok? r)))
      (is (string? (:error/message r))))))

(deftest e2e-eval-timeout
  (testing "POST /agent/eval times out an infinite loop"
    (let [r (post-eval "(loop [] (recur))")]
      (is (false? (:ok? r)))
      (is (= "timeout" (name (keyword (:error/kind r))))))))

(deftest e2e-drift-derivation
  (testing "agent can derive drift from L0/L1 and from L2; same answer"
    (let [via-l1 (post-eval "(count (:rows (relations :kind :relation/projects :validity :absent)))")
          via-l2 (post-eval "(count (drift))")]
      (is (= (:result via-l1) (:result via-l2))))))

(deftest e2e-help
  (testing "help surfaces L1 primitives entry"
    (let [r (post-eval "(get-in (help) ['fukan.agent.api :L1])")]
      (is (true? (:ok? r)))
      (is (some #(= "primitives" (name (keyword (:name %)))) (:result r))))))
