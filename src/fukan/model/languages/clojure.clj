(ns fukan.model.languages.clojure
  "Clojure-specific analysis and model construction.
   Includes clj-kondo analysis and Malli schema node building."
  (:require [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [clojure.repl :as repl]))

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

(defn- extract-source-schema-refs
  "Extract symbol references from schema source string.
   Returns a set of keywords for symbols that match registered schema names."
  [source-str registered-schema-names]
  (when source-str
    ;; Parse the source to find all symbols
    (let [form (try (read-string source-str) (catch Exception _ nil))]
      (when (and (seq? form) (= 'def (first form)))
        ;; Get the schema body (3rd or 4th element depending on docstring)
        (let [body (if (string? (nth form 2 nil))
                     (nth form 3 nil)  ; has docstring
                     (nth form 2 nil))  ; no docstring
              refs (atom #{})]
          (letfn [(walk [x]
                    (cond
                      ;; Symbol that matches a registered schema name
                      (and (symbol? x)
                           (contains? registered-schema-names (keyword (name x))))
                      (swap! refs conj (keyword (name x)))

                      ;; Vector - recurse
                      (vector? x)
                      (doseq [child x] (walk child))

                      ;; Map - recurse into values
                      (map? x)
                      (doseq [v (vals x)] (walk v))

                      ;; Seq - recurse
                      (seq? x)
                      (doseq [child x] (walk child))))]
            (walk body)
            @refs))))))

(defn discover-schema-data
  "Scan loaded namespaces for vars with ^:schema metadata.
   Returns a map of {keyword -> {:schema-form form :owner-ns ns-str :schema-refs #{...}}}.

   :schema-refs contains keywords for other schemas referenced in the source form.

   This is a pure function that reads metadata from the runtime - it does
   not mutate any global state."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [;; First pass: collect all schema names
        all-schemas (->> (all-ns)
                         (mapcat (fn [ns]
                                   (for [[sym v] (ns-publics ns)
                                         :when (:schema (meta v))]
                                     (keyword (name sym)))))
                         set)
        ;; Second pass: build schema data with refs
        schema-data (->> (all-ns)
                         (mapcat (fn [ns]
                                   (for [[sym v] (ns-publics ns)
                                         :when (:schema (meta v))]
                                     (let [full-sym (symbol (str (ns-name ns)) (str sym))
                                           source (try (repl/source-fn full-sym) (catch Exception _ nil))
                                           refs (extract-source-schema-refs source all-schemas)]
                                       [(keyword (name sym))
                                        {:schema-form @v
                                         :owner-ns (str (ns-name ns))
                                         :schema-refs (or refs #{})}]))))
                         (into {}))]
    schema-data))

;; -----------------------------------------------------------------------------
;; Schema Node Building

(defn build-schema-nodes
  "Build schema nodes from discovered schema data.
   Schema nodes are placed inside their owning namespace container.

   ns-index is a map of {ns-sym -> node-id} for looking up parent namespaces.
   schema-data is a map of {keyword -> {:schema-form form :owner-ns ns-str :schema-refs #{...}}}
   as returned by discover-schema-data.

   Returns a map of {schema-node-id -> node}."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [ns-index schema-data]
  (->> schema-data
       (map (fn [[k {:keys [schema-form owner-ns schema-refs]}]]
              (let [id (str "schema:" (clojure.core/name k))
                    owner-ns-sym (when owner-ns (symbol owner-ns))
                    parent-ns-id (get ns-index owner-ns-sym)]
                [id {:id id
                     :kind :schema
                     :label (name k)
                     :parent parent-ns-id  ; schemas belong to their owning namespace
                     :children #{}
                     :data {:schema-key k
                            :owner-ns owner-ns
                            :schema-form schema-form
                            :schema-refs (or schema-refs #{})}}])))
       (into {})))
