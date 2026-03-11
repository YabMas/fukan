(ns fukan.projection.schema
  "Schema query helpers for the projection layer.
   Provides lookups over schema data in the model: find a schema node
   by keyword, resolve a keyword to its TypeExpr form, extract schema
   keyword references from a TypeExpr, and list schemas owned by
   a namespace. Used by graph and detail projections to compute
   schema-flow edges and dataflow sections.")

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

(defn- collect-refs
  "Recursively collect :ref keywords from a TypeExpr tree."
  [type-expr]
  (when (map? type-expr)
    (case (:tag type-expr)
      :ref [(:name type-expr)]
      :primitive []
      :map (mapcat (fn [entry] (collect-refs (:type entry))) (:entries type-expr))
      :map-of (concat (collect-refs (:key-type type-expr))
                       (collect-refs (:value-type type-expr)))
      :vector (collect-refs (:element type-expr))
      :set (collect-refs (:element type-expr))
      :maybe (collect-refs (:inner type-expr))
      :or (mapcat collect-refs (:variants type-expr))
      :and (mapcat collect-refs (:types type-expr))
      :tuple (mapcat collect-refs (:elements type-expr))
      :fn (concat (mapcat collect-refs (:inputs type-expr))
                   (collect-refs (:output type-expr)))
      :enum []
      :predicate []
      :unknown []
      ;; default
      [])))

;; -----------------------------------------------------------------------------
;; Public API

(defn get-schema
  "Get schema TypeExpr by keyword from model.
   Returns the TypeExpr, or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (get-in % [:data :schema])))))

(defn schemas-for-module
  "Get all schema keywords defined in a module.
   Returns a set of keywords."
  [model module-id]
  (->> (schema-nodes model)
       (filter #(= module-id (:parent %)))
       (map #(get-in % [:data :schema-key]))
       set))

(defn extract-schema-refs
  "Extract all keyword schema references from a TypeExpr.
   Returns a set of keywords (e.g., #{:Node :Edge :Model}).
   Only returns refs that are registered in the model's schema nodes."
  [model type-expr]
  (let [registered-schemas (all-schema-keys model)]
    (into #{} (filter registered-schemas) (collect-refs type-expr))))

(defn find-schema-node-id
  "Find a schema node's ID by its schema key.
   Returns the node ID or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (:id %)))))

(defn schema-key->node-id
  "Build a map from schema keyword to node ID for all schemas in the model."
  [model]
  (->> (schema-nodes model)
       (map (fn [n] [(get-in n [:data :schema-key]) (:id n)]))
       (into {})))

(defn schema-owner-id
  "Get the module node ID that owns a schema.
   Returns the parent node ID or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                (:parent %)))))
