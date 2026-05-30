(ns fukan.validation.rules-4d
  "Phase 4d — module-visibility rules (per DESIGN.md §4d)."
  (:require [fukan.validation.violation :as v]
            [fukan.model.build :as build]))

(defn- module-api-tags-by-target [model]
  (->> (:tag-apps model)
       (filter (fn [ta]
                 (and (= "Boundary" (-> ta :tag :namespace))
                      (= "ModuleApi" (-> ta :tag :name)))))
       (group-by (comp :id :target))))

(defn- allium-kind-of
  "Return the Allium kind keyword for primitive id (or nil if no such tag).
   Reads the Allium::<X> tag application's :name to identify primitive kind."
  [model id]
  (let [tags (filter (fn [ta]
                       (and (= "Allium" (-> ta :tag :namespace))
                            (= id (-> ta :target :id))))
                     (:tag-apps model))
        names (set (map (comp :name :tag) tags))]
    (cond
      (names "Surface")  :surface
      (names "Entity")   :entity
      (names "Value")    :value
      (names "Variant")  :variant
      (names "Event")    :event
      (names "Actor")    :actor
      (names "Rule")     :rule
      (names "Invariant") :invariant
      (names "Contract") :contract
      :else nil)))

(def ^:private disallowed-kinds
  #{:rule :invariant :contract})

(defn- multiple-module-api [model]
  (for [[module-id tas] (module-api-tags-by-target model)
        :when (> (count tas) 1)]
    (v/make-violation
      {:severity :error :phase :phase4 :sub-phase :4d
       :kind :4d/multiple-module-api-tags
       :location {:module module-id :count (count tas)}
       :message (str "module " module-id " has " (count tas) " Boundary::ModuleApi tag applications; expected at most 1")})))

(defn- check-export-entry [model module-id entry]
  ;; `entry` is either bare name "Foo" or "Contract.op" form.
  ;; Both resolve to "<module>::<entry>" — the dot is preserved in the id.
  (let [primitive-id (str module-id "::" entry)
        prim (build/get-primitive model primitive-id)
        kind (allium-kind-of model primitive-id)]
    (cond
      (nil? prim)
      [(v/make-violation
         {:severity :error :phase :phase4 :sub-phase :4d
          :kind :4d/exports-unresolved
          :location {:module module-id :entry entry :tried primitive-id}
          :message (str "exports: entry " entry " in module " module-id " does not resolve to a known primitive")})]

      (contains? disallowed-kinds kind)
      [(v/make-violation
         {:severity :error :phase :phase4 :sub-phase :4d
          :kind :4d/exports-disallowed-kind
          :location {:module module-id :entry entry :kind kind}
          :message (str "exports: entry " entry " is a " (name kind) " — Contracts, Rules, and Invariants are not individually exportable")})]

      :else [])))

(defn- exports-validity [model]
  (mapcat
    (fn [[module-id [tag]]]
      (mapcat #(check-export-entry model module-id %)
              (-> tag :payload :canvas/exported)))
    (module-api-tags-by-target model)))

(defn check
  [model]
  (vec (concat
         (multiple-module-api model)
         (exports-validity model))))
