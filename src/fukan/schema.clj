(ns fukan.schema
  "Schema registry for Malli schemas.
   Provides a central registry that integrates with Malli's default registry."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;; -----------------------------------------------------------------------------
;; Schema Registry
;;
;; We use a mutable registry that:
;; - Integrates with Malli's default registry (so mx/defn works)
;; - Tracks which namespace owns each schema

(defonce ^:private schema-owners
  (atom {}))  ; keyword -> ns-string

(defonce ^:private custom-schemas
  (atom {}))  ; keyword -> schema

;; Set up a composite registry that includes our custom schemas
(defonce ^:private _registry-setup
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry custom-schemas))))

(defn register!
  "Register a schema with an unqualified keyword.
   Throws if the key is already registered (collision detection)."
  [k schema owner-ns]
  (when (contains? @custom-schemas k)
    (throw (ex-info (str "Schema key collision: " k " already registered by "
                         (get @schema-owners k))
                    {:key k
                     :existing-owner (get @schema-owners k)
                     :new-owner owner-ns})))
  (swap! custom-schemas assoc k schema)
  (swap! schema-owners assoc k owner-ns)
  k)

(defn get-schema
  "Look up a schema by keyword."
  [k]
  (get @custom-schemas k))

(defn schema-owner
  "Get the owner namespace string for a schema key."
  [k]
  (get @schema-owners k))

(defn schemas-for-ns
  "Get all schema keywords defined in a namespace."
  [ns-str]
  (->> @schema-owners
       (filter (fn [[k ns]] (= ns ns-str)))
       (map first)
       set))

(defn all-schemas
  "Get all registered schema keywords."
  []
  (keys @custom-schemas))

(defn clear-schemas!
  "Clear all registered schemas. Call before discover-schemas! on restart."
  []
  (reset! custom-schemas {})
  (reset! schema-owners {}))

(defn discover-schemas!
  "Scan all loaded namespaces for vars with ^:schema metadata and register them.
   Uses unqualified keywords (e.g., :Model, :Node, :EditorState)."
  []
  (doseq [ns (all-ns)
          [sym v] (ns-publics ns)
          :when (:schema (meta v))]
    (let [k (keyword (name sym))
          owner-ns (str (ns-name ns))]
      (register! k @v owner-ns))))

;; -----------------------------------------------------------------------------
;; Schema Analysis

(defn extract-schema-refs
  "Extract all keyword schema references from a schema form.
   Returns a set of keywords (e.g., #{:Node :Edge :Model}).
   Only returns refs that are registered in our schema registry."
  [schema-form]
  (let [registered-schemas (set (all-schemas))
        refs (atom #{})]
    (letfn [(walk [s]
              (cond
                ;; Keyword - potential schema reference (unqualified)
                (keyword? s)
                (when (contains? registered-schemas s)
                  (swap! refs conj s))

                ;; Vector form - recurse into children
                (vector? s)
                (doseq [child (rest s)] (walk child))

                ;; Map form - recurse into values
                (map? s)
                (doseq [v (vals s)] (walk v))))]
      (walk schema-form)
      @refs)))
