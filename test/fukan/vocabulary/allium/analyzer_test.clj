(ns fukan.vocabulary.allium.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.vocabulary.allium.analyzer :as analyzer]
            [fukan.libs.allium.parser :as parser]
            [fukan.model.build :as build]))

(defn- ast [text]
  (parser/parse-allium (str "-- allium: 1\n" text)))

(deftest module-container-from-empty-file
  (testing "an empty .allium file produces one module-Container"
    (let [model (build/empty-model)
          a     (ast "")
          model (analyzer/analyze-file model a "test/module")]
      (is (some? (build/get-primitive model "test/module")))
      (is (= :primitive/container
             (:kind (build/get-primitive model "test/module"))))
      (let [tag-app (->> (:tag-apps model)
                         (filter #(= "Module" (-> % :tag :name)))
                         first)]
        (is (some? tag-app) "Allium::Module tag applied")
        (is (= "test/module" (-> tag-app :target :id)))))))

(deftest module-container-multiple-files
  (testing "analyze-file is composable across multiple files"
    (let [model (-> (build/empty-model)
                    (analyzer/analyze-file (ast "") "auth")
                    (analyzer/analyze-file (ast "") "billing"))]
      (is (some? (build/get-primitive model "auth")))
      (is (some? (build/get-primitive model "billing")))
      (is (= 2 (count (filter #(= "Module" (-> % :tag :name))
                              (:tag-apps model))))))))

(deftest module-container-label-defaults-to-coordinate
  (let [model (analyzer/analyze-file (build/empty-model) (ast "") "fukan/web/views")
        c (build/get-primitive model "fukan/web/views")]
    (is (= "fukan/web/views" (:label c)))))
