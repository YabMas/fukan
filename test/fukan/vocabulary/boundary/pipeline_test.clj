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
      ;; Boundary tag-definitions registered (Function, Binding, ModuleApi, Subsystem, Exports):
      (let [boundary-tag-defs (filter #(= "Boundary" (:namespace %))
                                      (:tag-defs model))]
        (is (= 5 (count boundary-tag-defs))
            "all 5 Boundary::* tag-definitions registered"))
      ;; Allium output preserved (still has Allium tag-defs):
      (let [allium-tag-defs (filter #(= "Allium" (:namespace %))
                                    (:tag-defs model))]
        (is (pos? (count allium-tag-defs))
            "Allium tag-definitions still registered"))
      ;; Boundary::Function tags applied to fn-declared Operations:
      ;; Corpus has 15 fn declarations (7 in infra, 1 in web, 3 in model/pipeline, 4 in web/views)
      (let [fn-tags (filter (fn [ta]
                              (and (= "Boundary" (-> ta :tag :namespace))
                                   (= "Function" (-> ta :tag :name))))
                            (:tag-apps model))]
        (is (= 15 (count fn-tags))
            "all 15 corpus fn declarations produce Boundary::Function tags"))
      ;; Boundary::ModuleApi tags on modules with exports:
      ;; Corpus has 2 files with exports: (model/pipeline.boundary, web/views/spec.boundary)
      (let [api-tags (filter (fn [ta]
                               (and (= "Boundary" (-> ta :tag :namespace))
                                    (= "ModuleApi" (-> ta :tag :name))))
                             (:tag-apps model))]
        (is (= 2 (count api-tags))
            "exactly 2 Boundary::ModuleApi tags (model/pipeline, web/views)")))))
