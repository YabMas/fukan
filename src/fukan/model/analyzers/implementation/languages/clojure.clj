(ns fukan.model.analyzers.implementation.languages.clojure
  "Clojure-specific analysis and model construction.
   Runs clj-kondo static analysis, discovers Malli schema definitions,
   enriches nodes with runtime metadata, resolves contract.edn files,
   and produces a complete AnalysisResult for the build pipeline."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.set :as set]
            [fukan.model.analyzers :as analyzers]
            [fukan.model.analyzers.implementation.builders :as builders]))

;; -----------------------------------------------------------------------------
;; Static Analysis

(defn- extract-defmethod-defs
  "Extract defmethod forms from var-usages and convert to synthetic SymbolDef records.
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
  "Runs clj-kondo on src-path and returns the raw kondo analysis map.

   Returns a map with kondo-native field names:
   - :namespace-definitions - list of {:name :filename ...}
   - :var-definitions - list of {:ns :name :filename :private ...}
   - :var-usages - list of {:from :from-var :to :name ...}
   - :namespace-usages - list of {:from :to :filename ...}

   defmethod forms (reported by clj-kondo as var-usages with :defmethod true)
   are normalized into var-definitions so they appear as leaf nodes in the model.

   Throws if clj-kondo fails to run."
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
;; Kondo → generic normalization

(defn- normalize-kondo-output
  "Map clj-kondo field names to generic CodeAnalysis field names.
   Top-level keys: :namespace-definitions → :module-definitions,
   :var-definitions → :symbol-definitions, :var-usages → :symbol-references,
   :namespace-usages → :module-imports.
   Per SymbolDef: :ns → :module.
   Per SymbolRef: :from-var → :from-symbol."
  [kondo-analysis]
  (-> kondo-analysis
      (set/rename-keys {:namespace-definitions :module-definitions
                        :var-definitions       :symbol-definitions
                        :var-usages            :symbol-references
                        :namespace-usages      :module-imports})
      (update :symbol-definitions
              (fn [defs]
                (mapv #(set/rename-keys % {:ns :module}) defs)))
      (update :symbol-references
              (fn [refs]
                (mapv #(set/rename-keys % {:from-var :from-symbol}) refs)))))

;; -----------------------------------------------------------------------------
;; Malli → TypeExpr conversion

(defn- malli-props
  "Extract property map from a Malli vector form, nil if absent."
  [form]
  (when (and (vector? form)
             (>= (count form) 2)
             (map? (second form)))
    (second form)))

(defn- malli-children
  "Children of a Malli vector form, after tag and optional props."
  [form]
  (let [tail (rest form)]
    (if (and (seq tail) (map? (first tail)))
      (rest tail)
      tail)))

(defn- keyword->type-expr
  "Classify a keyword as :ref or :primitive based on initial case.
   Uppercase-initial → ref, lowercase → primitive."
  [k]
  (let [n (name k)]
    (if (Character/isUpperCase (first n))
      {:tag :ref :name k}
      {:tag :primitive :name n})))

(defn malli->type-expr
  "Recursively convert a Malli schema form to a TypeExpr map.
   Handles all standard Malli types; unknown forms become :unknown."
  [form]
  (cond
    ;; Keyword — classify as ref or primitive
    (keyword? form)
    (keyword->type-expr form)

    ;; Vector form — dispatch on tag
    (vector? form)
    (let [tag (first form)
          props (malli-props form)
          children (malli-children form)
          desc (:description props)]
      (case tag
        :map
        (cond-> {:tag :map
                 :entries (vec (for [entry children
                                     :when (vector? entry)]
                                 (let [[k & rest-parts] entry
                                       [opts child-schema] (if (map? (first rest-parts))
                                                             [(first rest-parts) (second rest-parts)]
                                                             [{} (first rest-parts)])]
                                   (cond-> {:key (str k)
                                            :optional (boolean (:optional opts))
                                            :type (malli->type-expr child-schema)}
                                     (:description opts) (assoc :description (:description opts))))))}
          desc (assoc :description desc))

        :map-of
        (cond-> {:tag :map-of
                 :key-type (malli->type-expr (first children))
                 :value-type (malli->type-expr (second children))}
          desc (assoc :description desc))

        :vector
        (cond-> {:tag :vector
                 :element (malli->type-expr (first children))}
          desc (assoc :description desc))

        :set
        (cond-> {:tag :set
                 :element (malli->type-expr (first children))}
          desc (assoc :description desc))

        :maybe
        (cond-> {:tag :maybe
                 :inner (malli->type-expr (first children))}
          desc (assoc :description desc))

        :or
        (cond-> {:tag :or
                 :variants (mapv malli->type-expr children)}
          desc (assoc :description desc))

        :and
        (let [converted (mapv malli->type-expr children)
              structural (remove #(#{:predicate :unknown} (:tag %)) converted)]
          (if (= 1 (count structural))
            ;; Validation-only intersection: base type + predicates/regex → collapse
            (cond-> (first structural)
              desc (assoc :description desc))
            ;; Genuine intersection of multiple structural types
            (cond-> {:tag :and
                     :types converted}
              desc (assoc :description desc))))

        :enum
        (cond-> {:tag :enum
                 :values (vec children)}
          desc (assoc :description desc))

        (:= :==)
        (cond-> {:tag :enum
                 :values (vec children)}
          desc (assoc :description desc))

        :tuple
        (cond-> {:tag :tuple
                 :elements (mapv malli->type-expr children)}
          desc (assoc :description desc))

        (:fn :re)
        (cond-> {:tag :predicate}
          desc (assoc :description desc))

        :=>
        (let [[input output] children
              inputs (if (and (vector? input) (= :cat (first input)))
                       (mapv malli->type-expr (rest input))
                       [(malli->type-expr input)])]
          (cond-> {:tag :fn
                   :inputs inputs
                   :output (malli->type-expr output)}
            desc (assoc :description desc)))

        :cat
        (cond-> {:tag :tuple
                 :elements (mapv malli->type-expr children)}
          desc (assoc :description desc))

        :multi
        (cond-> {:tag :or
                 :variants (mapv (fn [child]
                                   (if (vector? child)
                                     (malli->type-expr (second child))
                                     (malli->type-expr child)))
                                 children)}
          desc (assoc :description desc))

        ;; Primitive/ref with props (e.g. [:string {:min 1}]) — treat as the base type
        (if (keyword? tag)
          (cond-> (keyword->type-expr tag)
            desc (assoc :description desc))
          (cond-> {:tag :unknown :original (pr-str form)}
            desc (assoc :description desc)))))

    ;; Anything else — unknown
    :else
    {:tag :unknown :original (pr-str form)}))

(defn malli->fn-signature
  "Convert a Malli function schema [:=> [:cat a b] out] to FunctionSignature
   {:inputs [TypeExpr] :output TypeExpr}, or nil if not a function schema."
  [form]
  (when (and (vector? form) (= :=> (first form)))
    (let [[_ input output] form
          inputs (if (and (vector? input) (= :cat (first input)))
                   (mapv malli->type-expr (rest input))
                   [(malli->type-expr input)])]
      {:inputs inputs :output (malli->type-expr output)})))

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
                     :doc (:description (malli-props @v))
                     :owner-ns (str (ns-name ns))}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Schema Node Building

(defn- build-schema-nodes
  "Build schema nodes from discovered schema data.
   Schema nodes are placed inside their owning namespace module.
   Converts raw Malli schema forms to TypeExpr at build time.

   ns-index is a map of {ns-sym -> node-id} for looking up parent namespaces.
   schema-data is a map of {keyword -> {:schema-form form :doc str? :owner-ns ns-str}}
   as returned by discover-schema-data.

   Returns a map of {schema-node-id -> node}."
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
                            :schema (malli->type-expr schema-form)
                            :doc doc}}])))
       (into {})))

;; -----------------------------------------------------------------------------
;; Runtime enrichment

(defn- enrich-with-runtime-metadata
  "Attach :malli/schema signatures to function nodes from runtime vars.
   Converts Malli function schemas to FunctionSignature (TypeExpr-based).
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
                  (if-let [parts (when schema (malli->fn-signature schema))]
                    (assoc acc id (assoc-in node [:data :signature] parts))
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
               :id (str ns-sym "/" (name var-sym))
               :schema (malli->fn-signature schema)}
        (:doc (meta v)) (assoc :doc (:doc (meta v)))))))

(defn- read-contract-file
  "Read contract.edn from a directory path if present.
   Resolves qualified symbols to {:name :schema} format."
  [dir-path]
  (when (and dir-path (not= dir-path ""))
    (let [file (io/file dir-path "contract.edn")]
      (when (.exists file)
        (let [raw (edn/read-string (slurp file))]
          (update raw :functions (fn [fns] (mapv resolve-fn-ref fns))))))))

(defn- discover-contract-nodes
  "Discover contract.edn files under src-path and produce module nodes
   at directory-path IDs with :boundary data. These nodes will be merged
   with folder nodes created by the build pipeline."
  [src-path]
  (->> (file-seq (io/file src-path))
       (filter #(= "contract.edn" (.getName %)))
       (reduce (fn [acc contract-file]
                 (let [dir-path (.getPath (.getParentFile contract-file))
                       boundary (read-contract-file dir-path)]
                   (if boundary
                     (assoc acc dir-path
                            {:id dir-path
                             :kind :module
                             :label (last (str/split dir-path #"/"))
                             :parent nil
                             :children #{}
                             :data {:kind :module
                                    :boundary boundary}})
                     acc)))
               {})))

;; -----------------------------------------------------------------------------
;; AnalysisResult

(defn- build-dispatch-edges
  "Build :dispatches edges from defmethod var-usages.
   Each defmethod creates an edge from the dispatch point (defmulti)
   to the handler (defmethod). Direction: dispatch-point → handler,
   per spec: 'A polymorphically routes to B at runtime.'

   Uses raw kondo var-usages (before normalization) because defmethod
   entries have :to pointing to the defmulti's namespace."
  [raw-var-usages symbol-index]
  (->> raw-var-usages
       (filter :defmethod)
       (keep (fn [{:keys [name from to dispatch-val-str]}]
               (let [dispatch-point-id (get symbol-index [to name])
                     handler-name (symbol (str name " " dispatch-val-str))
                     handler-id (get symbol-index [from handler-name])]
                 (when (and dispatch-point-id handler-id
                            (not= dispatch-point-id handler-id))
                   {:from dispatch-point-id :to handler-id :kind :dispatches}))))
       (into #{})
       vec))

(defn- analysis->result
  "Convert CodeAnalysis into a language analysis result.
   Normalizes kondo field names to generic names, then builds
   module nodes, symbol nodes, and reference edges.
   Module nodes have :parent nil — build-model assigns folder parents."
  {:malli/schema [:=> [:cat :CodeAnalysis] :AnalysisResult]}
  [analysis]
  (let [;; Capture raw var-usages before normalization for dispatch edges
        raw-var-usages (:var-usages analysis)

        ;; Normalize kondo → generic field names
        analysis (normalize-kondo-output analysis)
        module-defs (:module-definitions analysis)
        symbol-defs (:symbol-definitions analysis)

        ;; Build module nodes with nil parent (empty folder-index)
        {ns-nodes :nodes ns-index :index} (builders/build-module-nodes module-defs {})

        ;; Add :filename to each module node's data for folder parenting
        ns-filename-map (into {} (map (fn [nd] [(str (:name nd)) (:filename nd)]) module-defs))
        ns-nodes (reduce-kv (fn [acc id node]
                              (if-let [fname (get ns-filename-map id)]
                                (assoc acc id (assoc-in node [:data :filename] fname))
                                (assoc acc id node)))
                            {} ns-nodes)

        ;; Build symbol nodes
        {var-nodes :nodes var-index :index} (builders/build-symbol-nodes symbol-defs ns-index)

        ;; Build edges (var-level only — module dependencies are
        ;; derived from these by the projection layer)
        var-edges (builders/build-reference-edges analysis var-index ns-index)

        ;; Build dispatch edges (defmulti → defmethod)
        dispatch-edges (build-dispatch-edges raw-var-usages var-index)]

    {:source-files (mapv :filename module-defs)
     :nodes (merge ns-nodes var-nodes)
     :edges (into var-edges dispatch-edges)
     :ns-index ns-index}))

(defn analyze
  "Produce a complete Clojure language analysis result.
   Runs static analysis (clj-kondo), then enriches with runtime metadata:
   - Discovers ^:schema vars and builds schema nodes
   - Attaches :malli/schema function signatures to function nodes
   - Discovers contract.edn files and produces boundary module nodes"
  {:malli/schema [:=> [:cat :string] :AnalysisResult]}
  [src-path]
  (let [{:keys [nodes edges source-files ns-index]} (-> (run-kondo src-path)
                                                         analysis->result)
        ;; Discover schemas from runtime
        schema-data (discover-schema-data)

        ;; Enrich function nodes with runtime metadata (signatures)
        enriched-nodes (enrich-with-runtime-metadata nodes schema-data)

        ;; Build schema nodes using ns-index from analysis
        schema-nodes (build-schema-nodes ns-index schema-data)

        ;; Discover contract.edn files and produce boundary module nodes
        contract-nodes (discover-contract-nodes src-path)]

    {:source-files source-files
     :nodes (merge enriched-nodes schema-nodes contract-nodes)
     :edges edges}))

(defmethod analyzers/analyze :clojure [_ src-path]
  (analyze src-path))
