(ns fukan.model.analyzers.specification.languages.allium
  "Allium spec analyzer. Extracts domain types (entity, value, variant, enum)
   as schema nodes, rule triggers as function nodes, and cross-module
   function-call edges from rule bodies. Module descriptions are extracted
   for modules without a boundary file. Surface data (provides, exposes,
   guarantees) is owned by the boundary analyzer."
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
;; Use-alias resolution

(defn- resolve-use-to-module-id
  [importer-filepath use-path]
  (let [importer-dir (file->parent-folder-path importer-filepath)
        resolved (.normalize
                   (.resolve
                     (java.nio.file.Paths/get importer-dir (into-array String []))
                     (java.nio.file.Paths/get use-path (into-array String []))))]
    (str (.getParent resolved))))

(defn- build-use-alias-index
  [parsed-files]
  (into {}
    (mapcat
      (fn [{:keys [filepath ast]}]
        (let [source-module-id (file->parent-folder-path filepath)
              uses (->> (:declarations ast) (filter #(= :use (:type %))))]
          (for [{:keys [path alias]} uses
                :let [target-id (resolve-use-to-module-id filepath path)]]
            [[source-module-id alias] target-id])))
      parsed-files)))

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
  "Build a :map TypeExpr from an entity/value declaration's fields."
  [decl]
  {:tag :map
   :entries (->> (:fields decl)
                 (keep allium-field->type-expr)
                 vec)})

;; ---------------------------------------------------------------------------
;; Rule utilities

(def ^:private qualified-ref-pattern
  #"([a-z_][a-zA-Z0-9_]*)/([A-Z][a-zA-Z0-9_]*)")

(defn- extract-qualified-refs
  [text]
  (when text
    (->> (re-seq qualified-ref-pattern text)
         (mapv (fn [[_ alias name]] [alias name])))))

(defn- rule-clause-bodies
  [rule-decl]
  (->> (:clauses rule-decl)
       (keep :body)))

(defn- rule-trigger-name
  [rule-decl]
  (some (fn [c]
          (when (and (= :when (:clause-type c))
                     (= :call (get-in c [:trigger :kind])))
            (get-in c [:trigger :name])))
        (:clauses rule-decl)))

(defn- underscore->hyphen [s] (str/replace s "_" "-"))

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-allium-nodes
  "Build nodes from parsed allium files.
   Creates:
   - Module nodes with description (fallback if no boundary file)
   - Function nodes from rule triggers (private, behavioral)
   - Schema nodes from entity/value/variant/enum declarations"
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

            ;; Function nodes from rule triggers
            rules (->> decls (filter #(= :rule (:type %))))
            rule-fn-nodes
            (->> rules
                 (keep (fn [rule]
                         (let [trigger-name (rule-trigger-name rule)
                               doc (get decl-descs (:name rule))]
                           (when trigger-name
                             (let [fn-id (str module-id "/" trigger-name)]
                               [fn-id {:id fn-id
                                       :kind :function
                                       :label trigger-name
                                       :parent module-id
                                       :children #{}
                                       :data (cond-> {:kind :function
                                                      :private? true}
                                               doc (assoc :doc doc))}])))))
                 (into {}))

            ;; Schema nodes from entity/value declarations
            typed-decls (->> decls (filter #(#{:entity :value} (:type %))))
            schema-nodes
            (->> typed-decls
                 (map (fn [decl]
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
                                                     :schema (decl->schema-type-expr decl)}
                                               doc (assoc :doc doc))}])))
                 (into {}))

            ;; Schema nodes from enum declarations
            enum-decls (->> decls (filter #(= :enum (:type %))))
            enum-nodes
            (->> enum-decls
                 (map (fn [decl]
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
                                                     :schema {:tag :enum
                                                              :values (:values decl)}}
                                               doc (assoc :doc doc))}])))
                 (into {}))]
        (-> acc
            (assoc module-id module-node)
            (merge rule-fn-nodes)
            (merge schema-nodes)
            (merge enum-nodes))))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Edge construction

(defn- build-allium-edges
  "Build function-call edges from rule body qualified references."
  [_src-path parsed-files _registry]
  (let [use-index (build-use-alias-index parsed-files)

        exports-index
        (into {}
          (map (fn [{:keys [filepath ast]}]
                 (let [module-id (file->parent-folder-path filepath)
                       triggers (->> (:declarations ast)
                                     (filter #(= :rule (:type %)))
                                     (keep (fn [r]
                                             (some (fn [c]
                                                     (when (and (= :when (:clause-type c))
                                                                (= :call (get-in c [:trigger :kind])))
                                                       (get-in c [:trigger :name])))
                                                   (:clauses r)))))]
                   [module-id (into #{} triggers)]))
               parsed-files))

        resolve-fn-edge
        (fn [source-module-id source-fn-id [alias target-name]]
          (let [target-module-id (get use-index [source-module-id alias])
                target-fn-id (when target-module-id
                               (str target-module-id "/"
                                    (underscore->hyphen target-name)))]
            (when (and target-fn-id
                       (contains? (get exports-index target-module-id)
                                  target-name))
              {:from source-fn-id :to target-fn-id :kind :function-call})))]

    (->> parsed-files
         (mapcat
           (fn [{:keys [filepath ast]}]
             (let [source-module-id (file->parent-folder-path filepath)
                   rules (->> (:declarations ast) (filter #(= :rule (:type %))))]
               (mapcat
                 (fn [rule]
                   (let [trigger (rule-trigger-name rule)
                         source-fn-id (when trigger
                                        (str source-module-id "/"
                                             (underscore->hyphen trigger)))
                         refs (->> (rule-clause-bodies rule)
                                   (mapcat extract-qualified-refs))]
                     (when source-fn-id
                       (keep #(resolve-fn-edge source-module-id source-fn-id %)
                             refs))))
                 rules))))
         (into #{})
         vec)))

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Produce an Allium language analysis result from source files.
   Discovers .allium files under src-path, parses them, and builds
   an AnalysisResult with schema nodes (types), rule-trigger function
   nodes, and cross-module function-call edges."
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
       :edges (build-allium-edges src-path parsed nil)})))

(defmethod analyzers/analyze :allium [_ src-path]
  (analyze src-path))
