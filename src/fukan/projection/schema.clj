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
  (let [registered-schemas (all-schema-keys model)
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

(defn schema-info
  "Get full schema info for a schema key.
   Returns {:schema-key :schema-form :owner-ns} or nil if not found."
  [model schema-key]
  (->> (schema-nodes model)
       (some #(when (= schema-key (get-in % [:data :schema-key]))
                {:schema-key schema-key
                 :schema-form (get-in % [:data :schema-form])
                 :owner-ns (get-in % [:data :owner-ns])}))))
