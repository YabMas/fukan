(ns fukan.model.languages.clojure
  "Clojure-specific analysis and model construction.
   Includes clj-kondo analysis and Malli schema node building."
  (:require [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [fukan.model.core :as core]
            [fukan.schema :as schema]))

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
;; Schema Node Building

(defn build-schema-nodes
  "Build schema nodes from all registered Malli schemas.
   Schema nodes are placed inside their owning namespace container.

   ns-index is a map of {ns-sym -> node-id} for looking up parent namespaces.

   Returns a map of {schema-node-id -> node}."
  [ns-index]
  (->> (schema/all-schemas)
       (map (fn [k]
              (let [id (core/gen-id)
                    owner-ns (schema/schema-owner k)
                    owner-ns-sym (when owner-ns (symbol owner-ns))
                    parent-ns-id (get ns-index owner-ns-sym)]
                [id {:id id
                     :kind :schema
                     :label (name k)
                     :parent parent-ns-id  ; schemas belong to their owning namespace
                     :children #{}
                     :data {:schema-key k
                            :owner-ns owner-ns}}])))
       (into {})))
