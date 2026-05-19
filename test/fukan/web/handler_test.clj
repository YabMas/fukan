(ns fukan.web.handler-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [fukan.web.handler :as handler]
            [fukan.infra.model :as infra-model]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [cheshire.core :as json]))

(defn- swap-model! [m]
  ;; Test helper — set the infra model atom via the named test helper.
  (infra-model/set-model-for-test! m))

(deftest get-graph-returns-cytoscape-json
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))]
    (swap-model! model)
    (let [h (handler/create-handler)
          response (h {:request-method :get :uri "/graph"})]
      (is (= 200 (:status response)))
      (is (str/includes? (get-in response [:headers "Content-Type"]) "json"))
      (let [body (json/parse-string (:body response) true)]
        (is (>= (count (:nodes body)) 2))))))

(deftest get-projector-returns-blueprint
  (let [model (-> (build/empty-model)
                  (build/add-primitive (p/make-container {:id "m" :label "m"}))
                  (build/add-tag-application
                    (v/make-tag-application
                      {:tag {:namespace "Allium" :name "Module"}
                       :target {:case :target/primitive :id "m"}}))
                  (build/add-primitive (p/make-rule {:id "m::R" :label "R"})))]
    (swap-model! model)
    (let [h (handler/create-handler)
          response (h {:request-method :get
                       :uri "/projector"
                       :query-params {"primitive-id" "m::R"
                                      "projection-kind" "projection-kind/rule"}})]
      (is (= 200 (:status response)))
      (let [body (json/parse-string (:body response) true)]
        ;; Cheshire serialises kebab keywords as "primitive-id" (not camelCase)
        (is (= "m::R" (:primitive-id body)))
        (is (contains? (:rendered body) :markdown))))))

(deftest get-projector-missing-params-returns-400
  (let [h (handler/create-handler)
        response (h {:request-method :get :uri "/projector"})]
    (is (= 400 (:status response)))))
