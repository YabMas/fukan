(ns fukan.model.api
  "Internal graph model construction from source code.
   Public API facade - re-exports from implementation modules."
  (:require [fukan.model.core :as core]
            [fukan.model.build :as build]
            [fukan.model.languages.clojure :as clj-lang]))

;; -----------------------------------------------------------------------------
;; Re-exported schemas from model.core
;; NOTE: No ^:schema metadata here - definitions live in model.core

;; Analysis schemas
(def NsDef core/NsDef)
(def VarDef core/VarDef)
(def VarUsage core/VarUsage)
(def NsUsage core/NsUsage)
(def AnalysisData core/AnalysisData)

;; Model schemas
(def NodeId core/NodeId)
(def NodeKind core/NodeKind)
(def Node core/Node)
(def Edge core/Edge)
(def Model core/Model)

;; -----------------------------------------------------------------------------
;; Main API

(defn build-model
  "Build the complete model from Clojure source code.

   Takes a path to source directory and returns a Model containing:
   - :nodes - {id -> node} for all folders, namespaces, vars, and schemas
   - :edges - vector of {:from :to} edges (var-level and ns-level combined)

   Internally runs clj-kondo analysis and builds the graph model.
   Schema nodes include the schema form in their :data map.

   Edge aggregation (folder-level) and schema flow edges are computed
   on-demand by the view layer, not pre-computed here.

   Note: Single-child folder chains are pruned - the root of the tree is the
   first folder with multiple children or non-folder children."
  {:malli/schema [:=> [:cat :string] :Model]}
  [src-path]
  (let [analysis (clj-lang/run-kondo src-path)
        ;; Discover schemas from loaded namespaces (pure - no mutation)
        schema-data (clj-lang/discover-schema-data)
        ;; Create type-nodes-fn that captures the schema data
        type-nodes-fn (fn [ns-index]
                        (clj-lang/build-schema-nodes ns-index schema-data))]
    (build/build-model analysis {:type-nodes-fn type-nodes-fn})))

;; -----------------------------------------------------------------------------
;; Schema Query Functions

(defn get-schema
  "Get schema form by keyword from model.
   Returns the schema form, or nil if not found."
  [model schema-key]
  (->> (:nodes model)
       vals
       (some #(when (and (= :schema (:kind %))
                         (= schema-key (get-in % [:data :schema-key])))
                (get-in % [:data :schema-form])))))

(defn schemas-for-ns
  "Get all schema keywords defined in a namespace.
   Returns a set of keywords."
  [model ns-str]
  (->> (:nodes model)
       vals
       (filter #(and (= :schema (:kind %))
                     (= ns-str (get-in % [:data :owner-ns]))))
       (map #(get-in % [:data :schema-key]))
       set))

(defn all-schema-keys
  "Get all registered schema keywords from model.
   Returns a set of keywords."
  [model]
  (->> (:nodes model)
       vals
       (filter #(= :schema (:kind %)))
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
