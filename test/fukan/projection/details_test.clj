(ns fukan.projection.details-test
  "Example-based tests for entity-details.
   Verifies correct detail structure for each entity kind and edge parsing."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.projection.details :as details]))

;; ---------------------------------------------------------------------------
;; Fixture model

(def test-model
  {:nodes {"root"      {:id "root" :kind :container :label "root" :parent nil
                         :children #{"ns:alpha" "ns:beta"}
                         :data {:kind :container}}
           "ns:alpha"  {:id "ns:alpha" :kind :container :label "alpha" :parent "root"
                         :children #{"ns:alpha/foo" "ns:alpha/bar"}
                         :data {:kind :container :doc "Alpha namespace."}}
           "ns:beta"   {:id "ns:beta" :kind :container :label "beta" :parent "root"
                         :children #{"ns:beta/baz"}
                         :data {:kind :container}}
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

(deftest container-details
  (testing "container entity-details returns expected structure"
    (let [detail (details/entity-details test-model "ns:alpha")]
      (is (some? detail))
      (is (= "alpha" (:label detail)))
      (is (= :container (:kind detail))))))

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

(deftest container-with-description
  (testing "top-level :description on node is extracted"
    (let [model (assoc-in test-model [:nodes "ns:alpha" :description]
                          "A module for alpha things.")
          detail (details/entity-details model "ns:alpha")]
      (is (= "A module for alpha things." (:description detail))))))

(deftest container-with-guarantees
  (testing "container with surface guarantees includes them in detail"
    (let [model (assoc-in test-model [:nodes "ns:alpha" :data :surface]
                          {:guarantees ["idempotent" "snapshot_isolation"]})
          detail (details/entity-details model "ns:alpha")]
      (is (some? (:guarantees detail)))
      (is (= ["idempotent" "snapshot_isolation"] (:guarantees detail))))))

(deftest container-without-guarantees
  (testing "container without surface has no guarantees"
    (let [detail (details/entity-details test-model "ns:alpha")]
      (is (nil? (:guarantees detail))))))

(deftest nil-entity
  (testing "nil entity-id returns nil"
    (is (nil? (details/entity-details test-model nil)))))

(deftest nonexistent-entity
  (testing "nonexistent entity-id returns nil"
    (is (nil? (details/entity-details test-model "does-not-exist")))))
