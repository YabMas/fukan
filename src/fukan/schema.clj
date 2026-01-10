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
  "Register a schema with a qualified keyword.
   The namespace part of the keyword indicates ownership."
  [k schema]
  (let [ns-part (namespace k)]
    (swap! custom-schemas assoc k schema)
    (swap! schema-owners assoc k ns-part))
  k)

(defn get-schema
  "Look up a schema by qualified keyword."
  [k]
  (get @custom-schemas k))

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
