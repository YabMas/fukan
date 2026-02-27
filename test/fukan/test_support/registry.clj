(ns fukan.test-support.registry
  "Test schema registry: discovers all ^:schema vars and installs a Malli
   registry so generators can resolve keyword refs (:NodeId, :Node, :Model, etc.).
   Call (install-registry!) in a test fixture before using malli.generator."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(defn- discover-schemas
  "Scan all loaded namespaces for vars with ^:schema metadata.
   Returns a map of {keyword -> malli-schema-form}."
  []
  (->> (all-ns)
       (mapcat (fn [ns]
                 (for [[sym v] (ns-publics ns)
                       :when (:schema (meta v))]
                   [(keyword (name sym)) @v])))
       (into {})))

(defn install-registry!
  "Install a Malli registry containing all ^:schema vars.
   Must be called after the namespaces defining schemas are loaded.
   Idempotent — safe to call multiple times."
  []
  (let [schemas (discover-schemas)]
    (mr/set-default-registry!
      (mr/composite-registry
        (m/default-schemas)
        (mr/mutable-registry (atom schemas))))))
