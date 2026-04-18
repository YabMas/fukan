(ns fukan.model.analyzers.specification.languages.boundary
  "Boundary-language analyzer. The .boundary file is the spec spine for a
   module: its `fn` declarations become Function nodes, its `exposes`
   declarations become Schema nodes, and its `guarantee` declarations
   land in the module's Boundary. The .allium file enriches this spine
   with TypeExpr structure for exposed schemas and surface annotations
   (description, @guarantee) for the module."
  (:require [fukan.libs.boundary.parser :as parser]
            [fukan.model.analyzers :as analyzers]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; Constants

(def ^:private builtin-types
  "Type names that map to primitive TypeExpr tags rather than refs."
  #{"String" "Boolean" "Integer" "Set" "List" "Map" "Unit"
    "FilePath" "Path" "Html" "Any"})

(def ^:private primitive-mapping
  {"String"  "string"
   "Integer" "int"
   "Boolean" "boolean"
   "Unit"    "nil"
   "Any"     "any"})

;; ---------------------------------------------------------------------------
;; File utilities

(defn- file->parent-folder-path
  "Return the parent directory path for a boundary file."
  [filepath]
  (let [parts (str/split filepath #"/")]
    (str/join "/" (butlast parts))))

(defn- discover-boundary-files
  "Find .boundary files recursively under src-path."
  [src-path]
  (->> (file-seq (io/file src-path))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".boundary")))
       (mapv #(.getPath %))))

(defn- underscore->hyphen
  "Convert underscore-cased identifiers to kebab-case so spec-derived
   function nodes line up with implementation labels."
  [s]
  (str/replace s "_" "-"))

;; ---------------------------------------------------------------------------
;; Type expression conversion

(defn- type-ref->type-expr
  "Convert a parsed boundary/allium type-ref AST node to a model TypeExpr."
  [type-ref]
  (case (:kind type-ref)
    :simple    (if-let [prim (get primitive-mapping (:name type-ref))]
                 {:tag :primitive :name prim}
                 (if (contains? builtin-types (:name type-ref))
                   {:tag :primitive :name (:name type-ref)}
                   {:tag :ref :name (keyword (:name type-ref))}))
    :qualified {:tag :ref :name (keyword (:ns type-ref) (:name type-ref))}
    :optional  {:tag :maybe :inner (type-ref->type-expr (:inner type-ref))}
    :generic   (let [base (:name type-ref)
                     params (mapv type-ref->type-expr (:params type-ref))]
                 (case base
                   "List"  {:tag :vector :element (first params)}
                   "Set"   {:tag :set :element (first params)}
                   "Map"   (if (= 2 (count params))
                             {:tag :map-of :key-type (first params)
                              :value-type (second params)}
                             {:tag :unknown :original (str base "<...>")})
                   {:tag :ref :name (keyword base)}))
    :union     {:tag :or :variants (mapv type-ref->type-expr (:members type-ref))}
    {:tag :unknown :original (pr-str type-ref)}))

(defn- fn-decl->signature
  "Build a FunctionSignature {:inputs [...] :output ...} from a parsed `fn` decl."
  [decl]
  (when (or (seq (:params decl)) (:return decl))
    (cond-> {}
      (seq (:params decl)) (assoc :inputs (mapv #(type-ref->type-expr (:type %))
                                                (:params decl)))
      (:return decl)        (assoc :output (type-ref->type-expr (:return decl))))))

;; ---------------------------------------------------------------------------
;; Surface construction (carried for downstream collapse into Boundary)

(defn- build-surface-from-boundary
  "Build a surface map from boundary-level metadata.
   Only the module description is carried here; the build pipeline
   collapses it into the module's Boundary. Functions and schemas are
   emitted as first-class nodes below. Prose guarantees live in the
   module's .allium sibling, not in .boundary."
  [_decls module-desc]
  (cond-> {}
    module-desc (assoc :description module-desc)))

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-fn-nodes
  "Emit Function nodes for each `fn` decl in a parsed boundary file.
   Labels and IDs use kebab-case so they merge with implementation-derived
   function nodes for the same operation."
  [module-id decls]
  (->> decls
       (filter #(= :fn (:type %)))
       (map (fn [decl]
              (let [hyphen-name (underscore->hyphen (:name decl))
                    fn-id (str module-id "/" hyphen-name)
                    signature (fn-decl->signature decl)
                    description (:description decl)]
                [fn-id
                 (cond-> {:id fn-id
                          :kind :function
                          :label hyphen-name
                          :parent module-id
                          :children #{}
                          :data (cond-> {:kind :function
                                         :private? false}
                                  signature   (assoc :signature signature)
                                  description (assoc :doc description))})])))
       (into {})))

(defn- build-schema-nodes
  "Emit Schema nodes for each `exposes` decl in a parsed boundary file.
   Schemas are marked public; TypeExpr is a placeholder ref that the
   allium analyzer fills in with concrete structure when an `entity`,
   `value`, or `variant` of the same name is declared in the module's
   .allium file."
  [module-id decls]
  (->> decls
       (filter #(= :exposes (:type %)))
       (map (fn [decl]
              (let [name (:name decl)
                    schema-id (str module-id "/" name)
                    schema-key (keyword name)]
                [schema-id
                 {:id schema-id
                  :kind :schema
                  :label name
                  :parent module-id
                  :children #{}
                  :data {:kind :schema
                         :schema-key schema-key
                         :schema {:tag :ref :name schema-key}
                         :private? false}}])))
       (into {})))

(defn- build-module-node
  "Build a Module node from a parsed boundary file."
  [module-id module-desc surface]
  (cond->
    {:id module-id
     :kind :module
     :label (last (str/split module-id #"/"))
     :parent nil
     :children #{}
     :data (cond-> {:kind :module :has-spec true}
             (seq surface) (assoc :surface surface))}
    module-desc (assoc :description module-desc)))

(defn- build-boundary-nodes
  "Build all nodes for a parsed boundary file: module, function nodes,
   and schema nodes. Function and Schema nodes carry the boundary's
   public commitment; allium enriches them via the merge step in build."
  [_src-path parsed-files]
  (reduce
    (fn [acc {:keys [filepath ast text]}]
      (let [module-id (file->parent-folder-path filepath)
            decls (:declarations ast)
            module-desc (parser/extract-module-description text)
            surface (build-surface-from-boundary decls module-desc)
            module-node (build-module-node module-id module-desc surface)
            fn-nodes (build-fn-nodes module-id decls)
            schema-nodes (build-schema-nodes module-id decls)]
        (-> acc
            (assoc module-id module-node)
            (merge fn-nodes)
            (merge schema-nodes))))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Produce a boundary language analysis result from source files.
   Discovers .boundary files under src-path, parses them, and emits the
   spec spine: Module + Function (from `fn`) + Schema (from `exposes`)
   nodes, with surface metadata for downstream collapse into Boundary.
   No edges — schema-reference edges are derived in the build pipeline
   from TypeExpr keyword refs once allium has filled in schema structure."
  {:malli/schema [:=> [:cat :FilePath] :AnalysisResult]}
  [src-path]
  (let [files (discover-boundary-files src-path)
        parsed (->> files
                    (map (fn [fp]
                           (let [text (slurp fp)
                                 ast (parser/parse-boundary text)]
                             {:filepath fp :ast ast :text text})))
                    (filter (fn [{:keys [ast]}]
                              (not (insta/failure? ast))))
                    vec)]
    (if (empty? parsed)
      {:source-files [] :nodes {} :edges []}
      {:source-files (mapv :filepath parsed)
       :nodes (build-boundary-nodes src-path parsed)
       :edges []})))

(defmethod analyzers/analyze :boundary [_ src-path]
  (analyze src-path))
