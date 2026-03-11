(ns fukan.test-support.registry
  "Test schema registry: discovers all ^:schema vars and installs a Malli
   registry so generators can resolve keyword refs (:NodeId, :Node, :Model, etc.).
   Call (install-registry!) in a test fixture before using malli.generator."
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [fukan.model.analyzers.implementation.languages.clojure :as clj-lang]))

(defn install-registry!
  "Install a Malli registry containing all ^:schema vars.
   Must be called after the namespaces defining schemas are loaded.
   Idempotent — safe to call multiple times."
  []
  (let [schemas (into {} (map (fn [[k {:keys [schema-form]}]] [k schema-form]))
                       (#'clj-lang/discover-schema-data))]
    (mr/set-default-registry!
      (mr/composite-registry
        (m/default-schemas)
        (mr/mutable-registry (atom schemas))))))
