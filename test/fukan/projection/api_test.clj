(ns fukan.projection.api-test
  "Tests for projection API: search, overview, and schema-node-id."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.projection.api :as api]))

;; ---------------------------------------------------------------------------
;; Fixture model

(def test-model
  {:nodes {"root"     {:id "root" :kind :module :label "Root Module" :parent nil
                        :children #{"ns:alpha" "ns:beta"}
                        :data {:kind :module}}
           "ns:alpha" {:id "ns:alpha" :kind :module :label "alpha" :parent "root"
                        :children #{"ns:alpha/foo" "ns:alpha/bar" "ns:alpha/MySchema"}
                        :data {:kind :module}}
           "ns:beta"  {:id "ns:beta" :kind :module :label "beta" :parent "root"
                        :children #{"ns:beta/baz"}
                        :data {:kind :module}}
           "ns:alpha/foo" {:id "ns:alpha/foo" :kind :function :label "foo" :parent "ns:alpha"
                            :children #{} :data {:kind :function :private? false}}
           "ns:alpha/bar" {:id "ns:alpha/bar" :kind :function :label "bar" :parent "ns:alpha"
                            :children #{} :data {:kind :function :private? false}}
           "ns:alpha/MySchema" {:id "ns:alpha/MySchema" :kind :schema :label "MySchema" :parent "ns:alpha"
                                 :children #{} :data {:kind :schema :schema-key :MySchema}}
           "ns:beta/baz"  {:id "ns:beta/baz" :kind :function :label "baz" :parent "ns:beta"
                            :children #{} :data {:kind :function :private? false}}}
   :edges [{:from "ns:alpha/foo" :to "ns:beta/baz" :kind :function-call}]})

;; ---------------------------------------------------------------------------
;; Finding 6: Search tests

(deftest search-case-insensitive
  (testing "search is case-insensitive"
    (let [results (api/search test-model "FOO" 10)]
      (is (= 1 (count results)))
      (is (= "ns:alpha/foo" (:id (first results)))))))

(deftest search-substring
  (testing "search matches substrings"
    (let [results (api/search test-model "ba" 10)]
      (is (= 2 (count results)))
      (is (every? #(#{"ns:alpha/bar" "ns:beta/baz"} (:id %)) results)))))

(deftest search-limit
  (testing "search respects limit"
    (let [results (api/search test-model "a" 2)]
      (is (<= (count results) 2)))))

(deftest search-no-matches
  (testing "search returns empty for no matches"
    (let [results (api/search test-model "zzzzz" 10)]
      (is (empty? results)))))

(deftest search-result-shape
  (testing "search results have expected fields"
    (let [results (api/search test-model "foo" 10)
          r (first results)]
      (is (contains? r :id))
      (is (contains? r :kind))
      (is (contains? r :label))
      (is (contains? r :parent)))))

;; ---------------------------------------------------------------------------
;; Finding 6: Overview tests

(deftest overview-totals
  (testing "overview has correct totals"
    (let [ov (api/overview test-model)]
      (is (= 7 (:total-nodes ov)))
      (is (= 1 (:total-edges ov))))))

(deftest overview-by-kind
  (testing "overview by-kind breakdown is correct"
    (let [ov (api/overview test-model)]
      (is (= 3 (get-in ov [:by-kind :module])))
      (is (= 3 (get-in ov [:by-kind :function])))
      (is (= 1 (get-in ov [:by-kind :schema]))))))

;; ---------------------------------------------------------------------------
;; Finding 7: FindSchemaNodeId tests

(deftest schema-node-id-found
  (testing "schema-node-id returns correct ID for known key"
    (is (= "ns:alpha/MySchema" (api/schema-node-id test-model :MySchema)))))

(deftest schema-node-id-not-found
  (testing "schema-node-id returns nil for unknown key"
    (is (nil? (api/schema-node-id test-model :NonExistent)))))

;; ---------------------------------------------------------------------------
;; Navigate perspective passthrough

(def perspective-model
  {:nodes {"root" {:id "root" :kind :module :label "root" :parent nil
                    :children #{"ns-a" "ns-b"} :data {:kind :module}}
           "ns-a" {:id "ns-a" :kind :module :label "ns-a" :parent "root"
                    :children #{"dp"} :data {:kind :module}}
           "ns-b" {:id "ns-b" :kind :module :label "ns-b" :parent "root"
                    :children #{"hd"} :data {:kind :module}}
           "dp" {:id "dp" :kind :function :label "dp" :parent "ns-a"
                  :children #{} :data {:kind :function :private? false}}
           "hd" {:id "hd" :kind :function :label "hd" :parent "ns-b"
                  :children #{} :data {:kind :function :private? false}}}
   :edges [{:from "dp" :to "hd" :kind :dispatches}]})

(deftest navigate-passes-perspective
  (testing "navigate passes perspective through to entity-graph"
    (let [call-result (api/navigate perspective-model
                        {:view-id "root" :expanded #{}
                         :visible-edge-types #{:code-flow}
                         :perspective :call-graph})
          dep-result (api/navigate perspective-model
                       {:view-id "root" :expanded #{}
                        :visible-edge-types #{:code-flow}
                        :perspective :dependency-graph})
          call-edges (set (map (juxt :from :to) (:edges (:graph call-result))))
          dep-edges (set (map (juxt :from :to) (:edges (:graph dep-result))))]
      (is (contains? call-edges ["ns-a" "ns-b"])
          "call-graph: dispatch-point module → handler module")
      (is (contains? dep-edges ["ns-b" "ns-a"])
          "dependency-graph: handler module → dispatch-point module (flipped)"))))
