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
    (let [model (pipeline/build-model "src")]
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
      ;; Corpus has 46 fn declarations: 7 in infra, 1 in web, 1 in web/views/shell,
      ;; 1 in web/views/graph, 1 in web/views/sidebar, 1 in web/views/breadcrumb,
      ;; 1 in model/pipeline, 4 in project_layer/registry, 1 in project_layer/defaults,
      ;; and 28 in the agent subsystem (13 api + 4 system + 3 sci + 2 query + 1 edb +
      ;; 5 views_loader).
      (let [fn-tags (filter (fn [ta]
                              (and (= "Boundary" (-> ta :tag :namespace))
                                   (= "Function" (-> ta :tag :name))))
                            (:tag-apps model))]
        (is (= 46 (count fn-tags))
            "all 46 corpus fn declarations produce Boundary::Function tags"))
      ;; Boundary::ModuleApi tags on modules with exports:
      ;; Corpus has 3 files with exports: (infra/server.boundary,
      ;; web/views/graph.boundary, project_layer/registry.boundary).
      ;; model/pipeline.boundary lost its exports: list when pipeline.allium was
      ;; rewritten against the kernel substrate — its single fn build_model is
      ;; signature-only.
      (let [api-tags (filter (fn [ta]
                               (and (= "Boundary" (-> ta :tag :namespace))
                                    (= "ModuleApi" (-> ta :tag :name))))
                             (:tag-apps model))]
        (is (= 3 (count api-tags))
            "3 Boundary::ModuleApi tags (infra/server, web/views/graph, project_layer/registry)")))))
