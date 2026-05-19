(ns fukan.vocabulary.boundary.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.model.pipeline :as pipeline]
            [fukan.model.build :as build]
            [fukan.vocabulary.allium.pipeline :as allium]
            [fukan.vocabulary.boundary.pipeline :as boundary]
            [malli.core :as m]))

(deftest boundary-pipeline-loads-fixture
  (testing "running just the boundary pipeline (post-Allium) on a fixture"
    (let [;; Pre-load via Allium to get the module-Container.
          m1 (allium/load-source "test/fixtures/boundary/simple")
          m2 (boundary/load-source m1 "test/fixtures/boundary/simple")
          op (build/get-primitive m2 "module::hello")
          fn-tags (filter (fn [ta]
                            (and (= "Boundary" (-> ta :tag :namespace))
                                 (= "Function" (-> ta :tag :name))))
                          (:tag-apps m2))]
      (is (some? op) "fn hello() produced an Operation primitive")
      (is (= 1 (count fn-tags)) "Boundary::Function tag applied")
      (let [api-tags (filter (fn [ta]
                               (and (= "Boundary" (-> ta :tag :namespace))
                                    (= "ModuleApi" (-> ta :tag :name))))
                             (:tag-apps m2))]
        (is (= 1 (count api-tags)) "exports: produced Boundary::ModuleApi tag")))))

(deftest combined-pipeline-loads-fukan-corpus
  (testing "loading src/ through the multi-extension pipeline produces a
            validated Model carrying both Allium and Boundary content"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against build/Model schema")
      (testing "Boundary tags get registered"
        (let [tag-namespaces (->> (:tag-defs model)
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
