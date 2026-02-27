(ns fukan.model.languages.clojure
  "Clojure-specific analysis and model construction.
   Runs clj-kondo static analysis to produce AnalysisData, discovers
   Malli schema definitions (^:schema vars), and produces a Contribution
   for the language-agnostic model build pipeline."
  (:require [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [fukan.model.build :as build]))

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

(defn run-kondo
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
;; Integrant Config Analysis

(defn- integrant-key->ns-sym
  "Convert an Integrant component key to a Clojure namespace symbol.
   :fukan.infra/server → fukan.infra.server"
  [k]
  (symbol (str (namespace k) "." (name k))))

(defn extract-integrant-deps
  "Extract namespace-usage edges from an Integrant system config string.
   Reads the config with a custom #ig/ref reader and maps component
   dependencies to namespace-level edges.

   Returns a vector of NsUsage maps."
  [config-str]
  (let [config (edn/read-string {:readers {'ig/ref identity
                                           'ig/refset (fn [x] x)}}
                                config-str)
        component-keys (set (keys config))]
    (->> config
         (mapcat (fn [[from-key from-config]]
                   (let [refs (->> (tree-seq coll? seq from-config)
                                   (filter #(and (keyword? %)
                                                 (namespace %)
                                                 (contains? component-keys %))))]
                     (for [ref-key refs]
                       {:from (integrant-key->ns-sym from-key)
                        :to (integrant-key->ns-sym ref-key)
                        :filename "system.edn"}))))
         vec)))

;; -----------------------------------------------------------------------------
;; Schema Discovery

(defn- malli-description
  "Extract :description from a Malli schema form's property map.
   Returns nil for bare types or forms without properties."
  [form]
  (when (and (vector? form) (>= (count form) 2) (map? (second form)))
    (:description (second form))))

(defn discover-schema-data
  "Scan loaded namespaces for vars with ^:schema metadata.
   Returns a map of {keyword -> {:schema-form form :doc str? :owner-ns ns-str}}.

   This is a pure function that reads metadata from the runtime - it does
   not mutate any global state."
  {:malli/schema [:=> [:cat] :map]}
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
  {:malli/schema [:=> [:cat :map :map] :map]}
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
;; Contribution

(defn analysis->contribution
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
  "Produce a full Clojure language contribution from source analysis.
   Runs clj-kondo, augments with Integrant config deps, and converts
   to a Contribution."
  {:malli/schema [:=> [:cat :string] :Contribution]}
  [src-path]
  (let [analysis (run-kondo src-path)
        ig-config (some-> (io/resource "fukan/system.edn") slurp)
        analysis (cond-> analysis
                   ig-config (update :namespace-usages
                                     into (extract-integrant-deps ig-config)))]
    (analysis->contribution analysis)))
