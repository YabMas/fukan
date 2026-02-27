(ns fukan.model.languages.clojure
  "Clojure-specific analysis and model construction.
   Runs clj-kondo static analysis to produce AnalysisData, then discovers
   Malli schema definitions (^:schema vars) and builds schema nodes that
   integrate into the language-agnostic model graph."
  (:require [clojure.java.shell :as shell]
            [clojure.edn :as edn]))

;; -----------------------------------------------------------------------------
;; Static Analysis

(defn run-kondo
  "Runs clj-kondo on src-path and returns the analysis map.

   Returns a map containing:
   - :namespace-definitions - list of {:name :filename ...}
   - :var-definitions - list of {:ns :name :filename :private ...}
   - :var-usages - list of {:from :from-var :to :name ...}
   - :namespace-usages - list of {:from :to :filename ...}

   Throws if clj-kondo fails to run."
  {:malli/schema [:=> [:cat :string] :AnalysisData]}
  [src-path]
  (let [config "{:output {:format :edn} :analysis {:var-usages true :var-definitions {:shallow false} :namespace-usages true}}"
        result (shell/sh "clj-kondo"
                         "--lint" src-path
                         "--config" config
                         "--parallel")]
    (if (and (zero? (:exit result))
             (empty? (:err result)))
      (-> result :out edn/read-string :analysis)
      ;; clj-kondo returns non-zero for lint errors, but we still get analysis
      ;; Only fail if we can't parse the output
      (let [parsed (try
                     (-> result :out edn/read-string)
                     (catch Exception e
                       (throw (ex-info "Failed to parse clj-kondo output"
                                       {:exit (:exit result)
                                        :stderr (:err result)
                                        :stdout (:out result)}
                                       e))))]
        (:analysis parsed)))))

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
