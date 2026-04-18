(ns fukan.model.analyzers.specification.languages.allium
  "Allium spec analyzer. Enrichment layer over the .boundary spine.

   Emits Schema nodes from `entity`, `value`, `variant`, and `enum`
   declarations — each marked `:private? true` by default. When the
   module's .boundary file `exposes` the same name, the Schema node
   merges with the boundary-emitted public Schema, contributing the
   TypeExpr structure while inheriting the public marking.

   Module nodes carry the .allium description as a fallback for modules
   that have no .boundary file or whose .boundary lacks a header comment.

   Rules, surfaces, and contracts are not yet emitted as graph elements;
   they will appear as their own node kinds in a future iteration."
  (:require [fukan.libs.allium.parser :as parser]
            [fukan.model.analyzers :as analyzers]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; Constants

(def ^:private builtin-types
  #{"String" "Boolean" "Integer" "Set" "List" "Map"})

;; ---------------------------------------------------------------------------
;; File utilities

(defn- file->parent-folder-path
  [filepath]
  (let [parts (str/split filepath #"/")]
    (str/join "/" (butlast parts))))

(defn- discover-allium-files
  [src-path]
  (->> (file-seq (io/file src-path))
       (filter #(and (.isFile %)
                     (str/ends-with? (.getName %) ".allium")))
       (mapv #(.getPath %))))

;; ---------------------------------------------------------------------------
;; Description extraction from raw text

(defn- strip-comment-prefix
  [line]
  (let [trimmed (str/trim line)]
    (cond
      (str/starts-with? trimmed "-- ") (subs trimmed 3)
      (str/starts-with? trimmed "--") (subs trimmed 2)
      :else trimmed)))

(def ^:private section-divider-re #"^(?:--\s*)?[-=]{4,}")

(defn- extract-module-description
  "Extract the module description from the header comment block."
  [text]
  (let [lines (str/split-lines text)]
    (->> lines
         (drop 1) ;; skip "-- allium: N"
         (drop-while str/blank?)
         (take-while (fn [line]
                       (let [trimmed (str/trim line)]
                         (and (or (str/blank? trimmed)
                                  (str/starts-with? trimmed "--"))
                              (not (re-find section-divider-re trimmed))))))
         (map strip-comment-prefix)
         (remove str/blank?)
         (str/join "\n")
         not-empty)))

(defn- extract-declaration-descriptions
  "Build a map from declaration name → description by scanning raw text."
  [text]
  (let [lines (str/split-lines text)
        decl-re #"^\s*(?:external\s+)?(?:entity|value|variant|rule|surface|enum)\s+(\w+)"]
    (loop [i 0
           result {}]
      (if (>= i (count lines))
        result
        (let [line (nth lines i)]
          (if-let [[_ decl-name] (re-find decl-re line)]
            (let [prev-lines (->> (range (dec i) -1 -1)
                                  (map #(str/trim (nth lines %))))
                  after-skip (loop [ls prev-lines, skipped-div? false]
                               (cond
                                 (and (seq ls) (str/blank? (first ls)))
                                 (recur (rest ls) skipped-div?)

                                 (and (seq ls) (not skipped-div?)
                                      (re-find section-divider-re (first ls)))
                                 (recur (rest ls) true)

                                 :else ls))
                  desc (->> after-skip
                            (take-while #(and (str/starts-with? % "--")
                                              (not (re-find section-divider-re %))))
                            reverse
                            (map strip-comment-prefix)
                            (remove str/blank?)
                            (str/join " ")
                            not-empty)]
              (recur (inc i) (if desc (assoc result decl-name desc) result)))
            (recur (inc i) result)))))))

;; ---------------------------------------------------------------------------
;; Type expression conversion

(defn- type-ref->type-expr
  "Convert a parser type-ref AST node to a model TypeExpr."
  [type-ref]
  (case (:kind type-ref)
    :simple    (if (contains? builtin-types (:name type-ref))
                 {:tag :primitive :name (:name type-ref)}
                 {:tag :ref :name (keyword (:name type-ref))})
    :qualified {:tag :ref :name (keyword (:ns type-ref) (:name type-ref))}
    :optional  {:tag :maybe :inner (type-ref->type-expr (:inner type-ref))}
    :generic   (let [base (:name type-ref)
                     params (mapv type-ref->type-expr (:params type-ref))]
                 (case base
                   "List"  {:tag :vector :element (first params)}
                   "Set"   {:tag :set :element (first params)}
                   "Map"   (if (= 2 (count params))
                             {:tag :map-of :key-type (first params) :value-type (second params)}
                             {:tag :primitive :name (str base "<...>")})
                   {:tag :primitive :name (str base "<...>")}))
    :union     {:tag :or :variants (mapv type-ref->type-expr (:members type-ref))}
    :inline-obj {:tag :map
                 :entries (->> (:fields type-ref)
                               (mapv (fn [f]
                                       {:key (:name f)
                                        :optional false
                                        :type (type-ref->type-expr (:type-ref f))})))}
    {:tag :unknown :original (pr-str type-ref)}))

(defn- allium-field->type-expr
  "Convert a parsed allium field to a TypeExpr map entry."
  [field]
  (when (= :typed (:field-kind field))
    {:key (:name field)
     :optional false
     :type (type-ref->type-expr (:type-ref field))}))

(defn- decl->schema-type-expr
  "Build a :map TypeExpr from an entity/value/variant declaration's fields."
  [decl]
  {:tag :map
   :entries (->> (:fields decl)
                 (keep allium-field->type-expr)
                 vec)})

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-typed-schema-node
  "Build a private Schema node from an entity, value, or variant declaration.
   The :map TypeExpr captures the field structure."
  [module-id decl decl-descs]
  (let [schema-id (str module-id "/" (:name decl))
        schema-key (keyword (:name decl))
        doc (get decl-descs (:name decl))]
    [schema-id {:id schema-id
                :kind :schema
                :label (:name decl)
                :parent module-id
                :children #{}
                :data (cond-> {:kind :schema
                               :schema-key schema-key
                               :schema (decl->schema-type-expr decl)
                               :private? true}
                        doc (assoc :doc doc))}]))

(defn- build-enum-schema-node
  "Build a private Schema node from an enum declaration. The TypeExpr is
   :enum with the literal values list."
  [module-id decl decl-descs]
  (let [schema-id (str module-id "/" (:name decl))
        schema-key (keyword (:name decl))
        doc (get decl-descs (:name decl))]
    [schema-id {:id schema-id
                :kind :schema
                :label (:name decl)
                :parent module-id
                :children #{}
                :data (cond-> {:kind :schema
                               :schema-key schema-key
                               :schema {:tag :enum :values (:values decl)}
                               :private? true}
                        doc (assoc :doc doc))}]))

(defn- build-allium-nodes
  "Build nodes from parsed allium files.
   Creates:
   - Module nodes with description (fallback when no .boundary file)
   - Schema nodes from entity/value/variant declarations (private)
   - Schema nodes from enum declarations (private)

   Schemas are emitted as private; the boundary analyzer publicizes a
   schema by emitting a public Schema with the same id, which merges
   with the allium one in the build pipeline."
  [_src-path parsed-files]
  (reduce
    (fn [acc {:keys [filepath ast text]}]
      (let [module-id (file->parent-folder-path filepath)
            decls (:declarations ast)
            module-desc (extract-module-description text)
            decl-descs (extract-declaration-descriptions text)

            module-node (cond->
                          {:id module-id
                           :kind :module
                           :label (last (str/split module-id #"/"))
                           :parent nil
                           :children #{}
                           :data {:kind :module :has-spec true}}
                          module-desc (assoc :description module-desc))

            typed-decls (->> decls (filter #(#{:entity :value :variant} (:type %))))
            schema-nodes (into {} (map #(build-typed-schema-node module-id % decl-descs))
                               typed-decls)

            enum-decls (->> decls (filter #(= :enum (:type %))))
            enum-nodes (into {} (map #(build-enum-schema-node module-id % decl-descs))
                             enum-decls)]
        (-> acc
            (assoc module-id module-node)
            (merge schema-nodes)
            (merge enum-nodes))))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Produce an Allium language analysis result from source files.
   Discovers .allium files under src-path, parses them, and emits Schema
   nodes (private) for each entity, value, variant, and enum declaration,
   plus a Module node carrying the file-level description.

   Allium is the enrichment layer; the .boundary file is the spec spine
   and emits the public Function and Schema commitments. No edges — call
   structure is derived from implementation analysis, and schema-reference
   edges are computed in the build pipeline from TypeExpr refs once
   allium and boundary contributions have merged."
  {:malli/schema [:=> [:cat :FilePath] :AnalysisResult]}
  [src-path]
  (let [files (discover-allium-files src-path)
        parsed (->> files
                    (map (fn [fp]
                           (let [text (slurp fp)
                                 ast (parser/parse-allium text)]
                             {:filepath fp :ast ast :text text})))
                    (filter (fn [{:keys [ast]}]
                              (not (insta/failure? ast))))
                    vec)]
    (if (empty? parsed)
      {:source-files [] :nodes {} :edges []}
      {:source-files (mapv :filepath parsed)
       :nodes (build-allium-nodes src-path parsed)
       :edges []})))

(defmethod analyzers/analyze :allium [_ src-path]
  (analyze src-path))
