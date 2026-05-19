(ns fukan.validation.rules-4g
  "Phase 4g — cross-module reference visibility rules (per DESIGN.md §4g).

   Closure (4f) is the upstream guarantee. After 4f passes, every name
   reachable from outside a module is exported; private references are
   fabrications or stale. This sub-phase enforces directly: every
   cross-module reference must target an exported item (or an item in
   an open module, or a Contract / Allium::ExternalEntity)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [fukan.validation.violation :as v]))

(defn- module-ids [model]
  (set (map (comp :id :target)
            (filter (fn [ta]
                      (and (= "Allium" (-> ta :tag :namespace))
                           (= "Module" (-> ta :tag :name))))
                    (:tag-apps model)))))

(defn- closed-module-tags [model]
  (filter (fn [ta]
            (and (= "Boundary" (-> ta :tag :namespace))
                 (= "ModuleApi" (-> ta :tag :name))))
          (:tag-apps model)))

(defn- module-of-primitive
  "Given a primitive id like 'a/sub::Foo', return the module id 'a/sub'.
   Returns nil if no module prefix matches."
  [modules primitive-id]
  (some (fn [m] (when (str/starts-with? primitive-id (str m "::")) m))
        modules))

(defn- visible-primitive-ids
  "All externally-visible primitive ids: from closed-module exports, from open
   modules (entire contents), from Contracts (always type-visible) and
   Allium::ExternalEntity Containers."
  [model]
  (let [closed-tags (closed-module-tags model)
        closed (set (map (comp :id :target) closed-tags))
        modules (module-ids model)
        open (set/difference modules closed)
        from-closed (set (mapcat (fn [ta]
                                   (let [m (-> ta :target :id)]
                                     (map #(str m "::" %)
                                          (-> ta :payload :exported))))
                                 closed-tags))
        from-open (set (filter #(some (fn [m] (str/starts-with? % (str m "::")))
                                      open)
                               (keys (:primitives model))))
        contracts (set (map (comp :id :target)
                            (filter (fn [ta]
                                      (and (= "Allium" (-> ta :tag :namespace))
                                           (= "Contract" (-> ta :tag :name))))
                                    (:tag-apps model))))
        externals (set (map (comp :id :target)
                            (filter (fn [ta]
                                      (and (= "Allium" (-> ta :tag :namespace))
                                           (= "ExternalEntity" (-> ta :tag :name))))
                                    (:tag-apps model))))]
    (set/union from-closed from-open contracts externals)))

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

(defn- collect-type-ref-targets
  "Walk all primitives' :type-ref values (in :fields and :parameters) and
   collect (referrer-id, target-container-id) pairs."
  [model]
  (let [from-fields (mapcat (fn [pr]
                              (mapcat (fn [field]
                                        (map (fn [t] [(:id pr) t])
                                             (walk-type (:type-ref field))))
                                      (:fields pr)))
                            (vals (:primitives model)))
        from-params (mapcat (fn [pr]
                              (mapcat (fn [param]
                                        (map (fn [t] [(:id pr) t])
                                             (walk-type (:type-ref param))))
                                      (:parameters pr)))
                            (vals (:primitives model)))]
    (set (concat from-fields from-params))))

(defn check
  [model]
  (let [modules (module-ids model)
        visible (visible-primitive-ids model)
        refs    (collect-type-ref-targets model)]
    (vec (for [[referrer target] refs
               :let [referrer-module (module-of-primitive modules referrer)
                     target-module   (module-of-primitive modules target)]
               :when (and target-module
                          (not= referrer-module target-module)  ; cross-module only
                          (not (contains? visible target)))]
           (v/make-violation
             {:severity :error :phase :phase4 :sub-phase :4g
              :kind :4g/cross-module-private-reference
              :location {:referrer referrer :target target
                         :referrer-module referrer-module
                         :target-module target-module}
              :message (str referrer " references " target
                            " which is not externally visible from " target-module)})))))
