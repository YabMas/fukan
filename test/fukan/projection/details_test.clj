(ns fukan.projection.details-test
  "Example-based tests for entity-details.
   Verifies correct detail structure for each entity kind and edge parsing."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.projection.details :as details]))

;; ---------------------------------------------------------------------------
;; Fixture model

(def test-model
  {:nodes {"root"      {:id "root" :kind :module :label "root" :parent nil
                         :children #{"ns:alpha" "ns:beta"}
                         :data {:kind :module}}
           "ns:alpha"  {:id "ns:alpha" :kind :module :label "alpha" :parent "root"
                         :children #{"ns:alpha/foo" "ns:alpha/bar"}
                         :data {:kind :module :doc "Alpha namespace."}}
           "ns:beta"   {:id "ns:beta" :kind :module :label "beta" :parent "root"
                         :children #{"ns:beta/baz"}
                         :data {:kind :module}}
           "ns:alpha/foo" {:id "ns:alpha/foo" :kind :function :label "foo" :parent "ns:alpha"
                            :children #{}
                            :data {:kind :function :private? false}}
           "ns:alpha/bar" {:id "ns:alpha/bar" :kind :function :label "bar" :parent "ns:alpha"
                            :children #{}
                            :data {:kind :function :private? true :doc "Bar fn."}}
           "ns:beta/baz"  {:id "ns:beta/baz" :kind :function :label "baz" :parent "ns:beta"
                            :children #{}
                            :data {:kind :function :private? false}}}
   :edges [{:from "ns:alpha/foo" :to "ns:beta/baz"}
           {:from "ns:alpha/bar" :to "ns:alpha/foo"}]})

;; ---------------------------------------------------------------------------
;; Tests

(deftest module-details
  (testing "module entity-details returns expected structure"
    (let [detail (details/entity-details test-model "ns:alpha")]
      (is (some? detail))
      (is (= "alpha" (:label detail)))
      (is (= :module (:kind detail))))))

(deftest function-details
  (testing "function entity-details returns expected structure"
    (let [detail (details/entity-details test-model "ns:alpha/bar")]
      (is (some? detail))
      (is (= "bar" (:label detail)))
      (is (= :function (:kind detail))))))

(deftest edge-details
  (testing "edge ID parsing returns edge detail"
    (let [edge-id "edge~ns:alpha/foo~ns:beta/baz~code-flow"
          detail (details/entity-details test-model edge-id)]
      (is (some? detail))
      (is (= :edge (:kind detail))))))

(deftest module-with-description
  (testing "top-level :description on node is extracted"
    (let [model (assoc-in test-model [:nodes "ns:alpha" :description]
                          "A module for alpha things.")
          detail (details/entity-details model "ns:alpha")]
      (is (= "A module for alpha things." (:description detail))))))

(deftest module-with-guarantees
  (testing "module with boundary guarantees includes them in detail"
    (let [model (assoc-in test-model [:nodes "ns:alpha" :data :boundary]
                          {:guarantees ["idempotent" "snapshot_isolation"]})
          detail (details/entity-details model "ns:alpha")]
      (is (some? (:guarantees detail)))
      (is (= ["idempotent" "snapshot_isolation"] (:guarantees detail))))))

(deftest module-without-guarantees
  (testing "module without boundary has no guarantees"
    (let [detail (details/entity-details test-model "ns:alpha")]
      (is (nil? (:guarantees detail))))))

(deftest nil-entity
  (testing "nil entity-id returns nil"
    (is (nil? (details/entity-details test-model nil)))))

(deftest nonexistent-entity
  (testing "nonexistent entity-id returns nil"
    (is (nil? (details/entity-details test-model "does-not-exist")))))

;; ---------------------------------------------------------------------------
;; Finding 5: edge details should include :detail-kind :edge

(deftest edge-detail-kind
  (testing "edge detail has :detail-kind :edge discriminant"
    (let [model (assoc test-model :edges [{:from "ns:alpha/foo" :to "ns:beta/baz" :kind :function-call}])
          edge-id "edge~ns:alpha/foo~ns:beta/baz~code-flow"
          detail (details/entity-details model edge-id)]
      (is (= :edge (:detail-kind detail))))))

;; ---------------------------------------------------------------------------
;; Finding 4: code-flow edge details split into called-fns and dispatched-fns

(deftest edge-detail-dispatched-fns
  (testing "code-flow edge detail separates function-call and dispatches relationships"
    (let [model {:nodes {"root"       {:id "root" :kind :module :label "root" :parent nil
                                        :children #{"ns:alpha" "ns:beta"} :data {:kind :module}}
                          "ns:alpha"  {:id "ns:alpha" :kind :module :label "alpha" :parent "root"
                                        :children #{"ns:alpha/call-fn" "ns:alpha/dispatch-pt"}
                                        :data {:kind :module}}
                          "ns:beta"   {:id "ns:beta" :kind :module :label "beta" :parent "root"
                                        :children #{"ns:beta/target" "ns:beta/handler"}
                                        :data {:kind :module}}
                          "ns:alpha/call-fn"     {:id "ns:alpha/call-fn" :kind :function :label "call-fn"
                                                   :parent "ns:alpha" :children #{}
                                                   :data {:kind :function :private? false}}
                          "ns:alpha/dispatch-pt" {:id "ns:alpha/dispatch-pt" :kind :function :label "dispatch-pt"
                                                   :parent "ns:alpha" :children #{}
                                                   :data {:kind :function :private? false}}
                          "ns:beta/target"       {:id "ns:beta/target" :kind :function :label "target"
                                                   :parent "ns:beta" :children #{}
                                                   :data {:kind :function :private? false}}
                          "ns:beta/handler"      {:id "ns:beta/handler" :kind :function :label "handler"
                                                   :parent "ns:beta" :children #{}
                                                   :data {:kind :function :private? false}}}
                  :edges [{:from "ns:alpha/call-fn" :to "ns:beta/target" :kind :function-call}
                          {:from "ns:alpha/dispatch-pt" :to "ns:beta/handler" :kind :dispatches}]}
          edge-id "edge~ns:alpha~ns:beta~code-flow"
          detail (details/entity-details model edge-id)]
      (is (some? detail))
      (is (= :edge (:detail-kind detail)))
      (is (some? (:called-fns detail)) "should have called-fns from function-call edges")
      (is (some? (:dispatched-fns detail)) "should have dispatched-fns from dispatches edges")
      (is (= 1 (count (:called-fns detail))))
      (is (= 1 (count (:dispatched-fns detail)))))))
