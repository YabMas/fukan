(ns fukan.vocabulary.allium.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.pipeline :as pipeline]
            [fukan.model.build :as build]
            [malli.core :as m]))

(deftest pipeline-loads-fukan-corpus
  (testing "loading src/ produces a validated Model with the 5 fukan module-Containers"
    (let [model (pipeline/load-source "src")]
      (is (m/validate build/Model model)
          "loaded Model validates against fukan.model.build/Model schema")

      (testing "5 module-Containers exist"
        (let [module-containers (filter (fn [[_ p]]
                                          (= :primitive/container (:kind p)))
                                        (:primitives model))]
          ;; coordinates are: fukan/infra, fukan/web, fukan/web/views,
          ;;                  fukan/model, fukan/model/pipeline
          (is (>= (count module-containers) 5)
              "at least one Container per .allium file")))

      (testing "every module has an Allium::Module tag"
        (let [module-tag-apps (filter (fn [ta]
                                        (= {:namespace "Allium" :name "Module"}
                                           (:tag ta)))
                                      (:tag-apps model))]
          (is (>= (count module-tag-apps) 5)
              "Allium::Module tag applied to each module-Container"))))))
