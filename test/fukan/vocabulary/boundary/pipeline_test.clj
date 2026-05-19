(ns fukan.vocabulary.boundary.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.pipeline :as pipeline]
            [fukan.model.build :as build]
            [malli.core :as m]))

(deftest combined-pipeline-loads-fukan-corpus
  (testing "loading src/ through the multi-extension pipeline produces a
            validated Model carrying both Allium and Boundary content"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against build/Model schema")
      (testing "Boundary tags get registered"
        (let [tag-namespaces (->> (:tag-definitions model)
                                  (map :namespace)
                                  set)]
          (is (contains? tag-namespaces "Boundary")
              "expected at least one Boundary::* TagDefinition registered")))
      (testing "Boundary::Function tag applied to fn-declared Operations"
        (let [function-tag-apps (filter (fn [ta]
                                          (= {:namespace "Boundary" :name "Function"}
                                             (:tag ta)))
                                        (:tag-apps model))]
          (is (pos? (count function-tag-apps))
              "at least one fn declaration should produce a Boundary::Function tag"))))))
