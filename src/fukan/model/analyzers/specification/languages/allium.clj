(ns fukan.model.analyzers.specification.languages.allium
  "Allium-specific analysis that produces an AnalysisResult for the model
   build pipeline. Discovers .allium files, parses them, and builds
   nodes and edges from declarations and type references."
  (:require [fukan.libs.allium.parser :as parser]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; Constants

(def ^:private builtin-types
  "Type names that are language builtins and should not generate edges."
  #{"String" "Boolean" "Integer" "Set" "List" "Map"})

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

(defn- file->parent-folder-path
  "Return the parent directory path for an allium file.
   'src/fukan/model/spec.allium' → 'src/fukan/model'
   This matches the folder node ID convention used by the build pipeline."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (str/join "/" (butlast parts))))

(defn- discover-allium-files
  "Find .allium files recursively under src-path."
  [src-path]
  (->> (file-seq (io/file src-path))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".allium")))
       (mapv #(.getPath %))))

;; ---------------------------------------------------------------------------
;; Spec registry and use resolution

(defn- build-spec-registry
  "Build a registry mapping parent directory names to filepaths.
   E.g., 'model' → 'src/fukan/model/spec.allium'"
  [filepaths]
  (into {} (map (fn [fp]
                  (let [parent-dir (.getName (.getParentFile (io/file fp)))]
                    [parent-dir fp]))
                filepaths)))

(defn- resolve-use-path
  "Resolve a use path (e.g., './model.allium') to a filepath via the registry.
   Strips leading './' and trailing '.allium' to get the lookup key."
  [use-path registry]
  (let [stem (-> use-path
                 (str/replace #"^\./" "")
                 (str/replace #"\.allium$" ""))]
    (get registry stem)))

;; ---------------------------------------------------------------------------
;; Type reference extraction

(defn- extract-type-refs
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

(defn- extract-declaration-refs
  "Collect all type refs from one declaration.
   Returns a set of {:name s} or {:alias s :name s} maps."
  [decl]
  (case (:type decl)
    (:entity :value)
    (into #{} (mapcat (fn [field]
                        (if (= :variant (:field-kind field))
                          (mapcat #(extract-type-refs (:type-ref %)) (:fields field))
                          (extract-type-refs (:type-ref field))))
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

    :surface
    (into #{} (mapcat (fn [field] (extract-type-refs (:type-ref field)))
                      (:fields decl)))

    :external-entity #{}
    :external-value #{}
    :enum #{}
    #{}))

;; ---------------------------------------------------------------------------
;; Spec data extraction

(defn- extract-surface
  "Extract a Surface map from a surface declaration.
   Fields with specific names map to Surface properties:
     facing, exposes, provides, guarantees."
  [decl]
  (let [fields (:fields decl)
        by-name (group-by :name fields)
        facing (some-> (get by-name "facing") first :type-ref :name)
        extract-entries (fn [field-name]
                          (when-let [entries (get by-name field-name)]
                            (->> entries
                                 (mapv (fn [f]
                                         (cond-> {:name (:name f)
                                                  :type-ref (if (:type-ref f)
                                                              (pr-str (:type-ref f))
                                                              (or (:expr f) ""))}
                                           (:comment f) (assoc :description (:comment f))))))))]
    (cond-> {}
      facing (assoc :facing facing)
      (:description decl) (assoc :description (:description decl))
      (seq (get by-name "exposes")) (assoc :exposes (extract-entries "exposes"))
      (seq (get by-name "provides")) (assoc :provides (extract-entries "provides"))
      (seq (get by-name "guarantees")) (assoc :guarantees
                                              (mapv (fn [f]
                                                      (or (-> f :type-ref :name)
                                                          (:expr f)
                                                          ""))
                                                    (get by-name "guarantees"))))))

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-allium-nodes
  "Build nodes from parsed allium files.
   Each file enriches its parent directory's module node with spec data
   (description, surface). No declaration children are created —
   spec entities exist as data on the module, not as graph nodes."
  [_src-path parsed-files]
  (reduce
    (fn [acc {:keys [filepath ast]}]
      (let [module-id (file->parent-folder-path filepath)
            decls (:declarations ast)

            ;; Extract spec data from declarations
            entities (->> decls (filter #(#{:entity :value} (:type %))))
            surfaces (->> decls (filter #(= :surface (:type %))))
            description (or
                          ;; First entity/value with a description string
                          (some :description entities)
                          ;; Or first surface description
                          (some :description surfaces))
            surface (when (seq surfaces)
                      (extract-surface (first surfaces)))

            module-node (cond->
                          {:id module-id
                           :kind :module
                           :label module-id
                           :parent nil
                           :children #{}
                           :data (cond-> {:kind :module}
                                   surface (assoc :surface surface))}
                          description (assoc :description description))]
        (assoc acc module-id module-node)))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Edge construction

(defn- build-allium-edges
  "Build edges from parsed allium files.
   Currently returns no edges — Allium use declarations are spec-level
   imports, not implementation dependencies. Code edges from the Clojure
   analyzer already capture the real dependency graph."
  [_src-path _parsed-files _registry]
  [])

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Produce an Allium language analysis result from source files.
   Discovers .allium files under src-path, parses them, and builds
   an AnalysisResult with nodes and edges."
  {:malli/schema [:=> [:cat :string] :AnalysisResult]}
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
