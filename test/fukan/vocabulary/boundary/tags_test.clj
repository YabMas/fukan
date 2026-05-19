(ns fukan.vocabulary.boundary.tags-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.boundary.tags :as tags]))

(deftest registry-shape
  (testing "boundary-tag-definitions is a non-empty vector of TagDefinitions"
    (is (vector? tags/boundary-tag-definitions))
    (is (= 5 (count tags/boundary-tag-definitions)))))

(deftest expected-tags-present
  (testing "all five expected Boundary::* tags are registered"
    (let [tag-names (set (map (juxt :namespace :name)
                              tags/boundary-tag-definitions))]
      (is (= #{["Boundary" "Function"]
               ["Boundary" "Binding"]
               ["Boundary" "ModuleApi"]
               ["Boundary" "Subsystem"]
               ["Boundary" "Exports"]}
             tag-names)))))

(deftest applies-to-correct-targets
  (testing "each tag applies to the right kernel target kind"
    (let [by-name (into {} (map (fn [td] [[(:namespace td) (:name td)] td])
                                tags/boundary-tag-definitions))]
      (is (= :target/operation (get-in by-name [["Boundary" "Function"]  :applies-to])))
      (is (= :target/edge      (get-in by-name [["Boundary" "Binding"]   :applies-to])))
      (is (= :target/container (get-in by-name [["Boundary" "ModuleApi"] :applies-to])))
      (is (= :target/container (get-in by-name [["Boundary" "Subsystem"] :applies-to])))
      (is (= :target/container (get-in by-name [["Boundary" "Exports"]   :applies-to]))))))
