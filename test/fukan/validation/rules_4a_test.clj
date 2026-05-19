(ns fukan.validation.rules-4a-test
  (:require [clojure.test :refer [deftest is]]
            [fukan.validation.rules-4a :as r4a]
            [fukan.model.build :as build]
            [fukan.model.primitives :as p]
            [fukan.model.vocabulary :as v]))

(defn- model-with-modules
  "Build a test model with module-Containers + optional subsystem composites.
   Each subsystem entry: {:id <coord> :name <subsystem-name> :children <set>}."
  [{:keys [modules subsystems]}]
  (let [m0 (build/empty-model)
        m1 (reduce (fn [m id]
                     (-> m
                         (build/add-primitive
                           (p/make-container {:id id :label id}))
                         (build/add-tag-application
                           (v/make-tag-application
                             {:tag {:namespace "Allium" :name "Module"}
                              :target {:case :target/primitive :id id}}))))
                   m0 modules)
        m2 (reduce (fn [m {:keys [id name children]}]
                     (-> m
                         (build/add-primitive
                           (p/make-container
                             {:id id :label name :children (set children)}))
                         (build/add-tag-application
                           (v/make-tag-application
                             {:tag    {:namespace "Boundary" :name "Subsystem"}
                              :target {:case :target/primitive :id id}
                              :payload {:name name}}))))
                   m1 subsystems)]
    m2))

(deftest module-with-two-parents-is-error
  (let [model (model-with-modules
                {:modules ["m1"]
                 :subsystems [{:id "s1" :name "S1" :children ["m1"]}
                              {:id "s2" :name "S2" :children ["m1"]}]})
        violations (r4a/check model)
        relevant (filter #(= :4a/multiple-composite-parents (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))
    (is (= "m1" (-> relevant first :location :module)))))

(deftest module-with-no-parent-is-warning
  (let [model (model-with-modules {:modules ["lonely"]})
        violations (r4a/check model)
        relevant (filter #(= :4a/top-level-module (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :warning (-> relevant first :severity)))))

(deftest subsystem-cycle-is-error
  (let [model (model-with-modules
                {:subsystems [{:id "s1" :name "S1" :children ["s2"]}
                              {:id "s2" :name "S2" :children ["s1"]}]})
        violations (r4a/check model)
        relevant (filter #(= :4a/subsystem-cycle (:kind %)) violations)]
    (is (pos? (count relevant)))
    (is (every? #(= :error (:severity %)) relevant))))

(deftest contains-unresolved-path-is-error
  (let [model (model-with-modules
                {:subsystems [{:id "s1" :name "S1"
                               :children ["nonexistent/module"]}]})
        violations (r4a/check model)
        relevant (filter #(= :4a/unresolved-contains (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest duplicate-subsystem-names-is-error
  (let [model (model-with-modules
                {:subsystems [{:id "x/a" :name "Auth" :children []}
                              {:id "x/b" :name "Auth" :children []}]})
        violations (r4a/check model)
        relevant (filter #(= :4a/duplicate-subsystem-name (:kind %)) violations)]
    (is (= 1 (count relevant)))
    (is (= :error (-> relevant first :severity)))))

(deftest clean-model-has-no-4a-errors
  (let [model (model-with-modules
                {:modules ["m1" "m2"]
                 :subsystems [{:id "s" :name "S" :children ["m1" "m2"]}]})
        errors (filter #(= :error (:severity %)) (r4a/check model))]
    (is (empty? errors) "no 4a errors on a clean composition")))
