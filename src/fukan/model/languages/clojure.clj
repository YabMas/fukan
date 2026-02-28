(ns fukan.model.languages.clojure
  "Clojure-specific analysis and model construction.
   Runs clj-kondo static analysis to produce AnalysisData, discovers
   Malli schema definitions (^:schema vars), and produces a Contribution
   for the language-agnostic model build pipeline."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [fukan.model.build :as build]
            [fukan.schema.forms :as forms]))

;; -----------------------------------------------------------------------------
;; Static Analysis

(defn- extract-defmethod-defs
  "Extract defmethod forms from var-usages and convert to synthetic VarDef records.
   clj-kondo reports defmethod as a var-usage (with :defmethod true) rather than
   a var-definition, so namespaces that contain only defmethod forms appear empty
   and get pruned from the model. This normalizes them into var-definitions."
  [var-usages]
  (->> var-usages
       (filter :defmethod)
       (mapv (fn [{:keys [name from filename row dispatch-val-str]}]
               {:ns from
                :name (symbol (str name " " dispatch-val-str))
                :filename filename
                :row row
                :private false}))))

(defn- run-kondo
  "Runs clj-kondo on src-path and returns the analysis map.

   Returns a map containing:
   - :namespace-definitions - list of {:name :filename ...}
   - :var-definitions - list of {:ns :name :filename :private ...}
   - :var-usages - list of {:from :from-var :to :name ...}
   - :namespace-usages - list of {:from :to :filename ...}

   defmethod forms (reported by clj-kondo as var-usages with :defmethod true)
   are normalized into var-definitions so they appear as leaf nodes in the model.

   Throws if clj-kondo fails to run."
  {:malli/schema [:=> [:cat :string] :AnalysisData]}
  [src-path]
  (let [config "{:output {:format :edn} :analysis {:var-usages true :var-definitions {:shallow false} :namespace-usages true}}"
        result (shell/sh "clj-kondo"
                         "--lint" src-path
                         "--config" config
                         "--parallel")
        analysis (if (and (zero? (:exit result))
                          (empty? (:err result)))
                   (-> result :out edn/read-string :analysis)
                   (let [parsed (try
                                  (-> result :out edn/read-string)
                                  (catch Exception e
                                    (throw (ex-info "Failed to parse clj-kondo output"
                                                    {:exit (:exit result)
                                                     :stderr (:err result)
                                                     :stdout (:out result)}
                                                    e))))]
                     (:analysis parsed)))]
    (update analysis :var-definitions
            into (extract-defmethod-defs (:var-usages analysis)))))

;; -----------------------------------------------------------------------------
;; Schema Discovery

(def ^:schema SchemaDiscoveryEntry
  [:map {:description "Discovered metadata for a single ^:schema var."}
   [:schema-form {:description "The Malli schema form (arbitrary syntax tree)."} :any]
   [:doc [:maybe :string]]
   [:owner-ns :string]])

(def ^:schema SchemaDiscoveryData
  [:map-of {:description "All discovered ^:schema vars keyed by schema keyword."}
   :keyword :SchemaDiscoveryEntry])

(defn- malli-description
  "Extract :description from a Malli schema form's property map.
   Returns nil for bare types or forms without properties."
  [form]
  (forms/form-description form))

(defn discover-schema-data
  "Scan loaded namespaces for vars with ^:schema metadata.
   Returns a map of {keyword -> {:schema-form form :doc str? :owner-ns ns-str}}.

   This is a pure function that reads metadata from the runtime - it does
   not mutate any global state."
  {:malli/schema [:=> [:cat] :SchemaDiscoveryData]}
  []
  (->> (all-ns)
       (mapcat (fn [ns]
                 (for [[sym v] (ns-publics ns)
                       :when (:schema (meta v))]
                   [(keyword (name sym))
                    {:schema-form @v
                     :doc (malli-description @v)
                     :owner-ns (str (ns-name ns))}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Schema Node Building

(defn build-schema-nodes
  "Build schema nodes from discovered schema data.
   Schema nodes are placed inside their owning namespace container.

   ns-index is a map of {ns-sym -> node-id} for looking up parent namespaces.
   schema-data is a map of {keyword -> {:schema-form form :doc str? :owner-ns ns-str}}
   as returned by discover-schema-data.

   Returns a map of {schema-node-id -> node}."
  {:malli/schema [:=> [:cat [:map-of :symbol :NodeId] :SchemaDiscoveryData] [:map-of :NodeId :Node]]}
  [ns-index schema-data]
  (->> schema-data
       (map (fn [[k {:keys [schema-form doc owner-ns]}]]
              (let [id (str "schema:" (clojure.core/name k))
                    owner-ns-sym (when owner-ns (symbol owner-ns))
                    parent-ns-id (get ns-index owner-ns-sym)]
                [id {:id id
                     :kind :schema
                     :label (name k)
                     :parent parent-ns-id
                     :children #{}
                     :data {:kind :schema
                            :schema-key k
                            :schema schema-form
                            :doc doc}}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Runtime enrichment

(defn enrich-with-runtime-metadata
  "Attach :malli/schema signatures to function nodes from runtime vars.
   schema-data is used to exclude schema-defining vars (they become schema nodes).
   Must be called after namespaces are loaded in the JVM."
  [nodes schema-data]
  (let [schema-var-ids (->> schema-data
                            (map (fn [[k {:keys [owner-ns]}]]
                                   (str owner-ns "/" (name k))))
                            set)]
    (reduce (fn [acc [id node]]
              (if (and (= :function (:kind node))
                       (not (contains? schema-var-ids id)))
                (let [[ns-str var-str] (str/split id #"/" 2)
                      ns-sym (symbol ns-str)
                      var-sym (symbol var-str)
                      schema (try (some-> (find-ns ns-sym)
                                          (ns-resolve var-sym)
                                          meta :malli/schema)
                                  (catch Exception _ nil))]
                  (if schema
                    (assoc acc id (assoc-in node [:data :signature] schema))
                    (assoc acc id node)))
                (assoc acc id node)))
            {} nodes)))

;; -----------------------------------------------------------------------------
;; Contract resolution

(defn- resolve-fn-ref
  "Resolve a qualified symbol to {:name :schema}.
   Requires the namespace if not already loaded.
   Throws if the var is missing or has no :malli/schema metadata."
  [sym]
  (let [ns-sym (symbol (namespace sym))
        var-sym (symbol (name sym))]
    (try
      (require ns-sym)
      (catch Exception _ nil))
    (let [ns-obj (or (find-ns ns-sym)
                     (throw (ex-info (str "Contract references unknown namespace: " ns-sym)
                                     {:sym sym :ns ns-sym})))
          v      (or (ns-resolve ns-obj var-sym)
                     (throw (ex-info (str "Contract references unknown var: " sym)
                                     {:sym sym})))
          schema (or (:malli/schema (meta v))
                     (throw (ex-info (str "Contract function missing :malli/schema metadata: " sym)
                                     {:sym sym})))]
      (cond-> {:name (name var-sym)
               :schema schema}
        (:doc (meta v)) (assoc :doc (:doc (meta v)))))))

(defn- read-contract-file
  "Read contract.edn from a directory path if present.
   Resolves qualified symbols to {:name :schema} format."
  [dir-path]
  (when (and dir-path (not= dir-path ""))
    (let [file (io/file dir-path "contract.edn")]
      (when (.exists file)
        (let [raw (edn/read-string (slurp file))]
          (update raw :functions
                  (fn [fns]
                    (mapv resolve-fn-ref fns))))))))

(defn resolve-contracts
  "Pre-resolve contract.edn files for container nodes in the contribution.
   Attaches contracts to container nodes' :data :contract.
   Must be called after namespaces are loaded in the JVM."
  [nodes]
  (reduce (fn [acc [id node]]
            (if (= :container (:kind node))
              (if-let [contract (read-contract-file id)]
                (assoc acc id (update node :data #(assoc (or % {}) :contract contract)))
                (assoc acc id node))
              (assoc acc id node)))
          {} nodes))

;; -----------------------------------------------------------------------------
;; Contribution

(defn- analysis->contribution
  "Convert AnalysisData into a language contribution.
   Builds namespace nodes, var nodes, and edges from the analysis data.
   Container nodes have :parent nil — build-model assigns folder parents."
  {:malli/schema [:=> [:cat :AnalysisData] :Contribution]}
  [analysis]
  (let [ns-defs (:namespace-definitions analysis)
        var-defs (:var-definitions analysis)

        ;; Build ns nodes with nil parent (empty folder-index)
        {ns-nodes :nodes ns-index :index} (build/build-namespace-nodes ns-defs {})

        ;; Add :filename to each ns container node's data for folder parenting
        ns-filename-map (into {} (map (fn [nd] [(str (:name nd)) (:filename nd)]) ns-defs))
        ns-nodes (reduce-kv (fn [acc id node]
                              (if-let [fname (get ns-filename-map id)]
                                (assoc acc id (assoc-in node [:data :filename] fname))
                                (assoc acc id node)))
                            {} ns-nodes)

        ;; Build var nodes
        {var-nodes :nodes var-index :index} (build/build-var-nodes var-defs ns-index)

        ;; Build edges
        var-edges (build/build-edges analysis var-index ns-index)
        ns-edges (build/build-ns-edges analysis ns-index)]

    {:source-files (mapv :filename ns-defs)
     :nodes (merge ns-nodes var-nodes)
     :edges (vec (into (set var-edges) ns-edges))}))

(defn contribution
  "Produce a full Clojure language contribution from source analysis."
  {:malli/schema [:=> [:cat :string] :Contribution]}
  [src-path]
  (-> (run-kondo src-path)
      analysis->contribution))
