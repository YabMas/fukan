(ns fukan.validation.rules-4f
  "Phase 4f — export closure rule (per DESIGN.md §4f).

   A closed module's exports: list must be self-coherent: every type
   referenced from any exported item's signature must itself be reachable
   through exports — either listed in the same module's exports, or
   exported by another module, or marked as Allium::ExternalEntity.

   MVP scope: Entity and Operation closure. Surface/Variant/Actor
   closures land additively when corpus surfaces them as load-bearing."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [fukan.validation.violation :as v]
            [fukan.model.build :as build]))

(defn- module-api-tags [model]
  (filter (fn [ta]
            (and (= "Boundary" (-> ta :tag :namespace))
                 (= "ModuleApi" (-> ta :tag :name))))
          (:tag-apps model)))

(defn- external-entity-ids [model]
  (set (map (comp :id :target)
            (filter (fn [ta]
                      (and (= "Allium" (-> ta :tag :namespace))
                           (= "ExternalEntity" (-> ta :tag :name))))
                    (:tag-apps model)))))

(defn- all-module-ids [model]
  (set (map (comp :id :target)
            (filter (fn [ta]
                      (and (= "Allium" (-> ta :tag :namespace))
                           (= "Module" (-> ta :tag :name))))
                    (:tag-apps model)))))

(defn- closed-module-ids [model]
  (set (map (comp :id :target) (module-api-tags model))))

(defn- visible-primitive-ids
  "Set of primitive ids externally visible across the loaded model:
   - Items exported by any closed module
   - Everything in modules that are open (no Boundary::ModuleApi tag)
   - Allium::ExternalEntity Containers"
  [model]
  (let [closed (closed-module-ids model)
        all-mods (all-module-ids model)
        open (set/difference all-mods closed)
        from-closed (set (mapcat (fn [ta]
                                   (let [m (-> ta :target :id)]
                                     (map #(str m "::" %)
                                          (-> ta :payload :exported))))
                                 (module-api-tags model)))
        from-open (set (filter (fn [pid]
                                 (some (fn [m]
                                         (str/starts-with? pid (str m "::")))
                                       open))
                               (keys (:primitives model))))]
    (set/union from-closed from-open (external-entity-ids model))))

(defn- walk-type
  "Walk a kernel Type value collecting all Composite-named container ids."
  [t]
  (cond
    (nil? t) #{}
    (= :type/composite (:case t))
    (if-let [c (-> t :shape :container)] #{c} #{})
    (= :type/collection (:case t))
    (walk-type (:of t))
    (= :type/union (:case t))
    (reduce set/union #{} (map walk-type (:types t)))
    :else #{}))

(defn- closure-for-entity [container]
  (reduce set/union #{} (map (comp walk-type :type-ref) (:fields container))))

(defn- closure-for-operation [op]
  (set/union (reduce set/union #{} (map (comp walk-type :type-ref) (:parameters op)))
             (walk-type (:return-type op))))

(defn- closure-for [model primitive-id]
  (let [prim (build/get-primitive model primitive-id)]
    (cond
      (= :primitive/container (:kind prim))   (closure-for-entity prim)
      (= :primitive/operation (:kind prim))   (closure-for-operation prim)
      :else #{})))

(defn- check-closure-for-module [model module-id exports]
  (let [visible (visible-primitive-ids model)]
    (mapcat
      (fn [entry]
        (let [primitive-id (str module-id "::" entry)
              prim         (build/get-primitive model primitive-id)
              required     (when prim (closure-for model primitive-id))
              missing      (when required (set/difference required visible))]
          (when (seq missing)
            [(v/make-violation
               {:severity :error :phase :phase4 :sub-phase :4f
                :kind :4f/closure-violation
                :location {:module module-id :exported entry :missing (vec missing)}
                :message (str "module " module-id " exports " entry
                              " but its signature references unreached types: "
                              (vec missing))})])))
      exports)))

(defn check
  [model]
  (vec (mapcat
         (fn [ta]
           (check-closure-for-module model
                                     (-> ta :target :id)
                                     (-> ta :payload :exported)))
         (module-api-tags model))))
