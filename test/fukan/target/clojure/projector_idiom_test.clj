(ns fukan.target.clojure.projector-idiom-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.target.clojure.projector :as projector]
            [fukan.project-layer.registry :as registry]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- module-with-rule [label]
  (-> (build/empty-model)
      (build/add-primitive (p/make-container {:id "m" :label "m"}))
      (build/add-tag-application
        (v/make-tag-application
          {:tag {:namespace "Allium" :name "Module"}
           :target {:case :target/primitive :id "m"}}))
      (build/add-primitive (p/make-rule {:id (str "m::" label) :label label}))))

(deftest idiom-matches-by-primitive-kind
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:primitive-kind :primitive/rule}
                   :body "rules use threading macros"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/rule)]
    (is (= ["rules use threading macros"] (:idioms bp)))))

(deftest idiom-no-match
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:primitive-kind :primitive/operation}
                   :body "ops use core.async"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/rule)]
    (is (= [] (:idioms bp)))))

(deftest idiom-composes-multiple-matches
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:primitive-kind :primitive/rule}
                   :body "rules use threading"})
                (registry/with-idiom
                  {:route {:projection-kind :projection-kind/rule}
                   :body "rules return :ok | :error"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/rule)]
    (is (= ["rules use threading" "rules return :ok | :error"] (:idioms bp)))))

(deftest idiom-address-pattern
  (let [reg (-> (registry/make-registry)
                (registry/with-idiom
                  {:route {:address-pattern #".*-test$"}
                   :body "tests use kaocha"}))
        model (module-with-rule "R")
        bp (projector/project model reg "m::R" :projection-kind/test)]
    (is (= ["tests use kaocha"] (:idioms bp)))))
