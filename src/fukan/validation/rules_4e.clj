(ns fukan.validation.rules-4e
  "Phase 4e — subsystem-visibility rules (per DESIGN.md §4e)."
  (:require [clojure.string :as str]
            [fukan.validation.violation :as v]
            [fukan.model.build :as build]))

(defn- subsystem-exports-tags [model]
  (filter (fn [ta]
            (and (= "Boundary" (-> ta :tag :namespace))
                 (= "Exports"  (-> ta :tag :name))))
          (:tag-apps model)))

(defn- module-api-exports
  "Returns map module-id → set of exported entries (strings)."
  [model]
  (->> (:tag-apps model)
       (filter (fn [ta]
                 (and (= "Boundary" (-> ta :tag :namespace))
                      (= "ModuleApi" (-> ta :tag :name)))))
       (map (fn [ta] [(-> ta :target :id) (set (-> ta :payload :exported))]))
       (into {})))

(defn- composite-children-by-name
  "For a composite Container, the child filename-stem (last /-segment of the
   child id) is the implicit alias. Returns map alias → child-id."
  [model composite-id]
  (let [c (build/get-primitive model composite-id)
        children (:children c)]
    (into {}
          (for [child-id children
                :let [last-seg (peek (str/split child-id #"/"))]]
            [last-seg child-id]))))

(defn- check-subsystem-exports [model exports-tag]
  (let [composite-id (-> exports-tag :target :id)
        exported (-> exports-tag :payload :exported)
        alias-map (composite-children-by-name model composite-id)
        closed-modules (module-api-exports model)]
    (mapcat
      (fn [entry]
        (let [[alias item] (str/split entry #"/" 2)
              child-id (alias-map alias)]
          (cond
            (nil? child-id)
            [(v/make-violation
               {:severity :error :phase :phase4 :sub-phase :4e
                :kind :4e/subsystem-exports-unresolved
                :location {:composite composite-id :entry entry :alias alias}
                :message (str "subsystem " composite-id " exports " entry " but alias " alias " is not a directly-contained child")})]

            (and item
                 (contains? closed-modules child-id)
                 (not (contains? (closed-modules child-id) item)))
            [(v/make-violation
               {:severity :error :phase :phase4 :sub-phase :4e
                :kind :4e/subsystem-exports-private
                :location {:composite composite-id :entry entry :module child-id :item item}
                :message (str "subsystem " composite-id " exports " entry " but module " child-id " is closed and does not export " item)})]

            :else [])))
      exported)))

(defn check
  [model]
  (vec (mapcat #(check-subsystem-exports model %) (subsystem-exports-tags model))))
