(ns fukan.vocabulary.allium.analyzer
  "Per-file Allium AST → kernel content (with Allium::* tag applications).
   The entry point `analyze-file` takes a Model, an AST, and a coordinate;
   returns the Model extended with this file's content."
  (:require [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]
            [fukan.model.build :as build]))

(defn analyze-file
  "Add this file's kernel content + Allium::* tag applications to `model`.
   The coordinate becomes the module-Container's id; Allium::Module tag
   is applied.

   Subsequent Plan-2b tasks extend analyze-file to walk (:declarations ast)
   and dispatch per declaration type — for Task 4, declarations are not
   yet processed."
  [model _ast coordinate]
  (let [module-c (p/make-container
                   {:id coordinate
                    :label coordinate})]
    (-> model
        (build/add-primitive module-c)
        (build/add-tag-application
          (v/make-tag-application
            {:tag {:namespace "Allium" :name "Module"}
             :target {:case :target/primitive :id coordinate}})))))
