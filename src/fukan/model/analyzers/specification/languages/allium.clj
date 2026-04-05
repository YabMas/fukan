(ns fukan.model.analyzers.specification.languages.allium
  "Allium-specific analysis that produces an AnalysisResult for the model
   build pipeline. Discovers .allium files, parses them, and builds
   nodes and edges from declarations and type references."
  (:require [fukan.libs.allium.parser :as parser]
            [fukan.model.analyzers :as analyzers]
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

(defn- file->ns-sym
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
;; Description extraction from raw text
;;
;; Allium specs use -- comments as docstrings. The parser discards them,
;; so we extract descriptions from the raw file text. Two kinds:
;; - Module description: the comment block between the header and the
;;   first declaration (or section divider).
;; - Declaration descriptions: comment block immediately before a keyword
;;   (entity, value, rule, surface, etc.).

(defn- strip-comment-prefix
  "Strip leading '-- ' or '--' from a comment line."
  [line]
  (let [trimmed (str/trim line)]
    (cond
      (str/starts-with? trimmed "-- ") (subs trimmed 3)
      (str/starts-with? trimmed "--") (subs trimmed 2)
      :else trimmed)))

(def ^:private declaration-keywords
  "Keywords that start allium declarations."
  #{"entity" "value" "variant" "rule" "surface" "enum" "config"
    "external" "invariant" "open" "use" "given"})

(def ^:private section-divider-re #"^-{4,}")

(defn- extract-module-description
  "Extract the module description from the header comment block.
   Returns the text between '-- allium: N' and the first declaration
   or section divider, stripped of comment prefixes."
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
  "Build a map from declaration name → description by scanning raw text.
   Looks for comment blocks immediately before declaration keywords."
  [text]
  (let [lines (str/split-lines text)
        decl-re #"^\s*(?:external\s+)?(?:entity|value|variant|rule|surface|enum)\s+(\w+)"]
    (loop [i 0
           result {}]
      (if (>= i (count lines))
        result
        (let [line (nth lines i)]
          (if-let [[_ decl-name] (re-find decl-re line)]
            ;; Found a declaration — scan backwards for comment block
            (let [desc (->> (range (dec i) -1 -1)
                            (map #(str/trim (nth lines %)))
                            (take-while #(str/starts-with? % "--"))
                            (remove #(re-find section-divider-re %))
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
  "Resolve a relative use path against the importing file's directory.
   E.g., from 'src/brian_arch/effects/spec.allium' with '../wire/spec.allium'
   → 'src/brian_arch/wire' (the target module ID)."
  [importer-filepath use-path]
  (let [importer-dir (file->parent-folder-path importer-filepath)
        resolved (.normalize
                   (.resolve
                     (java.nio.file.Paths/get importer-dir (into-array String []))
                     (java.nio.file.Paths/get use-path (into-array String []))))]
    (str (.getParent resolved))))

(defn- build-use-alias-index
  "Build a map from [source-module-id alias] → target-module-id.
   Walks use declarations in each parsed file and resolves relative paths."
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
    (let [regular-refs (into #{} (mapcat (fn [field] (extract-type-refs (:type-ref field)))
                                         (remove #(= :provides-block (:field-kind %)) (:fields decl))))
          provides-refs (->> (:fields decl)
                             (filter #(= :provides-block (:field-kind %)))
                             (mapcat :entries)
                             (mapcat (fn [entry]
                                       (concat
                                         (mapcat (fn [p] (extract-type-refs (:type p))) (:params entry))
                                         (extract-type-refs (:return entry)))))
                             set)]
      (into regular-refs provides-refs))

    :external-entity #{}
    :external-value #{}
    :enum #{}
    #{}))

;; ---------------------------------------------------------------------------
;; Spec data extraction

(defn- extract-surface
  "Extract a Surface map from a surface declaration.
   Fields use distinct :field-kind values from the parser:
     :facing, :exposes, :provides-block, :annotation (for @guarantee)."
  [decl]
  (let [fields (:fields decl)
        by-kind (group-by :field-kind fields)
        facing-field (first (get by-kind :facing))
        facing (some-> facing-field :type-ref :name)
        exposes-field (first (get by-kind :exposes))
        exposes-entries (when exposes-field
                          (mapv (fn [e] {:name e}) (:entries exposes-field)))
        provides-entries (->> (get by-kind :provides-block)
                              (mapcat :entries)
                              vec)
        guarantees (->> (get by-kind :annotation)
                        (filter #(= "guarantee" (:kind %)))
                        (mapv :name))]
    (cond-> {}
      facing (assoc :facing facing)
      (:description decl) (assoc :description (:description decl))
      (seq exposes-entries) (assoc :exposes exposes-entries)
      (seq provides-entries) (assoc :provides provides-entries)
      (seq guarantees) (assoc :guarantees guarantees))))

;; ---------------------------------------------------------------------------
;; Node construction

(defn- build-allium-nodes
  "Build nodes from parsed allium files.
   Each file enriches its parent directory's module node with spec data
   (description, surface). Creates Function child nodes for rule triggers
   and surface names. Extracts descriptions from raw file text."
  [_src-path parsed-files]
  (reduce
    (fn [acc {:keys [filepath ast text]}]
      (let [module-id (file->parent-folder-path filepath)
            decls (:declarations ast)

            ;; Extract descriptions from raw text
            module-desc (extract-module-description text)
            decl-descs (extract-declaration-descriptions text)

            ;; Extract spec data from declarations
            entities (->> decls (filter #(#{:entity :value} (:type %))))
            surfaces (->> decls (filter #(= :surface (:type %))))
            surface (when (seq surfaces)
                      (extract-surface (first surfaces)))

            module-node (cond->
                          {:id module-id
                           :kind :module
                           :label (last (str/split module-id #"/"))
                           :parent nil
                           :children #{}
                           :data (cond-> {:kind :module :has-spec true}
                                   surface (assoc :surface surface))}
                          module-desc (assoc :description module-desc))

            ;; Create Function nodes for rule triggers and surface names.
            ;; Both represent the module's callable interface — the things
            ;; other modules reference via qualified names. Marked private
            ;; so they don't clutter the graph alongside implementation
            ;; functions; they exist as edge endpoints for cross-module
            ;; spec references.
            rules (->> decls (filter #(= :rule (:type %))))
            rule-fn-nodes
            (->> rules
                 (keep (fn [rule]
                         (let [trigger-name
                               (some (fn [c]
                                       (when (and (= :when (:clause-type c))
                                                  (= :call (get-in c [:trigger :kind])))
                                         (get-in c [:trigger :name])))
                                     (:clauses rule))]
                           (when trigger-name
                             (let [fn-id (str module-id "/" trigger-name)
                                   doc (get decl-descs (:name rule))]
                               [fn-id {:id fn-id
                                       :kind :function
                                       :label trigger-name
                                       :parent module-id
                                       :children #{}
                                       :data {:kind :function
                                              :private? true
                                              :doc doc}}])))))
                 (into {}))

            ;; Surface names as Function nodes — surfaces are referenced
            ;; by name in related: blocks across modules.
            surface-fn-nodes
            (->> surfaces
                 (map (fn [s]
                        (let [fn-id (str module-id "/" (:name s))
                              doc (get decl-descs (:name s))]
                          [fn-id {:id fn-id
                                  :kind :function
                                  :label (:name s)
                                  :parent module-id
                                  :children #{}
                                  :data {:kind :function
                                         :private? true
                                         :doc doc}}])))
                 (into {}))]
        (-> acc
            (assoc module-id module-node)
            (merge rule-fn-nodes)
            (merge surface-fn-nodes))))
    {}
    parsed-files))

;; ---------------------------------------------------------------------------
;; Edge construction

(def ^:private qualified-ref-pattern
  "Regex matching qualified references like effects/ExecuteResponseEffect.
   Captures [alias, name] pairs from rule bodies and provides entries."
  #"([a-z_][a-zA-Z0-9_]*)/([A-Z][a-zA-Z0-9_]*)")

(defn- extract-qualified-refs
  "Extract [alias name] pairs from a text string (clause body)."
  [text]
  (when text
    (->> (re-seq qualified-ref-pattern text)
         (mapv (fn [[_ alias name]] [alias name])))))

(defn- rule-clause-bodies
  "Extract all text-captured clause bodies from a rule declaration.
   Returns a sequence of strings from ensures, requires, and let clauses."
  [rule-decl]
  (->> (:clauses rule-decl)
       (keep :body)))

(defn- rule-trigger-name
  "Extract the trigger name from a rule's when clause, if it uses call syntax."
  [rule-decl]
  (some (fn [c]
          (when (and (= :when (:clause-type c))
                     (= :call (get-in c [:trigger :kind])))
            (get-in c [:trigger :name])))
        (:clauses rule-decl)))

(defn- underscore->hyphen [s] (str/replace s "_" "-"))

(defn- primary-provides-fn
  "Find the primary function name for a module. Used as source for
   binding-trigger rules and surface-level cross-module references.
   Prefers provides entry names; falls back to surface name."
  [ast]
  (let [surfaces (->> (:declarations ast) (filter #(= :surface (:type %))))
        provides-name (->> surfaces
                           (mapcat :fields)
                           (filter #(= :provides-block (:field-kind %)))
                           (mapcat :entries)
                           (map :name)
                           first)]
    (or provides-name
        (:name (first surfaces)))))

(defn- build-allium-edges
  "Build edges from spec cross-module references.

   Function-call edges: walks rule bodies for qualified references
   (alias/Name), resolves aliases to target modules, creates
   :function-call edges between Function nodes. For binding-trigger
   rules (no call-syntax trigger), falls back to the module's primary
   provides function as the source.

   Schema-reference edges: walks entity, value, and variant field
   type-refs for qualified types (alias/Name), creates :schema-reference
   edges between the declaring module and the target module."
  [_src-path parsed-files _registry]
  (let [use-index (build-use-alias-index parsed-files)

        ;; Build index: module-id → set of exported function names.
        ;; Includes both surface provides entries and rule trigger names.
        exports-index
        (into {}
          (map (fn [{:keys [filepath ast]}]
                 (let [module-id (file->parent-folder-path filepath)
                       provides (->> (:declarations ast)
                                     (filter #(= :surface (:type %)))
                                     (mapcat :fields)
                                     (filter #(= :provides-block (:field-kind %)))
                                     (mapcat :entries)
                                     (map :name))
                       triggers (->> (:declarations ast)
                                     (filter #(= :rule (:type %)))
                                     (keep (fn [r]
                                             (some (fn [c]
                                                     (when (and (= :when (:clause-type c))
                                                                (= :call (get-in c [:trigger :kind])))
                                                       (get-in c [:trigger :name])))
                                                   (:clauses r)))))
                       surface-names (->> (:declarations ast)
                                          (filter #(= :surface (:type %)))
                                          (map :name))]
                   [module-id (into #{} (concat provides triggers surface-names))]))
               parsed-files))

        ;; Helper: resolve a qualified ref to a :function-call edge
        resolve-fn-edge
        (fn [source-module-id source-fn-id [alias target-name]]
          (let [target-module-id (get use-index [source-module-id alias])
                target-fn-id (when target-module-id
                               (str target-module-id "/"
                                    (underscore->hyphen target-name)))]
            (when (and target-fn-id
                       (contains? (get exports-index target-module-id)
                                  target-name))
              {:from source-fn-id :to target-fn-id :kind :function-call})))

        ;; --- Function-call edges from rule bodies ---
        rule-edges
        (->> parsed-files
             (mapcat
               (fn [{:keys [filepath ast]}]
                 (let [source-module-id (file->parent-folder-path filepath)
                       rules (->> (:declarations ast) (filter #(= :rule (:type %))))
                       primary-fn (primary-provides-fn ast)]
                   (mapcat
                     (fn [rule]
                       (let [trigger (rule-trigger-name rule)
                             source-name (or trigger primary-fn)
                             source-fn-id (when source-name
                                            (str source-module-id "/"
                                                 (underscore->hyphen source-name)))
                             refs (->> (rule-clause-bodies rule)
                                       (mapcat extract-qualified-refs))]
                         (when source-fn-id
                           (keep #(resolve-fn-edge source-module-id source-fn-id %)
                                 refs))))
                     rules))))
             (into #{}))

        ;; --- Function-call edges from surface related/provides ---
        ;; related: blocks and qualified provides entries contain
        ;; cross-module references as text (e.g., loop/StreamDefinition).
        surface-edges
        (->> parsed-files
             (mapcat
               (fn [{:keys [filepath ast]}]
                 (let [source-module-id (file->parent-folder-path filepath)
                       primary-fn (primary-provides-fn ast)
                       surfaces (->> (:declarations ast)
                                     (filter #(= :surface (:type %))))
                       ;; Collect text from related blocks and derived provides
                       surface-texts
                       (->> surfaces
                            (mapcat :fields)
                            (keep (fn [f]
                                    (case (:field-kind f)
                                      :related (:body f)
                                      ;; Qualified provides parsed as derived field
                                      :derived (when (= "provides" (:name f))
                                                 (:expr f))
                                      nil))))]
                   (when primary-fn
                     (let [source-fn-id (str source-module-id "/"
                                            (underscore->hyphen primary-fn))
                           refs (mapcat extract-qualified-refs surface-texts)]
                       (keep #(resolve-fn-edge source-module-id source-fn-id %)
                             refs))))))
             (into #{}))

        fn-call-edges (into rule-edges surface-edges)

        ;; --- Schema-reference edges from type declarations ---
        schema-ref-edges
        (->> parsed-files
             (mapcat
               (fn [{:keys [filepath ast]}]
                 (let [source-module-id (file->parent-folder-path filepath)
                       decls (:declarations ast)
                       typed-decls (filter #(#{:entity :value :variant :surface}
                                              (:type %)) decls)]
                   (for [decl typed-decls
                         ref (extract-declaration-refs decl)
                         :when (:alias ref)
                         :let [target-module-id (get use-index
                                                  [source-module-id (:alias ref)])]
                         :when target-module-id]
                     {:from source-module-id
                      :to target-module-id
                      :kind :schema-reference}))))
             (into #{}))]

    (vec (into fn-call-edges schema-ref-edges))))

;; ---------------------------------------------------------------------------
;; Public API

(defn analyze
  "Produce an Allium language analysis result from source files.
   Discovers .allium files under src-path, parses them, and builds
   an AnalysisResult with nodes and edges."
  {:malli/schema [:=> [:cat :FilePath] :AnalysisResult]}
  [src-path]
  (let [files (discover-allium-files src-path)
        parsed (->> files
                    (map (fn [fp]
                           (let [text (slurp fp)
                                 ast (parser/parse-allium text)]
                             {:filepath fp :ast ast :text text})))
                    ;; Skip files that failed to parse
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
