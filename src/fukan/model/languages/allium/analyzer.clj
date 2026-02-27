(ns fukan.model.languages.allium.analyzer
  "Allium-specific analysis that produces a Contribution for the model
   build pipeline. Discovers .allium files, parses them, and builds
   nodes and edges from declarations and type references."
  (:require [fukan.model.languages.allium.parser :as parser]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; Constants

(def ^:private builtin-types
  "Type names that are language builtins and should not generate edges."
  #{"String" "Boolean" "Integer" "Set" "List" "Map"})

(def ^:private node-decl-types
  "Declaration types that produce leaf nodes in the model."
  #{:entity :value :variant :enum :external-entity :rule})

;; ---------------------------------------------------------------------------
;; File / namespace utilities

(defn file->ns-sym
  "Convert an allium file path to a namespace symbol.
   'src/fukan/model/spec.allium' with src-path 'src' → 'fukan.model.spec-allium'"
  [filepath src-path]
  (let [prefix (if (str/ends-with? src-path "/") src-path (str src-path "/"))
        relative (if (str/starts-with? filepath prefix)
                   (subs filepath (count prefix))
                   filepath)
        parts (str/split relative #"/")
        last-part (str/replace (last parts) ".allium" "-allium")
        all-parts (conj (vec (butlast parts)) last-part)]
    (symbol (str/join "." all-parts))))

(defn- discover-allium-files
  "Find .allium files recursively under src-path."
  [src-path]
  (->> (file-seq (io/file src-path))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".allium")))
       (mapv #(.getPath %))))

;; ---------------------------------------------------------------------------
;; Spec registry and use resolution

(defn build-spec-registry
  "Build a registry mapping parent directory names to filepaths.
   E.g., 'model' → 'src/fukan/model/spec.allium'"
  [filepaths]
  (into {} (map (fn [fp]
                  (let [parent-dir (.getName (.getParentFile (io/file fp)))]
                    [parent-dir fp]))
                filepaths)))

(defn resolve-use-path
  "Resolve a use path (e.g., './model.allium') to a filepath via the registry.
   Strips leading './' and trailing '.allium' to get the lookup key."
  [use-path registry]
  (let [stem (-> use-path
                 (str/replace #"^\./" "")
                 (str/replace #"\.allium$" ""))]
    (get registry stem)))

;; ---------------------------------------------------------------------------
;; Type reference extraction

(defn extract-type-refs
  "Extract all non-builtin type references from a type-ref AST node.
   Returns a set of {:name s} or {:alias s :name s} maps."
  [type-ref]
  (when type-ref
    (case (:kind type-ref)
      :simple
      (let [n (:name type-ref)]
        (if (contains? builtin-types n)
          #{}
          #{{:name n}}))

      :qualified
      #{{:alias (:ns type-ref) :name (:name type-ref)}}

      :generic
      (let [base (if (contains? builtin-types (:name type-ref))
                   #{}
                   #{{:name (:name type-ref)}})]
        (into base (mapcat extract-type-refs (:params type-ref))))

      :optional
      (extract-type-refs (:inner type-ref))

      :union
      (into #{} (mapcat extract-type-refs (:members type-ref)))

      :inline-obj
      (into #{} (mapcat (fn [f] (extract-type-refs (:type-ref f)))
                        (:fields type-ref)))

      #{})))

(defn extract-declaration-refs
  "Collect all type refs from one declaration.
   Returns a set of {:name s} or {:alias s :name s} maps."
  [decl]
  (case (:type decl)
    (:entity :value)
    (into #{} (mapcat (fn [field] (extract-type-refs (:type-ref field)))
                      (:fields decl)))

    :variant
    (into (or (extract-type-refs (:base decl)) #{})
          (mapcat (fn [field] (extract-type-refs (:type-ref field)))
                  (:fields decl)))

    :rule
    (let [params (->> (:clauses decl)
                       (filter #(= :when (:clause-type %)))
                       (mapcat (fn [c] (get-in c [:trigger :params]))))]
      (into #{} (mapcat (fn [p] (extract-type-refs (:type-ref p)))
                        params)))

    :external-entity #{}
    :enum #{}
    #{}))

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-allium-nodes
  "Build nodes from parsed allium files.
   Each file → container node. Each named declaration → function (leaf) node."
  [src-path parsed-files]
  (reduce
    (fn [acc {:keys [filepath ast]}]
      (let [ns-sym (file->ns-sym filepath src-path)
            container-id (str ns-sym)
            container {:id container-id
                       :kind :container
                       :label (str ns-sym)
                       :parent nil
                       :children #{}
                       :data {:kind :container
                              :filename filepath}}
            decls (->> (:declarations ast)
                       (filter #(contains? node-decl-types (:type %))))
            decl-nodes (into {}
                         (map (fn [decl]
                                (let [id (str container-id "/" (:name decl))]
                                  [id {:id id
                                       :kind :function
                                       :label (:name decl)
                                       :parent container-id
                                       :children #{}
                                       :data {:kind :function
                                              :private? false}}])))
                         decls)]
        (merge acc {container-id container} decl-nodes)))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Edge construction

(defn- build-alias-map
  "Build alias → target-ns-str map from use declarations in a file's AST."
  [ast src-path registry]
  (into {}
    (keep (fn [d]
            (when (= :use (:type d))
              (let [target-fp (resolve-use-path (:path d) registry)
                    target-sym (when target-fp (file->ns-sym target-fp src-path))]
                (when target-sym
                  [(:alias d) (str target-sym)])))))
    (:declarations ast)))

(defn- build-allium-edges
  "Build edges from parsed allium files.
   Type references → declaration-to-declaration edges.
   Use declarations → container-to-container edges."
  [src-path parsed-files registry]
  (let [;; Build per-file data
        file-data (mapv (fn [{:keys [filepath ast]}]
                          (let [ns-str (str (file->ns-sym filepath src-path))]
                            {:ns-str ns-str
                             :ast ast
                             :aliases (build-alias-map ast src-path registry)
                             :decl-names (set (keep :name (:declarations ast)))}))
                        parsed-files)]
    (vec (into #{}
      (mapcat
        (fn [{:keys [ns-str ast aliases decl-names]}]
          (let [decls (->> (:declarations ast)
                           (filter #(contains? node-decl-types (:type %))))]
            (concat
              ;; Type reference edges
              (mapcat
                (fn [decl]
                  (let [from-id (str ns-str "/" (:name decl))
                        refs (extract-declaration-refs decl)]
                    (keep (fn [{:keys [alias name]}]
                            (if alias
                              ;; Qualified ref: resolve alias to target namespace
                              (let [target-ns (get aliases alias)]
                                (when target-ns
                                  (let [to-id (str target-ns "/" name)]
                                    (when (not= from-id to-id)
                                      {:from from-id :to to-id}))))
                              ;; Simple ref: look up in same file only
                              (when (contains? decl-names name)
                                (let [to-id (str ns-str "/" name)]
                                  (when (not= from-id to-id)
                                    {:from from-id :to to-id})))))
                          refs)))
                decls)
              ;; Use declaration edges (container-to-container)
              (keep (fn [d]
                      (when (= :use (:type d))
                        (let [target-fp (resolve-use-path (:path d) registry)
                              target-ns (when target-fp
                                          (str (file->ns-sym target-fp src-path)))]
                          (when (and target-ns (not= ns-str target-ns))
                            {:from ns-str :to target-ns}))))
                    (:declarations ast)))))
        file-data)))))

;; ---------------------------------------------------------------------------
;; Public API

(defn allium-contribution
  "Produce an Allium language contribution from source files.
   Discovers .allium files under src-path, parses them, and builds
   a Contribution with nodes and edges."
  {:malli/schema [:=> [:cat :string] :Contribution]}
  [src-path]
  (let [files (discover-allium-files src-path)
        registry (build-spec-registry files)
        parsed (->> files
                    (map (fn [fp]
                           (let [ast (parser/parse-file fp)]
                             {:filepath fp :ast ast})))
                    ;; Skip files that failed to parse
                    (filter (fn [{:keys [ast]}]
                              (not (insta/failure? ast))))
                    vec)]
    (if (empty? parsed)
      {:source-files [] :nodes {} :edges []}
      {:source-files (mapv :filepath parsed)
       :nodes (build-allium-nodes src-path parsed)
       :edges (build-allium-edges src-path parsed registry)})))
