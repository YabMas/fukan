(ns fukan.projection.schema
  "Schema query helpers for the projection layer.
   Provides lookups over schema data in the model: find a schema node
   by keyword, resolve a keyword to its Malli form, extract schema
   keyword references from a schema form, and list schemas owned by
   a namespace. Used by graph and detail projections to compute
   schema-flow edges and dataflow sections."
  (:require [fukan.schema.forms :as forms]))

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
                (get-in % [:data :schema])))))

(defn schemas-for-ns
  "Get all schema keywords defined in a module.
   Returns a set of keywords."
  [model module-id]
  (->> (schema-nodes model)
       (filter #(= module-id (:parent %)))
       (map #(get-in % [:data :schema-key]))
       set))

(defn extract-schema-refs
  "Extract all keyword schema references from a schema form.
   Returns a set of keywords (e.g., #{:Node :Edge :Model}).
   Only returns refs that are registered in the model's schema nodes."
  [model schema-form]
  (let [registered-schemas (all-schema-keys model)]
    (into #{} (filter registered-schemas) (forms/extract-keyword-refs schema-form))))

(defn find-schema-node-id
  "Find a schema node's ID by its schema key.
   Returns the node ID or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (:id %)))))

(defn schema-owner-id
  "Get the module node ID that owns a schema.
   Returns the parent node ID or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (:parent %)))))
