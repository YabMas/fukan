(ns fukan.projection.path-test
  "Example-based tests for entity-path breadcrumb computation."
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.projection.path :as path]))

;; ---------------------------------------------------------------------------
;; Fixture model

(def test-model
  {:nodes {"root"     {:id "root" :kind :module :label "root" :parent nil
                        :children #{"ns:alpha"}
                        :data {:kind :module}}
           "ns:alpha" {:id "ns:alpha" :kind :module :label "alpha" :parent "root"
                        :children #{"ns:alpha/foo"}
                        :data {:kind :module}}
           "ns:alpha/foo" {:id "ns:alpha/foo" :kind :function :label "foo" :parent "ns:alpha"
                            :children #{}
                            :data {:kind :function :private? false}}}
   :edges []})

;; ---------------------------------------------------------------------------
;; Tests

(deftest root-path
  (testing "root entity path is a single segment"
    (let [p (path/entity-path test-model "root")]
      (is (= 1 (count p)))
      (is (nil? (:id (first p)))))))

(deftest module-path
  (testing "module path has root + module"
    (let [p (path/entity-path test-model "ns:alpha")]
      (is (= 2 (count p)))
      (is (nil? (:id (first p))))
      (is (= "ns:alpha" (:id (second p)))))))

(deftest leaf-path
  (testing "leaf path has root + module + leaf"
    (let [p (path/entity-path test-model "ns:alpha/foo")]
      (is (= 3 (count p)))
      (is (= "ns:alpha/foo" (:id (last p)))))))

(deftest path-ends-with-entity
  (testing "last segment ID matches queried entity"
    (let [p (path/entity-path test-model "ns:alpha/foo")]
      (is (= "ns:alpha/foo" (:id (last p)))))))

(deftest path-always-non-empty
  (testing "path is never empty for any node"
    (doseq [nid (keys (:nodes test-model))]
      (let [p (path/entity-path test-model nid)]
        (is (seq p) (str "empty path for " nid))))))

(deftest nil-entity-path
  (testing "nil entity-id returns root-only path"
    (let [p (path/entity-path test-model nil)]
      (is (= 1 (count p))))))
