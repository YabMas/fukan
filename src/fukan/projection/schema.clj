(ns fukan.projection.schema
  "Schema query functions for projection layer.
   Provides domain queries over schema data in the model.")

;; -----------------------------------------------------------------------------
;; Private helpers

(defn- schema-nodes
  "Get all schema nodes from the model."
  [model]
  (->> (:nodes model)
       vals
       (filter #(= :schema (:kind %)))))

(defn- all-schema-keys
  "Get all registered schema keywords from model.
   Returns a set of keywords."
  [model]
  (->> (schema-nodes model)
       (map #(get-in % [:data :schema-key]))
       set))

;; -----------------------------------------------------------------------------
;; Public API

(defn get-schema
  "Get schema form by keyword from model.
   Returns the schema form, or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (get-in % [:data :schema-form])))))

(defn schemas-for-ns
  "Get all schema keywords defined in a namespace.
   Returns a set of keywords."
  [model ns-str]
  (->> (schema-nodes model)
       (filter #(= ns-str (get-in % [:data :owner-ns])))
       (map #(get-in % [:data :schema-key]))
       set))

(defn extract-schema-refs
  "Extract all keyword schema references from a schema form.
   Returns a set of keywords (e.g., #{:Node :Edge :Model}).
   Only returns refs that are registered in the model's schema nodes."
  [model schema-form]
  (let [registered-schemas (all-schema-keys model)]
    (->> (tree-seq coll? seq schema-form)
         (filter keyword?)
         (filter #(contains? registered-schemas %))
         set)))

(defn find-schema-node-id
  "Find a schema node's ID by its schema key.
   Returns the node ID or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (:id %)))))

(defn schema-owner-ns-id
  "Get the namespace node ID that owns a schema.
   Returns the parent node ID or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (:parent %)))))
