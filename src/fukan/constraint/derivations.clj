(ns fukan.constraint.derivations
  "Kernel-universal derivations: translate kernel substrate to Datalog EDB.

   Per MODEL.md §6.6. Each derivation is a relation name (keyword) keyed
   to a set of tuples (vectors). Used as the seed EDB for the evaluator."
  (:require [clojure.string :as str]))

(defn- primitive-tuples [model]
  (set (map (fn [[id _]] [id]) (:primitives model))))

(defn- primitive-kind-tuples [model]
  (set (map (fn [[id prim]] [id (:kind prim)]) (:primitives model))))

(defn- has-tag-tuples [model]
  (set (map (fn [ta]
              [(-> ta :target :id)
               (str (-> ta :tag :namespace) "::" (-> ta :tag :name))])
            (:tag-apps model))))

(defn- tag-payload-tuples [model]
  (set (map (fn [ta]
              [(-> ta :target :id)
               (str (-> ta :tag :namespace) "::" (-> ta :tag :name))
               (or (:payload ta) {})])
            (:tag-apps model))))

(defn- module-ids [model]
  (set (map (comp :id :target)
            (filter (fn [ta]
                      (and (= "Allium" (-> ta :tag :namespace))
                           (= "Module" (-> ta :tag :name))))
                    (:tag-apps model)))))

(defn- in-module-tuples [model]
  (let [modules (module-ids model)]
    (set (for [[id _] (:primitives model)
               m modules
               :when (str/starts-with? id (str m "::"))]
           [id m]))))

(defn- edge-tuples [model]
  (set (map (fn [edge]
              [(-> edge :from :id) (:kind edge) (-> edge :to :id)])
            (:edges model))))

(defn- has-field-tuples [model]
  (set (for [[id prim] (:primitives model)
             field (:fields prim)]
         [id (:name field)])))

(defn model->edb
  "Translate a kernel Model into a Datalog EDB (predicate → set of tuples).
   Plan 4 derivations include only the kernel-universal predicates per
   MODEL.md §6.6. Plan 4+ can extend additively."
  [model]
  {:primitive       (primitive-tuples model)
   :primitive-kind  (primitive-kind-tuples model)
   :has-tag         (has-tag-tuples model)
   :tag-payload     (tag-payload-tuples model)
   :in-module       (in-module-tuples model)
   :edge            (edge-tuples model)
   :has-field       (has-field-tuples model)})
